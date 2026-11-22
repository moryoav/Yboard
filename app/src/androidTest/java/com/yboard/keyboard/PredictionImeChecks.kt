package com.yboard.keyboard

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import com.yboard.keyboard.ime.YboardIME
import com.yboard.keyboard.keyboard.KeyDef
import com.yboard.keyboard.keyboard.KeyType
import com.yboard.keyboard.keyboard.KeyboardRootView
import com.yboard.keyboard.keyboard.KeyboardView
import com.yboard.keyboard.local.QwenModel
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.text.Pt
import com.yboard.keyboard.text.SentenceContext
import com.yboard.keyboard.text.Suggester
import com.yboard.keyboard.text.UserWords
import org.json.JSONArray
import org.json.JSONObject

/** Real service, separate app editor and actual local model. Synthetic text only. */
internal class PredictionImeChecks(private val instrumentation: Instrumentation) {
    private lateinit var ime: YboardIME
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun field(name: String) = YboardIME::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST") private fun slots() = field("slotWords").get(ime) as List<String>
    private fun keyboard() = (field("root").get(ime) as KeyboardRootView).keyboard
    private fun pendingSwipes() = (field("pendingSwipes").get(ime) as Collection<*>).size
    private fun text() = ime.currentInputConnection?.getTextBeforeCursor(SentenceContext.READ_LIMIT, 0)?.toString().orEmpty()
    private fun draft() = text() + ime.currentInputConnection.getTextAfterCursor(SentenceContext.READ_LIMIT, 0)?.toString().orEmpty()
    private fun touchGlide(word: String, beforeLift: () -> Unit = {}) {
        val keyboard = keyboard()
        val method = KeyboardView::class.java.getDeclaredMethod("keyCenters").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val centers = method.invoke(keyboard) as Map<Char, Pt>
        val started = SystemClock.uptimeMillis()
        fun send(action: Int, point: Pt) {
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            try { check(keyboard.onTouchEvent(event)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, centers.getValue(word.first()))
        word.drop(1).forEach { send(MotionEvent.ACTION_MOVE, centers.getValue(it)) }
        beforeLift()
        send(MotionEvent.ACTION_UP, centers.getValue(word.last()))
    }
    private fun await(label: String, timeoutMs: Long = 20_000, condition: () -> Boolean): Long {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            var passed = false
            onMain { passed = condition() }
            if (passed) return SystemClock.elapsedRealtime() - start
            SystemClock.sleep(25)
        }
        var details = ""
        onMain { details = "text=${text()}; slots=${slots()}; pending=${pendingSwipes()}; status=${com.yboard.keyboard.local.LocalQwen.status.value}" }
        error("Timed out: $label; $details")
    }
    private fun launch(value: String, inputType: Int = 131073, options: Int = 0): Boolean {
        val encoded = java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val command = "am start -W -n com.yboard.keyboard.probe/com.yboard.keyboard.test.EditorProbeActivity -f 0x10008000 --es text64 '$encoded' --ei inputType $inputType --ei imeOptions $options"
        instrumentation.uiAutomation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
        SystemClock.sleep(1000)
        var found = false
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!found && SystemClock.elapsedRealtime() < deadline) {
            onMain {
                val type = Class.forName("android.app.ActivityThread")
                val thread = type.getDeclaredMethod("currentActivityThread").invoke(null)
                val services = type.getDeclaredField("mServices").apply { isAccessible = true }.get(thread) as Map<*, *>
                services.values.filterIsInstance<YboardIME>().firstOrNull { it.isInputViewShown && it.currentInputConnection != null }
                    ?.let { ime = it; found = true }
            }
            if (!found) SystemClock.sleep(100)
        }
        // OnePlus routes password fields to its protected system keyboard.
        if (!found && inputType == 129) return false
        check(found) { "IME service was not started by the probe editor" }
        await("synthetic editor connection") { text() == value }
        return true
    }

    /** Measures the real IME from synthetic finger lift until the editor receives the word. */
    fun glideBenchmark(): String {
        Prefs.init(instrumentation.targetContext)
        val saved = Prefs.current
        val report = JSONArray()
        val fixtures = listOf(
            Triple("en", "I will be ", "there"),
            Triple("en", "We talked about the plans for the week and ".repeat(10) + "I will be ", "there"),
            Triple("he", "אני רוצה לשתות ", "מים"),
        )
        try {
            for ((language, prefix, word) in fixtures) {
                // Isolate glide preparation from background next-word requests.
                onMain { Prefs.update { it.copy(language = language, english = true, hebrew = true, suggestions = true, nextWords = false,
                    learnWords = false, autoCaps = false, swipeTyping = true) } }
                var expectedStrip: List<String>? = null
                val times = mutableListOf<Double>()
                repeat(6) { trial ->
                    launch(prefix)
                    await("glide keyboard ready") { keyboard().gestureTyping && keyboard().width > 0 }
                    var lifted = 0L
                    onMain {
                        touchGlide(word) {
                            // Simulate the worker time available during a short gesture.
                            SystemClock.sleep(200)
                            lifted = SystemClock.elapsedRealtimeNanos()
                        }
                    }
                    await("glide benchmark $language $trial") { draft() == prefix + word && slots().getOrNull(1) == word }
                    times += (SystemClock.elapsedRealtimeNanos() - lifted) / 1_000_000.0
                    onMain {
                        if (expectedStrip == null) expectedStrip = slots().toList()
                        check(expectedStrip == slots()) { "Replay produced different alternatives" }
                    }
                }
                report.put(JSONObject().put("language", language).put("contextLength", prefix.length)
                    .put("ms", JSONArray(times.drop(1))).put("strip", JSONArray(expectedStrip)))
            }
        } finally { onMain { Prefs.update { saved } } }
        return JSONObject().put("model", QwenModel.selected.label).put("glides", report).toString()
    }

    fun run(): String {
        Prefs.init(instrumentation.targetContext)
        val saved = Prefs.current
        val report = JSONObject()
        try {
            onMain { Prefs.update { it.copy(language = "en", suggestions = true, nextWords = true,
                learnWords = false, autoCorrect = true, autoCaps = false, swipeTyping = true) } }
            launch("See you to")
            onMain { ime.onKey(KeyDef(KeyType.CHAR, "m")) }
            report.put("completionWaitMs", await("prefix completion") { slots().contains("tomorrow") })
            onMain {
                check(slots().filter { it.isNotEmpty() }.all { it.startsWith("tom", true) })
                ime.onSuggestion(slots().indexOf("tomorrow"))
            }
            await("accepted completion") { text() == "See you tomorrow " }
            report.put("nextWordWaitMs", await("next word") { slots().any { it.isNotEmpty() } })
            onMain { ime.onSuggestion(slots().indexOfFirst { it.isNotEmpty() }) }
            await("second suggestion tap") { text().length > "See you tomorrow ".length }
            onMain {
                val expected = text().dropLast(1) + "."
                ime.onKey(KeyDef(KeyType.PERIOD, "."))
                check(text() == expected)
            }
            launch("See you tom")
            await("prefix completion for comma") { slots().contains("tomorrow") }
            onMain {
                ime.onSuggestion(slots().indexOf("tomorrow"))
                ime.onKey(KeyDef(KeyType.COMMA, ","))
                check(text() == "See you tomorrow,")
            }
            launch("See you tom")
            await("prefix completion before cursor move") { slots().contains("tomorrow") }
            onMain { ime.onSuggestion(slots().indexOf("tomorrow")) }
            await("accepted completion before cursor move") { text() == "See you tomorrow " }
            onMain {
                ime.currentInputConnection.setSelection(4, 4)
                ime.onKey(KeyDef(KeyType.COMMA, ","))
                check(text() == "See ,")
                check(ime.currentInputConnection.getTextAfterCursor(80, 0).toString() == "you tomorrow ")
            }
            launch("teh")
            onMain { ime.onKey(KeyDef(KeyType.SPACE, " ")) }
            await("literal typing") { text() == "teh " }
            val longSentence = "I remember " + "the conversation about our plans ".repeat(20)
            launch(longSentence + "tom")
            onMain {
                val input = YboardIME::class.java.getDeclaredMethod("localInput").apply { isAccessible = true }.invoke(ime) as SentenceContext.Input
                check(input.context == longSentence) { "Full sentence was truncated" }
            }
            launch("I will be ")
            val centers = "qwertyuiop".mapIndexed { i, c -> c to Pt(i * 100f, 0f) }.toMap() +
                "asdfghjkl".mapIndexed { i, c -> c to Pt((i + .5f) * 100, 140f) }.toMap() +
                "zxcvbnm".mapIndexed { i, c -> c to Pt((i + 1.5f) * 100, 280f) }.toMap()
            fun glide(word: String) {
                ime.onSwipeStart()
                ime.onSwipe(word.map { centers.getValue(it) }, centers, 100f)
            }
            onMain { glide("there") }
            report.put("swipeWaitMs", await("contextual swipe") {
                text() == "I will be there" && slots().contains("there") && field("stripCursor").getInt(ime) == text().length
            })
            onMain { ime.onSuggestion(slots().indexOf("there")) }
            await("accepted swipe") { text() == "I will be there " }
            onMain {
                ime.onKey(KeyDef(KeyType.PERIOD, "."))
                check(text() == "I will be there.")
            }
            launch("I will be ")
            onMain {
                touchGlide("there")
                touchGlide("again")
                touchGlide("tomorrow")
                check(pendingSwipes() == 3) { "All three released gestures should be queued" }
                check(text() == "I will be ")
            }
            report.put("queuedSwipeWaitMs", await("three swipes in order", timeoutMs = 60_000) {
                text() == "I will be there again tomorrow"
            })
            onMain { check(slots().contains("tomorrow")) }
            launch("Move the cursor ")
            onMain {
                glide("there")
                glide("again")
                check(pendingSwipes() == 2)
                ime.currentInputConnection.setSelection(4, 4)
            }
            await("swipes discarded after cursor move") { pendingSwipes() == 0 }
            onMain { check(text() == "Move" && ime.currentInputConnection.getTextAfterCursor(80, 0).toString() == " the cursor ") }
            launch("A new message ")
            onMain {
                ime.onSwipe("there".map { centers.getValue(it) }, centers, 100f)
                ime.onKey(KeyDef(KeyType.CHAR, "x"))
            }
            SystemClock.sleep(1000)
            onMain { check(text() == "A new message x") }
            checkMixedTyping()
            checkAddressBarGlide()
            launch("Private ", options = 0x1000000)
            onMain {
                val allowed = YboardIME::class.java.getDeclaredMethod("localAllowed").apply { isAccessible = true }.invoke(ime) as Boolean
                check(!allowed && slots().isEmpty())
            }
            val ownPassword = launch("secret", inputType = 129)
            onMain {
                if (ownPassword) { check(slots().isEmpty()); check(!(field("learningAllowed").get(ime) as Boolean)) }
                else check(!com.yboard.keyboard.text.EditorPolicy.suggestions(129))
            }
            if (!ownPassword) {
                val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys input_method").use { fd ->
                    java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
                }
                val current = Regex("mCurId=(\\S+)").find(dump)?.groupValues?.get(1).orEmpty()
                check(current.isNotEmpty() && !current.startsWith("com.yboard.keyboard/")) { "No replacement password keyboard was active" }
            }
            report.put("passwordKeyboard", if (ownPassword) "Yboard guarded" else "OnePlus protected keyboard")
            checkWordReplacement()
            checkReportedPredictions()
            if (QwenModel.selected == QwenModel.Variant.QWEN3 && checkPersonalCompletions()) {
                report.put("personalCompletion", "Two taps reordered a close match, saved the count, and respected Learn my words")
            }
            launch("Finished ")
            report.put("passed", "prefix completion, consecutive taps, punctuation after suggestions, literal spaces at a moved cursor, full sentence, model-ranked swipes, ordered glide queue, cursor and tap cancellation, spacing after completed glides, standalone I, address bar glide, private/password guards, clipped where glide, word-end glide alternatives, edge completion capitalization and next words after a space")
            return report.toString()
        } finally { onMain { Prefs.update { saved } } }
    }

    private fun checkPersonalCompletions(): Boolean {
        val context = instrumentation.targetContext
        val user = UserWords.get(context, "en")
        val words = listOf("box", "books")
        val prefs = Prefs.current
        if (prefs.myWords.any { it.lowercase() in words }) return false
        val original = mutableMapOf<String, Pair<String?, Int>>()
        onMain {
            for (word in words) {
                original[word] = user.spelling(word) to user.count(word)
                user.forget(word)
            }
            Prefs.update { it.copy(learnWords = true) }
        }
        val prefix = "I will bring the bo"
        val initial = listOf("books", "book", "box")
        try {
            repeat(2) {
                launch(prefix)
                await("original autocomplete order") { slots() == initial }
                onMain { ime.onSuggestion(slots().indexOf("box")) }
                await("accepted familiar completion") { text() == "I will bring the box " }
            }
            launch(prefix)
            await("learned completion moved before a close match") { slots() == listOf("box", "book", "books") }
            val persisted = context.getSharedPreferences("yboard_words", Context.MODE_PRIVATE)
            await("learned count persisted") {
                JSONObject(persisted.getString("en", "{}").orEmpty()).optJSONArray("box")?.optInt(1) == 4
            }
            onMain { Prefs.update { it.copy(learnWords = false) } }
            await("disabling learning restores the original order") { slots() == initial }
            return true
        } finally {
            onMain {
                for ((word, entry) in original) {
                    user.forget(word)
                    val (spelling, count) = entry
                    if (spelling != null && count > 0) user.learn(spelling, count)
                }
                Prefs.update { prefs }
            }
        }
    }

    private fun checkAddressBarGlide() {
        val urlInput = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val options = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        launch("https://example.test", inputType = urlInput, options = options)
        onMain { ime.currentInputConnection.setSelection(0, text().length) }
        await("address selected") { ime.currentInputConnection.getSelectedText(0)?.toString() == "https://example.test" }
        onMain {
            check(keyboard().gestureTyping && slots().isEmpty() && !field("learningAllowed").getBoolean(ime))
            touchGlide("how")
        }
        await("glide replaces selected address") { draft() == "how" && pendingSwipes() == 0 }
        onMain {
            check(slots().isEmpty())
            touchGlide("many")
        }
        await("second address-bar glide appends a word") { draft() == "how many" && pendingSwipes() == 0 }
        onMain {
            check(slots().isEmpty())
            ime.onText(" teh")
            ime.onKey(KeyDef(KeyType.SPACE, " "))
            check(draft() == "how many teh " && slots().isEmpty())
        }
    }

    private fun checkReportedPredictions() {
        val sentence = "Sometimes, when I type, or glide, there seems to be an issue "
        launch(sentence)
        val centers = Suggester.keyPositions("en").mapValues { (_, p) -> Pt(p.first * 100, p.second * 140) }
        val clipped = "where".map(centers::getValue).zip("edgre".map(centers::getValue)) { a, b ->
            Pt(a.x + (b.x - a.x) * 0.4f, a.y + (b.y - a.y) * 0.4f)
        }
        onMain {
            ime.onSwipeStart()
            ime.onSwipe(clipped, centers, 100f)
        }
        await("clipped where glide") { draft() == sentence + "where" && slots().getOrNull(1) == "where" }
        onMain { ime.currentInputConnection.setSelection(sentence.length + 2, sentence.length + 2) }
        await("original glide alternatives inside the word") {
            field("replacement").get(ime) != null && slots().contains("edge")
        }
        val wordEnd = sentence.length + "where".length
        onMain { ime.currentInputConnection.setSelection(wordEnd, wordEnd) }
        await("original glide alternatives at the terminal word end") {
            field("selEnd").getInt(ime) == wordEnd && text() == sentence + "where" &&
                field("replacement").get(ime) != null && slots().contains("edge")
        }
        onMain { ime.onSuggestion(slots().indexOf("edge")) }
        await("terminal replacement preserves surrounding text") { draft() == sentence + "edge" }

        launch(sentence + "edge")
        await("ordinary capitalization for constrained edge completions") {
            slots().contains("edge") && slots().filter { it.isNotBlank() }.none { word -> word.drop(1).any { it.isUpperCase() } }
        }
        launch(sentence + "edge ")
        await("next words after a literal space") {
            field("replacement").get(ime) == null && field("currentWord").get(ime) == "" &&
                slots().any { it.isNotBlank() } && slots().none { it.startsWith("edge", true) }
        }
    }

    private fun checkMixedTyping() {
        launch("")
        onMain {
            ime.onKey(KeyDef(KeyType.SHIFT))
            touchGlide("not")
        }
        await("glided Not") { draft() == "Not" }
        onMain { touchGlide("a") } // A stationary touch on the a key.
        await("automatic space before a") { draft() == "Not a" }
        onMain { touchGlide("word") }
        await("automatic space before word") { draft() == "Not a word" }
        onMain { ime.onKey(KeyDef(KeyType.PERIOD, ".")) }
        await("period attached to the completed glide") { draft() == "Not a word." }

        launch("I will be ")
        onMain { touchGlide("there") }
        await("completed glide before taps") { text() == "I will be there" }
        onMain {
            touchGlide("i")
            touchGlide("n")
            ime.onKey(KeyDef(KeyType.COMMA, ","))
        }
        await("one space before a typed word, with no space before a comma") { draft() == "I will be there in," }

        launch("Then i")
        onMain { ime.onKey(KeyDef(KeyType.SPACE, " ")) }
        await("standalone i before space") { draft() == "Then I " }

        launch("Then i")
        onMain { ime.onKey(KeyDef(KeyType.PERIOD, ".")) }
        await("standalone i before punctuation") { draft() == "Then I." }

        launch("Then i")
        onMain { touchGlide("will") }
        await("standalone i before a glide", timeoutMs = 45_000) { draft() == "Then I will" }

        launch("2i")
        onMain { ime.onKey(KeyDef(KeyType.PERIOD, ".")) }
        await("i adjacent to a digit") { draft() == "2i." }
    }

    private fun checkWordReplacement() {
        launch("I will be ")
        onMain { touchGlide("there") }
        await("glide with alternatives", timeoutMs = 45_000) {
            text() == "I will be there" && field("stripCursor").getInt(ime) == text().length && slots().any { it.isNotBlank() && it != "there" }
        }
        onMain {
            ime.onKey(KeyDef(KeyType.SPACE, " "))
            ime.onText("tomorrow.")
        }
        await("more words after the glide") { draft() == "I will be there tomorrow." }
        onMain { ime.currentInputConnection.setSelection(12, 12) }
        await("original glide alternatives reopened") {
            field("replacement").get(ime) != null && slots().any { it.isNotBlank() } && !slots().contains("there")
        }
        onMain {
            val alternative = slots().first { it.isNotBlank() }
            ime.onSuggestion(slots().indexOf(alternative))
            check(draft() == "I will be $alternative tomorrow.")
        }

        onMain { Prefs.update { it.copy(suggestions = false, nextWords = false) } }
        val english = "First sentence. I saw teh, yesterday."
        launch(english)
        val caret = english.indexOf("teh") + 1
        onMain { ime.currentInputConnection.setSelection(caret, caret) }
        await("replacement with typing suggestions off") { slots().contains("the") }
        onMain {
            val choice = slots().indexOf("the")
            // Change text on the right of the caret before accepting the old suggestion.
            ime.currentInputConnection.deleteSurroundingText(0, 1)
            ime.onSuggestion(choice)
            check(draft() == english.replace("teh", "th"))
        }

        launch(english)
        onMain { ime.currentInputConnection.setSelection(caret, caret) }
        await("spelling replacement reopened") { slots().contains("the") }
        onMain { ime.onSuggestion(slots().indexOf("the")) }
        await("replacement preserved punctuation and remaining draft") { draft() == english.replace("teh", "the") }

        val hebrew = "אמרתי אניב, והמשכתי."
        launch(hebrew)
        val hebrewCaret = hebrew.indexOf("אניב") + 2
        onMain { ime.currentInputConnection.setSelection(hebrewCaret, hebrewCaret) }
        await("Hebrew word replacement") { slots().any { it.isNotBlank() } }
        onMain {
            val alternative = slots().first { it.isNotBlank() }
            ime.onSuggestion(slots().indexOf(alternative))
            check(draft() == hebrew.replace("אניב", alternative))
        }
        launch("Private word", options = 0x1000000)
        onMain { ime.currentInputConnection.setSelection(3, 3) }
        await("private word cursor move") { text() == "Pri" }
        onMain { check(slots().isEmpty() && field("replacement").get(ime) == null) }
        onMain { Prefs.update { it.copy(suggestions = true, nextWords = true) } }
    }
}
