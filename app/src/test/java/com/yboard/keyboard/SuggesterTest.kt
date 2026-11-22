package com.yboard.keyboard

import com.yboard.keyboard.text.Dictionary
import com.yboard.keyboard.text.Suggester
import com.yboard.keyboard.text.UserWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Suggestions and autocorrect, checked against the real bundled dictionaries. */
class SuggesterTest {

    companion object {
        lateinit var en: Dictionary
        lateinit var he: Dictionary

        private fun load(lang: String): Dictionary {
            val f = File("src/main/assets/dict/$lang.tsv")
            return Dictionary.fromLines(lang, f.readLines(Charsets.UTF_8).asSequence())
        }

        @BeforeClass
        @JvmStatic
        fun setup() {
            en = load("en")
            he = load("he")
        }
    }

    private fun english(user: UserWords = UserWords.inMemory("en")) = Suggester(en, user, "en")
    private fun hebrew() = Suggester(he, UserWords.inMemory("he"), "he")

    @Test
    fun dictionariesLoaded() {
        assertTrue(en.size > 50_000)
        assertTrue(he.size > 50_000)
        assertTrue(en.contains("the"))
        assertTrue(he.contains("שלום"))
    }

    @Test
    fun fixesCommonTypos() {
        val s = english()
        mapOf(
            "teh" to "the",
            "recieve" to "receive",
            "becuase" to "because",
            "tommorow" to "tomorrow",
            "whats" to "what's",
            "dont" to "don't",
        ).forEach { (typo, fix) -> assertEquals("autocorrect for $typo", fix, s.suggest(typo).autoCorrect) }
    }

    @Test
    fun keepsCapitalization() {
        assertEquals("The", english().suggest("Teh").autoCorrect)
        assertEquals("THE", english().suggest("TEH").autoCorrect)
    }

    @Test
    fun modelCompletionsKeepEstablishedAndTypedCapitalization() {
        assertEquals("edge", Suggester.modelCompletion("edge", "eDGE", "edge"))
        assertEquals("edges", Suggester.modelCompletion("edge", "eDges", "edges"))
        assertNull(Suggester.modelCompletion("edge", "eDGEE", null))
        assertNull(Suggester.modelCompletion("edge", "eDGEI", null))
        assertNull(Suggester.modelCompletion("", "eDGEE", null))
        assertEquals("iPhone", Suggester.modelCompletion("ip", "IPHONE", "iPhone"))
        assertEquals("EDGE", Suggester.modelCompletion("EDG", "edge", "edge"))
        assertEquals("eDGe", Suggester.modelCompletion("eDG", "eDGe", null))
        assertEquals("מחר", Suggester.modelCompletion("מ", "מחר", "מחר"))
    }

    @Test
    fun leavesRealWordsAlone() {
        val s = english()
        listOf("the", "house", "keyboard", "hello", "thanks", "I").forEach {
            assertNull("should not change $it", s.suggest(it).autoCorrect)
        }
    }

    @Test
    fun completesWords() {
        val r = english().suggest("tomo")
        assertTrue(r.suggestions.toString(), r.suggestions.any { it.startsWith("tomorrow") })
    }

    @Test
    fun learnsYourWords() {
        val user = UserWords.inMemory("en")
        val s = english(user)
        assertTrue(s.suggest("Yoavv").autoCorrect != "Yoavv")
        user.learn("Yoavv"); user.learn("Yoavv")
        assertNull("a learned word is never 'fixed'", s.suggest("Yoavv").autoCorrect)
        assertTrue(s.suggest("Yoa").suggestions.contains("Yoavv"))
    }

    @Test
    fun hebrewFinalLetters() {
        assertEquals("שלום", hebrew().suggest("שלומ").autoCorrect)
        assertNull(hebrew().suggest("אני").autoCorrect)
    }

    @Test
    fun fastEnough() {
        val s = english()
        val words = listOf("teh", "recieve", "becuase", "tomo", "keybaord", "wrold", "qucik", "hello", "the", "something")
        s.suggest("warmup")
        val start = System.nanoTime()
        repeat(5) { words.forEach { s.suggest(it) } }
        val avgMs = (System.nanoTime() - start) / 1e6 / (words.size * 5)
        assertTrue("average $avgMs ms per word", avgMs < 25.0)
    }
}
