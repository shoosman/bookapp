package com.shoosman.bookapp

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Журнал сбоев. При вылете пишет отчёт в файл; при следующем запуске
 * приложение показывает его, чтобы можно было скопировать и отправить.
 */
object CrashLog {
    private const val PENDING = "crash_pending.txt"
    private const val LAST = "crash_last.txt"
    private const val TRAIL_SIZE = 40

    private val trail = ArrayDeque<String>()
    private lateinit var appCtx: Context

    fun install(ctx: Context) {
        appCtx = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = report(error, "Приложение аварийно закрылось (поток ${thread.name})")
                File(appCtx.filesDir, PENDING).writeText(text)
                File(appCtx.filesDir, LAST).writeText(text)
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /** Запомнить действие пользователя — попадёт в отчёт как «последние действия». */
    @Synchronized
    fun note(message: String) {
        trail.addLast("${time("HH:mm:ss")}  $message")
        while (trail.size > TRAIL_SIZE) trail.removeFirst()
    }

    /** Отчёт о сбое с прошлого запуска, если он есть. */
    fun pending(): String? = runCatching {
        File(appCtx.filesDir, PENDING).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun clearPending() {
        runCatching { File(appCtx.filesDir, PENDING).delete() }
    }

    /** Журнал последних действий — даже если сбоев не было (для поиска причин «молча не работает»). */
    @Synchronized
    fun journal(): String = buildString {
        val (name, code) = runCatching {
            val info = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            (info.versionName ?: "?") to versionCode
        }.getOrDefault("?" to 0L)
        appendLine("Чтец $name ($code)")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Время: ${time("dd.MM.yyyy HH:mm:ss")}")
        appendLine()
        appendLine("Последние действия:")
        if (trail.isEmpty()) appendLine("  —") else trail.forEach { appendLine("  $it") }
    }

    /** Последний сохранённый отчёт (для настроек). */
    fun last(): String? = runCatching {
        File(appCtx.filesDir, LAST).takeIf { it.exists() }?.readText()
    }.getOrNull()

    /** Подробности не фатальной ошибки — для кнопки «Скопировать подробности». */
    fun describe(error: Throwable, title: String): String {
        val text = report(error, title)
        runCatching { File(appCtx.filesDir, LAST).writeText(text) }
        return text
    }

    @Synchronized
    private fun report(error: Throwable, title: String): String = buildString {
        appendLine(title)
        appendLine()
        val (name, code) = runCatching {
            val info = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            (info.versionName ?: "?") to versionCode
        }.getOrDefault("?" to 0L)
        appendLine("Чтец $name ($code)")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Время: ${time("dd.MM.yyyy HH:mm:ss")}")
        appendLine()
        appendLine("Последние действия:")
        if (trail.isEmpty()) appendLine("  —") else trail.forEach { appendLine("  $it") }
        appendLine()
        appendLine("Ошибка:")
        append(error.stackTraceToString().take(12_000))
    }

    private fun time(pattern: String) = SimpleDateFormat(pattern, Locale.US).format(Date())
}
