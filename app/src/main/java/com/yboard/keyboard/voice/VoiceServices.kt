package com.yboard.keyboard.voice

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

interface VoiceRecorder {
    val speaking: Boolean
    fun start()
    fun stop()
    fun setQuickEnd(frames: Int)
}

interface VoiceConnection {
    fun connect()
    fun append(pcm: ByteArray)
    fun commit(turnId: String)
    fun close()
}

interface VoiceDispatcher {
    fun post(task: () -> Unit)
    fun postDelayed(task: () -> Unit, delayMs: Long)
    fun cancelAll()
}

class MainVoiceDispatcher : VoiceDispatcher {
    private val handler = Handler(Looper.getMainLooper())
    override fun post(task: () -> Unit) { handler.post(task) }
    override fun postDelayed(task: () -> Unit, delayMs: Long) { handler.postDelayed(task, delayMs) }
    override fun cancelAll() { handler.removeCallbacksAndMessages(null) }
}

/** Platform boundaries, also used to exercise the real controller without a mic or cloud calls. */
open class VoiceServices {
    open val dispatcher: VoiceDispatcher by lazy {
        MainVoiceDispatcher()
    }
    open val worker by lazy { Executors.newSingleThreadExecutor() }
    open val aiWorker by lazy { Executors.newSingleThreadExecutor() }
    open fun microphone(listener: MicStream.Listener, options: VoiceOptions): VoiceRecorder = MicStream(listener, options)
    open fun connection(key: String, languages: List<String>, listener: LiveTranscriber.Listener,
                        vocabulary: List<String>, pairs: List<Pair<String, String>>, options: VoiceOptions): VoiceConnection =
        LiveTranscriber(key, languages, listener, vocabulary, pairs, options.quality.delay)
    open fun transcribe(key: String, wav: ByteArray, languages: List<String>, context: String,
                        vocabulary: List<String>, pairs: List<Pair<String, String>>, onlyLanguage: String? = null): String =
        OpenAi.transcribeFile(key, wav, languages, context, vocabulary, pairs, onlyLanguage)
    open fun command(key: String, context: String, last: String, spoken: String, language: String): VoiceEdit =
        OpenAi.command(key, context, last, spoken, language)
}
