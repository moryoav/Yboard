package com.yboard.keyboard.voice

/** Script checks for the languages supported by voice typing. */
object Scripts {

    private val word = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’׳״-][\\p{L}\\p{M}\\p{N}]+)*")
    private val extraSpaces = Regex("[ \\t]{2,}")
    private val latinWord = Regex("[A-Za-z']+")

    private fun disallowed(cp: Int, languages: List<String>): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.LATIN -> "en" !in languages || cp > 0x7f
        Character.UnicodeScript.HEBREW -> "he" !in languages
        Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED ->
            // Arabic punctuation and marks can be assigned to the Common or Inherited script.
            cp in 0x0600..0x06ff || cp in 0x0300..0x036f || Character.isLetter(cp)
        else -> true
    }

    fun hasDisallowed(text: String, languages: List<String>): Boolean =
        text.codePoints().anyMatch { disallowed(it, languages) }

    /** Drop whole contaminated words, so their allowed fragments cannot be mistaken for words. */
    fun withoutDisallowed(text: String, languages: List<String>): String {
        if (!hasDisallowed(text, languages)) return text
        val words = word.replace(text) { if (hasDisallowed(it.value, languages)) " " else it.value }
        val cleaned = buildString {
            words.codePoints().forEach { cp ->
                if (disallowed(cp, languages)) append(' ') else appendCodePoint(cp)
            }
        }.replace(extraSpaces, " ").trim()
        return cleaned.takeIf { it.codePoints().anyMatch { cp -> Character.isLetterOrDigit(cp) } }.orEmpty()
    }

    /** Arabic-family letters: the speech model mixes Hebrew up with these. */
    fun looksSemitic(text: String): Boolean = text.codePoints().anyMatch {
        val s = Character.UnicodeScript.of(it)
        s == Character.UnicodeScript.ARABIC || s == Character.UnicodeScript.SYRIAC
    }

    /**
     * The phrase's Latin words mostly aren't English ("hola amigos como estas"). [isEnglish] answers null when it can't
     * tell; [names] (your own words) and capitalized names in the middle of a sentence are never counted.
     */
    fun looksLikeOtherLatinLanguage(text: String, isEnglish: (String) -> Boolean?, names: Set<String> = emptySet()): Boolean {
        val words = latinWord.findAll(text).map { it.value.trim('\'') }.toList()
        val judged = words.filterIndexed { i, w -> w.length >= 2 && w.lowercase() !in names && !(i > 0 && w[0].isUpperCase()) }
            .mapNotNull(isEnglish)
        if (judged.size < 2) return false
        return judged.count { it } * 2 < judged.size
    }
}
