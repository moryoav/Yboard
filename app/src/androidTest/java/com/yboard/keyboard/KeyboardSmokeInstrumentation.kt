package com.yboard.keyboard

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.yboard.keyboard.keyboard.KeyboardRootView
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
            } else if (mode == "correction_learning") {
                result.putString("result", PredictionImeChecks(this).checkCorrectionLearning())
            } else if (mode == "typing_diagnostics") {
                result.putString("result", PredictionImeChecks(this).checkTypingDiagnostics())
            } else if (mode == "autofill_bar") {
                var failure: Throwable? = null
                runOnMainSync { try { checkAutofillBar() } catch (error: Throwable) { failure = error } }
                failure?.let { throw it }
                result.putString("result", "Inline chip dimensions, pinned chips and empty toolbar fallback passed")
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

    private fun checkAutofillBar() {
        val root = KeyboardRootView(targetContext, preview = true)
        fun chip(width: Int, height: Int) = View(targetContext).apply {
            layoutParams = ViewGroup.LayoutParams(width, height)
        }
        fun layout() {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }

        val login = chip(224, 72)
        val pinned = chip(90, 72)
        root.showAutofill(listOf(login), listOf(pinned))
        layout()
        check(root.toolbar.visibility == View.GONE && root.autofillBar.visibility == View.VISIBLE)
        check(login.width == 224 && login.height == 72) { "Scrolling chip measured ${login.width} × ${login.height}" }
        check(pinned.width == 90 && pinned.height == 72) { "Pinned chip measured ${pinned.width} × ${pinned.height}" }

        root.showAutofill(listOf(chip(0, 72), chip(224, 72).apply { visibility = View.INVISIBLE }), listOf(chip(90, 0)))
        layout()
        check(root.toolbar.visibility == View.VISIBLE && root.autofillBar.visibility == View.GONE)

        root.showAutofill(listOf(login), emptyList())
        root.showAutofill(emptyList(), emptyList())
        check(root.toolbar.visibility == View.VISIBLE && root.autofillBar.visibility == View.GONE)
    }
}
