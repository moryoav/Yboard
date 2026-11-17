package com.yboard.keyboard

import android.app.Instrumentation
import android.os.SystemClock
import com.yboard.keyboard.ime.YboardIME
import com.yboard.keyboard.keyboard.KeyDef
import com.yboard.keyboard.keyboard.KeyType
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.text.PredictionCache
import com.yboard.keyboard.text.PredictionPosition
import com.yboard.keyboard.text.PredictionStats
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Drives the real service and a separate app's editor, using synthetic cached AI replies. */
internal class PredictionImeChecks(private val instrumentation: Instrumentation) {
    private lateinit var ime: YboardIME
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun field(name: String) = YboardIME::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST") private fun slots() = field("slotWords").get(ime) as List<String>
    private fun refresh() = YboardIME::class.java.getDeclaredMethod("updateSuggestions").apply { isAccessible = true }.invoke(ime)
    private fun text() = ime.currentInputConnection.getTextBeforeCursor(400, 0).toString()
    private fun key(letter: String) {
        onMain { ime.onKey(KeyDef(KeyType.CHAR, letter)) }
        SystemClock.sleep(180)
    }
    private fun launch(value: String, inputType: Int = 131073, options: Int = 0) {
        val encoded = java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val command = "am start -W -n com.yboard.keyboard.probe/com.yboard.keyboard.test.EditorProbeActivity -f 0x10008000 --es text64 $encoded --ei inputType $inputType --ei imeOptions $options"
        instrumentation.uiAutomation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
        SystemClock.sleep(1000)
        onMain {
            val type = Class.forName("android.app.ActivityThread")
            val thread = type.getDeclaredMethod("currentActivityThread").invoke(null)
            val services = type.getDeclaredField("mServices").apply { isAccessible = true }.get(thread) as Map<*, *>
            ime = services.values.filterIsInstance<YboardIME>().single()
        }
        SystemClock.sleep(250)
        onMain { check(text() == value) { "Probe editor did not contain synthetic fixture" } }
    }

    fun run(): String {
        Prefs.init(instrumentation.targetContext)
        val saved = Prefs.current
        try {
            onMain { Prefs.update { it.copy(language = "en", aiPredictions = true, suggestions = true, nextWords = true,
                learnWords = false, autoCorrect = false, autoCaps = false, emojiSuggest = false) } }
            PredictionStats.reset()
            launch("See you ")
            onMain {
                (field("aiCache").get(ime) as PredictionCache).put(PredictionPosition("See you ", 8), listOf("tomorrow morning", "later today", "soon"))
                refresh()
                check(slots()[1] == "tomorrow")
                val toolbar = (field("root").get(ime) as com.yboard.keyboard.keyboard.KeyboardRootView).toolbar
                val pressed = toolbar.javaClass.getDeclaredField("pressedSlot").apply { isAccessible = true }
                val render = YboardIME::class.java.getDeclaredMethod("renderAiPredictions", PredictionPosition::class.java).apply { isAccessible = true }
                pressed.setInt(toolbar, 1)
                (field("aiCache").get(ime) as PredictionCache).put(PredictionPosition("See you ", 8), listOf("later today"))
                render.invoke(ime, PredictionPosition("See you ", 8))
                check(slots()[1] == "tomorrow") { "AI moved the suggestion during a touch" }
                pressed.setInt(toolbar, -1)
                render.invoke(ime, PredictionPosition("See you ", 8))
                check(slots()[1] == "later")
                (field("aiCache").get(ime) as PredictionCache).put(PredictionPosition("See you ", 8), listOf("tomorrow morning", "later today", "soon"))
                refresh()
            }
            key("t"); key("o"); key("m")
            onMain { check(slots().contains("tomorrow")); check(text() == "See you tom") }
            onMain { ime.onSuggestion(slots().indexOf("tomorrow")) }
            SystemClock.sleep(200)
            onMain { check(text() == "See you tomorrow "); check(slots().contains("morning")) }
            onMain { ime.onSuggestion(slots().indexOf("morning")) }
            SystemClock.sleep(200)
            onMain { check(text() == "See you tomorrow morning ") }

            launch("See you ")
            val jobType = Class.forName("com.yboard.keyboard.ime.YboardIME\$PredictionJob")
            val job = jobType.getDeclaredConstructor(PredictionPosition::class.java).apply { isAccessible = true }
                .newInstance(PredictionPosition("See you ", 8))
            onMain { field("predictionJob").set(ime, job) }
            key("t")
            onMain { check(field("predictionJob").get(ime) === job) { "Typing a prefix cancelled a compatible request" } }
            onMain { ime.onCursorMove(-1) }
            SystemClock.sleep(200)
            onMain { check(field("predictionJob").get(ime) == null) }

            launch("Private ", options = 0x1000000)
            onMain {
                val allowed = YboardIME::class.java.getDeclaredMethod("aiAllowed").apply { isAccessible = true }.invoke(ime) as Boolean
                check(!allowed)
                check(!(field("learningAllowed").get(ime) as Boolean))
            }
            launch("secret", inputType = 129)
            onMain { check(slots().isEmpty()); check(!(field("learningAllowed").get(ime) as Boolean)) }
            launch("A different field ")
            onMain {
                val prior = slots()
                YboardIME::class.java.getDeclaredMethod("renderAiPredictions", PredictionPosition::class.java)
                    .apply { isAccessible = true }.invoke(ime, PredictionPosition("See you ", 8))
                check(slots() == prior)
                check((field("aiCache").get(ime) as PredictionCache).words(PredictionPosition("See you ", 8)).isEmpty())
            }

            val release = CountDownLatch(1)
            val started = CountDownLatch(1)
            val predicted = CountDownLatch(1)
            val background = field("aiExecutor").get(ime) as ExecutorService
            val predictions = field("predictionExecutor").get(ime) as ExecutorService
            try {
                background.execute { started.countDown(); release.await(3, TimeUnit.SECONDS) }
                check(started.await(1, TimeUnit.SECONDS))
                predictions.execute { predicted.countDown() }
                check(predicted.await(500, TimeUnit.MILLISECONDS)) { "Prediction worker was blocked by background AI" }
            } finally { release.countDown() }
            return "Real IME: prefix completion, consecutive phrase taps, in-flight retention, cursor cancellation, field/password/private guards, touch stability and worker isolation passed; ${PredictionStats.summary()}"
        } finally { onMain { Prefs.update { saved } } }
    }
}
