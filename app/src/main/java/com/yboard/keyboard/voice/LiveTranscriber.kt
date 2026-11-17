package com.yboard.keyboard.voice

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import com.yboard.keyboard.settings.AiFeature
import com.yboard.keyboard.usage.LiveUsageMeter
import com.yboard.keyboard.usage.UsageStore
import java.io.ByteArrayOutputStream

/**
 * OpenAI Realtime transcription: we stream microphone audio and get words back while the
 * person is still talking. We decide where phrases end ourselves (the live model has no
 * server-side pause detection) and send a commit for each one.
 * Listener calls arrive on the main thread.
 */
class LiveTranscriber(
    private val apiKey: String,
    private val languages: List<String>,
    private val listener: Listener,
    /** Your own words (names, slang), so they're spelled right. */
    private val vocabulary: List<String> = emptyList(),
    /** Their English / Hebrew spellings. */
    private val pairs: List<Pair<String, String>> = emptyList(),
) {
    interface Listener {
        fun onConnected()
        fun onCommitted(itemId: String)
        fun onDelta(itemId: String, delta: String)
        fun onCompleted(itemId: String, transcript: String)
        fun onFailed(itemId: String?, message: String)
        /** The connection ended. [code] is the HTTP status when it never opened (401 = bad key). */
        fun onClosed(message: String?, code: Int)
        /** A phrase we ended had no speech in it, so no words will come for it. */
        fun onCommitIgnored() {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private val model = OpenAi.LIVE_MODEL
    private val usageMeter = LiveUsageMeter(model)
    @Volatile private var connected = false
    @Volatile private var closed = false
    @Volatile private var gotText = false
    /** How many settings OpenAI has rejected so far (see [OpenAi.liveSessionJson]). */
    private var level = 0
    private val pending = ByteArrayOutputStream()

    fun connect() {
        val req = Request.Builder().url(OpenAi.LIVE_URL)
            .header("Authorization", "Bearer $apiKey")
            .build()
        ws = OpenAi.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                Log.i(TAG, "live: connected")
                main.post { if (!closed) listener.onConnected() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finishUsage()
                if (closed) return
                closed = true
                val code = response?.code ?: 0
                val msg = if (response != null) {
                    OpenAi.errorMessage(code, try { response.body?.string() } catch (e: Exception) { null })
                } else {
                    t.message
                }
                Log.w(TAG, "live: failed, HTTP $code, ${t.javaClass.simpleName}: ${msg?.take(200)}")
                main.post { listener.onClosed(msg, code) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finishUsage()
                if (closed) return
                Log.w(TAG, "live: closed by server, code $code ${reason.take(200)}")
                closed = true
                main.post { listener.onClosed(reason.ifEmpty { null }, 0) }
            }
        })
        // OkHttp queues this until the connection opens.
        ws?.send(OpenAi.liveSessionJson(languages, level = 0, vocabulary = vocabulary, pairs = pairs, model = model))
    }

    /** Microphone audio (24 kHz mono 16-bit). Sent in ~100 ms pieces. Any thread. */
    fun append(pcm: ByteArray) {
        synchronized(pending) {
            pending.write(pcm)
            if (pending.size() >= 4800) flushLocked()
        }
    }

    /** End of a phrase: send what's left and ask for the final words. */
    fun commit() {
        synchronized(pending) { flushLocked() }
        ws?.send("""{"type":"input_audio_buffer.commit"}""")
    }

    fun close() {
        closed = true
        ws?.close(1000, "done")
        ws = null
        // Allow final completed events to arrive during the WebSocket close handshake.
        main.postDelayed({ finishUsage() }, 5000)
    }

    private fun finishUsage() {
        usageMeter.finish()?.let { UsageStore.record(AiFeature.LIVE, model, it) }
    }

    private fun flushLocked() {
        if (pending.size() == 0) return
        val count = pending.size()
        val audio = Base64.encodeToString(pending.toByteArray(), Base64.NO_WRAP)
        pending.reset()
        if (ws?.send(JSONObject().put("type", "input_audio_buffer.append").put("audio", audio).toString()) == true && connected) usageMeter.sent(count)
    }

    companion object {
        private const val TAG = "YboardVoice"
    }

    private fun handle(text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        val id = o.optString("item_id")
        when (o.optString("type")) {
            "input_audio_buffer.committed" -> main.post { listener.onCommitted(id) }
            "conversation.item.input_audio_transcription.delta" -> {
                val d = o.optString("delta")
                if (d.isNotEmpty()) {
                    gotText = true
                    main.post { listener.onDelta(id, d) }
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                usageMeter.completed("$id:${o.optInt("content_index")}", o.optJSONObject("usage"))
                gotText = true
                val t = o.optString("transcript")
                main.post { listener.onCompleted(id, t) }
            }
            "conversation.item.input_audio_transcription.failed" -> {
                val msg = o.optJSONObject("error")?.optString("message").orEmpty()
                main.post { listener.onFailed(id, msg) }
            }
            "error" -> {
                val err = o.optJSONObject("error")
                val msg = err?.optString("message").orEmpty()
                val param = err?.optString("param").orEmpty()
                // If an optional setting isn't accepted, retry without the least important one.
                Log.w(TAG, "live: error param=$param: ${msg.take(200)}")
                val aboutSettings = param.isNotEmpty() || listOf("param", "unknown", "languages", "delay", "prompt", "keywords")
                    .any { msg.contains(it, ignoreCase = true) }
                if (level < OpenAi.LAST_SESSION_LEVEL && !gotText && aboutSettings) {
                    level++
                    Log.i(TAG, "live: retrying with fewer settings (level $level)")
                    ws?.send(OpenAi.liveSessionJson(languages, level, vocabulary, pairs, model))
                } else if (msg.contains("buffer too small", true) || msg.contains("empty", true)) {
                    main.post { listener.onCommitIgnored() }
                } else {
                    main.post { listener.onFailed(null, msg) }
                }
            }
        }
    }
}
