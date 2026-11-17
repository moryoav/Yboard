package com.yboard.keyboard.text

/** Bounded, memory-only timings and counters. Never contains text, credentials or field IDs. */
object PredictionStats {
    private val samples = HashMap<String, ArrayDeque<Long>>()
    private val counts = HashMap<String, Long>()

    @Synchronized fun time(kind: String, millis: Long) {
        val values = samples.getOrPut(kind) { ArrayDeque() }
        if (values.size == 256) values.removeFirst()
        values.addLast(millis.coerceAtLeast(0))
    }

    @Synchronized fun count(kind: String) { counts[kind] = (counts[kind] ?: 0) + 1 }

    @Synchronized fun summary(): String = (samples.toSortedMap().map { (kind, values) ->
        val sorted = values.sorted()
        "$kind n=${sorted.size} p50=${sorted[(sorted.size - 1) / 2]}ms p95=${sorted[((sorted.size * 95 + 99) / 100 - 1).coerceIn(sorted.indices)]}ms"
    } + counts.toSortedMap().map { (kind, count) -> "$kind=$count" }).joinToString("; ")

    @Synchronized fun reset() { samples.clear(); counts.clear() }
}
