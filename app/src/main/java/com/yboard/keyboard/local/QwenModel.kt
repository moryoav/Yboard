package com.yboard.keyboard.local

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Pinned model downloads in app-private no-backup storage. */
object QwenModel {
    private const val FILE_NAME = "Qwen3-0.6B-Base-Q4_K_M.gguf"
    private const val SIZE = 396_704_544L
    private const val SHA256 = "ea719cfda3898e7eaf3fcebef8250857b9c7e3152b54632cf0293fdc6069cd58"
    private const val QWEN35_FILE_NAME = "Qwen3.5-0.8B-Base-Q4_K_M.gguf"
    private const val QWEN35_SIZE = 547_308_064L
    private const val QWEN35_SHA256 = "250053ed8e33a8ddc4e4516cbfe06b51f2cc6c77da298c1c9a1e72c6df012123"

    enum class Variant(
        val label: String,
        val fileName: String,
        val size: Long,
        val sha256: String,
        private val release: String,
        internal val marker: String,
    ) {
        QWEN3("Qwen3-0.6B-Base", FILE_NAME, SIZE, SHA256, "v1.9.0", "verified"),
        QWEN35("Qwen3.5-0.8B-Base", QWEN35_FILE_NAME, QWEN35_SIZE, QWEN35_SHA256, "v1.10.0", "verified-qwen35");

        internal val url get() = "https://github.com/moryoav/Yboard/releases/download/$release/$fileName"
    }

    data class State(val ready: Boolean = false, val downloading: Boolean = false, val bytes: Long = 0, val error: String? = null)
    data class Catalog(val selected: Variant = Variant.QWEN3, val models: Map<Variant, State> = emptyMap()) {
        operator fun get(model: Variant): State = models[model] ?: State()
    }
    private val mutable = MutableStateFlow(Catalog())
    val state = mutable.asStateFlow()
    val selected get() = state.value.selected
    val selectedState get() = state.value.let { it[it.selected] }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    private class Transfer(val model: Variant) {
        @Volatile var call: Call? = null
        @Volatile var stopped = false
    }
    private var transfer: Transfer? = null
    private var selection: SharedPreferences? = null
    private var directory: File? = null

    @Synchronized fun init(context: Context) {
        if (directory != null) return
        directory = File(context.applicationContext.noBackupFilesDir, "qwen").apply { mkdirs() }
        val prefs = context.applicationContext.getSharedPreferences("yboard_local_model", Context.MODE_PRIVATE)
        selection = prefs
        val saved = prefs.getString("selected", null)
        mutable.value = Catalog(
            selected = Variant.entries.firstOrNull { it.name == saved } ?: Variant.QWEN3,
            models = Variant.entries.associateWith { model ->
                val file = file(model)
                val ready = file.length() == model.size && runCatching {
                    File(directory, model.marker).readText() == verification(model)
                }.getOrDefault(false)
                State(ready = ready, bytes = part(model).length().coerceAtMost(model.size))
            },
        )
    }

    fun file(model: Variant): File = File(checkNotNull(directory) { "Model store is not initialized" }, model.fileName)
    private fun part(model: Variant) = File(directory, "${model.fileName}.part")
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun publish(model: Variant, next: State) {
        val previous = mutable.value[model]
        mutable.update { it.copy(models = it.models + (model to next)) }
        if (model == selected && (previous.ready != next.ready || previous.downloading != next.downloading)) {
            main.post { if (model == selected) listeners.forEach { it() } }
        }
    }

    @MainThread fun select(model: Variant) {
        if (model == selected || !state.value[model].ready) return
        mutable.update { it.copy(selected = model) }
        checkNotNull(selection).edit().putString("selected", model.name).apply()
        LocalQwen.release()
        listeners.forEach { it() }
    }

    @Synchronized fun download(context: Context, model: Variant) {
        init(context)
        if (transfer != null || state.value[model].ready) return
        val job = Transfer(model)
        transfer = job
        publish(model, state.value[model].copy(downloading = true, error = null))
        worker.execute {
            val part = part(model)
            val next = try {
                receive(job, part)
                State(ready = true)
            } catch (error: Exception) {
                State(bytes = part.length().coerceAtMost(model.size),
                    error = if (job.stopped) null else error.message ?: "Could not download the model. Try again.")
            }
            synchronized(this) {
                transfer = null
                publish(model, next)
            }
        }
    }

    private fun receive(job: Transfer, part: File) {
        val model = job.model
        if (part.length() > model.size) check(part.delete())
        // A process can stop after receiving the final byte but before activation.
        if (part.length() == model.size) {
            if (checksum(part) == model.sha256) {
                activate(part, model)
                return
            }
            check(part.delete())
        }
        var offset = part.length()
        check(StatFs(checkNotNull(directory).absolutePath).availableBytes > model.size - offset + 32L * 1024 * 1024) {
            "Not enough free space. Free some storage and try again."
        }
        // An origin that ignores Range may send the entire file again.
        val request = Request.Builder().url(model.url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        val requestCall = http.newCall(request)
        job.call = requestCall
        if (job.stopped) requestCall.cancel()
        requestCall.execute().use { response ->
            check(response.isSuccessful) { "Qwen could not be downloaded. Check the connection and try again." }
            if (offset > 0 && response.code != 206) offset = 0
            if (response.code == 206) check(response.header("Content-Range")?.startsWith("bytes $offset-") == true) { "The download could not be resumed. Try again." }
            val digest = MessageDigest.getInstance("SHA-256")
            if (offset > 0) part.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            var count = offset
            var lastUpdate = 0L
            java.io.FileOutputStream(part, offset > 0).use { output ->
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (job.stopped) throw java.io.IOException("Download paused")
                        val size = input.read(buffer)
                        if (size < 0) break
                        count += size
                        check(count <= model.size) { "The model download did not match the expected file." }
                        digest.update(buffer, 0, size); output.write(buffer, 0, size)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastUpdate > 200) { publish(model, State(downloading = true, bytes = count)); lastUpdate = now }
                    }
                } ?: error("The model download was empty.")
                output.fd.sync()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(count == model.size) { "The download was interrupted. Tap Resume download to continue." }
            if (hash != model.sha256) {
                part.delete()
                error("Qwen's download did not pass verification. Please download it again.")
            }
            activate(part, model)
        }
    }

    @Synchronized fun pause(model: Variant) {
        val job = transfer?.takeIf { it.model == model } ?: return
        job.stopped = true
        job.call?.cancel()
    }
    private fun activate(part: File, model: Variant) {
        Files.move(part.toPath(), file(model).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        markVerified(model)
    }
    private fun checksum(source: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun verification(model: Variant) = "${model.sha256}:${model.size}:${file(model).lastModified()}"
    private fun markVerified(model: Variant) { File(directory, model.marker).writeText(verification(model)) }

    /** Instrumentation imports the exact release model without exporting phone data. */
    @Synchronized internal fun importVerified(source: File, model: Variant) {
        check(transfer == null && !state.value[model].ready) { "Model already available or download pending" }
        check(source.length() == model.size) { "Wrong model size" }
        check(checksum(source) == model.sha256) { "Wrong model checksum" }
        source.copyTo(part(model), overwrite = true)
        activate(part(model), model)
        publish(model, State(ready = true))
    }
}
