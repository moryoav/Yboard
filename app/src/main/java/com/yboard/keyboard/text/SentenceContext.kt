package com.yboard.keyboard.text

/** Full current sentence and its preceding sentence, with no four-word/300-character truncation. */
object SentenceContext {
    const val READ_LIMIT = 32_768
    private val ending = Regex("[.!?。！？]+[\"'’”)]*(?=\\s|$)|[\\n\\r]+")
    data class Input(val context: String, val prefix: String, val before: String, val cursor: Int) {
        val anchor get() = cursor - prefix.length
        fun sameWord(other: Input) = context == other.context && anchor == other.anchor
    }

    fun capture(before: String, cursor: Int): Input {
        val prefix = PredictionContext.prefix(before)
        val base = before.dropLast(prefix.length)
        val boundaries = ending.findAll(base).toList()
        // Preserve one complete preceding sentence as well as the unfinished current sentence.
        val start = if (boundaries.size >= 2) boundaries[boundaries.size - 2].range.last + 1 else 0
        return Input(base.substring(start).trimStart(), prefix, before, cursor)
    }
}
