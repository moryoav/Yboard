package com.yboard.keyboard

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.voice.ApiKeyStore
import com.yboard.keyboard.voice.OpenAi
import com.yboard.keyboard.usage.UsageStore
import com.yboard.keyboard.usage.UsagePeriod
import com.yboard.keyboard.usage.UsageDatabase
import com.yboard.keyboard.usage.TokenUsage
import com.yboard.keyboard.usage.UsagePricing

/** Opt-in diagnostics with synthetic text. Never exports the phone's API key. */
class KeyboardSmokeInstrumentation : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            val mode = args.getString("mode")
            if (mode == "qwen_core") {
                result.putString("result", QwenChecks.core(targetContext, args.getString("model")))
            } else if (mode == "qwen_download") {
                result.putString("result", QwenChecks.download(targetContext, args.getString("variant")))
            } else if (mode == "qwen_switch") {
                result.putString("result", QwenChecks.switch(this, args.getString("model")))
            } else if (mode == "prediction_benchmark") {
                result.putString("result", QwenChecks.benchmark(this))
            } else if (mode == "glide_rank_benchmark") {
                result.putString("result", QwenChecks.glideBenchmark(this))
            } else if (mode == "glide_ime_benchmark") {
                result.putString("result", PredictionImeChecks(this).glideBenchmark())
            } else if (mode == "prediction_ime") {
                result.putString("result", PredictionImeChecks(this).run())
            } else if (mode == "update_notifications") {
                result.putString("result", UpdateNotificationChecks(this).run(args.getString("scenario").orEmpty()))
            } else if (mode == "sticker_local") {
                var text = ""
                var failure: Throwable? = null
                runOnMainSync { try { text = StickerLocalChecks.run(targetContext, context.assets) } catch (error: Throwable) { failure = error } }
                failure?.let { throw it }
                result.putString("result", text)
            } else if (mode == "image_models") {
                val key = ApiKeyStore.get(targetContext) ?: error("No saved API key")
                result.putString("result", OpenAi.models(key).filter { it.startsWith("gpt-image") }.joinToString())
            } else if (mode == "voice_regression") {
                var passed = 0
                var failure: Throwable? = null
                runOnMainSync { try { passed = VoiceRegression.run(targetContext) + VoiceRegression.transport() } catch (e: Throwable) { failure = e } }
                failure?.let { throw it }
                result.putString("result", "$passed voice controller regression scenarios passed; no cloud calls")
            } else if (mode == "usage_storage") {
                val fixture = java.io.File(targetContext.cacheDir, "usage-accounting-smoke").apply { mkdirs() }
                val wrapped = object : android.content.ContextWrapper(targetContext) {
                    override fun getNoBackupFilesDir() = fixture
                }
                val path = java.io.File(fixture, "yboard_ai_usage.db")
                android.database.sqlite.SQLiteDatabase.deleteDatabase(path)
                val zone = java.time.ZoneId.of("Asia/Jerusalem")
                val now = java.time.LocalDate.now(zone).atTime(12, 0).atZone(zone).toInstant()
                val u = TokenUsage(input = 1000, output = 100, cached = 800)
                var db = UsageDatabase(wrapped)
                try {
                    for (age in listOf(1000L, 3L * 86400000, 20L * 86400000, 40L * 86400000)) {
                        db.add(now.toEpochMilli() - age, "PREDICTION", "gpt-6-luna", u, UsagePricing.estimate("gpt-6-luna", u))
                    }
                    val unknown = TokenUsage(input = 10, output = 2)
                    db.add(now.toEpochMilli() - 500, "COMMAND", "unpriced-test-model", unknown, UsagePricing.estimate("unpriced-test-model", unknown))
                    check(db.overview(UsagePeriod.TODAY.start(now, zone), now.toEpochMilli()).totals.requests == 2L)
                    check(db.overview(UsagePeriod.WEEK.start(now, zone), now.toEpochMilli()).totals.requests == 3L)
                    val month = db.overview(UsagePeriod.MONTH.start(now, zone), now.toEpochMilli())
                    check(month.totals.requests == 4L && month.totals.input == 3010L && month.totals.cached == 2400L)
                    check(month.totals.totalCost == 234000L && month.totals.incomplete == 1L && month.models.size == 2)
                    db.close()
                    db = UsageDatabase(wrapped)
                    check(db.overview(UsagePeriod.MONTH.start(now, zone), now.toEpochMilli()).totals.requests == 4L)
                    result.putString("result", "Usage persistence, period aggregation, cached pricing and unknown-rate handling passed")
                } finally {
                    db.close()
                    android.database.sqlite.SQLiteDatabase.deleteDatabase(path)
                    fixture.delete()
                }
            } else if (mode == "models") {
                val key = ApiKeyStore.get(targetContext) ?: error("No saved API key")
                result.putString("result", OpenAi.models(key).joinToString())
            } else result.putString("result", "No cloud request. Pass -e mode qwen_core for a local prediction diagnostic.")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("error", "${e.javaClass.simpleName}: ${e.message}; ${e.stackTrace.firstOrNull { it.className.startsWith("com.yboard") }}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
