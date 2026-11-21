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
    private const val MODEL_RELEASES = "https://github.com/moryoav/Yboard/releases/download"
    private const val GEMMA_FILE = "gemma-3-270m-qat-Q4_0.gguf"
    private const val GEMMA_ORIGIN = "https://huggingface.co/ggml-org/gemma-3-270m-qat-GGUF/resolve/455826a6464d1f352ff560df17f6f9eeec230082"
    private const val GEMMA_ACCEPTED = "gemma_terms_2026_04_01"
    const val GEMMA_TERMS_URL = "https://gemma.ai/resources/terms/"

    enum class Variant(
        val label: String,
        val fileName: String,
        val size: Long,
        val sha256: String,
        internal val url: String,
        internal val marker: String,
    ) {
        QWEN3("Qwen3-0.6B-Base", FILE_NAME, SIZE, SHA256, "$MODEL_RELEASES/v1.9.0/$FILE_NAME", "verified"),
        QWEN35("Qwen3.5-0.8B-Base", QWEN35_FILE_NAME, QWEN35_SIZE, QWEN35_SHA256,
            "$MODEL_RELEASES/v1.10.0/$QWEN35_FILE_NAME", "verified-qwen35"),
        GEMMA("Gemma 3 270M Base", GEMMA_FILE, 241_409_024L,
            "0c4a546e737759fd7c60f4c34704f12cc3273222ba44a5bc3d6ba9d6053a9f9f",
            "$GEMMA_ORIGIN/$GEMMA_FILE", "verified-gemma270m"),
    }

    data class State(
        val ready: Boolean = false,
        val downloading: Boolean = false,
        val removing: Boolean = false,
        val bytes: Long = 0,
        val error: String? = null,
    )
    data class Catalog(val selected: Variant = Variant.QWEN3, val models: Map<Variant, State> = emptyMap()) {
        operator fun get(model: Variant): State = models[model] ?: State()
    }
    private val mutable = MutableStateFlow(Catalog())
    val state = mutable.asStateFlow()
    val selected get() = state.value.selected
    val selectedState get() = state.value.let { it[it.selected] }
    // A download can run while another model's local files are being removed.
    private val worker = Executors.newFixedThreadPool(2)
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
        val savedName = prefs.getString("selected", null)
        val saved = Variant.entries.firstOrNull { it.name == savedName && !requiresTerms(it) }
        val catalog = Catalog(selected = saved ?: Variant.QWEN3, models = Variant.entries.associateWith(::readState))
        // The process can stop after unlinking a selected download and before persisting its replacement.
        val fallback = if (saved != null && !catalog[saved].ready) fallbackFor(saved, catalog) else null
        mutable.value = if (fallback != null) catalog.copy(selected = fallback) else catalog
        if (fallback != null) persistSelection(fallback)
    }

    fun file(model: Variant): File = File(checkNotNull(directory) { "Model store is not initialized" }, model.fileName)
    private fun part(model: Variant) = File(directory, "${model.fileName}.part")
    private fun marker(model: Variant) = File(directory, model.marker)

    private fun readState(model: Variant): State {
        val ready = file(model).length() == model.size && runCatching {
            marker(model).readText() == verification(model)
        }.getOrDefault(false)
        return State(ready = ready, bytes = part(model).length().coerceAtMost(model.size))
    }

    fun fallbackFor(model: Variant, catalog: Catalog = state.value): Variant? =
        Variant.entries.firstOrNull { it != model && catalog[it].ready && !requiresTerms(it) }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    fun requiresTerms(model: Variant): Boolean =
        model == Variant.GEMMA && selection?.getBoolean(GEMMA_ACCEPTED, false) != true

    @MainThread fun acceptGemmaTerms() {
        checkNotNull(selection).edit().putBoolean(GEMMA_ACCEPTED, true).apply()
    }

    private fun publish(model: Variant, next: State) {
        val previous = mutable.value[model]
        mutable.update { it.copy(models = it.models + (model to next)) }
        if (model == selected && (previous.ready != next.ready || previous.downloading != next.downloading ||
                previous.removing != next.removing)) {
            main.post { if (model == selected) listeners.forEach { it() } }
        }
    }

    private fun persistSelection(model: Variant) {
        checkNotNull(selection).edit().putString("selected", model.name).apply()
    }

    @MainThread fun select(model: Variant) {
        if (model == selected || !state.value[model].ready || requiresTerms(model)) return
        mutable.update { it.copy(selected = model) }
        persistSelection(model)
        LocalQwen.release()
        listeners.forEach { it() }
    }

    @MainThread @Synchronized fun remove(model: Variant) {
        if (!state.value[model].ready) return
        publish(model, State(removing = true))
        if (model == selected) {
            persistSelection(model)
            LocalQwen.release()
        }
        worker.execute {
            val removal = runCatching {
                Files.deleteIfExists(part(model).toPath())
                Files.deleteIfExists(file(model).toPath())
                Files.deleteIfExists(marker(model).toPath())
            }
            val next = readState(model).copy(error = if (removal.isFailure) "Could not remove the model. Try again." else null)
            main.post {
                publish(model, next)
                if (model == selected && !next.ready) fallbackFor(model)?.let(::select)
            }
        }
    }

    @Synchronized fun download(context: Context, model: Variant) {
        init(context)
        val current = state.value[model]
        if (transfer != null || current.ready || current.removing || requiresTerms(model)) return
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
            check(response.isSuccessful) { "The model could not be downloaded. Check the connection and try again." }
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
                error("The download did not pass verification. Please download it again.")
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
    private fun markVerified(model: Variant) { marker(model).writeText(verification(model)) }

    /** Instrumentation imports the exact release model without exporting phone data. */
    @Synchronized internal fun importVerified(source: File, model: Variant) {
        val current = state.value[model]
        check(transfer == null && !current.ready && !current.removing) { "Model already available or transfer pending" }
        check(source.length() == model.size) { "Wrong model size" }
        check(checksum(source) == model.sha256) { "Wrong model checksum" }
        source.copyTo(part(model), overwrite = true)
        activate(part(model), model)
        publish(model, State(ready = true))
    }
}
