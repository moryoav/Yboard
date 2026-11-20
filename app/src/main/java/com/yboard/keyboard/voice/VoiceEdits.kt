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
    private val spaceBeforePunctuation = Regex("(?<=\\S)[ \\t]+([,.!?;:])")

    fun attachPunctuation(transcript: String): String =
        transcript.replace(spaceBeforePunctuation, "$1")

    private val lowerAfterPause = setOf(
        "and", "but", "or", "so", "then", "the", "a", "an", "to", "of", "in", "on", "at", "for", "with",
        "because", "that", "this", "it", "is", "was", "we", "you", "he", "she", "they", "my", "your",
        "his", "her", "our", "their", "if", "when", "while", "as", "from", "about", "like", "just",
        "using", "being", "having", "doing", "going", "getting", "making", "taking", "working",
        "really", "probably", "usually", "sometimes", "especially", "already", "still", "almost",
        "wrong", "right", "another", "each", "every", "some", "any", "these", "those",
    )

    /** Restores the casing of common words at the start of a continuation of the current sentence. */
    fun continuationCase(before: String, text: String, protectedWords: Set<String>): String {
        val left = before.trimEnd(' ', '\t')
        if (left.isBlank() || left.endsWith('\n')) return text
        if (endsSentence(left)) return text
        val word = text.takeWhile { it.isLetter() || it == '\'' || it == '’' }
        val lower = word.lowercase()
        if (word.firstOrNull()?.isUpperCase() != true || word.drop(1).any { it.isUpperCase() } ||
            lower !in lowerAfterPause || lower in protectedWords) return text
        return text.replaceFirstChar { it.lowercaseChar() }
    }

    fun endsSentence(text: String): Boolean =
        text.trimEnd(' ', '\t', '"', '\'', '”', '’', ')', ']').lastOrNull()?.let { it in ".!?…" } == true

    /** Adds a separating space when the editor needs one. */
    fun forInsert(before: String, text: String): String {
        if (text.isEmpty()) return text
        var t = text
        val prev = before.lastOrNull()
        val needsSpace = prev != null && !prev.isWhitespace() && prev != '(' && prev != '[' &&
            t.first() !in noSpaceBefore && !t.first().isWhitespace()
        if (needsSpace) t = " $t"
        // Don't double up spaces.
        if (prev != null && prev.isWhitespace() && t.first() == ' ') t = t.trimStart(' ')
        return t
    }

    // Words that mean the phrase is probably an editing command the AI should handle.
    // (Simple ones like "delete that" and spoken punctuation are handled by LocalCommands.)
    private val commandStarts = listOf(
        "delete", "remove", "erase", "undo", "scratch", "backspace", "take back", "take that", "take out",
        "never mind", "nevermind", "get rid", "capitalize", "make that", "make the last",
        "uppercase", "lowercase", "quote", "spell", "fix that", "correct that", "translate",
        "insert", "add a", "add an", "put a", "put an",
        "מחק", "מחקי", "למחוק", "בטל", "החלף", "תחליף", "תחליפי", "תשנה", "תשני", "תתקן", "תקן את", "תתרגם",
        "תוסיף", "תשים", "תוריד", "תמחוק",
    )
    private val politeStart = Regex("^(?:(?:please|can you|could you|would you|בבקשה) )+")
    private val wordReplacement = Regex("(?:replace .+ (?:with|by)|change .+ (?:to|into))(?: (.+))?")

    /**
     * An edit must start like an instruction. Words such as "replace" inside ordinary
     * dictation should neither interrupt the audio turn nor hide the live transcript.
     */
    fun looksLikeCommand(spoken: String, preview: Boolean = false): Boolean {
        val words = spoken.lowercase().replace(Regex("[\\p{P}\\p{S}]"), " ")
            .replace(Regex("\\s+"), " ").trim().replaceFirst(politeStart, "")
        val replacement = wordReplacement.matchEntire(words)
        return (replacement != null && (preview || replacement.groups[1] != null)) ||
            commandStarts.any { words == it || words.startsWith("$it ") }
    }
}
