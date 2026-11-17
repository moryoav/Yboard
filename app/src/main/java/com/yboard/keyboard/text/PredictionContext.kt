package com.yboard.keyboard.text

/** A bounded sentence tail shared by learning, local ranking and cloud completions. */
object PredictionContext {
    const val MAX_BEFORE = 300
    const val ORDER = 4
    private val tokens = Regex("[\\p{L}\\p{M}]+(?:['’׳״][\\p{L}\\p{M}]+)*")
    private val boundary = Regex("[.!?。！？\\n\\r:;]")

    fun words(before: String): List<String> {
        val tail = before.takeLast(MAX_BEFORE).split(boundary).last()
        return tokens.findAll(tail).map { it.value }.toList().takeLast(ORDER)
    }

    fun keys(before: String): List<String> {
        val words = words(before).map { Dictionary.normalize(it.replace('’', '\'')) }
        return if (words.isEmpty()) listOf(NextWords.START)
        else (1..words.size).map { words.takeLast(it).joinToString(" ") }
    }

    fun isWordChar(char: Char) = char.isLetter() || Character.getType(char) == Character.NON_SPACING_MARK.toInt() || char in "'’׳״"

    fun prefix(before: String): String {
        var index = before.length
        while (index > 0 && isWordChar(before[index - 1])) index--
        return before.substring(index).trimStart('\'', '’', '׳', '״')
    }

    /** Split before truncation so the base stays stable as the next word is typed. */
    fun position(before: String, cursor: Int): PredictionPosition {
        val prefix = prefix(before)
        return PredictionPosition(before.dropLast(prefix.length).takeLast(MAX_BEFORE), cursor - prefix.length, prefix)
    }
}

/** The absolute anchor prevents matching a repeated phrase at a different cursor position. */
data class PredictionPosition(val before: String, val anchor: Int, val prefix: String = "") {
    fun sameWord(other: PredictionPosition) = anchor == other.anchor && before == other.before
}
