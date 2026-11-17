package com.yboard.keyboard.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.yboard.keyboard.R
import kotlin.random.Random

/** Yboard's own click sounds (made for Yboard), played with low latency through SoundPool. */
object KeySounds {
    /** Style id -> label shown in settings. "system" uses the phone's own click. */
    val STYLES: List<Pair<String, String>> = listOf(
        "system" to "Phone",
        "soft" to "Soft",
        "typewriter" to "Typewriter",
        "mechanical" to "Mechanical",
        "bubble" to "Bubble",
        "wood" to "Wood",
        "glass" to "Glass",
    )

    private val resources = mapOf(
        "soft" to R.raw.key_soft,
        "typewriter" to R.raw.key_typewriter,
        "mechanical" to R.raw.key_mechanical,
        "bubble" to R.raw.key_bubble,
        "wood" to R.raw.key_wood,
        "glass" to R.raw.key_glass,
    )

    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()

    @Synchronized
    private fun ensure(context: Context): SoundPool {
        pool?.let { return it }
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        p.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(this) { ready.add(id) } }
        val app = context.applicationContext
        resources.forEach { (style, res) -> ids[style] = p.load(app, res, 1) }
        pool = p
        return p
    }

    /** Loads the sounds early so the first key press isn't silent. */
    fun warmUp(context: Context) {
        ensure(context)
    }

    fun play(context: Context, style: String, type: KeyType?, volume: Float) {
        val p = ensure(context)
        val id = ids[style] ?: return
        if (synchronized(this) { id !in ready }) return
        // Slightly different pitch for big keys, plus tiny random variation so fast typing sounds natural.
        val base = when (type) {
            KeyType.SPACE -> 0.88f
            KeyType.ENTER -> 0.8f
            KeyType.BACKSPACE -> 1.08f
            else -> 1f
        }
        val rate = (base * (0.97f + Random.nextFloat() * 0.06f)).coerceIn(0.5f, 2f)
        val v = volume.coerceIn(0.05f, 1f)
        p.play(id, v, v, 1, 0, rate)
    }
}
