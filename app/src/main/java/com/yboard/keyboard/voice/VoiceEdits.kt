package com.yboard.keyboard.voice

/**
 * One change to the text before the cursor: remove [remove] from the end, then type [insert].
 * The AI returns these for commands like "delete that"; plain dictation is just an insert.
 */
data class VoiceEdit(val remove: String, val insert: String)

/** Pure text rules for voice typing (no Android), so they can be unit-tested. */
object VoiceEdits {

    /**
     * How many characters to delete from the end of [before] to remove [remove].
     * Returns -1 when [remove] isn't actually at the end of the text (the AI got it wrong),
     * so nothing is deleted by mistake.
     */
    fun removeCount(before: String, remove: String): Int {
        if (remove.isEmpty()) return 0
        if (before.endsWith(remove)) return remove.length
        // Tolerate differences in spaces at the edges ("hello " vs " hello").
        val r = remove.trim()
        if (r.isEmpty()) return 0
        val b = before.trimEnd()
        if (!b.endsWith(r)) return -1
        var start = b.length - r.length
        // Take the space in front too, so "a b" minus "b" leaves "a", not "a ".
        if (remove.first().isWhitespace() && start > 0 && b[start - 1] == ' ') start--
        return before.length - start
    }

    private val noSpaceBefore = setOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '%', '…', '\n', '"', '\'', '״', '׳')
    private val lowerAfterPause = setOf(
        "and", "but", "or", "so", "then", "the", "a", "an", "to", "of", "in", "on", "at", "for", "with",
        "because", "that", "this", "it", "is", "was", "we", "you", "he", "she", "they", "my", "your",
        "his", "her", "our", "their", "if", "when", "while", "as", "from", "about", "like", "just",
    )

    /**
     * Prepares dictated [text] to go after [before]: adds a space when needed, and lowercases
     * a word like "And" that the speech model capitalized only because the speaker paused.
     */
    fun forInsert(before: String, text: String): String {
        if (text.isEmpty()) return text
        var t = text
        val prev = before.lastOrNull()
        val sentenceEnded = before.isBlank() || before.trimEnd().lastOrNull()?.let { it in ".!?\n" } == true
        if (!sentenceEnded && t.first().isUpperCase()) {
            val firstWord = t.takeWhile { it.isLetter() || it == '\'' }
            if (firstWord.lowercase() in lowerAfterPause && firstWord.drop(1).none { it.isUpperCase() }) {
                t = t.replaceFirstChar { it.lowercaseChar() }
            }
        }
        val needsSpace = prev != null && !prev.isWhitespace() && prev != '(' && prev != '[' &&
            t.first() !in noSpaceBefore && !t.first().isWhitespace()
        if (needsSpace) t = " $t"
        // Don't double up spaces.
        if (prev != null && prev.isWhitespace() && t.first() == ' ') t = t.trimStart(' ')
        return t
    }

    // Words that mean the phrase is probably an editing command the AI should handle.
    // (Simple ones like "delete that" and spoken punctuation are handled by LocalCommands.)
    private val englishCommandWords = listOf(
        "delete", "remove", "erase", "undo", "scratch", "backspace", "take back", "take that", "take out",
        "never mind", "nevermind", "get rid", "replace", "change", "instead", "capital", "capitalize", "caps",
        "uppercase", "lowercase", "quote", "quotes", "quotation", "emoji", "smiley", "spell", "fix that",
        "correct that", "last word", "last sentence", "that word", "brackets", "parenthesis", "translate",
        "insert", "add a", "add an", "put a", "put an", "around", "last two", "last three", "last few",
    )
    private val hebrewCommandWords = listOf(
        "מחק", "מחקי", "למחוק", "מחיקה", "בטל", "ביטול", "החלף", "תחליף", "תחליפי", "תשנה", "תשני",
        "מירכאות", "גרשיים", "סמיילי", "אימוג", "תתקן", "תקן את", "המילה האחרונה", "המשפט האחרון", "תתרגם",
        "תוסיף", "תשים", "תוריד", "תמחוק",
    )

    /**
     * True when [spoken] is probably an editing command ("replace Monday with Tuesday",
     * "תחליף את X ב Y"). Only these go to the AI, so normal dictation is typed right away.
     */
    fun looksLikeCommand(spoken: String): Boolean {
        val norm = spoken.lowercase().replace(Regex("[\\p{P}\\p{S}]"), " ").replace(Regex("\\s+"), " ").trim()
        if (norm.isEmpty()) return false
        val padded = " $norm "
        if (englishCommandWords.any { padded.contains(" $it ") }) return true
        return hebrewCommandWords.any { norm.contains(it) }
    }
}
