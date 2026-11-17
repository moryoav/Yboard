package com.yboard.keyboard.voice

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds speech in microphone audio by loudness, and where each phrase ends (a short pause).
 * Works on 20 ms frames. It adapts to the room's background noise.
 */
class Vad(
    private val startFrames: Int = 3,      // 60 ms of voice starts a phrase
    private val endFrames: Int = 25,       // 500 ms of quiet ends it
    private val prerollFrames: Int = 15,   // keep 300 ms from before the voice started
    private val maxPhraseFrames: Int = 750, // cut very long phrases at 15 s
    private val idleFrames: Int = 400,     // 8 s with nothing said
) {
    interface Sink {
        fun audio(frame: ShortArray)
        fun phraseEnd()
        fun longSilence()
        fun level(level: Float)
    }

    var inSpeech = false
        private set
    var noiseDb = -60.0
        private set

    /** Set while the words so far are a whole command: a shorter pause ends the phrase. */
    @Volatile var quickEndFrames: Int = 0

    private var loudRun = 0
    private var quietRun = 0
    private var phraseFrames = 0
    private var idle = 0
    private val preroll = ArrayDeque<ShortArray>()

    fun process(frame: ShortArray, sink: Sink) {
        val db = dbfs(frame)
        sink.level(((db + 58.0) / 40.0).coerceIn(0.0, 1.0).toFloat())
        val threshold = max(noiseDb + 11.0, -50.0)
        val loud = db > threshold
        if (!inSpeech) {
            preroll.addLast(frame.copyOf())
            if (preroll.size > prerollFrames) preroll.removeFirst()
            if (loud) {
                loudRun++
                noiseDb = noiseDb * 0.995 + db * 0.005   // slowly follow a louder room
            } else {
                loudRun = 0
                noiseDb = (noiseDb * 0.95 + db * 0.05).coerceIn(-80.0, -35.0)
            }
            if (loudRun >= startFrames) {
                inSpeech = true
                quietRun = 0
                phraseFrames = preroll.size
                preroll.forEach { sink.audio(it) }
                preroll.clear()
                idle = 0
            } else {
                idle++
                if (idleFrames > 0 && idle == idleFrames) sink.longSilence()
            }
        } else {
            sink.audio(frame)
            phraseFrames++
            if (loud) quietRun = 0 else quietRun++
            val needed = quickEndFrames.takeIf { it > 0 } ?: endFrames
            if (quietRun >= needed || phraseFrames >= maxPhraseFrames) {
                inSpeech = false
                quickEndFrames = 0
                loudRun = 0
                idle = 0
                sink.phraseEnd()
            }
        }
    }

    companion object {
        fun dbfs(frame: ShortArray): Double {
            if (frame.isEmpty()) return -100.0
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            val rms = sqrt(sum / frame.size) / 32768.0
            return 20.0 * log10(rms + 1e-9)
        }
    }
}

/** 16-bit mono PCM to a .wav file. */
object Wav {
    fun encode(pcm: ByteArray, rate: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(44 + pcm.size)
        fun int(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
        fun short(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    fun toBytes(frame: ShortArray): ByteArray {
        val b = ByteArray(frame.size * 2)
        for (i in frame.indices) {
            val s = frame[i].toInt()
            b[i * 2] = (s and 0xff).toByte()
            b[i * 2 + 1] = (s shr 8 and 0xff).toByte()
        }
        return b
    }
}
