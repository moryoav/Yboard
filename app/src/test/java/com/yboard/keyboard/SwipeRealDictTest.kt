package com.yboard.keyboard

import com.yboard.keyboard.text.Dictionary
import com.yboard.keyboard.text.Pt
import com.yboard.keyboard.text.Suggester
import com.yboard.keyboard.text.SwipeDecoder
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Swipe typing against the real 60k-word dictionaries and Yboard's real key layout. */
class SwipeRealDictTest {

    private val keyW = 100f
    private val rowH = 150f

    private fun setup(lang: String): Triple<SwipeDecoder, Map<Char, Pt>, Dictionary> {
        val dict = Dictionary.fromLines(lang, File("src/main/assets/dict/$lang.tsv").readLines().asSequence())
        val centers = Suggester.keyPositions(lang).mapValues { (_, p) -> Pt(p.first * keyW, p.second * rowH + rowH / 2) }
        return Triple(SwipeDecoder { c -> dict.startingWith(c) }, centers, dict)
    }

    private fun swipe(word: String, centers: Map<Char, Pt>, rnd: Random, noise: Float): List<Pt> {
        val keys = word.lowercase().mapNotNull { centers[it] }
            .fold(mutableListOf<Pt>()) { acc, p -> if (acc.lastOrNull() != p) acc.add(p); acc }
        val out = ArrayList<Pt>()
        for (i in 0 until keys.size - 1) {
            val a = keys[i]; val b = keys[i + 1]
            for (s in 0 until 10) {
                val t = s / 10f
                out.add(Pt(a.x + (b.x - a.x) * t + (rnd.nextFloat() - 0.5f) * 2 * noise * keyW,
                    a.y + (b.y - a.y) * t + (rnd.nextFloat() - 0.5f) * 2 * noise * keyW))
            }
        }
        out.add(keys.last())
        return out
    }

    private fun check(lang: String, words: List<String>, minTop1: Double, minTop4: Double) {
        val (decoder, centers, dict) = setup(lang)
        val rnd = Random(7)
        var top1 = 0; var top4 = 0; var total = 0
        val misses = ArrayList<String>()
        for (w in words) {
            if (!dict.contains(Dictionary.normalize(w))) continue
            repeat(5) {
                val res = decoder.decode(swipe(w, centers, rnd, 0.22f), centers, keyW, 4).map { it.lowercase() }
                total++
                if (res.firstOrNull() == w) top1++ else misses.add("$w→${res.firstOrNull()}")
                if (w in res) top4++
            }
        }
        val r1 = top1.toDouble() / total
        val r4 = top4.toDouble() / total
        println("$lang swipe: top1=${"%.2f".format(r1)} top4=${"%.2f".format(r4)} n=$total misses=${misses.distinct().take(15)}")
        assertTrue("$lang top-1 $r1 misses=${misses.distinct().take(15)}", r1 >= minTop1)
        assertTrue("$lang top-4 $r4", r4 >= minTop4)
    }

    @Test
    fun english() = check(
        "en",
        listOf(
            "hello", "world", "keyboard", "thanks", "because", "people", "good", "great", "love", "home",
            "today", "tomorrow", "phone", "message", "friend", "family", "happy", "please", "sorry", "really",
            "going", "something", "about", "would", "could", "there", "where", "when", "what", "yes",
            "maybe", "later", "morning", "night", "work", "time", "know", "think", "want", "school",
        ),
        minTop1 = 0.75, minTop4 = 0.92,
    )

    @Test
    fun hebrew() = check(
        "he",
        listOf("שלום", "תודה", "בוקר", "לילה", "אני", "אתה", "טוב", "יום", "בבקשה", "אוהב",
            "משפחה", "חבר", "עבודה", "בית", "מחר", "היום", "אולי", "עכשיו", "למה", "איפה"),
        minTop1 = 0.7, minTop4 = 0.9,
    )
}
