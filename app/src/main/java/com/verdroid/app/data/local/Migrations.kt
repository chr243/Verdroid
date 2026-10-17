package com.verdroid.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v2 (Phase 3): backoff attempt counter + pause reason on tasks. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `attempt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `pauseReason` TEXT")
    }
}

/** v3 (v0.9.16): agent memory. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, " +
            "`tags` TEXT NOT NULL DEFAULT '', `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_updatedAt` ON `memories` (`updatedAt`)")
    }
}

/** v4 (v0.9.17): short-term (per-chat) memory — chatId NULL = long-term. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `memories` ADD COLUMN `chatId` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_chatId` ON `memories` (`chatId`)")
    }
}

/** v5 (v1.0.12): chat archive — archivedAt NULL = on the home list. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `archivedAt` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_archivedAt` ON `tasks` (`archivedAt`)")
    }
}

/** v6 (v1.0.34): model thinking on messages. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `reasoning` TEXT")
    }
}

/** v7 (v1.0.58): schedules (reminders + scheduled agent runs). */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `schedules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
            "`name` TEXT NOT NULL, `message` TEXT NOT NULL, `specType` TEXT NOT NULL, `specValue` INTEGER NOT NULL, " +
            "`enabled` INTEGER NOT NULL, `onlyIfChanged` INTEGER NOT NULL DEFAULT 0, `chatTaskId` INTEGER, `sourceTaskId` INTEGER, " +
            "`nextRunAt` INTEGER, `lastRunAt` INTEGER, `lastStatus` TEXT, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedules_enabled` ON `schedules` (`enabled`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedules_nextRunAt` ON `schedules` (`nextRunAt`)")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
