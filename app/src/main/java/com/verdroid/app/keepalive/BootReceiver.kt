package com.verdroid.app.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.verdroid.app.data.work.AgentScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** BOOT_COMPLETED / app update / time change: re-arm schedules; re-queue interrupted (mid-run) tasks and keep paused tasks' scheduled resume. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: AgentScheduler
    @Inject lateinit var keepAlive: KeepAliveController
    @Inject lateinit var schedules: com.verdroid.app.data.schedule.ScheduleManager

    override fun onReceive(context: Context, intent: Intent) {
        val restart = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, ACTION_QUICKBOOT -> true
            // Clock / zone / exact-alarm permission changed: only the schedules' alarms need re-arming.
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, ACTION_EXACT_ALARM_STATE -> false
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Only tasks that were mid-run are restarted now (the keep-alive service follows them); paused ones keep
                // their scheduled resume. Nothing runs (no service, no notification) when no task was mid-run.
                if (restart) runCatching { scheduler.recover() }
                // Reminders / scheduled runs: re-arm every enabled schedule (alarms don't survive a reboot).
                runCatching { schedules.rearmAll() }
            } finally { pending.finish() }
        }
    }

    private companion object {
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_EXACT_ALARM_STATE = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
