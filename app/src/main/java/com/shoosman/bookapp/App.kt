package com.shoosman.bookapp

import android.app.Application
import androidx.room.Room
import com.shoosman.bookapp.data.AppDatabase
import com.shoosman.bookapp.data.BookRepository
import com.shoosman.bookapp.data.SettingsStore
import com.shoosman.bookapp.player.PlayerConnection

class App : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var repo: BookRepository
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var player: PlayerConnection
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        CrashLog.note("Запуск приложения")
        instance = this
        db = Room.databaseBuilder(this, AppDatabase::class.java, "books.db").build()
        repo = BookRepository(this, db)
        settings = SettingsStore(this)
        player = PlayerConnection(this, repo)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
