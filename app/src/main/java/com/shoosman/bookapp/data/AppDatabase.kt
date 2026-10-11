package com.shoosman.bookapp.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class, TrackEntity::class, BookmarkEntity::class, AnchorEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}
