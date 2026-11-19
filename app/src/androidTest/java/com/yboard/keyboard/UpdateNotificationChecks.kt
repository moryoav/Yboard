package com.yboard.keyboard

import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import com.yboard.keyboard.update.AppUpdater
import com.yboard.keyboard.update.UpdateCheckJob
import com.yboard.keyboard.update.UpdateNotifications
import com.yboard.keyboard.update.UpdateRelease
import java.util.concurrent.TimeUnit

/** Exercises Android's real notification service with a synthetic release. */
internal class UpdateNotificationChecks(private val instrumentation: Instrumentation) {
    private val context = object : ContextWrapper(instrumentation.targetContext) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("${name}_test", mode)
    }
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences(UpdateNotifications.PREFS_NAME, Context.MODE_PRIVATE)
    private val version = AppUpdater.installedVersionCode(context)
    private val release = UpdateRelease("v99.0.0", "99.0.0", version + 1, "Yboard-v99.0.0.apk",
        1, "a".repeat(64), "https://github.com/moryoav/Yboard/releases/download/v99.0.0/Yboard-v99.0.0.apk")

    private fun posted() =
        manager.activeNotifications.firstOrNull { it.id == UpdateNotifications.NOTIFICATION_ID }?.notification

    private fun show(info: UpdateRelease) = UpdateNotifications.showIfNewer(context, info)

    fun run(scenario: String): String {
        instrumentation.waitForIdleSync()
        val jobs = context.getSystemService(JobScheduler::class.java)
        val scheduled = checkNotNull(jobs.getPendingJob(UpdateCheckJob.JOB_ID))
        check(scheduled.isPeriodic && scheduled.isPersisted)
        check(scheduled.intervalMillis == TimeUnit.DAYS.toMillis(3))
        @Suppress("DEPRECATION")
        check(scheduled.networkType == JobInfo.NETWORK_TYPE_ANY)

        when (scenario) {
            "denied" -> {
                check(!UpdateNotifications.canNotify(context))
                show(release)
                check(posted() == null && preferences.getLong(UpdateNotifications.LAST_VERSION, 0) == 0L)
            }
            "post" -> {
                check(UpdateNotifications.canNotify(context))
                show(release.copy(versionCode = version))
                check(posted() == null)
                show(release)
                val deadline = SystemClock.elapsedRealtime() + 2500
                while (posted() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                val alert = checkNotNull(posted()) { "Android did not post the update alert" }
                check(alert.extras.getString(Notification.EXTRA_TITLE) == "Yboard 99.0.0 is available")
                check(alert.contentIntent != null && alert.channelId == UpdateNotifications.CHANNEL_ID)
                check(preferences.getLong(UpdateNotifications.LAST_VERSION, 0) == release.versionCode)
            }
            "dedupe" -> {
                check(UpdateNotifications.canNotify(context))
                check(preferences.getLong(UpdateNotifications.LAST_VERSION, 0) == release.versionCode)
                check(posted() == null) { "Tap the test notification before rerunning" }
                show(release)
                SystemClock.sleep(250)
                check(posted() == null) { "Yboard alerted twice for the same version" }
                check(preferences.edit().remove(UpdateNotifications.LAST_VERSION).commit())
            }
            else -> error("Unknown notification test scenario")
        }
        return "Persisted update job and notification scenario passed (${scenario})"
    }
}
