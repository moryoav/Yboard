package com.yboard.keyboard

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import com.yboard.keyboard.settings.AiFeature
import com.yboard.keyboard.settings.AiModel
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.text.*
import com.yboard.keyboard.voice.ApiKeyStore
import com.yboard.keyboard.voice.OpenAi
import org.json.JSONObject

/** Synthetic diagnostics only; no prompt, credential or learned personal text is exported. */
internal object PredictionChecks {
    fun local(context: Context): String {
        val cases = listOf(
            Triple("en", "I will let you ", "know"), Triple("en", "I'm looking forward to ", "seeing"),
            Triple("he", "אני בדרך ", "הביתה"), Triple("he", "תודה רבה על ", "העזרה")
        )
        val models = listOf("en", "he").associateWith { PhraseModel.load(context, it) }
        val words = listOf("en", "he").associateWith { NextWords.inMemory(it) }
        cases.forEach { (lang, before, expected) -> check(words.getValue(lang).predictContext(before, model = models[lang]).contains(expected)) }
        repeat(100) { cases.forEach { (lang, before, _) -> words.getValue(lang).predictContext(before, model = models[lang]) } }
        val timings = ArrayList<Long>()
        repeat(250) { cases.forEach { (lang, before, _) ->
            val start = SystemClock.elapsedRealtimeNanos()
            words.getValue(lang).predictContext(before, model = models[lang])
            timings.add(SystemClock.elapsedRealtimeNanos() - start)
        } }
        timings.sort()
        val p95 = timings[949] / 1_000_000.0
        check(p95 < 30) { "Local prediction p95=$p95 ms exceeded 30 ms" }
        return "Local EN/HE checks passed; ${models.getValue("en").size}/${models.getValue("he").size} contexts; 1000 lookups p50=${timings[499] / 1_000_000.0} ms p95=$p95 ms"
    }

    fun persistence(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val name = "prediction-regression-fixture"
        val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        store.edit().clear().commit()
        val constructor = NextWords::class.java.getDeclaredConstructor(android.content.SharedPreferences::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }
        var engine = constructor.newInstance(store, "en", """{"love":["pizza",9]}""")
        instrumentation.runOnMainSync { repeat(3) { engine.learnText("I work at workshop") } }
        SystemClock.sleep(3500)
        val raw = store.getString("en", null) ?: error("Delayed prediction save did not run")
        engine = constructor.newInstance(store, "en", raw)
        check(engine.predict("love", false).first() == "pizza")
        check(engine.predictContext("I work at ").first() == "workshop")
        check(!engine.predictContext("I work at ", personalized = false).contains("workshop"))
        store.edit().clear().commit()
        context.deleteSharedPreferences(name)
        return "Actual preferences save/reopen, legacy migration and personal-learning exclusion passed"
    }

    /** Compare selected models without changing settings or reading any editor text. */
    fun cloud(context: Context, models: List<String>, repeats: Int): String {
        Prefs.init(context)
        val key = ApiKeyStore.get(context) ?: error("No saved API key; cloud benchmark not run")
        val cases = listOf("en" to "I will let you ", "en" to "Can you send me the ", "he" to "אני בדרך ", "he" to "תודה רבה על ")
        val report = JSONObject()
        for (model in models.take(3)) {
            val firstTimes = ArrayList<Long>()
            val fullTimes = ArrayList<Long>()
            var passed = 0
            var failures = 0
            repeat(repeats.coerceIn(1, 5)) {
                for ((lang, text) in cases) {
                    val start = SystemClock.elapsedRealtime()
                    var first: Long? = null
                    val result = runCatching { OpenAi.predictNext(key, text, lang, emptyList(), emptyList(), NextWords.lastWordOf(text), AiModel(model),
                        onPredictions = { if (first == null) first = SystemClock.elapsedRealtime() - start }) }
                    result.onSuccess { options ->
                        if (options.isNotEmpty()) passed++ else failures++
                        first?.let { firstTimes.add(it) }
                        fullTimes.add(SystemClock.elapsedRealtime() - start)
                    }.onFailure { failures++ }
                }
            }
            firstTimes.sort(); fullTimes.sort()
            fun median(values: List<Long>) = values.getOrNull((values.size - 1).coerceAtLeast(0) / 2) ?: -1
            fun p95(values: List<Long>) = values.getOrNull(((values.size * 95 + 99) / 100 - 1).coerceAtLeast(0)) ?: -1
            report.put(model, JSONObject().put("valid", passed).put("failed", failures)
                .put("first_p50_ms", median(firstTimes)).put("first_p95_ms", p95(firstTimes))
                .put("complete_p50_ms", median(fullTimes)).put("complete_p95_ms", p95(fullTimes)))
        }
        return report.toString()
    }
}
