package com.yboard.keyboard

import android.content.Context
import com.yboard.keyboard.local.LocalQwen
import com.yboard.keyboard.local.QwenModel
import com.yboard.keyboard.local.QwenNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real local inference using synthetic sentences only. No API key or cloud request. */
internal object QwenChecks {
    fun download(context: Context): String {
        QwenModel.init(context)
        if (QwenModel.state.value.ready) return "Verified model already installed"
        fun await(label: String, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
            while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) {
                QwenModel.state.value.error?.takeUnless { it == "Download paused" }?.let { error(it) }
                android.os.SystemClock.sleep(100)
            }
            check(condition()) { "Timed out: $label" }
        }
        QwenModel.download(context)
        await("download bytes") { QwenModel.state.value.bytes > 2_000_000 || QwenModel.state.value.ready }
        if (!QwenModel.state.value.ready) {
            QwenModel.pause()
            await("pause") { !QwenModel.state.value.downloading }
            check(QwenModel.state.value.bytes > 0)
            QwenModel.download(context)
            await("verified resume") { QwenModel.state.value.ready }
        }
        check(QwenModel.file().length() == QwenModel.SIZE)
        return "Public model download, pause, resume, size and SHA-256 verification passed"
    }
    fun core(context: Context, importPath: String?): String {
        QwenModel.init(context)
        if (importPath != null) QwenModel.importVerified(File(importPath))
        check(QwenModel.state.value.ready) { "Download or import the verified Qwen model first" }
        val report = JSONArray()
        val fixtures = listOf(
            "automation " to "", "" to "hel", "See you" to "tom", "I will let you" to "", "I will be there" to "s",
            "אני בדרך" to "", "תודה רבה" to ""
        )
        for ((sentence, prefix) in fixtures) {
            val output = LocalQwen.predictBlocking(sentence, prefix)
            check(output.words.isNotEmpty()) { "Qwen returned no options for a synthetic fixture" }
            check(output.words.all { it.startsWith(prefix, true) })
            if (sentence == "automation ") check(output.words.any { it == "is" || it == "and" }) { "A short message fragment produced filler instead of a continuation" }
            report.put(JSONObject().put("sentence", sentence).put("prefix", prefix).put("words", JSONArray(output.words)).put("ms", output.elapsedMs))
        }
        val done = CountDownLatch(1)
        var scores: Result<DoubleArray>? = null
        LocalQwen.rank("I will be", listOf("there", "three", "the")) { scores = it; done.countDown() }
        check(done.await(60, TimeUnit.SECONDS))
        val values = checkNotNull(scores).getOrThrow()
        check(values[0] > values[1]) { "Sentence context did not prefer there over three" }
        val geometry = listOf(com.yboard.keyboard.text.SwipeDecoder.Candidate("there", 0f),
            com.yboard.keyboard.text.SwipeDecoder.Candidate("three", .47287515f), com.yboard.keyboard.text.SwipeDecoder.Candidate("the", .47822356f))
        check(com.yboard.keyboard.text.GlideRanking.rank(geometry, values).first() == "there") { "Language probability overwhelmed the gesture's clear detour" }
        report.put(JSONObject().put("rank", "I will be [there, three, the]").put("scores", JSONArray(values.toList())))
        return report.toString()
    }
}
