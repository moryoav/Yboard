package com.yboard.keyboard.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A persisted, network-constrained check for release metadata. */
class UpdateCheckJob : JobService() {
    private var check: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!UpdateNotifications.canNotify(this)) return false
        check = CoroutineScope(Dispatchers.Main).launch {
            try {
                val network = if (Build.VERSION.SDK_INT >= 28) params.network else null
                withContext(Dispatchers.IO) {
                    val app = applicationContext
                    val release = AppUpdater(app, network).check()
                    ensureActive()
                    if (release != null) UpdateNotifications.showIfNewer(app, release)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("YboardUpdates", "Background update check failed: ${e.javaClass.simpleName}")
            } finally {
                if (isActive) {
                    check = null
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        check?.cancel()
        check = null
        return false
    }

    override fun onDestroy() {
        check?.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val JOB_ID = 481

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.DAYS.toMillis(3), TimeUnit.DAYS.toMillis(1))
                .setPersisted(true)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.w("YboardUpdates", "Android did not schedule update checks")
            }
        }
    }
}
