package com.yboard.keyboard.keyboard

enum class KeyType {
    CHAR, SHIFT, BACKSPACE, ENTER, SPACE, SYMBOLS, SYMBOLS2, LETTERS, GLOBE, COMMA, PERIOD
}

enum class Mode { LETTERS, SYMBOLS, SYMBOLS2, NUMBER, PHONE }

enum class ShiftState { OFF, ON, LOCKED }

enum class EnterType { NEWLINE, GO, SEARCH, SEND, NEXT, DONE }

data class KeyDef(
    val type: KeyType,
    val label: String = "",
    val output: String = label,
    val width: Float = 1f,
    val hint: String? = null,
    val alternates: List<String> = emptyList(),
)

data class RowDef(val keys: List<KeyDef>, val heightFactor: Float = 1f)

data class LayoutDef(val rows: List<RowDef>, val letters: Boolean, val language: String)

object Layouts {

    private val numberRowKeys = "1234567890".map { KeyDef(KeyType.CHAR, it.toString()) }

    private val enAlternates = mapOf(
        "a" to listOf("à", "á", "â", "ä", "ã", "å", "æ", "ā"),
        "c" to listOf("ç", "ć", "č"),
        "e" to listOf("è", "é", "ê", "ë", "ē", "ę"),
        "i" to listOf("ì", "í", "î", "ï", "ī"),
        "n" to listOf("ñ", "ń"),
        "o" to listOf("ò", "ó", "ô", "ö", "õ", "ø", "œ", "ō"),
        "s" to listOf("ß", "ś", "š"),
        "u" to listOf("ù", "ú", "û", "ü", "ū"),
        "y" to listOf("ý", "ÿ"),
        "z" to listOf("ž", "ź", "ż"),
        "l" to listOf("ł"),
        "d" to listOf("ð"),
        "g" to listOf("ğ"),
    )

    val periodAlternates = listOf("?", "!", ",", "'", "\"", ":", ";", "-", "@", "#", "&", "(", ")", "/", "%", "…")

    private val symbolAlternates = mapOf(
        "1" to listOf("¹", "½", "⅓", "¼", "⅛"),
        "2" to listOf("²", "⅔"),
        "3" to listOf("³", "¾", "⅜"),
        "4" to listOf("⁴"),
        "0" to listOf("ⁿ", "∅"),
        "$" to listOf("₪", "€", "£", "¥", "¢", "₹"),
        "₪" to listOf("$", "€", "£", "¥", "¢", "₹"),
        "-" to listOf("–", "—", "_", "·"),
        "+" to listOf("±"),
        "(" to listOf("<", "[", "{"),
        ")" to listOf(">", "]", "}"),
        "\"" to listOf("“", "”", "„", "«", "»"),
        "'" to listOf("‘", "’", "‚", "‹", "›", "׳", "״"),
        "?" to listOf("¿"),
        "!" to listOf("¡"),
        "*" to listOf("★", "†", "‡"),
        "%" to listOf("‰"),
        "&" to listOf("§"),
        "=" to listOf("≠", "≈", "∞"),
    )

    private fun char(c: String, hint: String? = null, extra: List<String> = emptyList()): KeyDef {
        val alts = buildList {
            if (hint != null) add(hint)
            addAll(extra)
            addAll(symbolAlternates[c] ?: emptyList())
        }.distinct()
        return KeyDef(KeyType.CHAR, c, c, hint = hint, alternates = alts)
    }

    private fun bottomRow(
        language: String, mode: Mode, multiLang: Boolean, email: Boolean = false,
    ): RowDef {
        val modeKey = when (mode) {
            Mode.LETTERS -> KeyDef(KeyType.SYMBOLS, "?123", width = 1.5f)
            else -> KeyDef(KeyType.LETTERS, if (language == "he") "אבג" else "ABC", width = 1.5f)
        }
        if (email) {
            // Email boxes: @ and .com keys (hold .com for .co.il, .net...).
            return RowDef(buildList {
                add(modeKey)
                add(KeyDef(KeyType.CHAR, "@", "@"))
                if (multiLang) add(KeyDef(KeyType.GLOBE, width = 1f))
                add(KeyDef(KeyType.SPACE, output = " ", width = if (multiLang) 3f else 4f))
                add(KeyDef(KeyType.CHAR, ".com", ".com", alternates = listOf(".co.il", ".net", ".org", ".io", ".edu")))
                add(KeyDef(KeyType.PERIOD, ".", ".", alternates = listOf("_", "-", "+")))
                add(KeyDef(KeyType.ENTER, width = 1.5f))
            })
        }
        val keys = buildList {
            add(modeKey)
            add(KeyDef(KeyType.COMMA, ",", ","))
            if (multiLang) add(KeyDef(KeyType.GLOBE, width = 1f))
            add(KeyDef(KeyType.SPACE, output = " ", width = if (multiLang) 4f else 5f))
            add(KeyDef(KeyType.PERIOD, ".", ".", alternates = periodAlternates))
            add(KeyDef(KeyType.ENTER, width = 1.5f))
        }
        return RowDef(keys)
    }

    fun build(
        mode: Mode,
        language: String,
        numberRow: Boolean,
        multiLang: Boolean,
        email: Boolean = false,
    ): LayoutDef = when (mode) {
        Mode.LETTERS -> if (language == "he") hebrew(numberRow, multiLang) else english(numberRow, multiLang, email)
        Mode.SYMBOLS -> symbols(language, multiLang)
        Mode.SYMBOLS2 -> symbols2(language, multiLang)
        Mode.NUMBER -> numpad(false, language)
        Mode.PHONE -> numpad(true, language)
    }

    private fun english(numberRow: Boolean, multiLang: Boolean, email: Boolean = false): LayoutDef {
        val digits = "1234567890"
        val row1 = "qwertyuiop".mapIndexed { i, ch ->
            val s = ch.toString()
            char(s, hint = if (numberRow) null else digits[i].toString(), extra = enAlternates[s] ?: emptyList())
        }
        val row2 = "asdfghjkl".map { ch -> char(ch.toString(), extra = enAlternates[ch.toString()] ?: emptyList()) }
        val row3 = buildList {
            add(KeyDef(KeyType.SHIFT, width = 1.5f))
            "zxcvbnm".forEach { ch -> add(char(ch.toString(), extra = enAlternates[ch.toString()] ?: emptyList())) }
            add(KeyDef(KeyType.BACKSPACE, width = 1.5f))
        }
        val rows = buildList {
            if (numberRow) add(RowDef(numberRowKeys, 0.78f))
            add(RowDef(row1)); add(RowDef(row2)); add(RowDef(row3))
            add(bottomRow("en", Mode.LETTERS, multiLang, email))
        }
        return LayoutDef(rows, letters = true, language = "en")
    }

    /** Gboard's Hebrew layout: ' - ק ר א ט ו ן ם פ / ש ד ג כ ע י ח ל ך ף / ז ס ב ה נ מ צ ת ץ ⌫ */
    private fun hebrew(numberRow: Boolean, multiLang: Boolean): LayoutDef {
        val digits = "1234567890"
        val extras = mapOf(
            "'" to listOf("\"", "׳", "״", "`"),
            "-" to listOf("_", "–", "—"),
        )
        val row1 = listOf("'", "-", "ק", "ר", "א", "ט", "ו", "ן", "ם", "פ").mapIndexed { i, s ->
            char(s, hint = if (numberRow) null else digits[i].toString(), extra = extras[s] ?: emptyList())
        }
        val row2 = "שדגכעיחלךף".map { char(it.toString()) }
        val row3 = buildList {
            "זסבהנמצתץ".forEach { add(char(it.toString())) }
            add(KeyDef(KeyType.BACKSPACE, width = 1f))
        }
        val rows = buildList {
            if (numberRow) add(RowDef(numberRowKeys, 0.78f))
            add(RowDef(row1)); add(RowDef(row2)); add(RowDef(row3))
            add(bottomRow("he", Mode.LETTERS, multiLang))
        }
        return LayoutDef(rows, letters = true, language = "he")
    }

    private fun symbols(language: String, multiLang: Boolean): LayoutDef {
        val currency = if (language == "he") "₪" else "$"
        val row1 = "1234567890".map { char(it.toString()) }
        val row2 = listOf("@", "#", currency, "_", "&", "-", "+", "(", ")", "/").map { char(it) }
        val row3 = buildList {
            add(KeyDef(KeyType.SYMBOLS2, "=\\<", width = 1.5f))
            listOf("*", "\"", "'", ":", ";", "!", "?").forEach { add(char(it)) }
            add(KeyDef(KeyType.BACKSPACE, width = 1.5f))
        }
        return LayoutDef(
            listOf(RowDef(row1), RowDef(row2), RowDef(row3), bottomRow(language, Mode.SYMBOLS, multiLang)),
            letters = false, language = language
        )
    }

    private fun symbols2(language: String, multiLang: Boolean): LayoutDef {
        val row1 = listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { char(it) }
        val row2 = listOf("£", "€", "¥", "₪", "^", "°", "=", "{", "}", "\\").map { char(it) }
        val row3 = buildList {
            add(KeyDef(KeyType.SYMBOLS, "?123", width = 1.5f))
            listOf("%", "©", "®", "™", "✓", "[", "]").forEach { add(char(it)) }
            add(KeyDef(KeyType.BACKSPACE, width = 1.5f))
        }
        return LayoutDef(
            listOf(RowDef(row1), RowDef(row2), RowDef(row3), bottomRow(language, Mode.SYMBOLS2, multiLang)),
            letters = false, language = language
        )
    }

    private fun numpad(phone: Boolean, language: String): LayoutDef {
        fun k(s: String) = KeyDef(KeyType.CHAR, s, s, width = 2.5f, alternates = symbolAlternates[s] ?: emptyList())
        val rows = listOf(
            RowDef(listOf(k("1"), k("2"), k("3"), k(if (phone) "+" else "-"))),
            RowDef(listOf(k("4"), k("5"), k("6"), KeyDef(KeyType.SPACE, output = " ", width = 2.5f))),
            RowDef(listOf(k("7"), k("8"), k("9"), KeyDef(KeyType.BACKSPACE, width = 2.5f))),
            RowDef(listOf(
                k(if (phone) "*" else ","), k("0"), k(if (phone) "#" else "."),
                KeyDef(KeyType.ENTER, width = 2.5f)
            )),
        )
        return LayoutDef(rows, letters = false, language = language)
    }

    fun languageName(code: String) = if (code == "he") "עברית" else "English"
}
