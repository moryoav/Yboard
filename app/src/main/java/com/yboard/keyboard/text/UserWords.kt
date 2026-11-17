package com.yboard.keyboard.text

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Words Yboard learns from you (names, slang, words missing from the dictionary), plus how
 * often you use dictionary words, so suggestions get more personal over time.
 * Stored only on the phone.
 */
class UserWords private constructor(private val sp: SharedPreferences?, private val language: String) {

    private class Entry(var spelling: String, var count: Int)

    private val words = HashMap<String, Entry>()
    private var revision = 0L
    val cacheRevision: Long get() = revision + mineRevision
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var savePending = false

    init {
        try {
            val raw = sp?.getString(language, null)
            if (raw != null) {
                val o = JSONObject(raw)
                o.keys().forEach { norm ->
                    val e = o.optJSONArray(norm) ?: return@forEach
                    words[norm] = Entry(e.optString(0, norm), e.optInt(1, 1))
                }
            }
        } catch (_: Exception) {
        }
    }

    /** "My words" in this language: normalized -> how you spell it. */
    private fun mine(): Map<String, String> = myWords[language].orEmpty()

    /** You added this word yourself (Yboard → My words). */
    fun isMine(norm: String): Boolean = norm in mine()

    fun count(norm: String): Int = maxOf(words[norm]?.count ?: 0, if (norm in mine()) MINE_COUNT else 0)

    /** A word counts as "known" once you've typed it twice (or picked it from the strip), or added it. */
    fun isKnown(norm: String): Boolean = count(norm) >= KNOWN_AT

    fun spelling(norm: String): String? = mine()[norm] ?: words[norm]?.spelling

    fun learn(word: String, amount: Int = 1) {
        val clean = word.trim()
        if (clean.length < 2 || clean.length > 32 || clean.any { it.isDigit() || it.isWhitespace() }) return
        val norm = Dictionary.normalize(clean)
        val e = words.getOrPut(norm) { Entry(clean, 0) }
        e.count = (e.count + amount).coerceAtMost(9999)
        e.spelling = clean
        revision++
        if (words.size > MAX_WORDS) trim()
        scheduleSave()
    }

    /** Your most-used learned words (for AI predictions in your style). */
    fun topWords(n: Int): List<String> =
        (mine().values.take(n) + words.values.filter { it.count >= KNOWN_AT }.sortedByDescending { it.count }.map { it.spelling })
            .distinctBy { Dictionary.normalize(it) }.take(n)

    fun forget(norm: String) {
        if (words.remove(norm) != null) { revision++; scheduleSave() }
    }

    fun clear() {
        words.clear()
        revision++
        scheduleSave()
    }

    /** Known learned words starting with [c], as dictionary-style entries. */
    fun knownStartingWith(c: Char): List<WordFreq> =
        known { it.firstOrNull() == c }.sortedByDescending { it.freq }

    /** Dictionary words also gain a personal prior, not just names missing from the dictionary. */
    fun swipeCandidates(base: List<WordFreq>, c: Char, personalized: Boolean): List<WordFreq> {
        if (!personalized) return base
        val combined = base.associateBy { Dictionary.normalize(it.word) }.toMutableMap()
        knownStartingWith(c).forEach { wf -> combined.putIfAbsent(Dictionary.normalize(wf.word), wf) }
        return combined.values.map { wf ->
            val uses = count(Dictionary.normalize(wf.word))
            wf.copy(freq = (wf.freq + 8 * uses.coerceAtMost(18)).coerceAtMost(255))
        }.sortedByDescending { it.freq }
    }

    fun knownWithPrefix(prefix: String): List<WordFreq> = known { it.startsWith(prefix) }

    /** Learned words you use, plus your own words, whose normalized form passes [filter]. */
    private fun known(filter: (String) -> Boolean): List<WordFreq> {
        val out = HashMap<String, WordFreq>()
        words.forEach { (norm, e) ->
            if (e.count >= KNOWN_AT && filter(norm)) out[norm] = WordFreq(e.spelling, learnedFreq(e.count))
        }
        mine().forEach { (norm, spelling) ->
            if (filter(norm)) out[norm] = WordFreq(spelling, learnedFreq(count(norm)))
        }
        return out.values.toList()
    }

    private fun trim() {
        val drop = words.entries.sortedBy { it.value.count }.take(words.size - MAX_WORDS + 200)
        drop.forEach { words.remove(it.key) }
    }

    private fun scheduleSave() {
        if (savePending || sp == null) return
        savePending = true
        handler.postDelayed({
            savePending = false
            val o = JSONObject()
            words.forEach { (norm, e) -> o.put(norm, org.json.JSONArray().put(e.spelling).put(e.count)) }
            sp.edit().putString(language, o.toString()).apply()
        }, 3000L)
    }

    companion object {
        private const val KNOWN_AT = 2
        /** Your own words rank like a word you've used a dozen times. */
        private const val MINE_COUNT = 12
        private const val MAX_WORDS = 3000
        private val cache = HashMap<String, UserWords>()

        /** "My words" by language ("en" / "he"): normalized -> spelling. */
        @Volatile private var myWords: Map<String, Map<String, String>> = emptyMap()
        @Volatile private var mineRevision = 0L

        /** Sets "My words" (from settings). Each word goes to its language by its letters. */
        fun setMyWords(list: List<String>) {
            val next = list.groupBy { MyWords.languageOf(it) }
                .mapValues { (_, ws) -> ws.associateBy { Dictionary.normalize(it) } }
            if (next != myWords) { myWords = next; mineRevision++ }
        }

        fun learnedFreq(count: Int): Int = (110 + count * 15).coerceAtMost(240)

        fun get(context: Context, language: String): UserWords = synchronized(cache) {
            cache.getOrPut(language) {
                UserWords(context.applicationContext.getSharedPreferences("yboard_words", Context.MODE_PRIVATE), language)
            }
        }

        /** In-memory only (tests). */
        fun inMemory(language: String) = UserWords(null, language)

        fun clearAll(context: Context) {
            synchronized(cache) { cache.values.forEach { it.clear() } }
            context.applicationContext.getSharedPreferences("yboard_words", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
