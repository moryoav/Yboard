package com.yboard.keyboard.text

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Next-word predictions: which word usually comes after which. Starts with common pairs
 * ("thank" -> "you", "בוקר" -> "טוב") and learns yours as you type. Stored only on the phone.
 */
class NextWords private constructor(private val sp: SharedPreferences?, private val language: String) {

    // previous word (normalized, or START for a new sentence) -> next word spelling -> count
    private val pairs = LinkedHashMap<String, LinkedHashMap<String, Int>>(64, 0.75f, true)
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var savePending = false

    init {
        try {
            val raw = sp?.getString(language, null)
            if (raw != null) {
                val o = JSONObject(raw)
                o.keys().forEach { prev ->
                    val arr = o.optJSONArray(prev) ?: return@forEach
                    val m = LinkedHashMap<String, Int>()
                    for (i in 0 until arr.length() step 2) m[arr.getString(i)] = arr.getInt(i + 1)
                    pairs[prev] = m
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Learns that [next] came after [prev] (null = at the start of a sentence). */
    fun learn(prev: String?, next: String) {
        val word = clean(next) ?: return
        val key = prev?.let { clean(it)?.let(Dictionary::normalize) } ?: START
        val m = pairs.getOrPut(key) { LinkedHashMap() }
        // Keep the spelling the person uses ("I", names), counted case-insensitively.
        val existing = m.keys.firstOrNull { it.equals(word, ignoreCase = true) }
        val spelling = if (existing != null && word.lowercase() == word && existing != word) existing else word
        val count = (existing?.let { m.remove(it) } ?: 0) + 1
        m[spelling] = count.coerceAtMost(999)
        if (m.size > MAX_NEXT) m.entries.minByOrNull { it.value }?.let { m.remove(it.key) }
        if (pairs.size > MAX_PREV) pairs.keys.firstOrNull()?.let { pairs.remove(it) }  // least recently used
        scheduleSave()
    }

    /** Learns every word pair in a piece of text (for example a voice-typed phrase). */
    fun learnText(text: String, before: String = "") {
        var prev: String? = lastWordOf(before)
        val tokens = Regex("[\\p{L}\\p{M}'’]+|[.!?\\n]").findAll(text).map { it.value }.toList()
        for (t in tokens) {
            if (t.length == 1 && t[0] in ".!?\n") { prev = null; continue }
            learn(prev, t)
            prev = t
        }
    }

    /**
     * Up to [limit] likely next words after [prev] (null / sentence start = start of a sentence),
     * your own habits first, then common pairs, then very common words.
     */
    fun predict(prev: String?, sentenceStart: Boolean, limit: Int = 3): List<String> {
        val key = if (sentenceStart || prev == null) START else Dictionary.normalize(prev)
        val scores = LinkedHashMap<String, Double>()
        fun add(w: String, score: Double) {
            val k = scores.keys.firstOrNull { it.equals(w, ignoreCase = true) }
            if (k == null) scores[w] = score else scores[k] = maxOf(scores.getValue(k), score)
        }
        pairs[key]?.forEach { (w, c) -> add(w, 3.0 + c * 2.0) }
        seeds(language)[key]?.forEachIndexed { i, w -> add(w, 4.0 - i * 0.5) }
        val fallback = if (key == START) starts(language) else common(language)
        fallback.forEachIndexed { i, w -> add(w, 0.5 - i * 0.1) }
        val out = scores.entries.sortedByDescending { it.value }.map { it.key }
            .filterNot { prev != null && it.equals(prev, ignoreCase = true) }
            .take(limit)
        return if (key == START && language == "en") out.map { it.replaceFirstChar { c -> c.uppercaseChar() } } else out
    }

    /** Your most-used word pairs, like "love you" (for AI predictions in your style). */
    fun topPairs(n: Int): List<String> = pairs.entries
        .flatMap { (prev, m) -> m.map { (w, c) -> Triple(prev, w, c) } }
        .filter { it.third >= 2 }
        .sortedByDescending { it.third }
        .take(n)
        .map { (prev, w, _) -> if (prev == START) "(start) $w" else "$prev $w" }

    fun clear() {
        pairs.clear()
        scheduleSave()
    }

    private fun scheduleSave() {
        if (savePending || sp == null) return
        savePending = true
        handler.postDelayed({
            savePending = false
            val o = JSONObject()
            pairs.forEach { (prev, m) ->
                val arr = JSONArray()
                m.forEach { (w, c) -> arr.put(w).put(c) }
                o.put(prev, arr)
            }
            sp.edit().putString(language, o.toString()).apply()
        }, 3000L)
    }

    companion object {
        const val START = "^"
        private const val MAX_PREV = 4000
        private const val MAX_NEXT = 12
        private val cache = HashMap<String, NextWords>()

        private fun clean(w: String): String? {
            val t = w.trim().trim('\'', '’', '"', ',', '.', '!', '?', ':', ';', '(', ')')
            if (t.isEmpty() || t.length > 30 || t.any { it.isDigit() || it.isWhitespace() }) return null
            if (!t.any { it.isLetter() }) return null
            return t
        }

        /** The last word in [text], or null if the text ends a sentence (or is empty). */
        fun lastWordOf(text: String): String? {
            if (text.trimEnd(' ', '\t', '\r').endsWith('\n')) return null
            val t = text.trimEnd()
            if (t.isEmpty() || t.last() in ".!?\n") return null
            val m = Regex("([\\p{L}\\p{M}'’]+)[^\\p{L}\\p{M}'’]*$").find(t) ?: return null
            return m.groupValues[1]
        }

        fun get(context: Context, language: String): NextWords = synchronized(cache) {
            cache.getOrPut(language) {
                NextWords(context.applicationContext.getSharedPreferences("yboard_next", Context.MODE_PRIVATE), language)
            }
        }

        /** In-memory only (tests). */
        fun inMemory(language: String) = NextWords(null, language)

        fun clearAll(context: Context) {
            synchronized(cache) { cache.values.forEach { it.clear() } }
            context.applicationContext.getSharedPreferences("yboard_next", Context.MODE_PRIVATE).edit().clear().apply()
        }

        private fun starts(lang: String) = if (lang == "he") listOf("אני", "מה", "תודה") else listOf("I", "Thanks", "The")
        private fun common(lang: String) = if (lang == "he") listOf("את", "של", "על") else listOf("the", "to", "and")

        private val enSeeds by lazy {
            parse(
                "^:I,Thanks,Hi,The,What,How,Ok|i:am,have,love,think,will,don't,can,just|i'm:going,not,so,sorry,good,fine,at|" +
                    "you:are,can,want,have,know,too,guys|are:you,we,they,going|how:are,about,much,is,was|what:are,is,do,time,about,happened|" +
                    "thank:you|thanks:for,so,a|see:you,ya|good:morning,night,luck,job,idea|on:my,the,it,your|my:way,phone,mom,house,friend|" +
                    "way:to,home|going:to,home,out|to:the,be,go,do,get,see,me|let:me,us|let's:go,do,see,get|can:you,I,we,be|" +
                    "do:you,it,not,we|don't:know,worry,have,think|love:you,it,that|miss:you|call:me,you|talk:to,later|be:there,back,home,right|" +
                    "right:now,back,there|at:the,home,school,work|in:the,a,my|of:the,my,course|for:the,you,me,a|with:you,me,the,my|" +
                    "is:it,a,the,that,not|it's:ok,a,not,so,fine|that's:good,great,fine,it|this:is,weekend,morning|have:a,to,you,fun|" +
                    "happy:birthday,to,for|oh:my,no,ok|no:problem,way,worries|come:on,to,here,back|where:are,is,do|when:are,is,do,will|" +
                    "will:be,you,do,see|yes:I,please,sure|sure:thing|please:let,call,send|sorry:for,I|just:a,got,wanted|want:to,a,you|" +
                    "need:to,a,you|get:a,the,it,home|go:to,home,out|know:what,how,that|think:so,it,about|very:much,good,nice|so:much,I,good"
            )
        }
        private val heSeeds by lazy {
            parse(
                "^:אני,מה,תודה,היי,בוקר,אתה|מה:נשמע,קורה,שלומך,אתה,את,זה|אני:לא,רוצה,אוהב,חושב,בדרך,יכול,צריך|בוקר:טוב|לילה:טוב|" +
                    "ערב:טוב|שבת:שלום|יום:טוב,הולדת|תודה:רבה,לך,על|אין:בעיה,לי,מה|מזל:טוב|לא:יודע,משנה,יכול,צריך,רוצה|" +
                    "אתה:יכול,רוצה,בא,יודע|את:יכולה,רוצה,זה,באה|איפה:אתה,את,אתם|מתי:אתה,את,אתם|בסדר:גמור|הכל:בסדר,טוב|" +
                    "כל:הכבוד,הזמן,יום|בדרך:הביתה|אוהב:אותך|אוהבת:אותך|נדבר:אחר,מחר|אחר:כך|סבבה:אחי|יאללה:ביי,בוא|בוא:נלך,נדבר|" +
                    "רוצה:לבוא|אפשר:לבוא,לדבר|עוד:מעט,לא,רגע|רגע:אחד|שנה:טובה|חג:שמח|זה:לא,בסדר,טוב|היה:לי,טוב,כיף"
            )
        }

        private fun seeds(lang: String) = if (lang == "he") heSeeds else enSeeds

        private fun parse(s: String): Map<String, List<String>> = s.split('|').associate { part ->
            val (k, v) = part.split(':', limit = 2)
            k to v.split(',')
        }
    }
}
