package com.yboard.keyboard.text

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * A word list loaded from `assets/dict/<language>.tsv` (lines: `word<TAB>freq`, freq 1..255,
 * most common first). Supports exact lookup, prefix completion and per-first-letter lists
 * (used by autocorrect and swipe typing).
 */
class Dictionary private constructor(
    val language: String,
    private val keys: Array<String>,      // normalized, sorted A→Z (for prefix search)
    private val spellings: Array<String>, // same order as keys
    private val freqs: IntArray,          // same order as keys
    private val index: HashMap<String, Int>,
    private val byFirst: Map<Char, List<WordFreq>>,
) {
    val size: Int get() = keys.size

    fun contains(norm: String) = index.containsKey(norm)
    fun freqOf(norm: String): Int = index[norm]?.let { freqs[it] } ?: 0
    fun spelling(norm: String): String? = index[norm]?.let { spellings[it] }

    /** All words starting with [c] (normalized), most common first. */
    fun startingWith(c: Char): List<WordFreq> = byFirst[c] ?: emptyList()

    /** The most common words that start with [prefix] (normalized). */
    fun completions(prefix: String, limit: Int): List<WordFreq> {
        if (prefix.isEmpty()) return emptyList()
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < prefix) lo = mid + 1 else hi = mid
        }
        val best = ArrayList<WordFreq>()
        var i = lo
        var scanned = 0
        while (i < keys.size && keys[i].startsWith(prefix) && scanned < 25_000) {
            val wf = WordFreq(spellings[i], freqs[i])
            if (best.size < limit) {
                best.add(wf); best.sortByDescending { it.freq }
            } else if (wf.freq > best.last().freq) {
                best[best.size - 1] = wf; best.sortByDescending { it.freq }
            }
            i++; scanned++
        }
        return best
    }

    companion object {
        fun normalize(word: String): String = word.lowercase()

        fun fromLines(language: String, lines: Sequence<String>): Dictionary {
            val entries = ArrayList<Triple<String, String, Int>>()
            val seen = HashSet<String>()
            val byFirst = HashMap<Char, ArrayList<WordFreq>>()
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val word = line.substring(0, tab).trim()
                val freq = line.substring(tab + 1).trim().toIntOrNull()?.coerceIn(1, 255) ?: continue
                if (word.isEmpty()) continue
                val norm = normalize(word)
                if (!seen.add(norm)) continue
                entries.add(Triple(norm, word, freq))
                // File is most-common-first, so these lists stay sorted by frequency.
                byFirst.getOrPut(norm[0]) { ArrayList() }.add(WordFreq(word, freq))
            }
            entries.sortBy { it.first }
            val keys = Array(entries.size) { entries[it].first }
            val spellings = Array(entries.size) { entries[it].second }
            val freqs = IntArray(entries.size) { entries[it].third }
            val index = HashMap<String, Int>(entries.size * 2)
            keys.forEachIndexed { i, k -> index[k] = i }
            byFirst.values.forEach { list -> list.sortByDescending { it.freq } }
            return Dictionary(language, keys, spellings, freqs, index, byFirst)
        }

        /** Loads the bundled dictionary, or null if it's missing. Call off the main thread. */
        fun load(context: Context, language: String): Dictionary? = try {
            context.assets.open("dict/$language.tsv").use { input ->
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16)
                fromLines(language, reader.lineSequence())
            }
        } catch (e: Exception) {
            null
        }
    }
}
