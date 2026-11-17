package com.yboard.keyboard.text

import android.content.Context
import kotlin.math.ln

/** Small, original English/Hebrew phrase corpus, indexed once off the input thread. */
class PhraseModel private constructor(private val contexts: Map<String, List<Pair<String, Int>>>) {
    val size get() = contexts.size

    fun candidates(key: String): List<Pair<String, Double>> = contexts[key].orEmpty().mapIndexed { index, (word, count) ->
        word to (3.8 + (if (key == NextWords.START) 0 else key.count { it == ' ' }) * 3.0 +
            0.35 * ln(count.toDouble() + 1) - index * 0.12)
    }

    companion object {
        fun fromLines(lines: Sequence<String>): PhraseModel {
            val all = LinkedHashMap<String, LinkedHashMap<String, Int>>()
            lines.map { it.trim() }.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                var before = ""
                Regex("[\\p{L}\\p{M}]+(?:['’׳״][\\p{L}\\p{M}]+)*|[.!?\\n:;]").findAll(line).forEach token@ { match ->
                    val word = match.value
                    if (word.length == 1 && !word[0].isLetter()) { before = ""; return@token }
                    for (key in PredictionContext.keys(before)) {
                        val next = all.getOrPut(key) { LinkedHashMap() }
                        val spelling = next.keys.firstOrNull { it.equals(word, true) } ?: word
                        next[spelling] = (next[spelling] ?: 0) + 1
                    }
                    before = (before + word + " ").takeLast(PredictionContext.MAX_BEFORE)
                }
            }
            return PhraseModel(all.mapValues { (_, words) -> words.entries.sortedByDescending { it.value }.take(24).map { it.key to it.value } })
        }

        fun load(context: Context, language: String): PhraseModel = context.assets.open("prediction/$language.txt")
            .bufferedReader(Charsets.UTF_8).use { fromLines(it.lineSequence()) }
    }
}
