package com.yboard.keyboard.text

/**
 * Suggestions for email boxes: your own address (once you start typing it), and the usual
 * endings like "@gmail.com" and ".co.il".
 */
object EmailHelper {

    /** [text] is typed, after removing [replace] (the part of it already typed). */
    data class Chip(val text: String, val replace: String = "")

    val DOMAINS = listOf("gmail.com", "walla.co.il", "hotmail.com", "outlook.com", "yahoo.com", "icloud.com")

    /** The address being typed: the text after the last space/comma before the cursor. */
    fun token(before: String): String = before.takeLastWhile { !it.isWhitespace() && it != ',' && it != ';' }

    /**
     * Three strip slots [left, middle, right] (null = empty) for the address being typed.
     * The middle one is the best guess.
     */
    fun chips(token: String, myEmail: String): List<Chip?> {
        val mine = myEmail.trim()
        val at = token.indexOf('@')
        if (at < 0) {
            val mineFits = mine.isNotEmpty() && mine.startsWith(token, ignoreCase = true) && !mine.equals(token, ignoreCase = true)
            if (token.isEmpty()) return listOf(null, if (mineFits) Chip(mine) else null, null)
            // Your own address (when it fits what you typed) goes in the middle, else @gmail.com.
            // Addresses are long, so yours only shares the strip with one other.
            return if (mineFits) listOf(Chip("@gmail.com"), Chip(mine, token), null)
            else listOf(Chip("@walla.co.il"), Chip("@gmail.com"), Chip("@hotmail.com"))
        }
        val domain = token.substring(at + 1)
        val matches = DOMAINS.filter { it.startsWith(domain, ignoreCase = true) && !it.equals(domain, ignoreCase = true) }
        if (domain.isNotEmpty() && matches.isNotEmpty() || domain.isEmpty()) {
            val m = if (domain.isEmpty()) DOMAINS else matches
            return listOf(m.getOrNull(1)?.let { Chip(it, domain) }, Chip(m[0], domain), m.getOrNull(2)?.let { Chip(it, domain) })
        }
        if (!domain.contains('.')) return listOf(Chip(".co.il"), Chip(".com"), Chip(".net"))
        return listOf(null, null, null)
    }
}
