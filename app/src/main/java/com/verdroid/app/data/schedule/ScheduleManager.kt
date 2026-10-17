package com.verdroid.app.data.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.verdroid.app.data.local.ScheduleDao
import com.verdroid.app.data.local.ScheduleEntity
import com.verdroid.app.domain.model.ChatMessage
import com.verdroid.app.domain.model.MessageKind
import com.verdroid.app.domain.model.MessageRole
import com.verdroid.app.domain.model.TaskStatus
import com.verdroid.app.domain.model.TaskType
import com.verdroid.app.domain.repository.AgentController
import com.verdroid.app.domain.repository.TaskRepository
import com.verdroid.app.domain.schedule.ScheduleKind
import com.verdroid.app.domain.schedule.ScheduleMath
import com.verdroid.app.domain.schedule.ScheduleSafety
import com.verdroid.app.domain.schedule.ScheduleSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What can make scheduled runs late or missing (checked whenever a schedule is created or enabled). */
data class ScheduleHealth(val exactAlarms: Boolean, val batteryExempt: Boolean, val notifications: Boolean) {
    val ok: Boolean get() = exactAlarms && batteryExempt && notifications
    fun warnings(): List<String> = buildList {
        if (!batteryExempt) add("Battery optimisation is on for Verdroid: Android (especially Xiaomi/HyperOS, Samsung, OnePlus) may delay or kill scheduled runs. Allow Verdroid to run in the background (Settings → Background & battery).")
        if (!exactAlarms) add("Exact alarms aren't allowed: reminders and daily times may fire several minutes late (Settings → Schedules → Allow exact alarms).")
        if (!notifications) add("Notifications are off for Verdroid: reminders and run summaries can't be shown.")
    }
}

/**
 * Reminders and scheduled agent runs (Settings → Schedules, agent tools schedule_*). Each enabled schedule has one
 * AlarmManager alarm at its next run (exact when the user allows exact alarms, else an inexact Doze-allowed alarm);
 * reminders just post a notification, tasks enqueue [ScheduledRunWorker] (network required) which runs the agent
 * unattended in the schedule's own chat ("Scheduled: <name>"). Alarms are re-armed on boot, app update, time/zone change
 * and app start; a run missed while the phone was off fires once (no burst of catch-ups).
 */
@Singleton
class ScheduleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ScheduleDao,
    private val tasks: TaskRepository,
    private val agent: AgentController,
    private val alerts: com.verdroid.app.data.notify.AlertNotifier,
    private val keepAlive: com.verdroid.app.keepalive.KeepAliveController,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    /** Serialises firing (boot receiver + app start + alarms may overlap) so a due run fires once. */
    private val fireLock = kotlinx.coroutines.sync.Mutex()
    private val alarms: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    /** Shown by [com.verdroid.app.ui.schedule.ScheduleHealthDialog] after a schedule is created/enabled with a problem. */
    private val _prompt = MutableStateFlow<ScheduleHealth?>(null)
    val prompt: StateFlow<ScheduleHealth?> = _prompt.asStateFlow()
    fun dismissPrompt() { _prompt.value = null }

    fun observe(): Flow<List<ScheduleEntity>> = dao.observeAll()
    suspend fun all(): List<ScheduleEntity> = dao.all()
    suspend fun get(id: Long): ScheduleEntity? = dao.get(id)

    /** Android 11 has no exact-alarm permission (always allowed); 12+ asks the user (SCHEDULE_EXACT_ALARM). */
    fun canExact(): Boolean = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) true
        else runCatching { alarms.canScheduleExactAlarms() }.getOrDefault(false)

    fun health(): ScheduleHealth = ScheduleHealth(
        exactAlarms = canExact(),
        batteryExempt = runCatching { keepAlive.isIgnoringBatteryOptimizations() }.getOrDefault(false),
        notifications = runCatching { androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false),
    )

    fun exactAlarmIntent(): Intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
    fun batteryIntent(): Intent = keepAlive.batteryOptimizationIntent()

    /** Checks [health] and raises the in-app prompt when something may delay runs; returns the warnings (also for chat). */
    fun checkHealth(): List<String> {
        val h = health()
        if (!h.ok) _prompt.value = h
        return h.warnings()
    }

    class InvalidSchedule(message: String) : Exception(message)

    /** Validates, stores and arms a new schedule. Throws [InvalidSchedule] with a user-readable reason. */
    suspend fun create(kind: ScheduleKind, name: String, message: String, spec: ScheduleSpec, onlyIfChanged: Boolean = false,
                       sourceTaskId: Long? = null, chatTaskId: Long? = null): ScheduleEntity {
        val now = System.currentTimeMillis()
        val text = message.trim()
        if (text.isEmpty()) throw InvalidSchedule("message/prompt is empty")
        when (spec) {
            is ScheduleSpec.Once -> if (spec.atMillis <= now) throw InvalidSchedule("that time is in the past (${ScheduleMath.formatLocal(spec.atMillis, zone)})")
            is ScheduleSpec.Interval -> if (spec.minutes < ScheduleSpec.MIN_INTERVAL_MINUTES || spec.minutes > ScheduleSpec.MAX_INTERVAL_MINUTES)
                throw InvalidSchedule("intervals must be ${ScheduleSpec.MIN_INTERVAL_MINUTES} min to 7 days")
            is ScheduleSpec.Daily -> Unit
        }
        if (dao.enabled().size >= MAX_ENABLED) throw InvalidSchedule("too many active schedules ($MAX_ENABLED); cancel some first")
        val (type, value) = ScheduleSpec.encode(spec)
        val e = ScheduleEntity(kind = kind.name, name = name.trim().ifEmpty { text.take(40) }.take(60), message = text.take(4000),
            specType = type, specValue = value, onlyIfChanged = onlyIfChanged && kind == ScheduleKind.TASK,
            chatTaskId = chatTaskId, sourceTaskId = sourceTaskId, nextRunAt = ScheduleMath.nextRun(spec, now, zone), createdAt = now)
        val saved = e.copy(id = dao.insert(e))
        arm(saved)
        return saved
    }

    suspend fun setEnabled(id: Long, on: Boolean): ScheduleEntity? {
        val e = dao.get(id) ?: return null
        val spec = specOf(e) ?: return null
        val next = if (on) ScheduleMath.nextRun(spec, System.currentTimeMillis(), zone, last = e.lastRunAt.takeIf { spec is ScheduleSpec.Interval }) else null
        if (on && next == null) return e // a past one-off can't be re-enabled
        val u = e.copy(enabled = on, nextRunAt = next)
        dao.update(u); arm(u)
        if (on) checkHealth()
        return u
    }

    suspend fun delete(id: Long): Boolean {
        cancelAlarm(id)
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        return dao.delete(id) > 0
    }

    /** Settings → Schedules → Run now (doesn't move the regular next run). */
    suspend fun runNow(id: Long) { dao.get(id)?.let { fire(it, scheduled = false) } }

    /** Boot / update / time change / app start: re-arm every enabled schedule; a run missed while off fires once now. */
    suspend fun rearmAll() = fireLock.withLock {
        val now = System.currentTimeMillis()
        dao.enabled().forEach { e ->
            val spec = specOf(e) ?: return@forEach
            val due = e.nextRunAt
            when {
                due != null && due <= now - LATE_GRACE_MS -> fire(e, scheduled = true, late = true)
                due == null -> { val u = e.copy(nextRunAt = ScheduleMath.nextRun(spec, now, zone, e.lastRunAt)); dao.update(u); arm(u) }
                else -> arm(e)
            }
        }
    }

    /** The alarm for [id] went off. */
    suspend fun onAlarm(id: Long): Unit = fireLock.withLock {
        val e = dao.get(id) ?: return
        if (!e.enabled) return
        val due = e.nextRunAt
        if (due != null && due > System.currentTimeMillis() + EARLY_TOLERANCE_MS) { arm(e); return } // stale alarm: re-arm
        fire(e, scheduled = true)
    }

    private suspend fun fire(e: ScheduleEntity, scheduled: Boolean, late: Boolean = false) {
        val now = System.currentTimeMillis()
        val spec = specOf(e)
        if (scheduled) {
            val next = spec?.let { ScheduleMath.nextRun(it, now, zone, last = e.nextRunAt) }
            val u = e.copy(nextRunAt = next, enabled = next != null && e.enabled)
            dao.update(u); arm(u)
        }
        when (e.kind) {
            ScheduleKind.REMINDER.name -> {
                alerts.notifyReminder(REMINDER_NOTIFY_BASE + (e.id % 10_000).toInt(), "⏰ " + e.name.take(60),
                    (if (late) "(late: the phone was off or asleep) " else "") + e.message, e.sourceTaskId)
                record(e.id, now, if (late) "ok (late)" else "ok")
            }
            else -> {
                val req = OneTimeWorkRequestBuilder<ScheduledRunWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf(ScheduledRunWorker.KEY_SCHEDULE_ID to e.id))
                    .addTag(WORK_TAG).build()
                // KEEP: a run still queued (no network yet) or running is not duplicated.
                WorkManager.getInstance(context).enqueueUniqueWork(workName(e.id), ExistingWorkPolicy.KEEP, req)
            }
        }
    }

    private suspend fun record(id: Long, at: Long, status: String) {
        dao.get(id)?.let { dao.update(it.copy(lastRunAt = at, lastStatus = status.take(200))) }
    }

    /** Body of [ScheduledRunWorker]: one unattended agent run; never retried (skip + log). */
    suspend fun runTask(id: Long) {
        val e = dao.get(id) ?: return
        val now = System.currentTimeMillis()
        val chat: Long = e.chatTaskId?.takeIf { tasks.getTask(it) != null }
            ?: tasks.createTask("Scheduled: ${e.name}".take(80), e.message, TaskType.infer(e.message)).also { c ->
                dao.get(id)?.let { dao.update(it.copy(chatTaskId = c)) }
            }
        if (chat in agent.runningTaskIds.value) { record(id, now, "skipped: the previous run is still going"); return }
        val t = tasks.getTask(chat)
        if (t != null && (t.status == TaskStatus.RATE_LIMITED || t.status == TaskStatus.PAUSED || t.status == TaskStatus.QUEUED)) {
            record(id, now, "skipped: the previous run is paused (${t.subtitle.take(60)})"); return
        }
        tasks.addMessage(ChatMessage(0, chat, MessageRole.USER, ScheduledPrompt.build(e.name, e.message, e.onlyIfChanged,
            ScheduleMath.formatLocal(now, zone)), MessageKind.SCHEDULED_PROMPT, null, null, null, null, false, null, now))
        agent.start(chat)
        val finished = withTimeoutOrNull(MAX_WAIT_MS) { agent.runningTaskIds.first { chat !in it } } != null
        val after = tasks.getTask(chat)
        val reply = tasks.getMessages(chat).lastOrNull { it.role == MessageRole.ASSISTANT && it.createdAt >= now && !it.content.isNullOrBlank() }?.content
        val status: String
        when {
            !finished -> status = "still running after ${MAX_WAIT_MS / 60_000} min"
            after?.status == TaskStatus.COMPLETED && reply != null -> {
                val unchanged = e.onlyIfChanged && ScheduledPrompt.isNoChange(reply)
                status = if (unchanged) "no change" else "ok"
                if (!unchanged) alerts.notify(RUN_NOTIFY_BASE + (chat % 10_000).toInt(), "⏰ ${e.name}".take(80), ScheduledPrompt.summary(reply), chat)
            }
            after?.status == TaskStatus.RATE_LIMITED || after?.status == TaskStatus.PAUSED -> status = "paused: ${after.subtitle.take(80)} (finishes on its own)"
            else -> {
                status = "failed: ${after?.subtitle?.take(80) ?: "no reply"}"
                // One notification per failure streak, not one per interval.
                if (e.lastStatus?.startsWith("failed") != true) alerts.notify(RUN_NOTIFY_BASE + (chat % 10_000).toInt(), "⏰ ${e.name}: run failed", status, chat)
            }
        }
        record(id, now, status)
    }

    private fun pending(id: Long, flags: Int): PendingIntent? = PendingIntent.getBroadcast(context, (ALARM_REQUEST_BASE + id % 100_000).toInt(),
        Intent(context, ScheduleAlarmReceiver::class.java).setAction(ScheduleAlarmReceiver.ACTION).putExtra(ScheduleAlarmReceiver.EXTRA_ID, id),
        flags or PendingIntent.FLAG_IMMUTABLE)

    private fun cancelAlarm(id: Long) { pending(id, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() } }

    private fun arm(e: ScheduleEntity) {
        val at = e.nextRunAt
        if (!e.enabled || at == null) { cancelAlarm(e.id); return }
        val pi = pending(e.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (canExact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Backend of the schedule_* agent tools: creating checks battery/exact-alarm health (dialog + warnings for the chat). */
    val toolBackend: com.verdroid.app.agent.tools.ScheduleBackend = object : com.verdroid.app.agent.tools.ScheduleBackend {
        override suspend fun create(kind: ScheduleKind, name: String, message: String, spec: ScheduleSpec, onlyIfChanged: Boolean,
                                    sourceTaskId: Long?, chatTaskId: Long?): Pair<ScheduleEntity, List<String>> {
            val e = this@ScheduleManager.create(kind, name, message, spec, onlyIfChanged, sourceTaskId, chatTaskId)
            return e to checkHealth()
        }
        override suspend fun list() = dao.all()
        override suspend fun delete(id: Long) = this@ScheduleManager.delete(id)
        override suspend fun setEnabled(id: Long, on: Boolean) = this@ScheduleManager.setEnabled(id, on)
        override suspend fun postInChat(taskId: Long, text: String) {
            tasks.addMessage(ChatMessage(0, taskId, MessageRole.SYSTEM, text, MessageKind.STATUS, null, null, null, null, false, null, System.currentTimeMillis()))
        }
    }

    /** Self-test: is an alarm armed for [id]? */
    fun isArmed(id: Long): Boolean = pending(id, PendingIntent.FLAG_NO_CREATE) != null

    companion object {
        const val MAX_ENABLED = 50
        const val WORK_TAG = "scheduled-run"
        fun workName(id: Long) = "scheduled-run-$id"
        fun specOf(e: ScheduleEntity): ScheduleSpec? = ScheduleSpec.decode(e.specType, e.specValue)
        private const val MAX_WAIT_MS = 9 * 60_000L
        private const val LATE_GRACE_MS = 2 * 60_000L
        private const val EARLY_TOLERANCE_MS = 60_000L
        private const val ALARM_REQUEST_BASE = 610_000
        private const val REMINDER_NOTIFY_BASE = 61_000
        private const val RUN_NOTIFY_BASE = 52_000 // = AgentLoop's finish notification for the same chat: replaced, not doubled
    }
}

/** Texts of a scheduled run (pure, unit-tested). */
object ScheduledPrompt {
    fun build(name: String, prompt: String, onlyIfChanged: Boolean, whenLocal: String): String = buildString {
        append(ScheduleSafety.MARKER).append(" \"").append(name).append("\" (").append(whenLocal).append(")\n\n")
        append(prompt.trim()).append("\n\n")
        append("[This is an unattended scheduled run: nobody is watching. Do the task with read-only tools (search, fetch, read files). ")
        append("Anything that needs the user's confirmation or acts as them (sending, deleting, installing, phone control, new schedules) is blocked: ")
        append("don't attempt workarounds; mention in your answer what you'd like to do. Crypto is backtesting only. ")
        append("X tools: assume the saved X login works and call x_timeline / x_user_posts / x_search directly; if one says NOT_LOGGED_IN, don't try to log in — say so in the summary. ")
        append("x_post works here only if the user turned on Always allow for X posts (otherwise it returns BLOCKED_IN_SCHEDULED_RUN), and the posting cap still applies; post only what this task explicitly asks for. ")
        append("End with a short summary (a few sentences) — it is shown as the notification.")
        if (onlyIfChanged) append(" Compare with your previous answers in this chat: if there is nothing new or changed since the last run, reply with exactly ${ScheduleSafety.NO_CHANGE} and nothing else (no notification is shown then).")
        append("]")
    }

    fun isNoChange(reply: String): Boolean = reply.trim().trim('*', '`', '.', ' ').equals(ScheduleSafety.NO_CHANGE, ignoreCase = true)

    /** Notification body: the reply without Markdown noise, cut to ~300 chars. */
    fun summary(reply: String): String {
        val plain = reply.replace(Regex("""```[\s\S]*?```"""), " ").replace(Regex("""[*_#>`|]+"""), "")
            .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1").replace(Regex("""\s+"""), " ").trim()
        return if (plain.length <= 300) plain else plain.take(297).trimEnd() + "…"
    }
}
