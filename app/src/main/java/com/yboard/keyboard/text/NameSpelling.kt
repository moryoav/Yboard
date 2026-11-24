package com.yboard.keyboard.text

import kotlin.math.abs

/**
 * Names from "My words" in the language you're speaking: in a Hebrew sentence "Yoav" becomes
 * "יואב" (and "ל-Yoav" becomes "ליואב"); in an English sentence "יואב" becomes "Yoav".
 * [pairs] are (English spelling, Hebrew spelling). The speech model's spelling may be off by a
 * letter ("Yoab"), and that still counts.
 */
object NameSpelling {

    private fun isHebrew(c: Char) = c in 'א'..'ת'
    private fun isLatin(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private val word = Regex("[\\p{L}'׳״’-]+")
    /** One or two Hebrew prefix letters stuck to an English name: "ל-Yoav", "ולYoav". */
    private val prefixed = Regex("^([ובלמשהכ]{1,2})-?([A-Za-z].*)$")
    private val finals = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')

    /** For comparing: small letters, Hebrew final letters as their normal form, no geresh marks. */
    private fun key(w: String) = w.lowercase().filter { it != '\'' && it != '׳' && it != '’' }.map { finals[it] ?: it }.joinToString("")

    /** Which language [text] is in: true = Hebrew, false = English, null = can't tell (no letters). */
    fun hebrewText(text: String): Boolean? {
        val h = text.count { isHebrew(it) }
        val l = text.count { isLatin(it) }
        return when {
            h > l -> true
            l > h -> false
            else -> null
        }
    }

    /** The spelling [map] has for [w]: exact, or the one name that's off by a letter (two for long names). */
    private fun lookup(w: String, map: Map<String, String>, isDictionaryWord: (String) -> Boolean?): String? {
        val k = key(w)
        map[k]?.let { return it }
        if (k.length < 4 || map.isEmpty() || isDictionaryWord(w) == true) return null
        return nearbySpelling(k, map, if (k.length >= 7) 2 else 1)
    }

    private fun nearbySpelling(k: String, map: Map<String, String>, allowed: Int): String? {
        val close = map.keys.filter { abs(it.length - k.length) <= allowed && distance(it, k) <= allowed }
        return if (close.size == 1) map[close[0]] else null
    }

    fun fix(
        text: String,
        pairs: List<Pair<String, String>>,
        hebrewByDefault: Boolean,
        vocabulary: List<String> = emptyList(),
        isDictionaryWord: (String) -> Boolean? = { null },
    ): String {
        if (text.isBlank() || (pairs.isEmpty() && vocabulary.isEmpty())) return text
        val toHebrew = pairs.associate { key(it.first) to it.second }
        val toEnglish = pairs.associate { key(it.second) to it.first }
        val savedSpellings = (pairs.flatMap { listOf(it.first, it.second) } + vocabulary)
            .groupBy(MyWords::languageOf)
            .mapValues { (_, words) -> words.associateBy(::key) }

        // Repair a near miss in the same alphabet only when the bundled dictionary rules it out.
        fun sameScriptSpelling(w: String): String? {
            val spellings = savedSpellings[MyWords.languageOf(w)] ?: return null
            val k = key(w)
            if (k.length < 4 || k in spellings || isDictionaryWord(w) != false) return null
            return nearbySpelling(k, spellings, 1)
        }

        // A lone near miss gives no sentence context for switching alphabets.
        val loneWord = word.findAll(text).singleOrNull()
        if (loneWord != null) {
            sameScriptSpelling(loneWord.value)?.let { return text.replaceRange(loneWord.range, it) }
        }

        /** An English-letter name (maybe with Hebrew prefix letters): (prefix, Hebrew spelling). */
        fun englishName(w: String): Pair<String, String>? {
            if (w.none { isLatin(it) }) return null
            if (w.none { isHebrew(it) }) lookup(w, toHebrew, isDictionaryWord)?.let { return "" to it }
            val m = prefixed.find(w) ?: return null
            return lookup(m.groupValues[2], toHebrew, isDictionaryWord)?.let { m.groupValues[1] to it }
        }
        fun hebrewName(w: String): String? =
            if (w.none { isLatin(it) } && w.any { isHebrew(it) }) lookup(w, toEnglish, isDictionaryWord) else null

        // The sentence's language, from its other words.
        var hebrew = 0
        var english = 0
        word.findAll(text).forEach { m ->
            val w = m.value
            if (englishName(w) != null || hebrewName(w) != null) return@forEach
            if (w.any { isHebrew(it) }) hebrew++ else if (w.any { isLatin(it) }) english++
        }
        val inHebrew = when {
            hebrew > english -> true
            english > hebrew -> false
            else -> hebrewByDefault
        }
        return word.replace(text) { m ->
            val w = m.value
            val translated = if (inHebrew) englishName(w)?.let { (prefix, name) -> prefix + name } else hebrewName(w)
            translated ?: sameScriptSpelling(w) ?: w
        }
    }

    /** Edit distance between two short words. */
    fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
