package com.shoosman.bookapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, TrackEntity::class, BookmarkEntity::class, AnchorEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        /** Версия 2: список прочитанного (заметки, даты, ссылка) и синхронизация (uid, отметки времени, удаление). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                db.execSQL("ALTER TABLE books ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN startedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN metaUpdatedAt INTEGER NOT NULL DEFAULT $now")
                db.execSQL("ALTER TABLE books ADD COLUMN shelfUpdatedAt INTEGER NOT NULL DEFAULT $now")
                db.execSQL("ALTER TABLE books ADD COLUMN textUpdatedAt INTEGER NOT NULL DEFAULT $now")
                db.execSQL("ALTER TABLE books ADD COLUMN progressUpdatedAt INTEGER NOT NULL DEFAULT $now")
                db.execSQL("ALTER TABLE books ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE books SET uid = " + UUID_SQL)
                db.execSQL("UPDATE books SET startedAt = addedAt WHERE status != 0")

                db.execSQL("ALTER TABLE bookmarks ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE bookmarks SET uid = " + UUID_SQL)

                db.execSQL("ALTER TABLE anchors ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE anchors ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE anchors SET uid = " + UUID_SQL)
            }
        }

        /** Версия 3: циклы книг. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN series TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN seriesIndex REAL NOT NULL DEFAULT 0")
            }
        }

        /** Случайный UUID средствами SQLite (формат 8-4-4-4-12). */
        private const val UUID_SQL =
            "lower(substr(hex(randomblob(4)),1) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(6)))"
    }
}
