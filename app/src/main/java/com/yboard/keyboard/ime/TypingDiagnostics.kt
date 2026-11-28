package com.yboard.keyboard.ime

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** On-device JSON Lines. All file operations are ordered on the same worker. */
internal object TypingDiagnostics {
    private const val SEGMENT_BYTES = 4 * 1024 * 1024L
    private lateinit var prefs: SharedPreferences
    private lateinit var previous: File
    private lateinit var current: File
    private var version = ""
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)

    data class Status(val recording: String? = null, val hasLog: Boolean = false, val writeFailed: Boolean = false)
    private val mutable = MutableStateFlow(Status())
    val status = mutable.asStateFlow()
    val recording get() = status.value.recording

    fun init(context: Context) {
        prefs = context.getSharedPreferences("yboard_typing_diagnostics", Context.MODE_PRIVATE)
        previous = File(context.noBackupFilesDir, "typing-diagnostics-previous.jsonl")
        current = File(context.noBackupFilesDir, "typing-diagnostics.jsonl")
        version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        scope.launch { updateAvailability() }
        if (prefs.getBoolean("enabled", false)) start("process_started")
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled == (recording != null)) return
        prefs.edit().putBoolean("enabled", enabled).apply()
        if (enabled) {
            start("recording_started")
        } else {
            record("recording_stopped")
            mutable.update { it.copy(recording = null) }
        }
    }

    private fun start(event: String) {
        mutable.update { it.copy(recording = UUID.randomUUID().toString()) }
        record(event)
    }

    fun record(event: String, editor: String? = null, fields: JSONObject.() -> Unit = {}) {
        val session = recording ?: return
        val row = JSONObject()
            .put("schema", 1)
            .put("time", Instant.now().toString())
            .put("recording", session)
            .put("yboard", version)
            .put("editor", editor)
            .put("event", event)
            .apply(fields)
        scope.launch {
            val bytes = (row.toString() + "\n").toByteArray(Charsets.UTF_8)
            try {
                if (current.length() > 0 && current.length() + bytes.size > SEGMENT_BYTES) {
                    remove(previous)
                    if (!current.renameTo(previous)) throw IOException("Could not rotate typing log")
                }
                FileOutputStream(current, true).use { it.write(bytes) }
                mutable.update { it.copy(hasLog = true) }
            } catch (_: IOException) {
                mutable.update { it.copy(writeFailed = true) }
            }
        }
    }

    suspend fun export(context: Context, destination: Uri) {
        val snapshot = withContext(worker) {
            ByteArrayOutputStream().apply {
                listOf(previous, current).filter { it.isFile }.forEach { file ->
                    file.inputStream().use { it.copyTo(this) }
                }
            }.toByteArray()
        }
        if (snapshot.isEmpty()) throw IOException("There is no typing log")
        withContext(Dispatchers.IO) {
            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: throw IOException("Could not open export destination")
            output.use { it.write(snapshot) }
        }
    }

    suspend fun clear() = withContext(worker) {
        try {
            remove(previous)
            remove(current)
            mutable.update { it.copy(writeFailed = false) }
        } finally {
            updateAvailability()
        }
    }

    private fun remove(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Could not clear typing log")
    }

    private fun updateAvailability() {
        mutable.update { it.copy(hasLog = previous.length() > 0 || current.length() > 0) }
    }
}
