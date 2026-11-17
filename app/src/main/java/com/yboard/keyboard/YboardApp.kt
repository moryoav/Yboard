package com.yboard.keyboard

import android.app.Application
import com.yboard.keyboard.settings.Prefs

class YboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}
