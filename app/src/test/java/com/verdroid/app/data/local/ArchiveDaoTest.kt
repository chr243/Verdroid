package com.verdroid.app.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.verdroid.app.agent.tools.ChartFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*
import org.robolectric.annotation.Config
import java.io.File

/** v1.0.12 chat archive: DAO behaviour and the 4 → 5 migration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ArchiveDaoTest {
    private lateinit var db: VerdroidDatabase
    private val tasks get() = db.taskDao()

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VerdroidDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    private suspend fun task(title: String, updated: Long, status: String = "COMPLETED") =
        tasks.insert(TaskEntity(title = title, prompt = title, type = "CHAT", status = status, subtitle = "", createdAt = updated, updatedAt = updated))

    @Test fun `archived chats leave the home list and come back on restore`() = runBlocking {
        val a = task("A", 1); val b = task("B", 2); val c = task("C", 3)
        assertEquals(listOf("C", "B", "A"), tasks.observeConversations().first().map { it.task.title })
        assertEquals(1, tasks.archive(a, now = 100)); assertEquals(1, tasks.archive(c, now = 200))
        assertEquals(listOf("B"), tasks.observeConversations().first().map { it.task.title })
        // newest archived first, with its archive time
        assertEquals(listOf(c to 200L, a to 100L), tasks.observeArchived().first().map { it.id to it.archivedAt })
        assertEquals(setOf(a, c), tasks.archivedIds().toSet())
        assertEquals(0, tasks.archive(a, now = 300)) // re-archiving keeps the first time
        assertEquals(100L, tasks.get(a)!!.archivedAt)
        assertEquals(1, tasks.restore(a))
        assertNull(tasks.get(a)!!.archivedAt)
        assertEquals(listOf("B", "A"), tasks.observeConversations().first().map { it.task.title })
        assertEquals(listOf(c), tasks.observeArchived().first().map { it.id })
        assertNotNull(b)
    }

    @Test fun `archived chats are not recovery candidates`() = runBlocking {
        val run = task("run", 1, "PAUSED"); val arch = task("arch", 2, "PAUSED")
        tasks.archive(arch, 5)
        assertEquals(listOf(run), tasks.withStatus(listOf("PAUSED")).map { it.id })
    }

    @Test fun `permanent delete cascades and chart results are found first`() = runBlocking {
        val id = task("charts", 1); val other = task("other", 2)
        val msg = db.messageDao().insert(MessageEntity(taskId = id, role = "assistant", content = "x", kind = "NORMAL", toolCallsJson = null,
            toolCallId = null, toolName = null, model = null, createdAt = 1))
        fun call(task: Long, name: String, result: String?) = ToolCallEntity(taskId = task, messageId = msg, callId = "c", name = name,
            argumentsJson = "{}", resultJson = result, status = "SUCCESS", source = "builtin", startedAt = 1, finishedAt = 2)
        db.toolCallDao().insert(call(id, "chart", """{"ok":true,"image_path":"/data/charts/chart-1.png"}"""))
        db.toolCallDao().insert(call(id, "web_fetch", """{"ok":true,"image_path":"/x.png"}"""))
        db.toolCallDao().insert(call(id, "chart", null))
        db.memoryDao().insert(MemoryEntity(text = "short", createdAt = 1, updatedAt = 1, chatId = id))
        db.memoryDao().insert(MemoryEntity(text = "global", createdAt = 1, updatedAt = 1))
        assertEquals(listOf("""{"ok":true,"image_path":"/data/charts/chart-1.png"}"""), db.toolCallDao().results(id, "chart"))
        tasks.archive(id, 9)
        tasks.delete(id); db.memoryDao().clearChat(id)
        assertNull(tasks.get(id)); assertTrue(db.messageDao().getAll(id).isEmpty()); assertTrue(db.toolCallDao().results(id, "chart").isEmpty())
        assertEquals(listOf("global"), db.memoryDao().all().map { it.text })
        assertTrue(tasks.archivedIds().isEmpty()); assertNotNull(tasks.get(other))
    }

    @Test fun `chart files only inside the charts dir`() {
        val root = kotlin.io.path.createTempDirectory("files").toFile()
        val charts = File(root, "charts").apply { mkdirs() }
        val mine = File(charts, "chart-1.png").apply { writeText("png") }
        val outside = File(root, "secret.png").apply { writeText("x") }
        val results = listOf(
            """{"ok":true,"image_path":"${mine.absolutePath}"}""",
            """{"ok":true,"image_path":"${outside.absolutePath}"}""",
            """{"ok":true,"image_path":"${charts.absolutePath}/../secret.png"}""",
            """{"ok":true,"image_path":"${File(charts, "notes.txt").absolutePath}"}""",
            """{"ok":true,"image_path":"${mine.absolutePath}"}""",
            "not json",
        )
        assertEquals(listOf(mine.canonicalPath), ChartFiles.owned(results, charts).map { it.canonicalPath })
        root.deleteRecursively()
    }

    /** Creates a real v[version] database from the exported schema JSON (tables, indices, Room's identity hash). */
    private fun createFromSchema(name: String, version: Int) {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val schema = listOf("schemas", "app/schemas").map { File(it, "com.verdroid.app.data.local.VerdroidDatabase/$version.json") }.first { it.exists() }
        val database = Json.parseToJsonElement(schema.readText()).jsonObject["database"]!!.jsonObject
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            database["entities"]!!.jsonArray.map { it.jsonObject }.forEach { e ->
                val table = e["tableName"]!!.jsonPrimitive.content
                db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { ix -> db.execSQL(ix.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            database["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = version
        }
    }

    /** Opens [name] with Room at the current version; Room runs the migrations and validates the resulting schema. */
    private fun openMigrated(name: String) = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VerdroidDatabase::class.java, name)
        .addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()

    @Test fun `migration 4 to 5 adds archivedAt and keeps rows`() = runBlocking {
        createFromSchema(DB, 4)
        SQLiteDatabase.openDatabase(ApplicationProvider.getApplicationContext<Application>().getDatabasePath(DB).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO tasks (id, title, prompt, type, status, subtitle, currentStep, createdAt, updatedAt, lastOpenedAt, attempt) " +
                "VALUES (7, 'Old chat', 'p', 'CHAT', 'COMPLETED', 's', 0, 1, 2, 0, 0)")
        }
        val migrated = openMigrated(DB)
        try {
            val t = migrated.taskDao().get(7)!!
            assertEquals("Old chat", t.title); assertNull(t.archivedAt)
            assertEquals(listOf(7L), migrated.taskDao().observeConversations().first().map { it.task.id })
            migrated.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_tasks_archivedAt'")
                .use { assertTrue(it.moveToFirst()) }
            assertEquals(8, migrated.openHelper.readableDatabase.version)
        } finally { migrated.close() }
    }

    @Test fun `migration 6 to 7 adds schedules and keeps chats`() = runBlocking {
        createFromSchema(DB, 6)
        SQLiteDatabase.openDatabase(ApplicationProvider.getApplicationContext<Application>().getDatabasePath(DB).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO tasks (id, title, prompt, type, status, subtitle, currentStep, createdAt, updatedAt, lastOpenedAt, attempt) " +
                "VALUES (9, 'Chat', 'p', 'CHAT', 'COMPLETED', 's', 0, 1, 2, 0, 0)")
        }
        val migrated = openMigrated(DB)
        try {
            assertEquals(8, migrated.openHelper.writableDatabase.version)
            assertEquals("Chat", migrated.taskDao().get(9)!!.title)
            val d = migrated.scheduleDao()
            assertTrue(d.all().isEmpty())
            val id = d.insert(ScheduleEntity(kind = "TASK", name = "CNN", message = "summarise", specType = "DAILY", specValue = 600, chatTaskId = 9, nextRunAt = 10, createdAt = 1))
            val s = d.get(id)!!
            assertTrue(s.enabled); assertFalse(s.onlyIfChanged); assertEquals(s, d.forChat(9))
            d.update(s.copy(enabled = false)); assertTrue(d.enabled().isEmpty())
            assertEquals(1, d.delete(id))
        } finally { migrated.close() }
    }

    @Test fun `all migrations from 1 validate against the current schema`() = runBlocking {
        createFromSchema(DB, 1)
        val migrated = openMigrated(DB)
        try {
            assertEquals(8, migrated.openHelper.writableDatabase.version) // Room throws here if a migration left a schema mismatch
            assertTrue(migrated.taskDao().archivedIds().isEmpty())
        } finally { migrated.close() }
    }

    @Test fun `migration 5 to 6 adds message reasoning and keeps rows`() = runBlocking {
        createFromSchema(DB, 5)
        SQLiteDatabase.openDatabase(ApplicationProvider.getApplicationContext<Application>().getDatabasePath(DB).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO tasks (id, title, prompt, type, status, subtitle, currentStep, createdAt, updatedAt, lastOpenedAt, attempt) " +
                "VALUES (7, 'Old chat', 'p', 'CHAT', 'COMPLETED', 's', 0, 1, 2, 0, 0)")
            it.execSQL("INSERT INTO messages (id, taskId, role, content, kind, summarized, createdAt) VALUES (3, 7, 'assistant', 'hi', 'NORMAL', 0, 1)")
        }
        val migrated = openMigrated(DB)
        try {
            val old = migrated.messageDao().getAll(7).single()
            assertEquals("hi", old.content); assertNull(old.reasoning)
            val id = migrated.messageDao().insert(old.copy(id = 0, content = "ok", reasoning = "Let me think"))
            assertEquals("Let me think", migrated.messageDao().getAll(7).first { it.id == id }.reasoning)
        } finally { migrated.close() }
    }

    @Test fun `migration 7 to 8 puts every chat on Default and keeps memories and schedules shared`() = runBlocking {
        createFromSchema(DB, 7)
        SQLiteDatabase.openDatabase(ApplicationProvider.getApplicationContext<Application>().getDatabasePath(DB).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO tasks (id, title, prompt, type, status, subtitle, currentStep, createdAt, updatedAt, lastOpenedAt, attempt) " +
                "VALUES (5, 'Old chat', 'p', 'CHAT', 'COMPLETED', 's', 0, 1, 2, 0, 0)")
            it.execSQL("INSERT INTO memories (id, text, tags, createdAt, updatedAt) VALUES (1, 'likes tea', '', 1, 1)")
            it.execSQL("INSERT INTO schedules (id, kind, name, message, specType, specValue, enabled, onlyIfChanged, createdAt) " +
                "VALUES (2, 'TASK', 'n', 'm', 'DAILY', 600, 1, 0, 1)")
        }
        val migrated = openMigrated(DB)
        try {
            assertEquals(8, migrated.openHelper.writableDatabase.version)
            assertEquals("default", migrated.taskDao().get(5)!!.profileId)
            assertTrue(migrated.taskDao().profileRows().isEmpty())
            assertNull(migrated.memoryDao().get(1)!!.profileId)
            assertNull(migrated.scheduleDao().get(2)!!.profileId)
            // switching a chat, then deleting its profile, brings it back to Default
            assertEquals(1, migrated.taskDao().setProfile(5, "p1"))
            assertEquals(listOf(TaskProfileRow(5, "p1")), migrated.taskDao().profileRows())
            migrated.memoryDao().insert(MemoryEntity(text = "own", createdAt = 1, updatedAt = 1, profileId = "p1"))
            assertEquals(1, migrated.taskDao().resetProfile("p1")); assertEquals(1, migrated.memoryDao().unscopeProfile("p1"))
            assertEquals("default", migrated.taskDao().get(5)!!.profileId)
            assertTrue(migrated.memoryDao().all().all { it.profileId == null })
        } finally { migrated.close() }
    }

    private companion object { const val DB = "migration-test" }
}
