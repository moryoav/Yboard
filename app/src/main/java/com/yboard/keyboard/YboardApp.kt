package com.yboard.keyboard

import android.app.Application
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.usage.UsageStore

class YboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        UsageStore.init(this)
    }
}
