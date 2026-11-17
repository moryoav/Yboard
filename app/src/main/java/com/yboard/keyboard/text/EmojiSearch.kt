package com.yboard.keyboard.text

import android.content.Context
import android.graphics.Paint

/**
 * Emoji search in English and Hebrew, using Unicode CLDR keywords
 * (`assets/emoji/keywords.tsv`: emoji, English keywords, Hebrew keywords).
 */
object EmojiSearch {
    private class Entry(val emoji: String, val en: List<String>, val he: List<String>)

    /** The most-used emoji (roughly Unicode's emoji frequency ranking), most popular first. */
    private val popularity: Map<String, Int> = (
        "😂 ❤️ 🤣 👍 😭 🙏 😘 🥰 😍 😊 🎉 😁 💕 🥺 😅 🔥 ☺️ 🤦 ♥️ 🤷 🙄 😆 🤗 😉 🎂 🤔 👏 🙂 😳 🥳 " +
            "😎 👌 💜 😔 💪 ✨ 💖 👀 😋 😏 😢 👉 💗 😩 💯 🌹 💞 🎈 💙 😃 😡 💐 😜 🙈 🤞 😄 🤤 🙌 🤪 ❣️ " +
            "😀 💋 💀 👇 💔 😌 💓 🤩 🙃 😬 😱 😴 🤭 😐 🌞 😒 😇 🌸 😈 🎶 ✌️ 🎊 🥵 😞 💚 ☀️ 🖤 💰 😚 👑 " +
            "🎁 💥 🙋 ☹️ 😑 🥴 👈 💩 ✅ 👋 🤍 🧡 💛 🤎"
        ).split(' ').filter { it.isNotBlank() }.withIndex().associate { it.value to it.index }

    @Volatile private var entries: List<Entry>? = null
    /** Whole keyword -> best emoji, for suggesting an emoji next to a typed word. */
    @Volatile private var byWord: Map<String, String> = emptyMap()

    // Keywords too vague to suggest an emoji for.
    private val vague = setOf(
        "face", "hand", "man", "woman", "person", "people", "sign", "symbol", "button", "mark", "flag", "word",
        "text", "small", "large", "big", "new", "top", "one", "two", "three", "the", "and", "for", "you", "not",
        "can", "boy", "girl", "body", "part", "other", "object", "place", "time", "free", "open", "back", "end",
        "up", "down", "left", "right", "on", "off", "out", "no", "ok", "go", "all", "her", "his", "him", "she",
        "פנים", "יד", "סימן", "כפתור", "דגל", "אדם", "איש", "אישה", "של", "את", "על", "עם", "לא", "כן", "זה",
    )
    @Volatile private var kaomoji: List<String>? = null
    private val glyphPaint = Paint()

    /** Call off the main thread. */
    fun load(context: Context) {
        if (entries != null) return
        entries = try {
            context.assets.open("emoji/keywords.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 2) return@mapNotNull null
                    // Skip emoji this phone can't draw yet (newer than its emoji font).
                    if (!glyphPaint.hasGlyph(parts[0])) return@mapNotNull null
                    Entry(
                        parts[0],
                        parts[1].split('|').filter { it.isNotBlank() },
                        parts.getOrNull(2)?.split('|')?.filter { it.isNotBlank() } ?: emptyList(),
                    )
                }.toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
        byWord = buildWordMap(entries.orEmpty())
    }

    private fun buildWordMap(all: List<Entry>): Map<String, String> {
        // For each single-word keyword: the emoji whose own name is that word wins, then the most popular.
        data class Pick(val emoji: String, val rank: Int)
        val best = HashMap<String, Pick>()
        all.forEachIndexed { order, e ->
            for ((i, k) in (e.en + e.he).withIndex()) {
                val w = k.lowercase()
                if (' ' in w || w in vague) continue
                val isName = i == 0 || i == e.en.size
                val rank = (if (isName) 0 else 100000) + (popularity[e.emoji] ?: 999) * 100 + order.coerceAtMost(99)
                val cur = best[w]
                if (cur == null || rank < cur.rank) best[w] = Pick(e.emoji, rank)
            }
        }
        return best.mapValues { it.value.emoji }
    }

    /** An emoji for a typed word ("pizza" -> 🍕, "לב" -> ❤️), or null. */
    fun forWord(word: String): String? {
        val w = word.trim().lowercase().trim('\'', '’', '"')
        val hebrew = w.any { it in '\u0590'..'\u05FF' }
        if (w.length < (if (hebrew) 2 else 3) || w in vague) return null
        val map = byWord
        map[w]?.let { return it }
        if (!hebrew) {
            if (w.endsWith("es")) map[w.dropLast(2)]?.let { return it }
            if (w.endsWith("s")) map[w.dropLast(1)]?.let { return it }
        } else {
            // Hebrew prefixes: ו ה ב ל מ ש כ ("והפיצה" -> "פיצה")
            if (w.length > 3 && w[0] in "והבלמשכ") map[w.substring(1)]?.let { return it }
        }
        return null
    }

    val isLoaded: Boolean get() = entries != null

    fun search(query: String, limit: Int = 40): List<String> {
        val q = query.trim().lowercase()
        val all = entries ?: return emptyList()
        if (q.isEmpty()) return emptyList()
        // Rank: how well a keyword matches, then whether the emoji's own name has the word,
        // then shorter (simpler) names first — so "heart" gives ❤️ before 💘.
        data class Hit(val emoji: String, val score: Int, val nameLen: Int, val order: Int)
        val hits = ArrayList<Hit>()
        all.forEachIndexed { order, e ->
            var best = Int.MAX_VALUE
            for ((i, k) in (e.en + e.he).withIndex()) {
                val isName = i == 0 || i == e.en.size
                val s = when {
                    k == q -> if (isName) 0 else 10
                    k.startsWith(q) -> if (isName) 20 else 30
                    k.split(' ', ':', '-').any { it.startsWith(q) } -> 40
                    q.length >= 3 && k.contains(q) -> 60
                    else -> continue
                }
                if (s < best) best = s
            }
            if (best == Int.MAX_VALUE) return@forEachIndexed
            // Compare names in the language being typed only.
            val hebrewQuery = q.any { it in '֐'..'׿' }
            val names = listOfNotNull(if (hebrewQuery) e.he.firstOrNull() else e.en.firstOrNull())
            val nameWords = names.flatMap { it.split(' ', ':', '-') }
            val bonus = when {
                nameWords.any { it == q } -> 6
                nameWords.any { it.startsWith(q) } -> 3
                else -> 0
            }
            val nameLen = names.minOfOrNull { it.length } ?: 99
            hits.add(Hit(e.emoji, best - bonus, nameLen, order))
        }
        return hits.sortedWith(compareBy<Hit>({ it.score }, { popularity[it.emoji] ?: 999 }, { it.nameLen }, { it.order }))
            .take(limit).map { it.emoji }
    }

    /** Kaomoji from `assets/emoji/kaomoji.txt` (lines starting with '#' are category titles). */
    fun kaomoji(context: Context): List<String> {
        kaomoji?.let { return it }
        val list = try {
            context.assets.open("emoji/kaomoji.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.trimEnd() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
        kaomoji = list
        return list
    }
}
