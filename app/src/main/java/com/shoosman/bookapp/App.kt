package com.shoosman.bookapp

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.room.Room
import com.shoosman.bookapp.data.AppDatabase
import com.shoosman.bookapp.data.BookRepository
import com.shoosman.bookapp.data.SettingsStore
import com.shoosman.bookapp.player.PlayerConnection
import com.shoosman.bookapp.update.Updater

class App : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var repo: BookRepository
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var player: PlayerConnection
        private set
    lateinit var updater: Updater
        private set
    lateinit var sync: com.shoosman.bookapp.sync.LibrarySync
        private set

    /** Ссылка, которой поделились с Чтецом из браузера или другого приложения. */
    val sharedText = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        CrashLog.note("Запуск приложения")
        instance = this
        db = Room.databaseBuilder(this, AppDatabase::class.java, "books.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        repo = BookRepository(this, db)
        settings = SettingsStore(this)
        player = PlayerConnection(this, repo)
        updater = Updater(this)
        sync = com.shoosman.bookapp.sync.LibrarySync(this, repo)
        sync.startAutoSync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            settings.flow.collect { player.setSkip(it.skipBackSec, it.skipForwardSec) }
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
