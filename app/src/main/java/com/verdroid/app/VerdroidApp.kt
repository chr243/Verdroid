package com.verdroid.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.verdroid.app.data.work.QuotaPollWorker
import com.verdroid.app.data.work.AgentScheduler
import com.verdroid.app.keepalive.KeepAliveController
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class VerdroidApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var scheduler: AgentScheduler
    @Inject lateinit var keepAlive: KeepAliveController
    @Inject lateinit var agent: com.verdroid.app.domain.repository.AgentController
    @Inject lateinit var sharedFolder: com.verdroid.app.data.storage.SharedFolder
    @Inject lateinit var installPrompt: com.verdroid.app.data.consent.InstallPrompt
    @Inject lateinit var schedules: com.verdroid.app.data.schedule.ScheduleManager
    @Inject lateinit var providerMigration: com.verdroid.app.data.network.ProviderModelMigration

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** v0.9.9: the ADB-over-TCP backend is gone — drop its settings, key and any queued boot work once. */
    private fun removeLegacyAdb() {
        runCatching {
            androidx.work.WorkManager.getInstance(this).cancelUniqueWork("adb-tcp-boot")
            deleteSharedPreferences("adb_shell")
            java.io.File(filesDir, "adb").deleteRecursively()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Crash log (Settings → App → Crash log): Documents/Verdroid/Internal/crash/, app-private fallback; chains to the default handler.
        com.verdroid.app.data.crash.CrashLog.install(
            dirs = { com.verdroid.app.data.crash.CrashLog.dirs(sharedFolder.internal.takeIf { runCatching { sharedFolder.hasAccess() }.getOrDefault(false) }, filesDir) },
            version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        com.verdroid.app.data.crash.CrashBreadcrumbs.runningTasks = { agent.runningTaskIds.value }
        // v1.0.56: package installs ask with a blocking dialog (falls back to ask-in-chat when nobody answers).
        com.verdroid.app.agent.tools.InstallConsent.confirmer = installPrompt
        // Phase 3: re-queue interrupted tasks and make sure paused ones keep their WorkManager resume job.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { scheduler.recover() }
        // Schedules: make sure every enabled one has its alarm (cheap; also covers a missed boot broadcast).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { schedules.rearmAll() } }
        QuotaPollWorker.schedule(this)
        removeLegacyAdb()
        // v1.0.49: old Coinbase Exchange tools removed → drop their encrypted key store (no-op when absent).
        runCatching { deleteSharedPreferences("crypto_secure") }
        // v1.0.45: custom:<model> → prov:<provider>:<model> in Model priority (once; no-op afterwards).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { providerMigration.run() } }
        // Documents/Verdroid with Input/, Output/, Internal/ (no-op until All files access is granted; Tools re-checks it),
        // then a one-time move of loose agent scripts/temp files from the root into Internal/.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { sharedFolder.ensure(); sharedFolder.migrateStrayRootFiles() } }
        // v0.9.19: the keep-alive service runs only while a task is running (no idle notification).
        keepAlive.watch(agent.runningTaskIds, CoroutineScope(SupervisorJob() + Dispatchers.Main))
    }
}
