package com.yboard.keyboard.text

import com.yboard.keyboard.keyboard.KeyType
import com.yboard.keyboard.keyboard.Layouts
import com.yboard.keyboard.keyboard.Mode
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Word suggestions and autocorrect. Mixes the bundled dictionary with words you've used
 * ([UserWords]); typos are scored with a keyboard-aware edit distance (hitting a key next
 * to the right one is a small mistake, hitting one far away is a big one).
 */
class Suggester(
    private val dict: Dictionary?,
    private val user: UserWords,
    private val language: String,
) {
    data class Result(
        val typed: String,
        /** The typed word is a real (or learned) word. */
        val known: Boolean,
        /** Best first, already in the typed word's capitalization. Never contains [typed]. */
        val suggestions: List<String>,
        /** What space/punctuation should turn the word into, or null to leave it. */
        val autoCorrect: String?,
    )

    private val positions: Map<Char, Pair<Float, Float>> = keyPositions(language)
    private var cachedRevision = -1L
    private val recent = object : LinkedHashMap<Pair<String, Int>, Result>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Int>, Result>?) = size > 8
    }

    fun isKnown(word: String): Boolean {
        val norm = Dictionary.normalize(word)
        return (dict?.contains(norm) == true) || user.isKnown(norm)
    }

    fun suggest(typed: String, limit: Int = 3): Result {
        if (cachedRevision != user.cacheRevision) { recent.clear(); cachedRevision = user.cacheRevision }
        return recent.getOrPut(typed to limit) { compute(typed, limit) }
    }

    private fun compute(typed: String, limit: Int): Result {
        val norm = Dictionary.normalize(typed)
        val known = isKnown(typed)
        if (norm.isEmpty() || dict == null) return Result(typed, known, emptyList(), null)

        val n = norm.length
        // word (spelling) -> (score, edit distance)
        val scored = HashMap<String, Pair<Double, Double>>()
        fun consider(wf: WordFreq, dist: Double, completion: Boolean) {
            val wn = Dictionary.normalize(wf.word)
            val freq = combinedFreq(wn, wf.freq)
            var score = ln(freq + 1.0) - 2.4 * dist
            if (completion) score -= 0.32 * (wn.length - n)
            if (wn == norm) score += 2.0
            val spelling = user.spelling(wn)?.takeIf { dict.spelling(wn) == null } ?: wf.word
            val prev = scored[spelling]
            if (prev == null || prev.first < score) scored[spelling] = score to dist
        }

        // 1. Completions: words that start with what you've typed.
        dict.completions(norm, 8).forEach { consider(it, 0.0, true) }
        user.knownWithPrefix(norm).forEach { consider(it, 0.0, true) }

        // 2. Corrections: close words, by keyboard-aware edit distance.
        val maxDist = when {
            n <= 2 -> 0.6
            n <= 4 -> 1.1
            else -> 1.8
        }
        val firsts = LinkedHashSet<Char>()
        firsts.add(norm[0])
        if (n >= 3) neighbors(norm[0]).forEach { firsts.add(it) }
        finalFormPartner(norm[0])?.let { firsts.add(it) }
        for (fc in firsts) {
            val list = dict.startingWith(fc)
            val cap = if (fc == norm[0]) list.size else min(list.size, 1500)
            for (i in 0 until cap) {
                val wf = list[i]
                val wl = wf.word.length
                if (abs(wl - n) > 2) continue
                val d = distance(norm, Dictionary.normalize(wf.word), maxDist)
                if (d <= maxDist) consider(wf, d, false)
            }
            user.knownStartingWith(fc).forEach { wf ->
                val d = distance(norm, Dictionary.normalize(wf.word), maxDist)
                if (d <= maxDist) consider(wf, d, false)
            }
        }

        val ranked = scored.entries.sortedByDescending { it.value.first }
        val suggestions = ranked.map { it.key }
            .filter { Dictionary.normalize(it) != norm }
            .take(limit)
            .map { applyCase(typed, it) }

        var autoCorrect: String? = null
        // One of your own words, typed in small letters: use your spelling ("whatsapp" -> "WhatsApp").
        // Not for everyday words ("Apple" the brand shouldn't change every "apple").
        val everyday = dict.spelling(norm)?.let { it == it.lowercase() } == true
        if (known && !everyday && user.isMine(norm) && typed.none { it.isUpperCase() }) {
            user.spelling(norm)?.takeIf { it != typed }?.let { return Result(typed, true, suggestions, it) }
        }
        val canCorrect = !known && n >= 2 && typed.none { it.isDigit() } &&
            !(typed.length > 1 && typed.drop(1).any { it.isUpperCase() } && !typed.all { !it.isLetter() || it.isUpperCase() })
        if (canCorrect) {
            val autoLimit = if (language == "he") 0.65 else if (n <= 4) 1.05 else 1.5
            val best = ranked.firstOrNull { Dictionary.normalize(it.key) != norm && it.key.length <= n + 2 }
            if (best != null && best.value.second <= autoLimit) autoCorrect = applyCase(typed, best.key)
        }
        return Result(typed, known, suggestions, autoCorrect)
    }

    private fun combinedFreq(norm: String, base: Int): Int {
        val c = user.count(norm)
        return if (c == 0) base else maxOf(base + 6 * min(c, 12), UserWords.learnedFreq(c))
    }

    // ------------------------------------------------------------- distance

    private fun keyDist(a: Char, b: Char): Float {
        val pa = positions[a] ?: return 9f
        val pb = positions[b] ?: return 9f
        val dx = pa.first - pb.first
        val dy = pa.second - pb.second
        return sqrt(dx * dx + dy * dy)
    }

    private fun neighbors(c: Char): List<Char> = positions.keys.filter { it != c && keyDist(c, it) <= 1.3f }

    private fun subCost(a: Char, b: Char): Double = when {
        a == b -> 0.0
        finalFormPartner(a) == b -> 0.25
        keyDist(a, b) <= 1.3f -> 0.45
        else -> 1.0
    }

    private fun indelCost(word: String, i: Int): Double {
        val c = word[i]
        if (c == '\'' || c == '’' || c == '-' || c == '׳' || c == '״') return 0.2
        if ((i > 0 && word[i - 1] == c) || (i + 1 < word.length && word[i + 1] == c)) return 0.5
        return 1.0
    }

    /** Keyboard-aware Damerau-Levenshtein distance, giving up early above [limit]. */
    fun distance(a: String, b: String, limit: Double = 99.0): Double {
        val n = a.length
        val m = b.length
        var prev2 = DoubleArray(m + 1)
        var prev = DoubleArray(m + 1)
        var cur = DoubleArray(m + 1)
        for (j in 1..m) prev[j] = prev[j - 1] + indelCost(b, j - 1)
        for (i in 1..n) {
            cur[0] = prev[0] + indelCost(a, i - 1)
            var rowMin = cur[0]
            for (j in 1..m) {
                var v = minOf(
                    prev[j] + indelCost(a, i - 1),
                    cur[j - 1] + indelCost(b, j - 1),
                    prev[j - 1] + subCost(a[i - 1], b[j - 1]),
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, prev2[j - 2] + 0.6)
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit) return rowMin
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    companion object {
        private val finals = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')
        private val finalsReverse = finals.entries.associate { it.value to it.key }

        fun finalFormPartner(c: Char): Char? = finals[c] ?: finalsReverse[c]

        /** Matches the capitalization of what was typed: "Helo" -> "Hello", "HELO" -> "HELLO". */
        fun applyCase(typed: String, word: String): String {
            val letters = typed.filter { it.isLetter() }
            return when {
                letters.length > 1 && letters.all { it.isUpperCase() } -> word.uppercase()
                typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
                else -> word
            }
        }

        /** Reorder model candidates for a typed prefix, preserving the model's order at a word boundary. */
        internal fun modelCompletions(prefix: String, candidates: List<ScoredWord>, dictionary: Dictionary?, user: UserWords?): List<String> {
            val usage = user?.takeIf { prefix.isNotEmpty() }
            val matching = candidates.mapNotNull { (word, score) ->
                val normalized = Dictionary.normalize(word)
                val spelling = dictionary?.spelling(normalized)
                    ?: user?.takeIf { it.isKnown(normalized) }?.spelling(normalized)
                val completion = modelCompletion(prefix, word, spelling) ?: return@mapNotNull null
                val bonus = usage?.let { UserWords.rankingBonus(it.count(normalized)) } ?: 0.0
                ScoredWord(completion, score + bonus)
            }
            val ordered = if (usage != null) matching.sortedByDescending { it.score } else matching
            return ordered.map { it.word }.distinct()
        }

        /** Prefer established spellings and suppress mixed-case guesses unsupported by the prefix. */
        internal fun modelCompletion(prefix: String, word: String, spelling: String?): String? {
            if (!word.startsWith(prefix, ignoreCase = true)) return null
            if (spelling != null) return applyCase(prefix, spelling)
            if (prefix.isNotEmpty() && word.equals(prefix, ignoreCase = true)) return prefix
            if (word.drop(1).none { it.isUpperCase() }) return applyCase(prefix, word)
            if (prefix.isNotEmpty() && word.startsWith(prefix) &&
                word.drop(prefix.length).none { it.isUpperCase() }) return word
            if (word.none { it.isLowerCase() } && prefix.none { it.isLowerCase() }) return word
            return null
        }

        /** Letter key centers in "key units" (1 = one key) for the language's letter layout. */
        fun keyPositions(language: String): Map<Char, Pair<Float, Float>> {
            val layout = Layouts.build(Mode.LETTERS, language, numberRow = false, multiLang = true)
            val out = HashMap<Char, Pair<Float, Float>>()
            layout.rows.forEachIndexed { r, row ->
                val total = row.keys.sumOf { it.width.toDouble() }.toFloat()
                var x = (10f - total) / 2f
                row.keys.forEach { k ->
                    if (k.type == KeyType.CHAR && k.label.length == 1) {
                        out[Dictionary.normalize(k.label)[0]] = (x + k.width / 2f) to r.toFloat()
                    }
                    x += k.width
                }
            }
            return out
        }
    }
}
