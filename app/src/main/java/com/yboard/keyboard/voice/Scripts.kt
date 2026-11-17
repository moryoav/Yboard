package com.yboard.keyboard.voice

/**
 * Voice typing is only Hebrew and English: letters from any other alphabet (Arabic, Russian...)
 * mean the speech model guessed the wrong language.
 */
object Scripts {

    private fun allowed(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.LATIN, Character.UnicodeScript.HEBREW,
        Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> true
        else -> false
    }

    private fun foreign(cp: Int) = Character.isLetter(cp) && !allowed(cp)

    /** [text] has letters that aren't Hebrew or English. */
    fun hasForeign(text: String): Boolean = text.codePoints().anyMatch { foreign(it) }

    /** [text] without words written in other alphabets (the last resort). */
    fun withoutForeign(text: String): String =
        text.split(' ').filter { w -> w.codePoints().noneMatch { foreign(it) } }
            .joinToString(" ").replace(Regex(" {2,}"), " ").trim()

    /** English letters with accents (é, ñ, ü, ç...): Spanish, French, German... not English. */
    fun hasAccents(text: String): Boolean = text.any { it.isLetter() && it.code in 0x00C0..0x024F }

    /** Arabic-family letters: the speech model mixes Hebrew up with these. */
    fun looksSemitic(text: String): Boolean = text.codePoints().anyMatch {
        val s = Character.UnicodeScript.of(it)
        s == Character.UnicodeScript.ARABIC || s == Character.UnicodeScript.SYRIAC
    }

    /**
     * The phrase doesn't sound like Hebrew or English: another alphabet, accented letters, or words in English
     * letters that mostly aren't English ("hola amigos como estas"). [isEnglish] answers null when it can't
     * tell; [names] (your own words) and capitalized names in the middle of a sentence are never counted.
     */
    fun notHebrewOrEnglish(text: String, isEnglish: (String) -> Boolean?, names: Set<String> = emptySet()): Boolean {
        if (hasForeign(text) || hasAccents(text)) return true
        val words = Regex("[A-Za-z']+").findAll(text).map { it.value.trim('\'') }.toList()
        val judged = words.filterIndexed { i, w -> w.length >= 2 && w.lowercase() !in names && !(i > 0 && w[0].isUpperCase()) }
            .mapNotNull(isEnglish)
        if (judged.size < 2) return false
        return judged.count { it } * 2 < judged.size
    }
}
