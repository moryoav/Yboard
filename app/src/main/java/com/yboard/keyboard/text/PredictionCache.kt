package com.yboard.keyboard.text

/** Session-only cache. No editor text or cloud result is written to disk. Main thread only. */
class PredictionCache(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(val position: PredictionPosition, val phrases: List<String>, val at: Long, val complete: Boolean)
    private val entries = object : LinkedHashMap<Pair<String, Int>, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Int>, Entry>?) = size > 64
    }

    fun put(position: PredictionPosition, phrases: List<String>, complete: Boolean = true) {
        if (phrases.isNotEmpty()) entries[position.before to position.anchor] = Entry(position.copy(prefix = ""), phrases, now(), complete)
    }

    fun complete(position: PredictionPosition): Boolean = entries[position.before to position.anchor]?.let { it.complete && now() - it.at <= 120_000 } == true
    fun remove(position: PredictionPosition) { entries.remove(position.before to position.anchor) }

    fun clear() = entries.clear()

    /** Continue a cached phrase only when the exact editor tail and absolute anchor agree. */
    fun phrases(position: PredictionPosition): List<String> {
        val time = now()
        entries.entries.removeAll { time - it.value.at > 120_000 }
        val exact = entries[position.before to position.anchor]?.phrases.orEmpty()
        val continued = entries.values.toList().asReversed().flatMap { entry ->
            val length = position.anchor - entry.position.anchor
            if (length !in 1..100 || length > position.before.length) return@flatMap emptyList()
            val added = position.before.takeLast(length)
            if (!added.last().isWhitespace() || (entry.position.before + added).takeLast(PredictionContext.MAX_BEFORE) != position.before)
                return@flatMap emptyList()
            // Punctuation and newlines invalidate the old sentence's continuation.
            if (added.any { !it.isLetter() && !it.isWhitespace() && it !in "'’׳״" } || added.any { it == '\n' || it == '\r' })
                return@flatMap emptyList()
            val consumed = added.trim().split(Regex("\\s+"))
            entry.phrases.mapNotNull { phrase ->
                val words = phrase.split(' ')
                if (words.size <= consumed.size || words.take(consumed.size).map { it.lowercase() } != consumed.map { it.lowercase() }) null
                else words.drop(consumed.size).joinToString(" ")
            }
        }
        return (exact + continued).distinctBy { it.lowercase() }
    }

    /** Only the next word is displayed; phrase tails are ready for the following tap. */
    fun words(position: PredictionPosition): List<String> = phrases(position).map { it.substringBefore(' ') }
        .filter { it.startsWith(position.prefix, true) && !it.equals(position.prefix, true) }
        .distinctBy { it.lowercase() }.take(3)
}
