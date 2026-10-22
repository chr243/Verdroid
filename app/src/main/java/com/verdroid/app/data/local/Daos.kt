package com.verdroid.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query(
        """
        SELECT t.*, (SELECT COUNT(*) FROM messages m WHERE m.taskId = t.id AND m.role = 'assistant'
                     AND m.kind = 'NORMAL' AND m.createdAt > t.lastOpenedAt
                     AND m.content IS NOT NULL AND TRIM(m.content) != ''
                     AND (m.toolCallsJson IS NULL OR TRIM(m.toolCallsJson) IN ('', '[]'))) AS unreadCount
        FROM tasks t WHERE t.archivedAt IS NULL ORDER BY t.updatedAt DESC
        """
    )
    fun observeConversations(): Flow<List<ConversationRow>>

    /** Settings > Archive: newest archived first. */
    @Query("SELECT * FROM tasks WHERE archivedAt IS NOT NULL ORDER BY archivedAt DESC, id DESC")
    fun observeArchived(): Flow<List<TaskEntity>>

    @Query("SELECT id FROM tasks WHERE archivedAt IS NOT NULL")
    suspend fun archivedIds(): List<Long>

    /** Moves a chat to the archive (no-op when already archived, so the first archive time is kept). */
    @Query("UPDATE tasks SET archivedAt = :now WHERE id = :id AND archivedAt IS NULL")
    suspend fun archive(id: Long, now: Long): Int

    @Query("UPDATE tasks SET archivedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long): Int

    /** Profiles: chats that are not on Default (id → profileId). */
    @Query("SELECT id, profileId FROM tasks WHERE profileId != 'default'")
    suspend fun profileRows(): List<TaskProfileRow>

    @Query("UPDATE tasks SET profileId = :profileId WHERE id = :id")
    suspend fun setProfile(id: Long, profileId: String): Int

    @Query("UPDATE tasks SET profileId = 'default' WHERE profileId = :profileId")
    suspend fun resetProfile(profileId: String): Int

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: Long): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskEntity?

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Query("UPDATE tasks SET status = :status, subtitle = :subtitle, resumeAt = :resumeAt, errorMessage = :error, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, subtitle: String, resumeAt: Long?, error: String?, now: Long)

    @Query("UPDATE tasks SET subtitle = :subtitle, updatedAt = :now WHERE id = :id")
    suspend fun updateSubtitle(id: Long, subtitle: String, now: Long)

    @Query("UPDATE tasks SET currentStep = :step, model = :model, checkpointJson = :checkpoint WHERE id = :id")
    suspend fun updateCheckpoint(id: Long, step: Int, model: String?, checkpoint: String)

    @Query("UPDATE tasks SET startedAt = :now WHERE id = :id")
    suspend fun markStarted(id: Long, now: Long)

    @Query("UPDATE tasks SET lastOpenedAt = :now WHERE id = :id")
    suspend fun markOpened(id: Long, now: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE tasks SET status = :status, subtitle = :subtitle, resumeAt = :resumeAt, pauseReason = :reason, attempt = :attempt, updatedAt = :now WHERE id = :id")
    suspend fun pause(id: Long, status: String, subtitle: String, resumeAt: Long?, reason: String, attempt: Int, now: Long)

    @Query("UPDATE tasks SET attempt = :attempt WHERE id = :id")
    suspend fun setAttempt(id: Long, attempt: Int)

    /** Recovery/resume candidates; archived chats never run again on their own. */
    @Query("SELECT * FROM tasks WHERE status IN (:statuses) AND archivedAt IS NULL ORDER BY updatedAt ASC")
    suspend fun withStatus(statuses: List<String>): List<TaskEntity>
}

data class TaskProfileRow(val id: Long, val profileId: String)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE taskId = :taskId ORDER BY id ASC")
    fun observe(taskId: Long): Flow<List<MessageEntity>>

    /** UI window: newest [limit] rows, tool outputs cut to [cap] chars + [marker] (see ChatWindow). */
    @Query("""SELECT id, taskId, role,
        CASE WHEN role = 'tool' AND length(content) > :cap THEN substr(content, 1, :cap) || :marker ELSE content END AS content,
        kind, toolCallsJson, toolCallId, toolName, model, summarized, resumeAt, createdAt, reasoning
        FROM (SELECT * FROM messages WHERE taskId = :taskId ORDER BY id DESC LIMIT :limit) ORDER BY id ASC""")
    fun observeWindow(taskId: Long, limit: Int, cap: Int, marker: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE taskId = :taskId ORDER BY id ASC")
    suspend fun getAll(taskId: Long): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    /** Newest user message of a chat (scheduled-run detection: the scheduler's messages start with a marker). */
    /** Tool names of the tool results after the chat's latest user message (shell-safety taint). */
    @Query("""SELECT toolName FROM messages WHERE taskId = :taskId AND role = 'tool'
        AND id > COALESCE((SELECT MAX(id) FROM messages WHERE taskId = :taskId AND role = 'user'), 0)""")
    suspend fun toolNamesSinceUser(taskId: Long): List<String?>

    @Query("SELECT content FROM messages WHERE taskId = :taskId AND role = 'user' ORDER BY id DESC LIMIT 1")
    suspend fun lastUserContent(taskId: Long): String?

    @Query("UPDATE messages SET summarized = 1 WHERE id IN (:ids)")
    suspend fun markSummarized(ids: List<Long>)
}

@Dao
interface ToolCallDao {
    @Query("SELECT * FROM tool_calls WHERE taskId = :taskId ORDER BY id ASC")
    fun observe(taskId: Long): Flow<List<ToolCallEntity>>

    /** UI window: tool calls of the newest [limit] messages; arguments/results cut to [cap] (charts kept whole). */
    @Query("""SELECT id, taskId, messageId, callId, name,
        CASE WHEN name != :chart AND length(argumentsJson) > :cap THEN substr(argumentsJson, 1, :cap) || :marker ELSE argumentsJson END AS argumentsJson,
        CASE WHEN name != :chart AND length(resultJson) > :cap THEN substr(resultJson, 1, :cap) || :marker ELSE resultJson END AS resultJson,
        status, source, startedAt, finishedAt
        FROM tool_calls WHERE taskId = :taskId AND messageId >=
            (SELECT IFNULL(MIN(id), 0) FROM (SELECT id FROM messages WHERE taskId = :taskId ORDER BY id DESC LIMIT :limit))
        ORDER BY id ASC""")
    fun observeWindow(taskId: Long, limit: Int, cap: Int, marker: String, chart: String): Flow<List<ToolCallEntity>>

    @Insert
    suspend fun insert(entity: ToolCallEntity): Long

    /** Results of one tool in a chat (e.g. `chart`, to delete its PNGs with the chat). */
    @Query("SELECT resultJson FROM tool_calls WHERE taskId = :taskId AND name = :name AND resultJson IS NOT NULL")
    suspend fun results(taskId: Long, name: String): List<String>

    @Query("UPDATE tool_calls SET resultJson = :result, status = :status, finishedAt = :now WHERE id = :id")
    suspend fun finish(id: Long, result: String, status: String, now: Long)
}

@Dao
interface RateLimitEventDao {
    @Query("SELECT * FROM rate_limit_events ORDER BY timestamp DESC LIMIT 500")
    fun observe(): Flow<List<RateLimitEventEntity>>

    @Insert
    suspend fun insert(e: RateLimitEventEntity): Long

    @Query("DELETE FROM rate_limit_events")
    suspend fun clear()
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY createdAt DESC LIMIT 300")
    fun observe(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE read = 0")
    fun observeUnread(): Flow<Int>

    @Insert
    suspend fun insert(n: NotificationEntity): Long

    @Query("UPDATE notifications SET read = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM notifications")
    suspend fun clear()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    suspend fun all(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun get(id: Long): MemoryEntity?

    @Insert suspend fun insert(m: MemoryEntity): Long
    @Update suspend fun update(m: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM memories WHERE chatId IS NULL")
    suspend fun clearGlobal()

    @Query("DELETE FROM memories WHERE chatId = :chatId")
    suspend fun clearChat(chatId: Long)

    /** Short-term memories whose chat no longer exists. */
    @Query("DELETE FROM memories WHERE chatId IS NOT NULL AND chatId NOT IN (SELECT id FROM tasks)")
    suspend fun deleteOrphans(): Int

    /** Deleted profile: its separate long-term memories become shared. */
    @Query("UPDATE memories SET profileId = NULL WHERE profileId = :profileId")
    suspend fun unscopeProfile(profileId: String): Int

    /** Long-term memories owned by a profile (migration of the old "separate memory" switch). */
    @Query("SELECT id, profileId FROM memories WHERE profileId IS NOT NULL AND chatId IS NULL")
    suspend fun ownedRows(): List<MemoryOwnerRow>

    @Query("UPDATE memories SET chatId = :chatId, updatedAt = :now WHERE id = :id")
    suspend fun setChat(id: Long, chatId: Long?, now: Long): Int
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY enabled DESC, nextRunAt IS NULL, nextRunAt, id")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules ORDER BY id")
    suspend fun all(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE enabled = 1")
    suspend fun enabled(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun get(id: Long): ScheduleEntity?

    @Query("SELECT * FROM schedules WHERE chatTaskId = :taskId LIMIT 1")
    suspend fun forChat(taskId: Long): ScheduleEntity?

    @Insert suspend fun insert(s: ScheduleEntity): Long
    @Update suspend fun update(s: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: Long): Int
}

@Database(
    entities = [TaskEntity::class, MessageEntity::class, ToolCallEntity::class, RateLimitEventEntity::class, NotificationEntity::class, MemoryEntity::class, ScheduleEntity::class],
    version = 8,
    exportSchema = true,
)
abstract class VerdroidDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun rateLimitEventDao(): RateLimitEventDao
    abstract fun notificationDao(): NotificationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun scheduleDao(): ScheduleDao
}

data class MemoryOwnerRow(val id: Long, val profileId: String)
