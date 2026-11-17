package com.yboard.keyboard

import com.yboard.keyboard.text.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ContextPredictionTest {
    private fun model(lang: String) = File("src/main/assets/prediction/$lang.txt").bufferedReader().use { PhraseModel.fromLines(it.lineSequence()) }

    @Test fun sentenceContextResetsAndHandlesBothLanguages() {
        assertEquals(listOf("at", "am at", "i am at"), PredictionContext.keys("Finished work. I am at "))
        assertEquals(listOf("^"), PredictionContext.keys("hello! \n"))
        assertEquals(listOf("עכשיו", "מגיע עכשיו", "אני מגיע עכשיו"), PredictionContext.keys("שלום. אני מגיע עכשיו "))
        assertEquals(listOf("on", "working on", "i'm working on"), PredictionContext.keys("I'm working on "))
        assertEquals("tom", PredictionContext.prefix("See you tom"))
        assertEquals("don'", PredictionContext.prefix("I don'"))
        assertEquals("I'm", PredictionContext.prefix("I'm"))
        assertEquals("שָׁלוֹם", PredictionContext.prefix("היי שָׁלוֹם"))
        assertEquals("", PredictionContext.prefix("See you tomorrow "))
        assertEquals(4, PredictionContext.keys("one two three four five six ").size)
    }

    @Test fun baseAndAbsoluteAnchorStayStableAsPrefixGrows() {
        val base = "x".repeat(300) + " See you "
        val a = PredictionContext.position(base, base.length)
        val b = PredictionContext.position(base + "tom", base.length + 3)
        assertTrue(a.sameWord(b))
        assertEquals("tom", b.prefix)
        assertFalse(a.sameWord(b.copy(anchor = b.anchor + 1)))
    }

    @Test fun severalWordsChangeTheMeaningAndPrefixesCompleteNaturally() {
        val en = NextWords.inMemory("en")
        val corpus = model("en")
        assertTrue(corpus.size > 1500)
        assertTrue(en.predictContext("I am at ", model = corpus).contains("home"))
        assertTrue(en.predictContext("I work at ", model = corpus).contains("the"))
        assertNotEquals(en.predictContext("I am at ", model = corpus), en.predictContext("I work at ", model = corpus))
        assertEquals("tomorrow", en.predictContext("See you ", "tom", model = corpus).first())
        val he = NextWords.inMemory("he")
        val hebrew = model("he")
        assertTrue(hebrew.size > 1000)
        assertEquals("הביתה", he.predictContext("אני בדרך ", "הב", model = hebrew).first())
        assertTrue(he.predictContext("אני רוצה ", model = hebrew).contains("ללכת"))
    }

    @Test fun repeatedHabitsBeatOneOffWordsAndSpecificHabitsBeatBroadOnes() {
        val words = NextWords.inMemory("en")
        words.learn("love", "pizza")
        assertEquals("you", words.predict("love", false).first())
        words.learn("love", "pizza")
        assertEquals("pizza", words.predict("love", false).first())
        repeat(5) { words.learnText("I am at home") }
        repeat(2) { words.learnText("I work at school") }
        assertEquals("home", words.predictContext("I am at ").first())
        assertEquals("school", words.predictContext("I work at ").first())
        repeat(500) { words.learn("at", "home") }
        assertEquals("school", words.predictContext("I work at ").first())
    }

    @Test fun legacyPairsAndNewContextsSurviveSerializationAndCanBeExcluded() {
        var words = NextWords.fromSnapshot("en", """{"love":["pizza",9],"at":["Dana",4]}""")
        repeat(3) { words.learnText("I work at workshop") }
        words = NextWords.fromSnapshot("en", words.snapshot())
        assertEquals("pizza", words.predict("love", false).first())
        assertEquals("workshop", words.predictContext("I work at ").first())
        assertFalse(words.predictContext("I work at ", personalized = false).contains("workshop"))
        assertTrue(words.hints("I work at ", 8).contains("i work at workshop"))
        assertFalse(words.hints("I love ", 8).any { it.contains("workshop") })
        words.clear()
        assertFalse(words.predictContext("I work at ").contains("workshop"))
    }

    @Test fun newSentencesAndVoicePhrasesDoNotTeachFalsePairs() {
        val words = NextWords.inMemory("en")
        words.learnText("home. Bring snacks", "I am at ")
        assertTrue(words.predictContext("I am at ").contains("home"))
        assertFalse(words.predictContext("home ").contains("Bring"))
        assertTrue(words.predictContext("").contains("Bring"))
        words.learnText("note: deliver parcels")
        assertFalse(words.predictContext("note ").contains("deliver"))
    }

    @Test fun dictionaryCacheInvalidatesWhenPersonalVocabularyChanges() {
        val user = UserWords.inMemory("en")
        val dictionary = Dictionary.fromLines("en", sequenceOf("hello\t100", "help\t90"))
        val suggestions = Suggester(dictionary, user, "en")
        assertFalse(suggestions.suggest("kerm", 3).suggestions.contains("Kermon"))
        user.learn("Kermon", 2)
        assertTrue(suggestions.suggest("kerm", 3).suggestions.contains("Kermon"))
        user.forget("kermon")
        assertFalse(suggestions.suggest("kerm", 3).suggestions.contains("Kermon"))
        try {
            UserWords.setMyWords(listOf("Kermon"))
            assertTrue(suggestions.suggest("kerm", 3).suggestions.contains("Kermon"))
        } finally { UserWords.setMyWords(emptyList()) }
        assertFalse(suggestions.suggest("kerm", 3).suggestions.contains("Kermon"))
    }

    @Test fun heldOutConversationContextsImproveOverSingleWordSeeds() {
        // These complete messages are not corpus rows. Expected words are an independent usage fixture.
        val fixtures = mapOf(
            "en" to listOf(
                "Hi, I will let you " to "know", "Well, I would like to " to "order",
                "Actually I am on my " to "way", "Okay, I am looking for " to "a",
                "Hello, can you send me the " to "address", "Hey, do you want to " to "come",
                "Hello, let me know if you " to "need", "Okay, I'll get back to " to "you",
                "Hey, sorry for the " to "delay", "Well, I have no " to "idea",
                "Yes, that sounds good to " to "me", "Hey, see you tomorrow " to "morning",
                "Okay, I need some help with " to "this", "Hello, I hope you are " to "doing",
                "Well, I am stuck in " to "traffic", "Hey, thank you for your " to "help",
                "Actually, how much does it " to "cost", "Hi, where do you want to " to "meet",
                "Yes, I will bring it with " to "me", "Okay, please let me know when it " to "arrives"
            ),
            "he" to listOf(
                "היי אני בדרך " to "הביתה", "טוב אני צריך עוד קצת " to "זמן",
                "היי אני מקווה שהכל " to "בסדר", "טוב אני לא יודע " to "עדיין",
                "היי אתה יכול לשלוח לי את " to "הכתובת", "טוב אני אבדוק ואחזור " to "אליך",
                "היי תודה רבה על " to "העזרה", "טוב אני מגיע בעוד כמה " to "דקות",
                "היי מתי נוח לך " to "להיפגש", "טוב נדבר אחר " to "כך",
                "היי יש לי שאלה " to "קטנה", "טוב אין לי " to "מושג",
                "היי אני שמח " to "לשמוע", "טוב זה נשמע " to "טוב",
                "היי מה אתה רוצה " to "לאכול", "טוב אני באמצע " to "פגישה",
                "היי אני אשלח לך את " to "הכתובת", "טוב הכל מוכן " to "למחר",
                "היי כמה זמן זה " to "ייקח", "טוב אל תדאג הכל יהיה " to "בסדר"
            )
        )
        for ((lang, cases) in fixtures) {
            val local = NextWords.inMemory(lang)
            val corpus = model(lang)
            var old = 0
            var new = 0
            for ((context, expected) in cases) {
                if (local.predict(NextWords.lastWordOf(context), false).any { it.equals(expected, true) }) old++
                val predictions = local.predictContext(context, model = corpus)
                if (predictions.any { it.equals(expected, true) }) new++
                else println("Miss $lang: $context -> $predictions (expected $expected)")
            }
            println("Prediction fixture $lang: seeded=$old/${cases.size}, contextual=$new/${cases.size}, contexts=${corpus.size}")
            assertTrue("$lang contextual=$new seeded=$old", new >= 17 && new > old)
        }
    }
}
