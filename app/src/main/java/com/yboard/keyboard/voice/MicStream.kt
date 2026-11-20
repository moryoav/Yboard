package com.yboard.keyboard.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Records the microphone at 24 kHz on its own thread and feeds it through [Vad].
 * Only speech (plus a little before it) is passed on, so silence isn't sent to OpenAI.
 * Listener calls come from the recording thread.
 */
class MicStream(private val listener: Listener, private val options: VoiceOptions = VoiceOptions()) : VoiceRecorder {

    interface Listener {
        fun onAudio(pcm: ByteArray)
        fun onPhraseEnd()
        fun onLevel(level: Float)
        fun onLongSilence()
        fun onMicError(message: String)
        /** Recording has fully stopped (after any last [onPhraseEnd]). */
        fun onMicStopped()
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var vad: Vad? = null

    /** Someone is talking right now. */
    override val speaking: Boolean get() = vad?.inSpeech == true

    override fun setPauseHint(commandFrames: Int, endsSentence: Boolean) {
        vad?.takeIf { it.inSpeech }?.let {
            it.transcriptEndsSentence = endsSentence
            it.quickEndFrames = commandFrames
        }
    }

    override fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "yboard-mic").apply { start() }
    }

    /** Stops recording. A phrase still being spoken is ended, so its words aren't lost. */
    override fun stop() {
        running = false
    }

    @SuppressLint("MissingPermission")  // checked before voice typing starts
    private fun open(rate: Int): AudioRecord? = try {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) null else {
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, rate / 5 * 2),
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
        }
    } catch (e: Exception) {
        null
    }

    private fun loop() {
        val rate = OpenAi.SAMPLE_RATE
        var factor = 1
        val rec = open(rate) ?: open(rate * 2)?.also { factor = 2 }
        if (rec == null) {
            listener.onMicError("Couldn't open the microphone. Is another app using it?")
            listener.onMicStopped()
            return
        }
        val vad = Vad(
            endFrames = options.endFrames,
            idleFrames = options.idleFrames,
            continuationEndFrames = options.continuationEndFrames,
        ).also { this.vad = it }
        val frame = rate / 50                       // 20 ms
        val raw = ShortArray(frame * factor)
        val out = ShortArray(frame)
        var levelTick = 0
        val sink = object : Vad.Sink {
            override fun audio(frame: ShortArray) = listener.onAudio(Wav.toBytes(frame))
            override fun phraseEnd() = listener.onPhraseEnd()
            override fun longSilence() = listener.onLongSilence()
            override fun level(level: Float) {
                if (levelTick++ % 3 == 0) listener.onLevel(level)  // ~16 updates a second is plenty
            }
        }
        try {
            rec.startRecording()
            while (running) {
                var got = 0
                while (got < raw.size && running) {
                    val n = rec.read(raw, got, raw.size - got)
                    if (n < 0) throw IllegalStateException("read failed ($n)")
                    got += n
                }
                if (got < raw.size) break
                if (factor == 2) {
                    for (i in 0 until frame) out[i] = ((raw[i * 2] + raw[i * 2 + 1]) / 2).toShort()
                    vad.process(out, sink)
                } else {
                    vad.process(raw.copyOf(), sink)
                }
            }
            if (vad.inSpeech) listener.onPhraseEnd()
        } catch (e: Exception) {
            listener.onMicError("The microphone stopped working.")
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            listener.onMicStopped()
        }
    }
}
