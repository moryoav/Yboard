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
            if (mode == "usage_storage") {
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
            } else if (mode == "ai" || mode == "models") {
                Prefs.init(targetContext)
                val key = ApiKeyStore.get(targetContext) ?: error("No saved API key")
                if (mode == "models") {
                    val models = OpenAi.models(key).filter { id ->
                        id.contains("nano") || id.contains("mini") || id.contains("luna") || id.contains("transcrib")
                    }
                    result.putString("result", "Available models: ${models.joinToString()}")
                } else {
                    if (args.getString("enable") == "true") Prefs.update { it.copy(aiPredictions = true) }
                    val start = SystemClock.elapsedRealtime()
                    val before = UsageStore.overview(UsagePeriod.MONTH).totals
                    val words = OpenAi.predictNext(key, "I would like to ", "en", emptyList(), emptyList(), "to")
                    check(words.isNotEmpty()) { "AI returned no predictions" }
                    if (args.getString("verify_usage") == "true") {
                        repeat(30) {
                            if (UsageStore.overview(UsagePeriod.MONTH).totals.requests <= before.requests) SystemClock.sleep(100)
                        }
                        val after = UsageStore.overview(UsagePeriod.MONTH).totals
                        check(after.input > before.input && after.output > before.output && after.totalCost > before.totalCost) { "Usage or price was not saved" }
                        result.putString("usage", "Real API token usage and estimated cost saved successfully")
                    }
                    result.putString("result", "AI predictions succeeded in ${SystemClock.elapsedRealtime() - start} ms: ${words.joinToString()}")
                }
            } else result.putString("result", "No cloud request. Pass -e mode ai or -e mode models to run a diagnostic.")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("error", "${e.javaClass.simpleName}: ${e.message}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
