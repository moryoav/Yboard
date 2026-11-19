package com.yboard.keyboard.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.yboard.keyboard.R
import com.yboard.keyboard.ui.MainActivity

object UpdateNotifications {
    internal const val CHANNEL_ID = "app_updates"
    internal const val NOTIFICATION_ID = 1
    internal const val PREFS_NAME = "yboard_update_notifications"
    internal const val LAST_VERSION = "last_notified_version"

    fun initialize(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_LOW))
        val last = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(LAST_VERSION, 0)
        if (last != 0L && last <= AppUpdater.installedVersionCode(context)) manager.cancel(NOTIFICATION_ID)
    }

    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun canNotify(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
        return !needsPermission(context) && manager.areNotificationsEnabled() &&
            channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun settingsIntent(context: Context): Intent {
        val manager = context.getSystemService(NotificationManager::class.java)
        val action = if (manager.areNotificationsEnabled()) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
        else Settings.ACTION_APP_NOTIFICATION_SETTINGS
        return Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
    }

    /** Called on the main thread after the updater validates the public release. */
    @SuppressLint("MissingPermission")
    fun showIfNewer(context: Context, release: UpdateRelease) {
        if (!release.isNewerThan(AppUpdater.installedVersionCode(context)) || !canNotify(context)) return
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!release.isNewerThan(preferences.getLong(LAST_VERSION, 0))) return

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_UPDATES)
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keyboard)
            .setContentTitle("Yboard ${release.versionName} is available")
            .setContentText("Tap to review the update.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        preferences.edit().putLong(LAST_VERSION, release.versionCode).apply()
    }
}
