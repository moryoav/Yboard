package com.yboard.keyboard

import android.content.Context
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import com.yboard.keyboard.voice.*
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.*
import okio.ByteString
import org.json.JSONObject

/** Deterministic controller regressions with synthetic audio/text. No microphone, network or saved key. */
object VoiceRegression {
    private class Clock : VoiceDispatcher {
        data class Task(val at: Long, val order: Int, val action: () -> Unit)
        private val tasks = ArrayList<Task>()
        private var now = 0L
        private var order = 0
        override fun post(task: () -> Unit) = postDelayed(task, 0)
        override fun postDelayed(task: () -> Unit, delayMs: Long) { tasks.add(Task(now + delayMs, order++, task)) }
        override fun cancelAll() { tasks.clear() }
        fun advance(ms: Long = 0) {
            now += ms
            var guard = 0
            while (true) {
                val task = tasks.filter { it.at <= now }.minWithOrNull(compareBy<Task> { it.at }.thenBy { it.order }) ?: break
                tasks.remove(task)
                check(++guard < 1000) { "Controller scheduled too many immediate tasks" }
                task.action()
            }
        }
    }
    private class DirectWorker : AbstractExecutorService() {
        private var closed = false
        override fun execute(command: Runnable) {
            check(!closed) { "Work submitted after session shutdown" }
            command.run()
        }
        override fun shutdown() { closed = true }
        override fun shutdownNow(): MutableList<Runnable> { closed = true; return mutableListOf() }
        override fun isShutdown() = closed
        override fun isTerminated() = closed
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = closed
    }
    private class Editor(context: Context) : BaseInputConnection(View(context), true) {
        val content = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
        override fun getEditable(): Editable = content
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int) = ExtractedText().also {
            it.text = content.toString(); it.selectionStart = Selection.getSelectionStart(content)
            it.selectionEnd = Selection.getSelectionEnd(content); it.startOffset = 0
        }
    }
    private class Fixture(context: Context, smart: Boolean = true) : VoiceTyping.Host {
        val clock = Clock()
        val editor = Editor(context)
        val messages = ArrayList<String>()
        val results = ArrayDeque<Any>()
        var fileCalls = 0
        var sends = 0
        var commandCalls = 0
        val commits = ArrayList<String>()
        lateinit var micListener: MicStream.Listener
        var micPending = false
        var micStopped = false
        val voice = VoiceTyping("synthetic-test-key", listOf("en", "he"), "en", smart, this,
            initialSelection = 0, services = object : VoiceServices() {
                override val dispatcher = clock
                override val worker = DirectWorker()
                override val aiWorker = DirectWorker()
                override fun microphone(listener: MicStream.Listener, options: VoiceOptions): VoiceRecorder {
                    micListener = listener
                    return object : VoiceRecorder {
                        override val speaking get() = micPending
                        override fun start() {}
                        override fun stop() {
                            if (micStopped) return
                            micStopped = true
                            if (micPending) { micPending = false; listener.onPhraseEnd() }
                            listener.onMicStopped()
                        }
                        override fun setQuickEnd(frames: Int) {}
                    }
                }
                override fun connection(key: String, languages: List<String>, listener: LiveTranscriber.Listener,
                                        vocabulary: List<String>, pairs: List<Pair<String, String>>, options: VoiceOptions) =
                    object : VoiceConnection {
                        override fun connect() { listener.onConnected() }
                        override fun append(pcm: ByteArray) {}
                        override fun commit(turnId: String) { commits.add(turnId) }
                        override fun close() {}
                    }
                override fun transcribe(key: String, wav: ByteArray, languages: List<String>, context: String,
                                        vocabulary: List<String>, pairs: List<Pair<String, String>>, onlyLanguage: String?): String {
                    fileCalls++
                    check(wav.size > 44)
                    val result = results.removeFirst()
                    if (result is Throwable) throw result
                    return result as String
                }
                override fun command(key: String, context: String, last: String, spoken: String, language: String): VoiceEdit {
                    commandCalls++; error("Unexpected command request")
                }
            }).also { it.start() }
        override fun inputConnection() = editor
        override fun onVoiceUi(status: String?, level: Float, listening: Boolean) {}
        override fun showMessage(text: String) { messages.add(text) }
        override fun sendMessage(lastTry: Boolean): VoiceTyping.SendResult { sends++; return VoiceTyping.SendResult.ACTION }
        fun audio() { micPending = true; micListener.onAudio(ByteArray(24000) { 1 }) }
        fun phrase() { audio(); micPending = false; micListener.onPhraseEnd(); clock.advance() }
        fun finish(id: String, words: String) { voice.onCommitted(id); voice.onCompleted(id, words); clock.advance() }
        fun text() = editor.content.toString()
        fun close() { voice.end(); clock.advance() }
    }

    fun run(context: Context): Int {
        var passed = 0
        fun scenario(test: (Fixture) -> Unit, smart: Boolean = true) {
            val f = Fixture(context, smart)
            try { test(f); passed++ } finally { f.close() }
        }
        scenario({ f ->
            f.phrase(); f.voice.onCommitted("one"); f.voice.stop(); f.clock.advance(5000)
            check(f.voice.isActive) { "Stop discarded the pending final transcript" }
            f.voice.onCompleted("one", "All the final words.")
            check(!f.voice.isActive && f.text() == "All the final words.")
        })
        scenario({ f ->
            f.audio(); f.voice.stop(); f.clock.advance()
            check(f.voice.isActive) { "Stopped before the mic delivered its last phrase" }
            f.finish("last", "Last phrase.")
            check(f.text() == "Last phrase." && !f.voice.isActive)
        })
        scenario({ f ->
            f.phrase(); f.results.add("Recovered final words."); f.voice.stop(); f.clock.advance(12000)
            check(f.fileCalls == 1 && f.text() == "Recovered final words." && !f.voice.isActive)
        })
        scenario({ f ->
            f.phrase(); f.finish("list", "I bought milk , bread, and eggs .")
            check(f.text() == "I bought milk, bread, and eggs.")
            f.phrase(); f.finish("continuation", "Before I left")
            f.phrase(); f.finish("comma", "comma")
            check(f.text() == "I bought milk, bread, and eggs. Before I left,")
        })
        scenario({ f ->
            f.phrase(); f.results.add("אם ירד גשם , אשאר בבית .")
            f.voice.onClosed("offline", 0); f.clock.advance()
            check(f.fileCalls == 1 && f.text() == "אם ירד גשם, אשאר בבית.")
        }, smart = false)
        scenario({ f ->
            f.phrase(); f.voice.onCommitted("one"); f.voice.onDelta("one", "First")
            f.phrase(); f.voice.onCommitted("two"); f.voice.onCompleted("two", "Second.")
            f.results.add("First recovered.")
            f.voice.onClosed("connection dropped", 0); f.clock.advance()
            check(f.fileCalls == 1 && f.text() == "First recovered. Second.") { "Recovery reordered or duplicated text: ${f.text()}" }
            f.voice.onCompleted("one", "Stale first.")
            check(f.text() == "First recovered. Second.")
        })
        scenario({ f ->
            f.phrase(); f.results.add("Recovered after error.")
            f.voice.onFailed(null, "invalid model"); f.clock.advance()
            check(f.fileCalls == 1 && f.messages.isNotEmpty() && f.text() == "Recovered after error.")
        })
        scenario({ f ->
            f.phrase(); f.voice.onCommitted("late"); f.voice.end()
            f.voice.onCompleted("late", "مرحبا"); f.voice.onDelta("late", "ignored")
            f.voice.onFailed(null, "late error"); f.voice.onClosed("late close", 0); f.clock.advance()
            check(f.fileCalls == 0 && f.text().isEmpty())
        })
        scenario({ f ->
            f.phrase(); f.voice.onCommitted("partial"); f.voice.onDelta("partial", "Keep these words")
            f.results.add(java.io.IOException("offline")); f.voice.onClosed("offline", 0); f.clock.advance()
            check(f.text() == "Keep these words" && f.messages.any { it.contains("kept") })
        })
        scenario({ f ->
            f.phrase(); f.voice.onCommitted("empty"); f.voice.onDelta("empty", "Keep live words")
            f.results.add(""); f.voice.onClosed("offline", 0); f.clock.advance()
            check(f.text() == "Keep live words" && f.messages.any { it.contains("kept") })
        })
        scenario({ f ->
            f.audio(); f.voice.onDelta("ongoing", "Early words")
            f.voice.onFailed(null, "live failed before phrase end")
            f.results.add("Recovered whole phrase.")
            f.voice.stop(); f.clock.advance()
            check(f.fileCalls == 1 && f.text() == "Recovered whole phrase." && !f.voice.isActive)
        })
        scenario({ f ->
            for ((i, words) in listOf("send", "delete everything", "new line", "hi question mark").withIndex()) {
                f.phrase(); f.voice.onCommitted("literal$i"); f.voice.onDelta("literal$i", words)
                check(f.text().contains(words, ignoreCase = true)) { "Literal preview was hidden" }
                f.voice.onCompleted("literal$i", words)
            }
            f.clock.advance(2000)
            check(f.sends == 0 && f.commandCalls == 0 && f.text().contains("delete everything", true) && f.text().contains("hi question mark", true))
        }, smart = false)
        scenario({ f ->
            f.phrase(); f.voice.onCommitted("cursor"); f.voice.onDelta("cursor", "Hello there")
            f.voice.onSelectionChanged(f.text().length, f.text().length)
            check(f.voice.isActive) { "Our own composing update stopped dictation" }
            Selection.setSelection(f.editor.content, 0)
            f.voice.onSelectionChanged(0, 0)
            check(!f.voice.isActive && f.messages.any { it.contains("cursor moved") })
            val before = f.text(); f.voice.onCompleted("cursor", "Must not edit here")
            check(f.text() == before)
        })
        scenario({ f ->
            f.phrase(); f.voice.onCommitted("first"); f.phrase(); f.voice.onCommitted("second")
            f.voice.onCompleted("second", "Second."); check(f.text().contains("Second."))
            f.voice.onCompleted("first", "First.")
            check(f.text() == "First. Second.")
        })
        scenario({ f ->
            f.phrase(); f.voice.onCommitted("first"); f.phrase()
            f.voice.onCommitIgnored(f.commits.last())
            f.voice.stop(); f.clock.advance()
            check(f.voice.isActive)
            f.voice.onCompleted("first", "Only real phrase.")
            check(f.text() == "Only real phrase." && !f.voice.isActive)
        })
        return passed
    }

    fun transport(): Int {
        val clock = Clock()
        val sent = ArrayList<JSONObject>()
        var ready = 0
        var failures = 0
        var closed = 0
        var finals = 0
        lateinit var callbacks: WebSocketListener
        val socket = object : WebSocket {
            override fun request() = Request.Builder().url("https://localhost/").build()
            override fun queueSize() = 0L
            override fun send(text: String): Boolean { sent.add(JSONObject(text)); return true }
            override fun send(bytes: ByteString) = false
            override fun close(code: Int, reason: String?) = true
            override fun cancel() {}
        }
        val listener = object : LiveTranscriber.Listener {
            override fun onConnected() { ready++ }
            override fun onCommitted(itemId: String) {}
            override fun onDelta(itemId: String, delta: String) {}
            override fun onCompleted(itemId: String, transcript: String) { finals++ }
            override fun onFailed(itemId: String?, message: String) { failures++ }
            override fun onClosed(message: String?, code: Int) { closed++ }
        }
        fun connection() = LiveTranscriber("synthetic-test-key", listOf("en"), listener,
            delay = "high", main = clock, socketFactory = { _, cb -> callbacks = cb; socket }).also {
            it.connect()
            callbacks.onOpen(socket, Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("Switching").build())
        }
        fun event(json: String) { callbacks.onMessage(socket, json); clock.advance() }
        var passed = 0
        val live = connection()
        live.append(ByteArray(4800)); live.commit("turn-one"); live.append(ByteArray(4800)); live.commit("turn-two")
        check(ready == 0 && sent.size == 1) { "Audio was sent before session configuration was accepted" }
        check(sent.first().getJSONObject("session").getJSONObject("audio").getJSONObject("input")
            .getJSONObject("transcription").getString("delay") == "high")
        event("""{"type":"session.updated"}""")
        check(ready == 1 && sent.map { it.getString("type") } == listOf("session.update", "input_audio_buffer.append", "input_audio_buffer.commit", "input_audio_buffer.append", "input_audio_buffer.commit"))
        check(sent[2].getString("event_id") == "turn-one" && sent[4].getString("event_id") == "turn-two")
        passed++
        live.close()
        event("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"late","transcript":"ignored"}""")
        check(finals == 0)
        passed++
        clock.cancelAll(); sent.clear()
        val invalid = connection()
        event("""{"type":"error","error":{"param":"session.audio.input.transcription.model","message":"Invalid model","code":"invalid_value"}}""")
        check(failures == 1 && sent.size == 1) { "Invalid model errors were swallowed as optional settings" }
        invalid.close(); clock.cancelAll(); sent.clear()
        passed++
        val optional = connection()
        event("""{"type":"error","error":{"param":"session.audio.input.transcription.keywords","message":"Invalid keywords","code":"invalid_value"}}""")
        check(sent.size == 2 && failures == 1)
        event("""{"type":"session.updated"}"""); check(ready == 2)
        optional.close(); clock.cancelAll(); sent.clear()
        passed++
        val timeout = connection()
        clock.advance(10000)
        check(closed == 1) { "Unacknowledged sessions have no timeout" }
        timeout.close(); clock.cancelAll()
        passed++
        return passed
    }
}
