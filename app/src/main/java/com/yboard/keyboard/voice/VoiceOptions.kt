package com.yboard.keyboard.voice

enum class TranscriptionQuality(val delay: String, val label: String) {
    FASTER("low", "Faster"), BALANCED("medium", "Balanced"), ACCURATE("high", "More accurate"),
}

data class VoiceOptions(
    val quality: TranscriptionQuality = TranscriptionQuality.BALANCED,
    val pauseMs: Int = 500,
    /** Zero keeps listening until the mic is stopped manually. */
    val silenceSeconds: Int = 15,
) {
    val endFrames: Int get() = pauseMs.coerceIn(250, 1500) / 20
    val idleFrames: Int get() = if (silenceSeconds == 0) 0 else silenceSeconds.coerceIn(8, 60) * 50
}

/** Distinguishes our asynchronous editor updates from the user moving the cursor. */
class VoiceSelection(initial: Int) {
    private var last = initial
    private val expected = ArrayDeque<Int>()

    fun expect(position: Int) {
        if (position < 0) return
        expected.addLast(position)
        while (expected.size > 64) expected.removeFirst()
    }

    fun reset(position: Int) { last = position; expected.clear() }

    fun moved(start: Int, end: Int): Boolean {
        if (start < 0 || end < 0) return false
        if (start != end) return true
        val index = expected.indexOfFirst { it == start }
        if (index >= 0) {
            repeat(index + 1) { expected.removeFirst() }
            last = start
            return false
        }
        if (start == last) return false
        // Some editors don't supply an initial position. Establish it on their first update.
        if (last < 0 && expected.isEmpty()) { last = start; return false }
        return true
    }
}
