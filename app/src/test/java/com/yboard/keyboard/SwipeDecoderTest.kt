package com.yboard.keyboard

import com.yboard.keyboard.text.Pt
import com.yboard.keyboard.text.SwipeDecoder
import com.yboard.keyboard.text.WordFreq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SwipeDecoderTest {

    companion object {
        const val KW = 100f
        const val ROW = 150f
        const val TRIALS = 20

        private fun layout(vararg rows: Pair<String, Float>): Map<Char, Pt> {
            val m = LinkedHashMap<Char, Pt>()
            rows.forEachIndexed { r, (letters, offset) ->
                letters.forEachIndexed { i, c -> m[c] = Pt((offset + i + 0.5f) * KW, (r + 0.5f) * ROW) }
            }
            return m
        }

        val QWERTY: Map<Char, Pt> = layout("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
        val HEBREW: Map<Char, Pt> = layout("קראטוןםפ" to 0f, "שדגכעיחלךף" to 0f, "זסבהנמצתץ" to 0f)

        private const val ENGLISH_RAW = """
            the:255 of:252 and:252 to:252 in:248 is:246 you:246 that:246 it:246 he:240 was:240 for:240
            on:240 are:236 as:236 with:236 his:232 they:232 at:230 be:232 this:234 have:234 from:228 or:228
            one:228 had:226 by:226 word:190 but:230 not:232 what:230 all:230 were:224 we:232 when:226
            your:228 can:230 said:222 there:226 use:205 an:228 each:205 which:222 she:226 do:232 how:226
            their:222 if:228 will:226 up:226 other:214 about:224 out:226 many:210 then:220 them:220
            these:214 so:232 some:218 her:224 would:226 make:216 like:228 him:220 into:216 time:220
            has:218 look:210 two:214 more:222 write:196 go:222 see:220 number:196 no:228 way:214
            could:220 people:214 my:230 than:214 first:210 water:196 been:220 call:204 who:222 its:210
            now:224 find:204 long:204 down:214 day:214 did:216 get:226 come:214 made:212 may:210
            part:200 over:214 new:220 take:212 only:220 little:204 know:230 place:200 year:200
            live:200 me:232 back:220 give:206 most:206 very:216 after:210 thing:206 our:218 just:230
            name:200 good:226 great:214 where:214 help:210 through:206 much:210 before:206 line:196
            right:220 too:220 mean:200 old:202 any:210 same:200 tell:206 boy:190 came:200 want:222
            show:202 also:216 around:200 three:200 small:196 set:192 put:204 end:196 does:206 well:222
            must:200 big:196 even:206 because:214 here:220 why:216 ask:196 went:200 read:194 need:214
            home:206 us:214 try:200 kind:196 hand:194 again:206 off:210 play:196 away:202 house:196
            still:206 should:214 world:200 every:196 food:190 keep:196 tree:170 never:206 eye:180
            thought:196 left:194 don't:226 few:186 while:196 might:196 next:196 hard:190 open:192
            life:202 those:200 got:214 night:200 four:186 once:190 book:186 stop:192 idea:186
            really:214 let:210 talk:190 soon:186 being:196 it's:222 hello:196 tie:150 keyboard:140
            quick:170 quite:186 quiet:160 going:210 gone:180 god:186 done:196 hell:170 held:170
            hold:180 work:204 wild:150 told:186 wall:160 yes:216 thank:200 thanks:206 thee:80 toe:120
            tea:160 ten:170 tip:150 top:180 true:190 type:176 text:186 test:176 best:200 rest:180
            love:210 lot:190 lost:186 real:190 ready:186 road:170 room:186 red:180 tired:170 tire:120
            tile:120 they're:180 hey:180 hi:190 London:160 phone:186 please:200 sorry:196 okay:196
            nice:196 happy:190 today:200 tomorrow:186 morning:180 tonight:176 dinner:170 coffee:176
            send:186 sure:206 maybe:196 think:214 though:196 tough:160 quote:140 queen:150 quit:150
            question:180 quickly:170 goods:140 gold:160 goal:160 foot:160 hood:120 wood:150 words:180
            worse:160 worth:170 wide:150 dot:140 don:120 dog:180 doing:196 door:176 date:170 hole:150
            halo:90 hill:150 hall:150 heel:110 whole:180 wold:40 told:186 tied:120 time:220 tin:100
            thy:80 tee:80 dent:110 dint:60 duet:80 bored:140 board:160 key:170 keys:150 quack:90
            great:214 greet:140 grey:150 grade:150 gate:150 grace:140 guide:150 gain:140 got:214
""" + "I:240 a:250"

        private const val HEBREW_RAW = """
            של:250 את:250 לא:250 זה:246 על:246 מה:240 אני:242 אתה:230 הוא:236 היא:230 כן:230 יש:232
            אין:222 עם:232 גם:226 כל:226 אבל:226 רק:218 עוד:218 טוב:216 שלום:206 תודה:212 בבקשה:196
            יום:212 ערב:176 בוקר:180 לילה:180 ילד:182 ילדה:166 ספר:176 בית:202 אמא:190 אבא:186 חבר:180
            מים:180 אוכל:176 עבודה:180 אהבה:170 שנה:196 זמן:200 למה:206 איך:206 מתי:190 איפה:190
            כמה:206 היום:206 מחר:190 שלו:204 שלי:210 בוא:186 תוך:176 תורה:150 תמונה:160 דבר:190 שם:210
            שמש:160 שלם:120 בקר:110 בקשה:150 בור:100 ספרים:150 טובה:170 ערבית:110
"""

        fun parse(raw: String): List<WordFreq> = raw.trim().split(Regex("\\s+")).map {
            val (w, f) = it.split(":")
            WordFreq(w, f.toInt())
        }.distinctBy { it.word }

        val ENGLISH: List<WordFreq> = parse(ENGLISH_RAW)
        val HEBREW_WORDS: List<WordFreq> = parse(HEBREW_RAW)

        fun index(words: List<WordFreq>): (Char) -> List<WordFreq> {
            val m = words.groupBy { it.word[0].lowercaseChar() }
                .mapValues { (_, v) -> v.sortedByDescending { it.freq } }
            return { c -> m[c] ?: emptyList() }
        }

        /**
         * Synthetic swipe: walks the (jittered) key centres of [word], with occasional corner
         * overshoots, uneven sample spacing (slower near corners) and small per-sample jitter.
         */
        fun swipe(
            word: String,
            centers: Map<Char, Pt>,
            rnd: Random,
            noise: Float = 0.25f,
            overshootProb: Float = 0.4f,
        ): List<Pt> {
            val keys = ArrayList<Pt>()
            for (c in word.lowercase()) {
                val p = centers[c] ?: continue
                if (keys.isEmpty() || keys.last() != p) keys.add(p)
            }
            fun jit(a: Float) = (rnd.nextFloat() * 2f - 1f) * a * KW
            val way = ArrayList<Pt>()
            for ((i, p) in keys.withIndex()) {
                val q = Pt(p.x + jit(noise), p.y + jit(noise))
                way.add(q)
                if (i in 1 until keys.size - 1 && rnd.nextFloat() < overshootProb) {
                    val prev = way[way.size - 2]
                    val dx = q.x - prev.x
                    val dy = q.y - prev.y
                    val len = sqrt(dx * dx + dy * dy)
                    if (len > 1f) {
                        val e = (0.15f + 0.25f * rnd.nextFloat()) * KW
                        way.add(Pt(q.x + dx / len * e, q.y + dy / len * e))
                    }
                }
            }
            if (way.size == 1) way.add(Pt(way[0].x + jit(0.1f), way[0].y + jit(0.1f)))
            var total = 0f
            for (i in 1 until way.size) total += d(way[i - 1], way[i])
            val baseStep = max(KW * 0.12f, total / 280f)
            val pts = ArrayList<Pt>()
            for (s in 0 until way.size - 1) {
                val a = way[s]
                val b = way[s + 1]
                val len = d(a, b)
                var t = 0f
                while (t < len) {
                    val u = t / len
                    pts.add(Pt(a.x + (b.x - a.x) * u + jit(0.03f), a.y + (b.y - a.y) * u + jit(0.03f)))
                    val speed = 0.35f + 0.65f * sin(PI.toFloat() * u)
                    t += baseStep * speed * (0.6f + 0.8f * rnd.nextFloat())
                }
            }
            pts.add(way.last())
            return pts
        }

        private fun d(a: Pt, b: Pt): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return sqrt(dx * dx + dy * dy)
        }

        fun seedOf(word: String) = word.fold(17) { h, c -> h * 31 + c.code }
    }

    private val decoder = SwipeDecoder(index(ENGLISH + HEBREW_WORDS))

    /** Returns (top-1 hits, top-[limit] hits) over [TRIALS] noisy swipes of [path letters]. */
    private fun accuracy(
        dec: SwipeDecoder,
        expected: String,
        centers: Map<Char, Pt>,
        swipeLetters: String = expected,
        trials: Int = TRIALS,
        log: Boolean = true,
    ): Pair<Int, Int> {
        val rnd = Random(seedOf(expected))
        var top1 = 0
        var topN = 0
        val misses = ArrayList<String>()
        repeat(trials) {
            val res = dec.decode(swipe(swipeLetters, centers, rnd), centers, KW)
            if (res.firstOrNull() == expected) top1++ else misses.add(res.toString())
            if (expected in res) topN++
        }
        if (log) {
            println("  %-10s top1 %2d/%d  top4 %2d/%d %s".format(
                expected, top1, trials, topN, trials,
                if (misses.isEmpty()) "" else "misses: " + misses.take(3).joinToString(" "),
            ))
        }
        return top1 to topN
    }

    private fun assertTop1(word: String, centers: Map<Char, Pt>, swipeLetters: String = word) {
        val (top1, _) = accuracy(decoder, word, centers, swipeLetters)
        assertTrue("$word: top-1 in only $top1/$TRIALS trials", top1 >= (TRIALS * 0.95).toInt())
    }

    @Test fun hello() = assertTop1("hello", QWERTY)
    @Test fun world() = assertTop1("world", QWERTY)
    @Test fun keyboard() = assertTop1("keyboard", QWERTY)
    @Test fun quick() = assertTop1("quick", QWERTY)
    @Test fun good() = assertTop1("good", QWERTY)
    @Test fun dontFromDont() = assertTop1("don't", QWERTY, swipeLetters = "dont")
    @Test fun the() = assertTop1("the", QWERTY)
    @Test fun tie() = assertTop1("tie", QWERTY)
    @Test fun shalom() = assertTop1("שלום", HEBREW)
    @Test fun toda() = assertTop1("תודה", HEBREW)
    @Test fun boker() = assertTop1("בוקר", HEBREW)

    @Test
    fun cleanSwipesOfEveryWordAreRecognised() {
        // Noise-free swipes through exact key centres: every swipeable word must be in the top 4.
        var total = 0
        var top1 = 0
        val failures = ArrayList<String>()
        for ((words, centers) in listOf(ENGLISH to QWERTY, HEBREW_WORDS to HEBREW)) {
            for (wf in words) {
                if (wf.word.count { it.lowercaseChar() in centers } < 2) continue
                total++
                val res = decoder.decode(swipe(wf.word, centers, Random(1), noise = 0f, overshootProb = 0f), centers, KW)
                if (res.firstOrNull() == wf.word) top1++
                if (wf.word !in res) failures.add("${wf.word} -> $res")
            }
        }
        println("Clean swipes: top1 $top1/$total; not in top4: $failures")
        assertTrue("not in top 4: $failures", failures.isEmpty())
    }

    @Test
    fun overallNoisyAccuracy() {
        var n = 0
        var top1 = 0
        var top4 = 0
        println("Per-word noisy accuracy ($TRIALS trials each):")
        for ((words, centers) in listOf(ENGLISH to QWERTY, HEBREW_WORDS to HEBREW)) {
            for (wf in words) {
                if (wf.word.count { it.lowercaseChar() in centers } < 2) continue
                val (a, b) = accuracy(decoder, wf.word, centers, log = false)
                if (a < TRIALS) accuracy(decoder, wf.word, centers, log = true)
                n += TRIALS
                top1 += a
                top4 += b
            }
        }
        val p1 = top1 * 100.0 / n
        val p4 = top4 * 100.0 / n
        println("Overall noisy: top1 %.1f%%  top4 %.1f%%".format(p1, p4))
        assertTrue("overall top-1 %.1f%%".format(p1), p1 >= 85.0)
        assertTrue("overall top-4 %.1f%%".format(p4), p4 >= 97.0)
    }

    @Test
    fun returnsDictionarySpellingCaseInsensitive() {
        val res = decoder.decode(swipe("london", QWERTY, Random(3)), QWERTY, KW)
        assertEquals("London", res.firstOrNull())
    }

    @Test
    fun skipsApostropheAndCollapsesDoubleLetters() {
        val dec = SwipeDecoder(index(listOf(WordFreq("don't", 200), WordFreq("hello", 200), WordFreq("helo", 1))))
        assertEquals("don't", dec.decode(swipe("dont", QWERTY, Random(5)), QWERTY, KW).firstOrNull())
        assertEquals("hello", dec.decode(swipe("helo", QWERTY, Random(5)), QWERTY, KW).firstOrNull())
        // Punctuation keys on the layout (e.g. an apostrophe key) must not be required or used.
        val withPunct = QWERTY + mapOf('\'' to Pt(950f, 225f), ',' to Pt(850f, 375f), '.' to Pt(950f, 375f))
        assertEquals("don't", dec.decode(swipe("dont", QWERTY, Random(6)), withPunct, KW).firstOrNull())
    }

    @Test
    fun singleLetterWordsAreIgnored() {
        val dec = SwipeDecoder(index(listOf(WordFreq("a", 255), WordFreq("I", 255))))
        assertTrue(dec.decode(swipe("as", QWERTY, Random(1)), QWERTY, KW).isEmpty())
        assertTrue(dec.decode(listOf(Pt(100f, 225f), Pt(102f, 226f)), QWERTY, KW).isEmpty())
    }

    @Test
    fun emptyAndDegenerateInputs() {
        assertTrue(decoder.decode(emptyList(), QWERTY, KW).isEmpty())
        assertTrue(decoder.decode(swipe("hello", QWERTY, Random(1)), emptyMap(), KW).isEmpty())
        assertTrue(decoder.decode(swipe("hello", QWERTY, Random(1)), QWERTY, 0f).isEmpty())
        assertTrue(decoder.decode(swipe("hello", QWERTY, Random(1)), QWERTY, KW, limit = 0).isEmpty())
        assertTrue(SwipeDecoder { emptyList() }.decode(swipe("hello", QWERTY, Random(1)), QWERTY, KW).isEmpty())
        // A path that barely moves: no crash, at most `limit` distinct words.
        val tiny = decoder.decode(listOf(Pt(450f, 75f), Pt(452f, 76f), Pt(451f, 75f)), QWERTY, KW)
        assertTrue(tiny.size <= 4 && tiny.toSet().size == tiny.size)
        // A single sample, and NaN samples mixed in.
        decoder.decode(listOf(Pt(450f, 75f)), QWERTY, KW)
        decoder.decode(listOf(Pt(Float.NaN, 1f)) + swipe("hello", QWERTY, Random(1)), QWERTY, KW)
    }

    @Test
    fun limitAndNoDuplicates() {
        val dup = ENGLISH + WordFreq("hello", 10) // same spelling twice in the dictionary
        val dec = SwipeDecoder(index(dup))
        for (limit in 1..6) {
            val res = dec.decode(swipe("hello", QWERTY, Random(9)), QWERTY, KW, limit)
            assertTrue(res.size <= limit)
            assertEquals(res.size, res.toSet().size)
            assertEquals("hello", res.first())
        }
    }

    @Test
    fun layoutSwitchBetweenCalls() {
        assertEquals("hello", decoder.decode(swipe("hello", QWERTY, Random(2)), QWERTY, KW).firstOrNull())
        assertEquals("שלום", decoder.decode(swipe("שלום", HEBREW, Random(2)), HEBREW, KW).firstOrNull())
        // Same layout scaled 2x with a 2x key width must give the same answer.
        val big = QWERTY.mapValues { (_, p) -> Pt(p.x * 2, p.y * 2) }
        val path = swipe("world", QWERTY, Random(2)).map { Pt(it.x * 2, it.y * 2) }
        assertEquals("world", decoder.decode(path, big, KW * 2).firstOrNull())
    }

    @Test
    fun speedWith60kWords() {
        val rnd = Random(42)
        val alphabet = "abcdefghijklmnopqrstuvwxyz"
        val fake = ArrayList<WordFreq>(60_000)
        repeat(60_000) {
            val len = 2 + rnd.nextInt(9)
            val w = buildString { repeat(len) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            val r = rnd.nextFloat()
            fake.add(WordFreq(w, 1 + (r * r * r * 150).toInt()))
        }
        val dec = SwipeDecoder(index(ENGLISH + fake))
        val words = listOf("hello", "world", "keyboard", "quick", "good", "dont", "the", "tie", "something", "because")
        val paths = words.flatMap { w -> val r = Random(seedOf(w)); List(TRIALS) { w to swipe(w, QWERTY, r) } }
        repeat(3) { for ((_, p) in paths) dec.decode(p, QWERTY, KW) } // JIT warm-up
        var hits = 0
        val t0 = System.nanoTime()
        for ((w, p) in paths) {
            val res = dec.decode(p, QWERTY, KW)
            if (res.firstOrNull()?.replace("'", "") == w) hits++
        }
        val avgMs = (System.nanoTime() - t0) / 1e6 / paths.size
        println("60k-word lexicon: avg %.2f ms/decode, top1 %d/%d".format(avgMs, hits, paths.size))
        assertTrue("avg decode %.2f ms".format(avgMs), avgMs < 40.0)
    }
}
