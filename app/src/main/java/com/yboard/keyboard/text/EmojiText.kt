package com.yboard.keyboard.text

import java.text.BreakIterator

/** Finding emojis in text, for the ✨ emoji sprinkle. */
object EmojiText {

    /** A code point that is (part of) an emoji: pictures, joiners, skin tones, variation selectors. */
    fun isEmojiPart(cp: Int): Boolean =
        cp == 0x200D || cp == 0xFE0F || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F ||
            isPicture(cp)

    private fun isPicture(cp: Int): Boolean =
        (cp in 0x1F000..0x1FAFF && cp !in 0x1F3FB..0x1F3FF) || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x2300..0x23FF ||
            cp == 0x00A9 || cp == 0x00AE || cp == 0x2122 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299

    /** [text] with the emojis taken out (spaces left as they were). */
    fun withoutEmojis(text: String): String {
        val sb = StringBuilder()
        text.codePoints().forEach { if (!isEmojiPart(it)) sb.appendCodePoint(it) }
        return sb.toString()
    }

    /** The emojis in [text], in order (each one whole, like 👍🏽 or 👩‍💻). */
    fun emojisIn(text: String): List<String> {
        val out = ArrayList<String>()
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(text)
        var start = bi.first()
        var end = bi.next()
        while (end != BreakIterator.DONE) {
            val g = text.substring(start, end)
            if (g.codePoints().anyMatch { isPicture(it) }) {
                // Glue pieces of one emoji back together (some systems split ZWJ sequences).
                if (out.isNotEmpty() && (out.last().endsWith("‍") || g.startsWith("‍") ||
                        g.codePoints().allMatch { isEmojiPart(it) && !isPicture(it) })
                ) out[out.size - 1] = out.last() + g else out.add(g)
            } else if (out.isNotEmpty() && g.codePoints().allMatch { isEmojiPart(it) }) {
                out[out.size - 1] = out.last() + g
            }
            start = end
            end = bi.next()
        }
        return out
    }

    private fun squash(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /**
     * The message after the AI added emojis. If the AI kept every word, its version is used (with your
     * spaces around it); if it changed any words, yours are kept and its new emojis go at the end.
     */
    fun sprinkleResult(original: String, ai: String): String {
        val a = ai.trim()
        if (a.isEmpty()) return original
        if (squash(withoutEmojis(a)) == squash(withoutEmojis(original))) {
            val lead = original.takeWhile { it.isWhitespace() }
            val trail = original.drop(lead.length).takeLastWhile { it.isWhitespace() }
            return lead + a + trail
        }
        val had = emojisIn(original).toSet()
        val found = emojisIn(a).filter { it !in had }.distinct().take(4)
        if (found.isEmpty()) return original
        val body = original.trimEnd()
        return body + " " + found.joinToString("") + original.substring(body.length)
    }
}
