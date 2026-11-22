package com.yboard.keyboard

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import com.yboard.keyboard.local.LocalQwen
import com.yboard.keyboard.local.QwenModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real local inference using synthetic sentences only. No API key or cloud request. */
internal object QwenChecks {
    fun download(context: Context, variant: String?): String {
        QwenModel.init(context)
        val model = variant?.let(QwenModel.Variant::valueOf) ?: QwenModel.selected
        if (QwenModel.state.value[model].ready) return "Verified ${model.label} already installed"
        fun await(label: String, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
            while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) {
                QwenModel.state.value[model].error?.let { error(it) }
                android.os.SystemClock.sleep(100)
            }
            check(condition()) { "Timed out: $label" }
        }
        QwenModel.download(context, model)
        await("download bytes") { QwenModel.state.value[model].let { it.bytes > 2_000_000 || it.ready } }
        if (!QwenModel.state.value[model].ready) {
            QwenModel.pause(model)
            await("pause") { !QwenModel.state.value[model].downloading }
            if (!QwenModel.state.value[model].ready) {
                check(QwenModel.state.value[model].bytes > 0)
                QwenModel.download(context, model)
                await("verified resume") { QwenModel.state.value[model].ready }
            }
        }
        check(QwenModel.file(model).length() == model.size)
        return "${model.label} public model download, pause, resume, size and SHA-256 verification passed"
    }
    fun core(context: Context, importPath: String?): String {
        QwenModel.init(context)
        val model = QwenModel.selected
        if (importPath != null && !QwenModel.state.value[model].ready) QwenModel.importVerified(File(importPath), model)
        check(QwenModel.state.value[model].ready) { "Download or import the verified Qwen model first" }
        val report = JSONArray()
        val fixtures = listOf(
            "automation " to "", "" to "hel", "See you" to "tom", "I will let you" to "", "I will be there" to "s",
            "אני בדרך" to "", "תודה רבה" to "",
        )
        for ((sentence, prefix) in fixtures) {
            val output = LocalQwen.predictBlocking(sentence, prefix)
            check(output.words.isNotEmpty()) { "Qwen returned no options for a synthetic fixture" }
            check(output.candidates.size <= 3 && output.candidates.all { it.score.isFinite() && it.score < 0.0 })
            check(output.candidates.zipWithNext().all { (a, b) -> a.score >= b.score })
            check(output.words.all { it.startsWith(prefix, true) })
            if (model == QwenModel.Variant.QWEN3 && sentence == "automation ") {
                check(output.words.any { it == "is" || it == "and" }) { "A short message fragment produced filler instead of a continuation" }
            }
            report.put(JSONObject().put("sentence", sentence).put("prefix", prefix).put("words", JSONArray(output.words))
                .put("scores", JSONArray(output.candidates.map { it.score })).put("ms", output.elapsedMs))
        }
        val values = rank("I will be", listOf("there", "three", "the"))
        check(values[0] > values[1]) { "Sentence context did not prefer there over three" }
        val geometry = listOf(com.yboard.keyboard.text.SwipeDecoder.Candidate("there", 0f),
            com.yboard.keyboard.text.SwipeDecoder.Candidate("three", .47287515f), com.yboard.keyboard.text.SwipeDecoder.Candidate("the", .47822356f))
        check(com.yboard.keyboard.text.GlideRanking.rank(geometry, values).first() == "there") { "Language probability overwhelmed the gesture's clear detour" }
        report.put(JSONObject().put("rank", "I will be [there, three, the]").put("scores", JSONArray(values.toList())))
        return JSONObject().put("model", model.label).put("checks", report).toString()
    }

    fun switch(instrumentation: Instrumentation, importPath: String?): String {
        QwenModel.init(instrumentation.targetContext)
        val first = QwenModel.Variant.QWEN3
        val second = QwenModel.Variant.QWEN35
        if (importPath != null && !QwenModel.state.value[second].ready) QwenModel.importVerified(File(importPath), second)
        check(QwenModel.state.value[first].ready && QwenModel.state.value[second].ready)
        val saved = QwenModel.selected
        try {
            instrumentation.runOnMainSync { QwenModel.select(first) }
            val candidates = listOf("there", "three", "the")
            val initial = rank("I will be", candidates)
            check(LocalQwen.status.value.model == first)
            checkPreparedRanking("I will be", candidates, initial)
            val cancelled = CountDownLatch(1)
            var stale: Result<DoubleArray>? = null
            instrumentation.runOnMainSync {
                LocalQwen.rank("I will be", candidates) { stale = it; cancelled.countDown() }
                QwenModel.select(second)
            }
            check(cancelled.await(10, TimeUnit.SECONDS))
            check(stale?.exceptionOrNull() is java.util.concurrent.CancellationException)
            val alternate = rank("I will be", candidates)
            check(LocalQwen.status.value.model == second)
            check(alternate.all { it.isFinite() } && !initial.contentEquals(alternate))
            val hebrew = rank("אני רוצה לשתות", listOf("מים", "שמים"))
            check(hebrew.all { it.isFinite() } && hebrew[0] > hebrew[1]) { "Hebrew sentence did not prefer water" }
            instrumentation.runOnMainSync { QwenModel.select(first) }
            check(rank("I will be", candidates).contentEquals(initial))
            check(LocalQwen.status.value.model == first)
            check(QwenModel.state.value[first].ready && QwenModel.state.value[second].ready)
            return "Both model files stayed ready. Switched engines, cancelled pending ranking, and scored English and Hebrew."
        } finally {
            instrumentation.runOnMainSync { QwenModel.select(saved) }
        }
    }

    fun benchmark(instrumentation: Instrumentation): String {
        QwenModel.init(instrumentation.targetContext)
        val saved = QwenModel.selected
        val report = JSONArray()
        val next = listOf("I will be", "Thanks for sharing the", "אני רוצה לשתות")
        val completions = listOf("See you" to "tom", "I will bring the" to "bo", "אני רוצה לשתות" to "מ")
        val glides = glideFixtures()
        try {
            for (model in QwenModel.Variant.entries) {
                check(QwenModel.state.value[model].ready) { "Download ${model.label} first" }
                instrumentation.runOnMainSync { QwenModel.select(model) }
                val warmup = LocalQwen.predictBlocking("I have a plan")
                check(warmup.words.isNotEmpty() && LocalQwen.status.value.model == model)
                val nextTimes = next.map { sentence ->
                    val output = LocalQwen.predictBlocking(sentence)
                    check(output.words.isNotEmpty()) { "${model.label} returned no next-word suggestions" }
                    output.elapsedMs
                }
                val completionTimes = completions.map { (sentence, prefix) ->
                    val output = LocalQwen.predictBlocking(sentence, prefix)
                    check(output.words.isNotEmpty() && output.words.all { it.startsWith(prefix, true) }) {
                        "${model.label} returned no matching completion for $prefix"
                    }
                    output.elapsedMs
                }
                val glideTimes = glides.map { (sentence, words) ->
                    val start = SystemClock.elapsedRealtime()
                    val scores = rank(sentence, words)
                    val elapsed = SystemClock.elapsedRealtime() - start
                    check(scores.size == words.size && scores.all { it.isFinite() })
                    elapsed
                }
                report.put(JSONObject().put("model", model.label)
                    .put("nextWordMs", JSONArray(nextTimes))
                    .put("completionMs", JSONArray(completionTimes))
                    .put("glideRanking24Ms", JSONArray(glideTimes)))
            }
        } finally {
            instrumentation.runOnMainSync { QwenModel.select(saved) }
        }
        return report.toString()
    }

    private fun glideFixtures(): List<Pair<String, List<String>>> {
        val english = listOf("there", "three", "the", "their", "then", "these", "them", "they",
            "where", "here", "tree", "through", "though", "thought", "thread", "those", "threw",
            "throw", "throat", "threat", "theater", "theory", "theme", "therefore")
        val hebrew = listOf("מים", "שמים", "יין", "מיץ", "חלב", "תה", "קפה", "סודה",
            "משקה", "בירה", "מרק", "מחר", "מפה", "מיד", "מישהו", "משהו", "מתוק",
            "טוב", "שלום", "בוקר", "עכשיו", "לחם", "פרי", "אוכל")
        return listOf("I will be" to english, "My favorite number is" to english, "אני רוצה לשתות" to hebrew)
    }

    /** Identical batches can be replayed against two builds to compare exact scores and wall time. */
    fun glideBenchmark(instrumentation: Instrumentation): String {
        QwenModel.init(instrumentation.targetContext)
        val saved = QwenModel.selected
        val report = JSONArray()
        try {
            for (model in QwenModel.Variant.entries) {
                check(QwenModel.state.value[model].ready) { "Download ${model.label} first" }
                instrumentation.runOnMainSync { QwenModel.select(model) }
                rank("I have a plan", listOf("for", "and", "now"))
                val batches = glideFixtures().map { (sentence, words) ->
                    check(words.size == 24)
                    val reference = rank(sentence, words)
                    check(reference.size == words.size && reference.all { it.isFinite() })
                    val times = List(4) {
                        val start = SystemClock.elapsedRealtimeNanos()
                        val scores = rank(sentence, words)
                        check(scores.contentEquals(reference)) { "Repeated ranking changed for ${model.label}" }
                        (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    }
                    JSONObject().put("sentence", sentence).put("ms", JSONArray(times))
                        .put("scoreBits", JSONArray(reference.map { it.toRawBits().toString(16) }))
                }
                report.put(JSONObject().put("model", model.label).put("batches", JSONArray(batches)))
            }
        } finally {
            instrumentation.runOnMainSync { QwenModel.select(saved) }
        }
        return report.toString()
    }

    private fun rank(sentence: String, candidates: List<String>): DoubleArray {
        val done = CountDownLatch(1)
        var scores: Result<DoubleArray>? = null
        LocalQwen.rank(sentence, candidates) { scores = it; done.countDown() }
        check(done.await(60, TimeUnit.SECONDS)) { "Ranking timed out" }
        return checkNotNull(scores).getOrThrow()
    }

    private fun checkPreparedRanking(sentence: String, candidates: List<String>, expected: DoubleArray) {
        val prepared = LocalQwen.prepare(sentence)
        val done = CountDownLatch(1)
        var scores: Result<DoubleArray>? = null
        val ranked = LocalQwen.rank(sentence, candidates, prepared) { scores = it; done.countDown() }
        check(ranked == prepared) { "Ranking cancelled its matching context preparation" }
        check(done.await(60, TimeUnit.SECONDS) && checkNotNull(scores).getOrThrow().contentEquals(expected))

        val stale = LocalQwen.prepare("An older sentence")
        val fallback = CountDownLatch(1)
        val replacement = LocalQwen.rank(sentence, candidates, stale) { scores = it; fallback.countDown() }
        check(replacement != stale) { "Ranking reused preparation from another sentence" }
        check(fallback.await(60, TimeUnit.SECONDS) && checkNotNull(scores).getOrThrow().contentEquals(expected))
    }
}
