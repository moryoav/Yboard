package com.yboard.keyboard

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.voice.ApiKeyStore
import com.yboard.keyboard.voice.OpenAi

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
            if (mode == "ai" || mode == "models") {
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
                    val words = OpenAi.predictNext(key, "I would like to ", "en", emptyList(), emptyList(), "to")
                    check(words.isNotEmpty()) { "AI returned no predictions" }
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
