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
    data class Status(val loaded: Boolean = false, val loading: Boolean = false, val error: String? = null)
    data class Result(val words: List<String>, val elapsedMs: Long)
    private val mutable = MutableStateFlow(Status())
    val status = mutable.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(1),
        { task -> Thread(task, "YboardQwen") }, ThreadPoolExecutor.DiscardOldestPolicy())
    @Volatile private var ticket = 0L
    private var onRankCancelled: (() -> Unit)? = null
    private var handle = 0L // worker thread only
    @Volatile private var clearPending = false
    @Volatile private var releasePending = false

    fun init(context: Context) { QwenModel.init(context) }
    @Synchronized fun cancel(): Long {
        ticket = try { QwenNative.cancel() } catch (_: LinkageError) { ticket + 1 }
        worker.queue.clear()
        onRankCancelled?.let { main.post { it() } }
        onRankCancelled = null
        return ticket
    }
    private fun ensureLoaded() {
        applyMaintenance()
        check(QwenModel.state.value.ready) { "Download Qwen in Yboard → Typing to enable suggestions and swipe typing." }
        if (handle != 0L) return
        mutable.value = Status(loading = true)
        handle = QwenNative.load(QwenModel.file().absolutePath, (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4))
        check(handle != 0L) { "Qwen could not be loaded on this device." }
        mutable.value = Status(loaded = true)
    }
    fun warmUp(context: Context) {
        init(context)
        if (!QwenModel.state.value.ready || mutable.value.loaded || mutable.value.loading) return
        worker.execute { runCatching { ensureLoaded() }.onFailure { mutable.value = Status(error = it.message ?: "Qwen could not be loaded") } }
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
    private fun applyMaintenance() {
        synchronized(this) {
            if (releasePending) {
                if (handle != 0L) QwenNative.close(handle)
                handle = 0L
                releasePending = false
                clearPending = false
                mutable.value = Status()
            } else if (clearPending) {
                if (handle != 0L) QwenNative.clear(handle)
                clearPending = false
            }
        }
    }

    fun prepare(context: String): Long {
        val id = cancel()
        worker.execute {
            if (id != ticket) return@execute
            runCatching { ensureLoaded(); if (id == ticket) QwenNative.prepare(handle, context, id) }
                .onFailure { if (id == ticket) mutable.value = Status(loaded = handle != 0L, error = it.message) }
        }
        return id
    }

    fun predict(context: String, prefix: String, callback: (kotlin.Result<Result>) -> Unit): Long {
        val id = cancel()
        worker.execute {
            if (id != ticket) return@execute
            val result = runCatching {
                val start = SystemClock.elapsedRealtime()
                ensureLoaded()
                val words = QwenNative.predict(handle, context, prefix, id).toList()
                Result(words, SystemClock.elapsedRealtime() - start)
            }
            main.post {
                if (id == ticket) {
                    mutable.value = Status(loaded = handle != 0L, error = result.exceptionOrNull()?.message)
                    callback(result)
                }
            }
        }
        return id
    }

    @Synchronized fun rank(context: String, words: List<String>, callback: (kotlin.Result<DoubleArray>) -> Unit): Long {
        val id = cancel()
        onRankCancelled = { callback(kotlin.Result.failure(CancellationException())) }
        worker.execute {
            if (id != ticket) return@execute
            val result = runCatching { ensureLoaded(); QwenNative.score(handle, context, words.toTypedArray(), id) }
            main.post {
                synchronized(this) {
                    if (id != ticket) return@post
                    onRankCancelled = null
                }
                mutable.value = Status(loaded = handle != 0L, error = result.exceptionOrNull()?.message)
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
