package com.yboard.keyboard.text

/**
 * "My words": words you add yourself (names, slang, brands). They show up in suggestions,
 * swipe typing finds them, autocorrect never changes them, and voice typing spells them right.
 */
object MyWords {
    const val MAX = 500

    /** Hebrew letters -> "he", anything else -> "en". */
    fun languageOf(word: String): String = if (word.any { it in 'א'..'ת' }) "he" else "en"

    private fun isHebrew(c: Char) = c in 'א'..'ת'
    private fun isWordMark(c: Char) = c == '\'' || c == '’' || c == '-' || c == '׳' || c == '״'

    /** What was typed in the box, split into words: "Yoav, Moran  Tel-Aviv" -> [Yoav, Moran, Tel-Aviv]. */
    fun parse(input: String): List<String> =
        input.split(Regex("[\\s,;/]+"))
            .map { w -> w.trim { !it.isLetter() && !isWordMark(it) }.trim { isWordMark(it) && it != '׳' && it != '\'' } }
            .filter { it.isNotEmpty() }

    /** Why [word] can't be added, or null if it's fine. */
    fun problem(word: String): String? = when {
        word.length < 2 -> "“$word” is too short."
        word.length > 32 -> "“${word.take(12)}…” is too long."
        word.any { it.isDigit() } -> "“$word”: letters only, no numbers."
        !word.all { it.isLetter() || isWordMark(it) } -> "“$word”: letters only."
        word.any { isHebrew(it) } && word.any { it.isLetter() && !isHebrew(it) } -> "“$word” mixes Hebrew and English letters."
        else -> null
    }

    /** [list] with [words] added at the top (an existing word is replaced, ignoring capitals). */
    fun add(list: List<String>, words: List<String>): List<String> {
        val fresh = words.distinctBy { Dictionary.normalize(it) }
        val keys = fresh.map { Dictionary.normalize(it) }.toSet()
        return (fresh + list.filterNot { Dictionary.normalize(it) in keys }).take(MAX)
    }

    fun remove(list: List<String>, word: String): List<String> =
        list.filterNot { Dictionary.normalize(it) == Dictionary.normalize(word) }

    // ---- the same word in the other language: (English spelling, Hebrew spelling)

    private fun hebrew(w: String) = w.any { isHebrew(it) }

    /** How [word] is written in the other language, if known. */
    fun pairOf(word: String, pairs: List<Pair<String, String>>): String? =
        if (hebrew(word)) pairs.firstOrNull { it.second == word }?.first
        else pairs.firstOrNull { it.first.equals(word, ignoreCase = true) }?.second

    /** [pairs] without any spelling of [word]. */
    fun withoutPair(pairs: List<Pair<String, String>>, word: String): List<Pair<String, String>> =
        pairs.filterNot { it.first.equals(word, ignoreCase = true) || it.second == word }

    /** [pairs] with [word] ⇄ [other] (replacing an old one), or null if both are in the same language. */
    fun withPair(pairs: List<Pair<String, String>>, word: String, other: String): List<Pair<String, String>>? {
        val w = word.trim()
        val o = other.trim()
        if (w.isEmpty() || o.isEmpty() || o.length > 40 || hebrew(w) == hebrew(o)) return null
        return withoutPair(pairs, w) + (if (hebrew(w)) o to w else w to o)
    }

    /** "Yoav = יואב" -> (Yoav, יואב); null unless it's one English and one Hebrew word. */
    fun parsePair(input: String): Pair<String, String>? {
        val parts = input.split('=', '⇄').map { it.trim() }
        if (parts.size != 2) return null
        val (a, b) = parts
        if (problem(a) != null || problem(b) != null || hebrew(a) == hebrew(b)) return null
        return if (hebrew(a)) b to a else a to b
    }
}
