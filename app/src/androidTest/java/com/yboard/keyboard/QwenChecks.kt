package com.yboard.keyboard

import android.app.Instrumentation
import android.content.Context
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
            "אני בדרך" to "", "תודה רבה" to ""
        )
        for ((sentence, prefix) in fixtures) {
            val output = LocalQwen.predictBlocking(sentence, prefix)
            check(output.words.isNotEmpty()) { "Qwen returned no options for a synthetic fixture" }
            check(output.words.all { it.startsWith(prefix, true) })
            if (model == QwenModel.Variant.QWEN3 && sentence == "automation ") {
                check(output.words.any { it == "is" || it == "and" }) { "A short message fragment produced filler instead of a continuation" }
            }
            report.put(JSONObject().put("sentence", sentence).put("prefix", prefix).put("words", JSONArray(output.words)).put("ms", output.elapsedMs))
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

    private fun rank(sentence: String, candidates: List<String>): DoubleArray {
        val done = CountDownLatch(1)
        var scores: Result<DoubleArray>? = null
        LocalQwen.rank(sentence, candidates) { scores = it; done.countDown() }
        check(done.await(60, TimeUnit.SECONDS)) { "Ranking timed out" }
        return checkNotNull(scores).getOrThrow()
    }
}
