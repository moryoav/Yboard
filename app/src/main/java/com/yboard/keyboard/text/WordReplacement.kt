package com.yboard.keyboard.text

/** Finds the whole word at the caret while leaving surrounding punctuation in the editor. */
internal object WordReplacement {
    private const val QUOTES = "'’׳״"

    data class Span(val word: String, val left: Int, val right: Int)

    fun atCursor(before: String, after: String, includeWordEnd: Boolean): Span? {
        var start = before.length
        var end = 0
        while (start > 0 && PredictionContext.isWordChar(before[start - 1])) start--
        while (end < after.length && PredictionContext.isWordChar(after[end])) end++
        if (end == 0 && !includeWordEnd) return null

        val raw = before.substring(start) + after.substring(0, end)
        val word = raw.trim { it in QUOTES }
        if (word.length !in 1..32 || word.none { it.isLetter() }) return null
        if (before.getOrNull(start - 1)?.isDigit() == true || after.getOrNull(end)?.isDigit() == true) return null

        val leadingQuotes = raw.indexOfFirst { it !in QUOTES }
        val left = before.length - start - leadingQuotes
        val right = word.length - left
        if (left < 0 || right < 0 || right == 0 && !includeWordEnd) return null
        return Span(word, left, right)
    }
}
