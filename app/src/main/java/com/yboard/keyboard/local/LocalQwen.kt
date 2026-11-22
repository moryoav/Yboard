package com.yboard.keyboard.local

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One resident model and a bounded latest-request queue. Never calls a cloud API. */
object LocalQwen {
    data class Status(
        val loaded: Boolean = false,
        val loading: Boolean = false,
        val error: String? = null,
        val model: QwenModel.Variant? = null,
    )
    data class Result(val words: List<String>, val elapsedMs: Long)
    private val mutable = MutableStateFlow(Status())
    val status = mutable.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(1),
        { task -> Thread(task, "YboardQwen") }, ThreadPoolExecutor.DiscardOldestPolicy())
    @Volatile private var ticket = 0L
    private data class Preparation(val context: String, val model: QwenModel.Variant)
    private var preparation: Preparation? = null // guarded by this object's monitor
    private var onRankCancelled: (() -> Unit)? = null
    private var handle = 0L // worker thread only
    private var loadedModel: QwenModel.Variant? = null // worker thread only
    @Volatile private var clearPending = false
    @Volatile private var releasePending = false

    fun init(context: Context) { QwenModel.init(context) }
    @Synchronized fun cancel(): Long {
        ticket = try { QwenNative.cancel() } catch (_: LinkageError) { ticket + 1 }
        worker.queue.clear()
        preparation = null
        onRankCancelled?.let { main.post { it() } }
        onRankCancelled = null
        return ticket
    }
    private fun ensureLoaded(model: QwenModel.Variant) {
        applyMaintenance()
        check(QwenModel.state.value[model].ready) { "Download the model under Yboard → Typing." }
        if (loadedModel == model) return
        closeModel()
        mutable.value = Status(loading = true, model = model)
        handle = QwenNative.load(QwenModel.file(model).absolutePath, (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4))
        check(handle != 0L) { "The model could not be loaded on this device." }
        loadedModel = model
        mutable.value = Status(loaded = true, model = model)
    }
    fun warmUp(context: Context) {
        init(context)
        val catalog = QwenModel.state.value
        val model = catalog.selected
        val current = mutable.value
        if (!catalog[model].ready || current.model == model && (current.loaded || current.loading)) return
        worker.execute {
            if (model != QwenModel.selected) return@execute
            runCatching { ensureLoaded(model) }
                .onFailure { mutable.value = Status(error = it.message ?: "The model could not be loaded", model = model) }
        }
    }
    @Synchronized fun clearContext() {
        clearPending = true
        cancel()
        worker.execute { applyMaintenance() }
    }
    @Synchronized fun release() {
        releasePending = true
        cancel()
        worker.execute { applyMaintenance() }
    }
    private fun closeModel() {
        if (handle != 0L) QwenNative.close(handle)
        handle = 0L
        loadedModel = null
    }
    private fun applyMaintenance() {
        synchronized(this) {
            if (releasePending) {
                closeModel()
                releasePending = false
                clearPending = false
                mutable.value = Status()
            } else if (clearPending) {
                if (handle != 0L) QwenNative.clear(handle)
                clearPending = false
            }
        }
    }

    @Synchronized fun prepare(context: String): Long {
        val model = QwenModel.selected
        val id = cancel()
        preparation = Preparation(context, model)
        worker.execute {
            if (id != ticket) return@execute
            runCatching { ensureLoaded(model); if (id == ticket) QwenNative.prepare(handle, context, id) }
                .onFailure { if (id == ticket) mutable.value = Status(loaded = loadedModel == model, error = it.message, model = model) }
        }
        return id
    }

    fun predict(context: String, prefix: String, callback: (kotlin.Result<Result>) -> Unit): Long {
        val model = QwenModel.selected
        val id = cancel()
        worker.execute {
            if (id != ticket) return@execute
            val result = runCatching {
                val start = SystemClock.elapsedRealtime()
                ensureLoaded(model)
                val words = QwenNative.predict(handle, context, prefix, id).toList()
                Result(words, SystemClock.elapsedRealtime() - start)
            }
            main.post {
                if (id == ticket) {
                    mutable.value = Status(loaded = mutable.value.loaded && mutable.value.model == model,
                        error = result.exceptionOrNull()?.message, model = model)
                    callback(result)
                }
            }
        }
        return id
    }

    @Synchronized fun rank(
        context: String,
        words: List<String>,
        preparedTicket: Long? = null,
        callback: (kotlin.Result<DoubleArray>) -> Unit,
    ): Long {
        val model = QwenModel.selected
        // Keep an in-flight preparation for the same context alive on the serial model worker.
        val id = if (preparedTicket == ticket && preparation == Preparation(context, model)) ticket else cancel()
        preparation = null
        onRankCancelled = { callback(kotlin.Result.failure(CancellationException())) }
        worker.execute {
            if (id != ticket) return@execute
            val result = runCatching { ensureLoaded(model); QwenNative.score(handle, context, words.toTypedArray(), id) }
            main.post {
                synchronized(this) {
                    if (id != ticket) return@post
                    onRankCancelled = null
                }
                mutable.value = Status(loaded = mutable.value.loaded && mutable.value.model == model,
                    error = result.exceptionOrNull()?.message, model = model)
                callback(result)
            }
        }
        return id
    }

    /** Diagnostics run on the instrumentation thread while the IME is not active. */
    internal fun predictBlocking(context: String, prefix: String = ""): Result {
        val done = java.util.concurrent.CountDownLatch(1)
        var answer: kotlin.Result<Result>? = null
        predict(context, prefix) { answer = it; done.countDown() }
        check(done.await(60, TimeUnit.SECONDS)) { "Qwen benchmark timed out" }
        return checkNotNull(answer).getOrThrow()
    }
}
