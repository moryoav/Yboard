package com.yboard.keyboard.voice

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

/** Streams 24 kHz PCM, preserving audio/commit order until the session is ready. */
class LiveTranscriber(
    private val apiKey: String,
    private val languages: List<String>,
    private val listener: Listener,
    private val vocabulary: List<String> = emptyList(),
    private val pairs: List<Pair<String, String>> = emptyList(),
    private val delay: String = "medium",
    private val main: VoiceDispatcher = MainVoiceDispatcher(),
    private val socketFactory: (Request, WebSocketListener) -> WebSocket = { request, callbacks ->
        OpenAi.http.newWebSocket(request, callbacks)
    },
) : VoiceConnection {
    interface Listener {
        fun onConnected()
        fun onCommitted(itemId: String)
        fun onDelta(itemId: String, delta: String)
        fun onCompleted(itemId: String, transcript: String)
        fun onFailed(itemId: String?, message: String)
        fun onClosed(message: String?, code: Int)
        fun onCommitIgnored(turnId: String?) {}
    }

    private var ws: WebSocket? = null
    private val model = OpenAi.LIVE_MODEL
    private val usageMeter = LiveUsageMeter(model)
    @Volatile private var ready = false
    @Volatile private var closed = false
    @Volatile private var gotText = false
    private var level = 0
    private val pending = ByteArrayOutputStream()
    private data class Frame(val text: String, val bytes: Int = 0)
    private val queued = ArrayDeque<Frame>()

    override fun connect() {
        val req = Request.Builder().url(OpenAi.LIVE_URL).header("Authorization", "Bearer $apiKey").build()
        ws = socketFactory(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed) { webSocket.close(1000, "done"); return }
                webSocket.send(settings())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                val message = if (response != null) OpenAi.errorMessage(code,
                    runCatching { response.body?.string() }.getOrNull()) else t.message
                terminate(message, code)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { terminate(reason.ifEmpty { null }, 0) }
        })
        main.postDelayed({ if (!closed && !ready) terminate("Live transcription didn't become ready in time.", 0) }, 10000)
    }

    private fun settings() = OpenAi.liveSessionJson(languages, level, vocabulary, pairs, model, delay)
    private fun dispatch(action: () -> Unit) { main.post { if (!closed) action() } }

    override fun append(pcm: ByteArray) = synchronized(pending) {
        if (!closed) {
            pending.write(pcm)
            if (pending.size() >= 4800) flushLocked()
        }
    }

    /** Called by the mic thread, before it can append any audio from the next phrase. */
    override fun commit(turnId: String) = synchronized(pending) {
        if (!closed) {
            flushLocked()
            enqueue(Frame(JSONObject().put("type", "input_audio_buffer.commit").put("event_id", turnId).toString()))
        }
    }

    override fun close() {
        closed = true
        synchronized(pending) { queued.clear(); pending.reset() }
        ws?.close(1000, "done")
        ws = null
        // Completion usage can still arrive during the close handshake, without delivering text callbacks.
        main.postDelayed({ finishUsage() }, 5000)
    }

    @Synchronized private fun terminate(message: String?, code: Int) {
        finishUsage()
        if (closed) return
        closed = true
        ws?.cancel()
        main.post { listener.onClosed(message, code) }
    }
    private fun finishUsage() { usageMeter.finish()?.let { UsageStore.record(AiFeature.LIVE, model, it) } }

    private fun flushLocked() {
        if (pending.size() == 0) return
        val bytes = pending.size()
        val audio = Base64.encodeToString(pending.toByteArray(), Base64.NO_WRAP)
        pending.reset()
        enqueue(Frame(JSONObject().put("type", "input_audio_buffer.append").put("audio", audio).toString(), bytes))
    }

    private fun enqueue(frame: Frame) {
        if (closed) return
        if (!ready) queued.addLast(frame) else send(frame)
    }
    private fun send(frame: Frame) {
        if (ws?.send(frame.text) == true) {
            if (frame.bytes > 0) usageMeter.sent(frame.bytes)
        } else terminate("The live connection couldn't send the audio.", 0)
    }

    private fun handle(text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = o.optString("type")
        val id = o.optString("item_id")
        if (type == "conversation.item.input_audio_transcription.completed") {
            usageMeter.completed("$id:${o.optInt("content_index")}", o.optJSONObject("usage"))
        }
        if (closed) return
        when (type) {
            "session.updated", "transcription_session.updated" -> synchronized(pending) {
                if (!ready) {
                    ready = true
                    while (queued.isNotEmpty() && !closed) send(queued.removeFirst())
                    dispatch { listener.onConnected() }
                }
            }
            "input_audio_buffer.committed" -> dispatch { listener.onCommitted(id) }
            "conversation.item.input_audio_transcription.delta" -> {
                val delta = o.optString("delta")
                if (delta.isNotEmpty()) { gotText = true; dispatch { listener.onDelta(id, delta) } }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                gotText = true
                dispatch { listener.onCompleted(id, o.optString("transcript")) }
            }
            "conversation.item.input_audio_transcription.failed" -> dispatch {
                listener.onFailed(id, o.optJSONObject("error")?.optString("message").orEmpty())
            }
            "error" -> {
                val error = o.optJSONObject("error") ?: JSONObject()
                val message = error.optString("message")
                val param = error.optString("param")
                Log.w("YboardVoice", "live error: ${error.optString("code")} param=$param")
                val optional = listOf("languages", "delay", "prompt", "keywords")
                val settingRejected = optional.any { param == it || param.endsWith(".$it") } ||
                    (error.optString("code") == "unknown_parameter" && optional.any { message.contains(it, true) })
                if (!gotText && level < OpenAi.LAST_SESSION_LEVEL && settingRejected) {
                    level++
                    ws?.send(settings())
                } else if (error.optString("code") == "input_audio_buffer_commit_empty" ||
                    (message.contains("buffer", true) && (message.contains("too small", true) || message.contains("empty", true)))) {
                    dispatch { listener.onCommitIgnored(error.optString("event_id").ifEmpty { null }) }
                } else dispatch { listener.onFailed(null, message) }
            }
        }
    }
}
