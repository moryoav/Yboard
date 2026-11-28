package com.yboard.keyboard

import android.app.Application
import com.yboard.keyboard.ime.TypingDiagnostics
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.update.UpdateCheckJob
import com.yboard.keyboard.update.UpdateNotifications
import com.yboard.keyboard.usage.UsageStore

class YboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        TypingDiagnostics.init(this)
        UsageStore.init(this)
        com.yboard.keyboard.local.QwenModel.init(this)
        UpdateNotifications.initialize(this)
        UpdateCheckJob.schedule(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_COMPLETE || level == TRIM_MEMORY_RUNNING_CRITICAL) com.yboard.keyboard.local.LocalQwen.release()
    }
}
