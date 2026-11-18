package com.yboard.keyboard.local

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** A pinned, verified model in app-private no-backup storage. No keyboard text is downloaded or uploaded. */
object QwenModel {
    const val NAME = "Qwen3-0.6B-Base"
    const val FILE_NAME = "Qwen3-0.6B-Base-Q4_K_M.gguf"
    const val SIZE = 396_704_544L
    const val SHA256 = "ea719cfda3898e7eaf3fcebef8250857b9c7e3152b54632cf0293fdc6069cd58"
    const val URL = "https://github.com/moryoav/Yboard/releases/download/v1.9.0/$FILE_NAME"
    data class State(val ready: Boolean = false, val downloading: Boolean = false, val bytes: Long = 0, val error: String? = null)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    @Volatile private var call: Call? = null
    @Volatile private var stopped = false
    private var directory: File? = null

    @Synchronized fun init(context: Context) {
        if (directory != null) return
        directory = File(context.applicationContext.noBackupFilesDir, "qwen").apply { mkdirs() }
        val model = file()
        val marker = File(directory, "verified")
        val expected = "$SHA256:$SIZE:${model.lastModified()}"
        mutable.value = State(ready = model.length() == SIZE && runCatching { marker.readText() == expected }.getOrDefault(false),
            bytes = File(directory, "$FILE_NAME.part").takeIf { it.exists() }?.length() ?: 0)
    }
    fun file(): File = File(checkNotNull(directory) { "Model store is not initialized" }, FILE_NAME)
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun publish(state: State) { mutable.value = state; main.post { listeners.forEach { it() } } }

    @Synchronized fun download(context: Context) {
        init(context)
        if (mutable.value.downloading || mutable.value.ready) return
        stopped = false
        publish(mutable.value.copy(downloading = true, error = null))
        worker.execute {
            val part = File(directory, "$FILE_NAME.part")
            try {
                if (part.length() > SIZE) check(part.delete())
                // A process can stop after receiving the final byte but before activation.
                if (part.length() == SIZE) {
                    if (checksum(part) == SHA256) {
                        activate(part)
                        publish(State(ready = true))
                        return@execute
                    }
                    check(part.delete())
                }
                var offset = part.length()
                check(StatFs(checkNotNull(directory).absolutePath).availableBytes > SIZE - offset + 32L * 1024 * 1024) { "Not enough free space for Qwen. Free about 400 MB and try again." }
                val request = Request.Builder().url(URL).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
                val requestCall = http.newCall(request)
                call = requestCall
                if (stopped) requestCall.cancel()
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
                                if (stopped) throw java.io.IOException("Download paused")
                                val size = input.read(buffer)
                                if (size < 0) break
                                count += size
                                check(count <= SIZE) { "The model download did not match the expected file." }
                                digest.update(buffer, 0, size); output.write(buffer, 0, size)
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastUpdate > 200) { publish(State(downloading = true, bytes = count)); lastUpdate = now }
                            }
                        } ?: error("The model download was empty.")
                        output.fd.sync()
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    check(count == SIZE) { "The download was interrupted. Tap Resume download to continue." }
                    if (hash != SHA256) {
                        part.delete()
                        error("Qwen's download did not pass verification. Please download it again.")
                    }
                    activate(part)
                    publish(State(ready = true))
                }
            } catch (error: Exception) {
                publish(State(bytes = part.takeIf { it.exists() }?.length() ?: 0,
                    error = if (stopped) "Download paused" else error.message ?: "Could not download Qwen. Try again."))
            } finally { call = null }
        }
    }
    fun pause() { stopped = true; call?.cancel() }
    private fun activate(part: File) {
        Files.move(part.toPath(), file().toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        markVerified()
    }
    private fun checksum(source: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun markVerified() { File(directory, "verified").writeText("$SHA256:$SIZE:${file().lastModified()}") }

    /** Instrumentation imports the exact release model without exporting phone data. */
    internal fun importVerified(source: File) {
        check(source.length() == SIZE) { "Wrong model size" }
        check(checksum(source) == SHA256) { "Wrong model checksum" }
        source.copyTo(file(), overwrite = true)
        markVerified()
        publish(State(ready = true))
    }
}
