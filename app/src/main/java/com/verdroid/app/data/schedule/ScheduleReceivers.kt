package com.verdroid.app.data.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A schedule's alarm: reminder notification or enqueue its agent run (see [ScheduleManager]). */
@AndroidEntryPoint
class ScheduleAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var schedules: ScheduleManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getLongExtra(EXTRA_ID, 0L).takeIf { it != 0L } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { runCatching { schedules.onAlarm(id) } } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION = "com.verdroid.app.SCHEDULE_ALARM"
        const val EXTRA_ID = "scheduleId"
    }
}

/** One unattended scheduled agent run (network required; never retried: failures are logged on the schedule). */
@HiltWorker
class ScheduledRunWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val schedules: ScheduleManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_SCHEDULE_ID, 0L).takeIf { it != 0L } ?: return Result.failure()
        runCatching { schedules.runTask(id) }
        return Result.success()
    }

    companion object { const val KEY_SCHEDULE_ID = "scheduleId" }
}
