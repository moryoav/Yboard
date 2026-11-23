package com.yboard.keyboard.text

/** Recent word occurrences in one editor, with no draft text persisted. */
internal class WordLearning(private val record: (String, Int) -> (() -> Unit)?) {
    private data class Occurrence(var start: Int, val word: String, val undo: (() -> Unit)?) {
        val end get() = start + word.length
    }

    private val recent = ArrayDeque<Occurrence>()
    val isEmpty get() = recent.isEmpty()
    fun maySettleAt(offset: Int) = recent.any { it.undo == null && offset !in it.start..it.end }

    fun clear() = recent.clear()

    fun confirm(start: Int?, word: String, amount: Int = 1) {
        val previous = recent.lastOrNull { it.start == start && it.word == word }
        if (previous?.undo != null) return
        if (previous != null) recent.remove(previous)
        val undo = record(word, amount) ?: return
        if (start != null && start >= 0) remember(Occurrence(start, word, undo))
    }

    /** A manual correction with a separator already present can finish by moving the caret away. */
    fun moved(offset: Int, before: String, after: String) {
        val text = before + after
        val origin = offset - before.length
        recent.filter { it.undo == null && offset !in it.start..it.end }.forEach { word ->
            val start = word.start - origin
            val end = start + word.word.length
            if (text.matchesWord(start, word.word) && text.getOrNull(end)?.isTokenChar() == false) {
                confirm(word.start, word.word)
            }
        }
    }

    /** Apply a successful edit, using the text the editor returned before the action. */
    fun edited(start: Int, before: String, removed: String, after: String, inserted: String, manual: Boolean) {
        if (recent.isEmpty() || removed == inserted) return
        val oldText = before + removed + after
        val newBefore = before + inserted
        val newText = newBefore + after
        val origin = start - before.length
        val end = start + removed.length
        val delta = inserted.length - removed.length
        var pending: Occurrence? = null
        val iterator = recent.iterator()
        while (iterator.hasNext()) {
            val word = iterator.next()
            if (word.end < start) continue
            if (word.start > end) {
                word.start += delta
                continue
            }
            val localStart = word.start - origin
            val newStart = if (word.start >= end) localStart + delta else localStart
            if (newText.matchesWord(newStart, word.word) && (word.start >= end || word.end <= start)) {
                word.start = origin + newStart
                continue
            }
            iterator.remove()
            if (!oldText.matchesWord(localStart, word.word)) continue
            word.undo?.invoke()
            if (manual && (inserted.isNotEmpty() || start > word.start || end < word.end)) {
                WordReplacement.atCursor(newBefore, after, includeWordEnd = true)?.let { span ->
                    pending = Occurrence(start + inserted.length - span.left, span.word, null)
                }
            }
        }
        pending?.let(::remember)
    }

    private fun remember(word: Occurrence) {
        if (recent.size == 32) recent.removeFirst()
        recent.addLast(word)
    }

    private fun String.matchesWord(start: Int, word: String): Boolean =
        regionMatches(start, word, 0, word.length) &&
            getOrNull(start - 1)?.isTokenChar() != true &&
            getOrNull(start + word.length)?.isTokenChar() != true

    private fun Char.isTokenChar() = PredictionContext.isWordChar(this) || isDigit()
}
