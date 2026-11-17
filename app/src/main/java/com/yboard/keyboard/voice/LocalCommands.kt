package com.yboard.keyboard.voice

/**
 * Voice commands Yboard understands by itself, instantly and without the internet:
 * "delete that", "delete that word", "new line", "question mark"... in English and Hebrew.
 * Anything trickier ("replace Monday with Tuesday") goes to the AI.
 */
object LocalCommands {

    /** What a whole-phrase command did (for the log; never contains the user's words). */
    enum class Kind { DELETE_PHRASE, DELETE_WORD, DELETE_SENTENCE, DELETE_ALL, UNDO, NEW_LINE, NEW_PARAGRAPH, PUNCTUATION, CANCEL_PHRASE, SEND }

    class Match(val kind: Kind, val edit: VoiceEdit)

    private val deletePhrase = setOf(
        "delete that", "delete this", "delete it", "delete", "scratch that", "scratch this", "remove that", "remove this", "remove it", "erase that", "erase this", "erase it",
        "never mind", "nevermind", "take that back", "clear that", "cancel that", "delete what i said",
        "delete what i just said", "go back", "delete the last thing", "delete the last thing i said",
        "delete the last part", "delete that part", "remove what i said", "remove what i just said",
        "erase what i said", "erase what i just said", "get rid of that", "get rid of it", "take that out",
        "delete the last phrase", "remove the last thing", "undo the last thing", "delete all that",
        "delete that please", "delete those words", "delete that sentence i said",
        "מחק", "תמחק", "תמחוק", "תמחקי", "מחקי", "למחוק", "מחק את זה", "תמחק את זה", "תמחוק את זה",
        "תמחקי את זה", "מחקי את זה", "למחוק את זה",
        "תמחק מה שאמרתי", "מחק מה שאמרתי", "תמחק את מה שאמרתי", "מחק את מה שאמרתי", "תמחק את הדבר האחרון",
        "תמחק את מה שכתבת", "מחק את מה שכתבת", "תמחק את זה מה שאמרתי", "תוריד את זה", "תוריד את מה שאמרתי",
        "תבטל את זה בבקשה", "תמחק את החלק האחרון", "מחק את החלק האחרון",
    )
    private val deleteWord = setOf(
        "delete that word", "delete the last word", "delete last word", "delete the word", "delete word",
        "delete a word", "delete one word", "remove the last word", "remove that word", "remove last word",
        "erase the last word", "erase that word", "delete the previous word", "backspace",
        "מחק מילה", "תמחק מילה", "מחק את המילה", "תמחק את המילה", "מחק את המילה האחרונה",
        "תמחק את המילה האחרונה", "תמחקי את המילה האחרונה", "מחקי את המילה האחרונה", "תמחוק את המילה האחרונה",
        "תמחק את המילה הזאת", "מחק את המילה הזאת", "תמחק את המילה הזו", "מחק את המילה הזו", "מחק מילה אחרונה",
        "תמחק מילה אחרונה", "תוריד את המילה האחרונה", "תוריד מילה",
    )
    private val deleteSentence = setOf(
        "delete the last sentence", "delete that sentence", "delete the sentence", "delete last sentence",
        "remove the last sentence", "erase the last sentence",
        "תמחק את המשפט", "תמחק את המשפט האחרון", "מחק את המשפט האחרון", "מחק את המשפט", "תמחק את המשפט הזה",
    )
    private val deleteAll = setOf(
        "delete everything", "clear everything", "delete all", "clear all", "erase everything", "delete all of it",
        "delete it all", "clear it all", "erase it all", "remove everything", "delete all the text", "clear the text",
        "delete the whole thing", "clear the whole thing", "delete all of that", "start over", "clear the box",
        "delete everything i wrote", "delete everything i said",
        "תמחק הכל", "מחק הכל", "תמחק את הכל", "מחק את הכל", "תמחק הכול", "מחק הכול", "תמחק את הכול",
        "מחק את הכול", "תמחקי הכל", "תמחקי הכול", "תמחקי את הכל", "למחוק הכל", "למחוק הכול", "תנקה הכל",
        "תנקה הכול", "נקה הכל", "תמחק את כל הטקסט", "מחק את כל הטקסט", "תמחק את כל מה שכתבתי",
        "תמחק את כל מה שאמרתי", "תמחוק הכל", "תמחוק הכול", "תתחיל מהתחלה",
    )

    /** "Undo" right after "delete everything" brings the text back; otherwise it removes the last phrase. */
    private val undo = setOf(
        "undo", "undo that", "undo this", "בטל", "תבטל", "בטל את זה", "תבטל את זה", "ביטול", "תבטלי",
    )
    /** Only bring text back (do nothing if there's nothing to bring back). */
    private val restore = setOf(
        "bring it back", "bring that back", "bring it all back", "restore", "restore that", "put it back",
        "תחזיר", "תחזיר את זה", "תחזיר את הכל", "תחזיר הכל", "תחזיר הכול", "תחזירי את זה",
    )
    private val newLine = setOf(
        "new line", "newline", "next line", "line break", "enter", "שורה חדשה", "ירידת שורה", "שורה הבאה",
        "רד שורה", "תרד שורה", "אנטר",
        // Common mishearings of "new line".
        "no line", "new lane", "knew line", "nu line", "new lines", "a new line", "noline", "new lion",
        "now line", "neww line", "new lyne", "שורה חדש", "שורות חדשה",
    )
    private val newParagraph = setOf("new paragraph", "פסקה חדשה")
    /** Sends the message (presses the app's send button, or Enter). */
    private val send = setOf(
        "send", "send it", "send that", "send this", "send the message", "send message", "send it now",
        "hit send", "press send", "send now", "and send", "and send it", "send it off", "send the text",
        "go ahead and send", "go ahead and send it", "send send", "send away", "tap send", "click send",
        "שלח", "תשלח", "תשלחי", "שלחי", "לשלוח", "שלח את זה", "תשלח את זה", "תשלחי את זה", "תשלח את ההודעה",
        "שלח את ההודעה", "שלח הודעה", "תשלח הודעה", "תשלח עכשיו", "שלח עכשיו", "ושלח", "ותשלח", "שלחו",
        "תשלחו", "שלח שלח", "תשלח תשלח", "שליחה", "לשלוח את זה", "לשלוח את ההודעה", "סנד",
    )
    /** How the speech model sometimes hears a lone "send" (only as a whole phrase: "sent" is also a word). */
    private val sendMisheard = setOf("sent", "sent it", "sand", "cent", "scent", "zend", "sends", "סנט")
    /** "send" at the very end of dictation, said without a pause: "see you soon send it". */
    private val sendAtEnd = setOf("send", "send it", "send it now", "send now", "send the message", "תשלח", "תשלחי", "תשלח את זה", "תשלחי את זה")
    /** Words that, right before "send", make it part of the sentence ("I'll send", "can you send"). */
    private val sendInSentence = setOf(
        "i", "i'll", "ill", "i'd", "we", "we'll", "you", "you'll", "they", "they'll", "he", "he'll", "she", "she'll",
        "it", "will", "to", "can", "can't", "cant", "cannot", "could", "would", "should", "please", "pls", "plz",
        "don't", "dont", "didn't", "didnt", "won't", "wont", "not", "never", "just", "gonna", "wanna", "let's", "lets",
        "must", "might", "may", "also", "and", "or", "me", "who", "did", "do", "does", "always", "then", "already",
        "אני", "אתה", "את", "הוא", "היא", "אנחנו", "הם", "הן", "אתם", "מי", "כבר", "לא", "רק", "גם", "אם", "תוכל",
        "תוכלי", "אפשר", "בבקשה", "שהוא", "שהיא", "שאתה", "שאת", "מתי", "למה", "איך", "עוד", "אז", "אמא", "אבא",
    )
    private val punctuation = mapOf(
        "question mark" to "?", "exclamation mark" to "!", "exclamation point" to "!", "period" to ".",
        "full stop" to ".", "dot" to ".", "comma" to ",", "colon" to ":", "semicolon" to ";", "dash" to " -",
        "hyphen" to "-", "ellipsis" to "…", "at sign" to "@",
        "סימן שאלה" to "?", "סימן קריאה" to "!", "נקודה" to ".", "פסיק" to ",", "נקודתיים" to ":",
        "מקף" to "-", "שלוש נקודות" to "…",
    )

    /** Spoken at the end of other words, these cancel the phrase ("see you soon, scratch that"). */
    private val cancelEndings = listOf(
        "delete that", "scratch that", "undo that", "never mind", "cancel that", "delete what i said",
        "תמחק את זה", "מחק את זה", "תבטל את זה", "בטל את זה", "תמחקי את זה", "תמחוק את זה",
    )

    private val fillers = listOf(
        "please", "now", "ok", "okay", "no", "oops", "actually", "um", "uh", "can you", "could you", "just",
        "בבקשה", "עכשיו", "יאללה", "אוקיי", "לא", "אופס", "רגע", "אפשר", "תעשה לי טובה",
    )

    /** Words in front of an insert command: "add a question mark", "go to a new line", "תוסיף פסיק". */
    private val insertVerbs = listOf(
        "add a", "add an", "add", "put a", "put an", "put", "insert a", "insert", "type a", "type", "write a",
        "write", "give me a", "start a", "go to a", "go to the", "go to", "make a", "do a", "drop a", "hit",
        "press", "tap", "תוסיף", "תוסיפי", "הוסף", "תשים", "שים", "תכתוב", "כתוב", "תעשה", "תרד", "תעבור",
        "לשורה", "תן",
    )

    private fun withoutInsertVerb(n: String): String {
        for (v in insertVerbs) if (n.startsWith("$v ")) return n.removePrefix("$v ")
        return n
    }

    private val allPhrases: List<String> by lazy {
        (deletePhrase + deleteWord + deleteSentence + deleteAll + undo + restore + newLine + newParagraph + send + sendMisheard + punctuation.keys).toList()
    }

    /**
     * Could [spoken] (still being said) be a whole-phrase command? True for "delete", "delete th",
     * "new", "question" ... so command words aren't flashed in the text field while you talk.
     */
    fun mightBeCommand(spoken: String): Boolean {
        val n = normalize(spoken)
        if (n.isEmpty()) return false
        val bare = withoutInsertVerb(n)
        val raw = clean(spoken)
        if (raw.isNotEmpty() && (allPhrases.any { it.startsWith(raw) } || closest(raw) != null)) return true
        if (closest(n) != null) return true
        return allPhrases.any { it.startsWith(n) || it.startsWith(bare) } ||
            insertVerbs.any { it.startsWith(n) && n.length >= 3 && it.contains(' ') }
    }

    // First words of spoken punctuation; at the end of the words heard so far they're held back
    // until we know ("question" might become "question mark").
    private val punctuationStarts = setOf("question", "exclamation", "full", "new", "סימן", "שורה", "פסקה", "שלוש")

    /**
     * What to show underlined in the text field for a phrase still being said, or null to keep it
     * out of the field because it sounds like a command.
     */
    fun preview(spoken: String, smart: Boolean): String? {
        val t = spoken.trim()
        if (t.isEmpty()) return ""
        if (mightBeCommand(t)) return null
        if (smart && VoiceEdits.looksLikeCommand(t)) return null
        val words = t.split(Regex("\\s+")).toMutableList()
        while (words.isNotEmpty()) {
            val last = words.last().lowercase().trim { !it.isLetter() }
            if (last.isNotEmpty() && punctuationStarts.any { it.startsWith(last) }) words.removeAt(words.size - 1) else break
        }
        return spokenPunctuation(words.joinToString(" "))
    }

    /** Lowercase and no punctuation. */
    private fun clean(spoken: String): String = spoken.lowercase()
        .replace(Regex("[\\p{P}\\p{S}&&[^@]]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** Lowercase, no punctuation, no "please". */
    fun normalize(spoken: String): String {
        var n = clean(spoken)
        var changed = true
        while (changed && n.isNotEmpty()) {
            changed = false
            for (f in fillers) {
                if (n == f) { n = ""; changed = true; break }
                if (n.startsWith("$f ")) { n = n.removePrefix("$f "); changed = true }
                if (n.endsWith(" $f")) { n = n.removeSuffix(" $f"); changed = true }
            }
        }
        return n
    }

    /**
     * A whole phrase that is one command. [lastPhrase] is the latest phrase voice typing added
     * that is still at the end of the text (what "delete that" removes), or null.
     */
    fun match(spoken: String, before: String, lastPhrase: String?): Match? {
        val stripped = normalize(spoken)
        val raw = clean(spoken)
        if (stripped.isEmpty() && raw.isEmpty()) return null
        // Try as said, without "please"/"no"..., without "add a"..., then close mishearings.
        val forms = listOf(stripped, raw, withoutInsertVerb(stripped)).filter { it.isNotEmpty() }.distinct()
        val n = forms.firstOrNull { isKnown(it) && (it == stripped || it == raw || isInsert(it)) }
            ?: forms.firstNotNullOfOrNull { closest(it) }
            ?: stripped
        val spaces = before.takeLastWhile { it == ' ' }
        return when {
            n in deletePhrase -> Match(
                Kind.DELETE_PHRASE,
                VoiceEdit(lastPhrase?.takeIf { it.isNotBlank() && before.endsWith(it) } ?: lastWord(before), ""),
            )
            n in deleteWord -> Match(Kind.DELETE_WORD, VoiceEdit(lastWord(before), ""))
            n in deleteSentence -> Match(Kind.DELETE_SENTENCE, VoiceEdit(lastSentence(before), ""))
            n in deleteAll -> Match(Kind.DELETE_ALL, VoiceEdit(before, ""))
            n in undo -> Match(
                Kind.UNDO,
                VoiceEdit(lastPhrase?.takeIf { it.isNotBlank() && before.endsWith(it) } ?: lastWord(before), ""),
            )
            n in restore -> Match(Kind.UNDO, VoiceEdit("", ""))
            n in send || n in sendMisheard || raw in sendMisheard -> Match(Kind.SEND, VoiceEdit("", ""))
            n in newLine -> Match(Kind.NEW_LINE, VoiceEdit(spaces, "\n"))
            n in newParagraph -> Match(Kind.NEW_PARAGRAPH, VoiceEdit(spaces, "\n\n"))
            n in punctuation -> Match(Kind.PUNCTUATION, VoiceEdit(spaces, punctuation.getValue(n)))
            cancelEndings.any { n.endsWith(" $it") } -> Match(Kind.CANCEL_PHRASE, VoiceEdit("", ""))
            else -> null
        }
    }

    /**
     * Dictation that ends with "send", said without a long pause ("See you soon. Send.",
     * "on my way send it"): the words to type before sending, or null when it's not that.
     * Never for a question, or when "send" is part of the sentence ("I'll send", "when should I send").
     */
    fun trailingSend(spoken: String): String? {
        val t = spoken.trim()
        if (t.isEmpty() || t.endsWith("?")) return null
        val body = t.trimEnd { it in ".!…" || it.isWhitespace() }
        // "See you soon. Send." / "See you soon, send it": its own sentence or clause at the end.
        val cut = body.indexOfLast { it in ".!?,;:\n…" }
        if (cut >= 0 && normalize(body.substring(cut + 1)) in send) {
            return wordsBeforeSend(body.substring(0, cut + 1))
        }
        // "on my way send it": no pause at all. Only the clearest forms.
        val words = body.split(Regex("\\s+"))
        for (k in 1..3) {
            if (words.size <= k) break
            val tail = clean(words.takeLast(k).joinToString(" "))
            if (tail !in sendAtEnd) continue
            val prev = words[words.size - k - 1].lowercase().replace('’', '\'').trim { !it.isLetter() && it != '\'' }
            if (prev in sendInSentence || prev.isEmpty()) return null
            return wordsBeforeSend(words.dropLast(k).joinToString(" "))
        }
        return null
    }

    /** The words before a trailing "send", without a dangling comma or an "okay," on its own. */
    private fun wordsBeforeSend(before: String): String {
        var b = before.trimEnd { it == ',' || it == ';' || it == ':' || it.isWhitespace() }
        // "See you soon. Okay, send." -> drop the "Okay".
        val lastStop = b.trimEnd { it in ".!…" }.indexOfLast { it in ".!?\n…" }
        val lastBit = b.substring(lastStop + 1)
        if (lastBit.isNotBlank() && normalize(lastBit).isEmpty()) b = b.substring(0, lastStop + 1)
        return b.trimEnd { it == ',' || it == ';' || it == ':' || it.isWhitespace() }
    }

    /**
     * How many 20 ms frames of quiet should end a phrase that is, so far, [spoken]:
     * 0 = the normal pause. A finished command ends after a short pause (180 ms); one that a
     * longer command starts with ("delete that" / "delete that word") waits a little more.
     */
    fun quickEndFrames(spoken: String): Int {
        val stripped = normalize(spoken)
        val raw = clean(spoken)
        val forms = listOf(stripped, raw, withoutInsertVerb(stripped)).filter { it.isNotEmpty() }.distinct()
        val n = forms.firstOrNull { it in allPhrases } ?: forms.firstNotNullOfOrNull { closest(it) } ?: return 0
        return if (allPhrases.any { it.startsWith("$n ") }) 15 else 9
    }

    private fun isInsert(n: String) = n in newLine || n in newParagraph || n in punctuation
    private fun isKnown(n: String) = n in allPhrases

    /**
     * A command that [n] is a near-miss of ("delete hat" -> "delete that", "new lane" -> "new line").
     * Only for longer phrases, and the first letter must match, so ordinary words aren't caught.
     */
    fun closest(n: String): String? {
        if (n.length < 6) return null
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for (c in allPhrases) {
            if (c.length < 6 || c[0] != n[0] || kotlin.math.abs(c.length - n.length) > 2) continue
            // "hi send" is "hi" + send, not "hit send": a send must start with the same word.
            if ((c in send || c in sendMisheard) && c.substringBefore(' ') != n.substringBefore(' ')) continue
            val allowed = if (c.length >= 10) 2 else 1
            val d = distance(n, c)
            if (d <= allowed && d < bestD) { best = c; bestD = d }
        }
        return best
    }

    /** Edit distance between two short strings. */
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

    /** The last word (with the space in front of it), or trailing spaces if there's no word. */
    fun lastWord(before: String): String {
        val t = before.trimEnd()
        if (t.isEmpty()) return before
        var i = t.length
        while (i > 0 && !t[i - 1].isWhitespace()) i--
        while (i > 0 && t[i - 1] == ' ') i--
        return before.substring(i)
    }

    /** The last sentence (with the space in front of it). */
    fun lastSentence(before: String): String {
        val t = before.trimEnd()
        if (t.isEmpty()) return before
        var end = t.length
        while (end > 0 && t[end - 1] in ".!?…") end--  // the sentence's own full stop
        var i = end
        while (i > 0 && t[i - 1] !in ".!?…\n") i--
        while (i < end && t[i] == ' ') i++
        while (i > 0 && t[i - 1] == ' ') i--
        return before.substring(i)
    }

    // Spoken punctuation inside a longer phrase: "hi question mark how are you" -> "hi? how are you".
    private val inline = listOf(
        "question mark" to "?", "exclamation mark" to "!", "exclamation point" to "!", "full stop" to ".",
        "semicolon" to ";", "comma" to ",", "new paragraph" to "\n\n", "new line" to "\n",
        "סימן שאלה" to "?", "סימן קריאה" to "!", "נקודתיים" to ":", "פסיק" to ",", "פסקה חדשה" to "\n\n",
        "שורה חדשה" to "\n",
    )
    // Common words that only mean punctuation at the very end of a phrase ("the period of" stays).
    private val endOnly = listOf("period" to ".", "colon" to ":", "נקודה" to ".")

    fun spokenPunctuation(text: String): String {
        var t = text
        for ((word, symbol) in inline) {
            val re = Regex("[\\s,.;:]*(?<!\\p{L})" + Regex.escape(word) + "(?!\\p{L})[.,!?;:]*", RegexOption.IGNORE_CASE)
            t = t.replace(re) { if (symbol.endsWith("\n")) symbol else "$symbol " }
        }
        for ((word, symbol) in endOnly) {
            val re = Regex("[\\s,.;:]*(?<!\\p{L})" + Regex.escape(word) + "[.,!?;:]*\\s*$", RegexOption.IGNORE_CASE)
            t = t.replace(re, symbol)
        }
        t = t.replace(Regex(" +\n"), "\n").replace(Regex("\n +"), "\n").replace(Regex(" {2,}"), " ").trim(' ')
        // A new sentence after ? ! . starts with a capital (English).
        t = t.replace(Regex("([.?!]) ([a-z])")) { m -> m.groupValues[1] + " " + m.groupValues[2].uppercase() }
        return t
    }
}
