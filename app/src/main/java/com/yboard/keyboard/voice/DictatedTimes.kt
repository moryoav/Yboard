package com.yboard.keyboard.voice

/** Formats clock times whose hour, minutes and half of the day can be read from the transcript. */
internal object DictatedTimes {
    private val englishNumbers = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50)
    private val units = englishNumbers.subList(1, 10).joinToString("|")
    private val hours = englishNumbers.subList(1, 13).joinToString("|")
    private val minutes = "[0-5]?[0-9]|oh[ \\t-]+(?:$units|[1-9])|" +
        "(?:${tens.keys.joinToString("|")})(?:[ \\t-]+(?:$units))?|${englishNumbers.joinToString("|")}"
    private val englishTime = Regex(
        """(?<![\p{L}\p{N}_:./+-])(?<hour>$hours|0?[1-9]|1[0-2])""" +
            """(?:[ \t]*:[ \t]*(?<digits>[0-5][0-9])|[ \t]+(?<words>$minutes)|[ \t]+o['’]clock)?""" +
            """[ \t]*(?<half>[ap])\.?[ \t]*m(?<dot>\.)?(?![\p{L}\p{N}_/]|\.[\p{L}\p{N}/])""",
        RegexOption.IGNORE_CASE,
    )
    private val previousWord = Regex("""([\p{L}\p{N}]+)[ \t]+$""")
    private val startsSentence = Regex("""^(?:I|It|We|They|You|He|She|That|This|Then|Please|The|A|An)\b""")
    private val numberSeparator = Regex("[ \\t-]+")

    private val hebrewHours = mapOf(
        "אחת" to 1, "שתיים" to 2, "שתים" to 2, "שתי" to 2, "שלוש" to 3, "ארבע" to 4,
        "חמש" to 5, "שש" to 6, "שבע" to 7, "שמונה" to 8, "תשע" to 9, "עשר" to 10,
        "אחת עשרה" to 11, "שתים עשרה" to 12, "שתיים עשרה" to 12, "שתי עשרה" to 12,
    )
    private val hebrewTime = Regex(
        """(?<![\p{L}\p{N}_])(?:בשעה[ \t]+|ב[-־]?)""" +
            """(?<hour>${hebrewHours.keys.joinToString("|")}|0?[1-9]|1[0-2])""" +
            """(?:[ \t]*:[ \t]*(?<digits>[0-5][0-9])|[ \t]+(?<fraction>וחצי|ורבע))?[ \t]+""" +
            """(?<half>אחר הצהריים|לפנות בוקר|בבוקר|בצהריים|בערב|בלילה)(?![\p{L}\p{N}_])""",
    )

    fun format(text: String): String {
        val english = englishTime.replace(text) { match ->
            val previous = previousWord.find(text.substring(0, match.range.first))?.groupValues?.get(1)?.lowercase()
            // Don't reinterpret the end of a longer number, such as "twenty one p.m.".
            if (previous != null && (previous in tens || previous in englishNumbers || previous.toIntOrNull() != null)) {
                return@replace match.value
            }
            val hour = englishNumber(match.groups["hour"]!!.value)
            val minute = match.groups["digits"]?.value?.toInt()
                ?: match.groups["words"]?.value?.let(::englishNumber) ?: 0
            val afternoon = match.groups["half"]!!.value.equals("p", ignoreCase = true)
            val time = clock(hour % 12 + if (afternoon) 12 else 0, minute)
            val following = text.substring(match.range.last + 1).trimStart(' ', '\t', '"', '”', '\'', '’', ')', ']')
            val sentenceDot = match.groups["dot"] != null &&
                (following.isEmpty() || following.startsWith('\n') || following.startsWith('\r') ||
                    startsSentence.containsMatchIn(following))
            if (sentenceDot) "$time." else time
        }
        return hebrewTime.replace(english) { match ->
            val spokenHour = match.groups["hour"]!!.value
            val hour = spokenHour.toIntOrNull() ?: hebrewHours.getValue(spokenHour)
            val hourOfDay = hebrewHourOfDay(hour, match.groups["half"]!!.value) ?: return@replace match.value
            val minute = match.groups["digits"]?.value?.toInt() ?: when (match.groups["fraction"]?.value) {
                "וחצי" -> 30
                "ורבע" -> 15
                else -> 0
            }
            "ב-${clock(hourOfDay, minute)}"
        }
    }

    private fun englishNumber(text: String): Int {
        text.toIntOrNull()?.let { return it }
        val parts = text.lowercase().split(numberSeparator)
        val first = parts.first()
        if (first == "oh") return parts[1].toIntOrNull() ?: englishNumbers.indexOf(parts[1])
        val tensValue = tens[first] ?: return englishNumbers.indexOf(first)
        return tensValue + (parts.getOrNull(1)?.let(englishNumbers::indexOf) ?: 0)
    }

    private fun hebrewHourOfDay(hour: Int, partOfDay: String): Int? = when (partOfDay) {
        "בבוקר" -> hour.takeIf { it in 1..11 }
        "לפנות בוקר" -> hour.takeIf { it in 1..6 }
        "בצהריים" -> if (hour == 12) 12 else hour.takeIf { it in 1..3 }?.plus(12)
        "אחר הצהריים" -> hour.takeIf { it in 1..7 }?.plus(12)
        "בערב" -> hour.takeIf { it in 5..11 }?.plus(12)
        "בלילה" -> when (hour) {
            12 -> 0
            in 1..5 -> hour
            in 8..11 -> hour + 12
            else -> null
        }
        else -> null
    }

    private fun clock(hour: Int, minute: Int): String =
        "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}
