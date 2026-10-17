package com.verdroid.app.agent.tools

import com.verdroid.app.data.local.ScheduleEntity
import com.verdroid.app.domain.schedule.ScheduleKind
import com.verdroid.app.domain.schedule.ScheduleMath
import com.verdroid.app.domain.schedule.ScheduleSpec
import kotlinx.serialization.json.*
import java.time.ZoneId

/** What the schedule_* tools need (ScheduleManager in the app; a fake in tests). */
interface ScheduleBackend {
    fun now(): Long = System.currentTimeMillis()
    fun zone(): ZoneId = ZoneId.systemDefault()
    /** Throws with a readable message when invalid. Returns the saved schedule and health warnings (battery, exact alarms…). */
    suspend fun create(kind: ScheduleKind, name: String, message: String, spec: ScheduleSpec, onlyIfChanged: Boolean,
                       sourceTaskId: Long?, chatTaskId: Long?): Pair<ScheduleEntity, List<String>>
    suspend fun list(): List<ScheduleEntity>
    suspend fun delete(id: Long): Boolean
    suspend fun setEnabled(id: Long, on: Boolean): ScheduleEntity?
    /** Visible card in the chat that created/changed a schedule. */
    suspend fun postInChat(taskId: Long, text: String) {}
}

object ScheduleToolsText {
    fun info(e: ScheduleEntity, zone: ZoneId): JsonObject = buildJsonObject {
        put("id", e.id); put("kind", e.kind.lowercase()); put("name", e.name)
        put(if (e.kind == ScheduleKind.REMINDER.name) "message" else "prompt", e.message.take(300))
        val spec = ScheduleSpec.decode(e.specType, e.specValue)
        put("schedule", spec?.let { ScheduleMath.describe(it, zone) } ?: "invalid")
        put("enabled", e.enabled)
        if (e.onlyIfChanged) put("only_if_changed", true)
        e.nextRunAt?.let { put("next_run_local", ScheduleMath.formatLocal(it, zone)) }
        e.lastRunAt?.let { put("last_run_local", ScheduleMath.formatLocal(it, zone)) }
        e.lastStatus?.let { put("last_status", it) }
    }

    fun card(e: ScheduleEntity, zone: ZoneId, now: Long): String {
        val spec = ScheduleSpec.decode(e.specType, e.specValue)
        val next = e.nextRunAt?.let { n ->
            val mins = (n - now + 59_999) / 60_000
            ScheduleMath.formatLocal(n, zone) + if (mins in 1..180) " (in $mins min)" else ""
        } ?: "—"
        val what = if (e.kind == ScheduleKind.REMINDER.name) "⏰ Reminder set: “${e.message.take(120)}”"
            else "🗓️ Scheduled task “${e.name}”: ${spec?.let { ScheduleMath.describe(it, zone) } ?: ""}" + if (e.onlyIfChanged) " · only notifies when something changed" else ""
        return "$what\nNext: $next · #${e.id} · manage in Settings → Schedules"
    }
}

private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.contentOrNull?.trim()?.toLongOrNull() }
private fun JsonObject.flag(k: String): Boolean = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull?.lowercase() == "true") } ?: false

private suspend fun ScheduleBackend.created(kind: ScheduleKind, name: String, message: String, spec: ScheduleSpec, onlyIfChanged: Boolean,
                                            ctx: ToolContext, chatTaskId: Long?): String {
    val (e, warnings) = try { create(kind, name, message, spec, onlyIfChanged, ctx.taskId.takeIf { it != 0L }, chatTaskId) }
        catch (ex: Exception) { return errorJson("Couldn't schedule: ${ex.message}") }
    if (ctx.taskId != 0L) {
        postInChat(ctx.taskId, ScheduleToolsText.card(e, zone(), now()) +
            if (warnings.isEmpty()) "" else "\n\n⚠️ " + warnings.joinToString("\n⚠️ "))
    }
    return buildJsonObject {
        put("ok", true)
        put("schedule", ScheduleToolsText.info(e, zone()))
        if (warnings.isNotEmpty()) put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
        put("note", "A confirmation card is shown in the chat. Tell the user briefly what was scheduled and when (local time)" +
            if (warnings.isNotEmpty()) ", and mention the warnings (Verdroid also shows a dialog to fix them)." else ".")
    }.toString()
}

/** One-off reminder notification (no model call when it fires). */
class ScheduleReminderTool(private val backend: ScheduleBackend) : AgentTool {
    override val name = "schedule_reminder"
    override val description = "Set a one-off reminder: at the time, the phone shows a notification with the message (no AI run). " +
        "Give in_minutes (e.g. 10) OR at (local \"HH:MM\", \"10am\", or ISO date-time like 2026-10-17T18:30). Use the current local time from the system prompt."
    override val parameters = schema(listOf("message"),
        "message" to prop("string", "Reminder text shown in the notification, e.g. \"Check the soup\""),
        "in_minutes" to prop("number", "Minutes from now (1–10080)"),
        "at" to prop("string", "Local time: \"HH:MM\" / \"7pm\" (next occurrence) or ISO \"YYYY-MM-DDTHH:MM\""),
    )
    override suspend fun execute(args: JsonObject): String = execute(args, ToolContext(0))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val msg = args.str("message")?.trim().orEmpty()
        if (msg.isEmpty()) return errorJson("message is required")
        val at = ScheduleToolsParse.at(args, backend.now(), backend.zone()) ?: return errorJson("Give in_minutes (1–10080) or a valid at time")
        return backend.created(ScheduleKind.REMINDER, msg.take(60), msg, ScheduleSpec.Once(at), false, ctx, null)
    }
}

/** Unattended agent run: once, daily at HH:MM, or every N minutes (>= 15). */
class ScheduleTaskTool(private val backend: ScheduleBackend) : AgentTool {
    override val name = "schedule_task"
    override val description = "Schedule an unattended agent run of a prompt: once (at / in_minutes), every day at daily_at (local HH:MM), or every " +
        "every_minutes (minimum 15). Each run happens in its own chat \"Scheduled: <name>\" (or this chat with chat=\"this\") and posts a " +
        "notification with the summary. Set only_if_changed=true for \"only tell me if something is new\". Runs have no user present: " +
        "tools that need confirmation (posting, deleting, installing, phone control) are blocked in them. Only create a schedule the user asked for."
    override val parameters = schema(listOf("prompt"),
        "prompt" to prop("string", "What the run should do, self-contained, e.g. \"Check cnn.com and summarise the top stories\""),
        "name" to prop("string", "Short name, e.g. \"CNN summary\""),
        "daily_at" to prop("string", "Every day at this local time: \"10:00\" / \"10am\""),
        "every_minutes" to prop("number", "Repeat every N minutes (15–10080)"),
        "at" to prop("string", "Once at this local time (\"HH:MM\" or ISO \"YYYY-MM-DDTHH:MM\")"),
        "in_minutes" to prop("number", "Once, N minutes from now"),
        "only_if_changed" to prop("boolean", "Only notify when something changed since the last run (the run decides)"),
        "chat" to prop("string", "\"new\" (default: a dedicated Scheduled chat) or \"this\" (post runs into the current chat)"),
    )
    override suspend fun execute(args: JsonObject): String = execute(args, ToolContext(0))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val prompt = args.str("prompt")?.trim().orEmpty()
        if (prompt.isEmpty()) return errorJson("prompt is required")
        val spec = ScheduleToolsParse.spec(args, backend.now(), backend.zone()) ?: return errorJson(
            "Give exactly one of daily_at (HH:MM), every_minutes (>= ${ScheduleSpec.MIN_INTERVAL_MINUTES}), at or in_minutes")
        val name = args.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: prompt.take(40)
        val chat = if (args.str("chat")?.trim()?.lowercase() == "this" && ctx.taskId != 0L) ctx.taskId else null
        return backend.created(ScheduleKind.TASK, name, prompt, spec, args.flag("only_if_changed"), ctx, chat)
    }
}

class ScheduleListTool(private val backend: ScheduleBackend) : AgentTool {
    override val name = "schedule_list"
    override val description = "List reminders and scheduled tasks with id, schedule, next run (local time), last run and status."
    override val parameters = schema(emptyList())
    override suspend fun execute(args: JsonObject): String {
        val all = backend.list()
        return buildJsonObject {
            put("count", all.size)
            put("schedules", JsonArray(all.map { ScheduleToolsText.info(it, backend.zone()) }))
        }.toString()
    }
}

class ScheduleCancelTool(private val backend: ScheduleBackend) : AgentTool {
    override val name = "schedule_cancel"
    override val description = "Delete a reminder or scheduled task by id (see schedule_list). Its chat and past results stay."
    override val parameters = schema(listOf("id"), "id" to prop("number", "Schedule id"))
    override suspend fun execute(args: JsonObject): String = execute(args, ToolContext(0))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val id = args.long("id") ?: return errorJson("id is required")
        val e = backend.list().firstOrNull { it.id == id } ?: return errorJson("No schedule #$id")
        if (!backend.delete(id)) return errorJson("Couldn't delete #$id")
        if (ctx.taskId != 0L) backend.postInChat(ctx.taskId, "🗑️ Schedule #$id “${e.name}” cancelled.")
        return buildJsonObject { put("ok", true); put("cancelled", id); put("name", e.name) }.toString()
    }
}

class SchedulePauseTool(backend: ScheduleBackend) : ScheduleToggleTool(backend, enable = false) {
    override val name = "schedule_pause"
    override val description = "Pause (disable) a schedule by id without deleting it."
}

class ScheduleResumeTool(backend: ScheduleBackend) : ScheduleToggleTool(backend, enable = true) {
    override val name = "schedule_resume"
    override val description = "Resume (enable) a paused schedule by id."
}

abstract class ScheduleToggleTool(private val backend: ScheduleBackend, private val enable: Boolean) : AgentTool {
    override val parameters = schema(listOf("id"), "id" to prop("number", "Schedule id"))
    override suspend fun execute(args: JsonObject): String = execute(args, ToolContext(0))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val id = args.long("id") ?: return errorJson("id is required")
        val e = backend.setEnabled(id, enable) ?: return errorJson("No schedule #$id")
        if (enable && !e.enabled) return errorJson("#$id is a one-off whose time has passed; create a new one instead")
        if (ctx.taskId != 0L) backend.postInChat(ctx.taskId, (if (enable) "▶️ Resumed" else "⏸️ Paused") + " schedule #$id “${e.name}”.")
        return buildJsonObject { put("ok", true); put("schedule", ScheduleToolsText.info(e, backend.zone())) }.toString()
    }
}

/** Argument parsing shared by the tools (pure). */
object ScheduleToolsParse {
    private fun JsonObject.num(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.contentOrNull?.trim()?.toLongOrNull() }

    fun at(args: JsonObject, now: Long, zone: ZoneId): Long? {
        args.num("in_minutes")?.let { m -> return if (m in 1..ScheduleSpec.MAX_INTERVAL_MINUTES) now + m * 60_000 else null }
        return args.str("at")?.let { ScheduleMath.parseAt(it, now, zone) }?.takeIf { it > now }
    }

    fun spec(args: JsonObject, now: Long, zone: ZoneId): ScheduleSpec? {
        val daily = args.str("daily_at")?.takeIf { it.isNotBlank() }
        val every = args.num("every_minutes")
        val once = args.containsKey("at") || args.containsKey("in_minutes")
        if (listOf(daily != null, every != null, once).count { it } != 1) return null
        return when {
            daily != null -> ScheduleMath.parseTimeOfDay(daily)?.let { (h, m) -> ScheduleSpec.Daily(h, m) }
            every != null -> every.takeIf { it in ScheduleSpec.MIN_INTERVAL_MINUTES..ScheduleSpec.MAX_INTERVAL_MINUTES }?.let { ScheduleSpec.Interval(it.toInt()) }
            else -> at(args, now, zone)?.let { ScheduleSpec.Once(it) }
        }
    }
}

/** Resolves the real backend on first use (breaks the DI cycle registry → manager → agent → registry). */
class LazyScheduleBackend(private val get: () -> ScheduleBackend) : ScheduleBackend {
    private val b by lazy(get)
    override fun now() = b.now()
    override fun zone() = b.zone()
    override suspend fun create(kind: ScheduleKind, name: String, message: String, spec: ScheduleSpec, onlyIfChanged: Boolean,
                                sourceTaskId: Long?, chatTaskId: Long?) = b.create(kind, name, message, spec, onlyIfChanged, sourceTaskId, chatTaskId)
    override suspend fun list() = b.list()
    override suspend fun delete(id: Long) = b.delete(id)
    override suspend fun setEnabled(id: Long, on: Boolean) = b.setEnabled(id, on)
    override suspend fun postInChat(taskId: Long, text: String) = b.postInChat(taskId, text)
}
