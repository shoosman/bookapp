package com.shoosman.bookapp.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.shoosman.bookapp.BuildConfig
import com.shoosman.bookapp.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Release(val version: String, val notes: String, val assetUrl: String, val assetName: String, val size: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    class Available(val release: Release) : UpdateState
    class Downloading(val release: Release, val fraction: Float?) : UpdateState
    class Failed(val message: String) : UpdateState
}

/** Проверка и установка обновлений из релизов GitHub. */
class Updater(private val ctx: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "0"

    /** Тихая проверка при запуске — не чаще раза в 12 часов, ошибки не показываем. */
    fun checkInBackground() {
        val last = prefs.getLong("lastCheck", 0)
        if (System.currentTimeMillis() - last < 12 * 3600_000L) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            val result = runCatching { withContext(Dispatchers.IO) { fetchLatest() } }
            prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            result.onSuccess { release ->
                _state.value = when {
                    release == null -> if (manual) UpdateState.UpToDate else UpdateState.Idle
                    !manual && prefs.getString("skipped", null) == release.version -> UpdateState.Idle
                    else -> UpdateState.Available(release)
                }
            }.onFailure {
                CrashLog.note("Проверка обновлений: ${it.message}")
                _state.value = if (manual) UpdateState.Failed(it.message ?: "Не удалось проверить обновления") else UpdateState.Idle
            }
        }
    }

    /** «Позже»: не напоминать об этой версии при автопроверке. */
    fun skip(release: Release) {
        prefs.edit().putString("skipped", release.version).apply()
        _state.value = UpdateState.Idle
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle
    }

    fun download(release: Release) {
        if (job?.isActive == true && _state.value is UpdateState.Downloading) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(release, 0f)
            CrashLog.note("Обновление: скачиваю ${release.version}")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    downloadApk(release) { f -> _state.value = UpdateState.Downloading(release, f) }
                }
            }
            result.onSuccess { file ->
                _state.value = UpdateState.Idle
                install(file)
            }.onFailure {
                CrashLog.note("Обновление: ошибка ${it.message}")
                _state.value = UpdateState.Failed("Не удалось скачать обновление: ${it.message}")
            }
        }
    }

    // ---------------- сеть ----------------

    private fun fetchLatest(): Release? {
        val conn = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest", "application/vnd.github+json")
        try {
            when (conn.responseCode) {
                200 -> Unit
                404 -> error(
                    if (BuildConfig.UPDATE_TOKEN.isEmpty()) "Нет доступа к релизам: репозиторий приватный, а токен для обновлений не настроен"
                    else "Релизы не найдены или у токена нет доступа к репозиторию",
                )
                401, 403 -> error("GitHub отказал в доступе (код ${conn.responseCode}). Возможно, токен истёк")
                else -> error("GitHub ответил кодом ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            if (!isNewer(version, currentVersion)) return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.getString("name")
                if (name.endsWith(".apk")) {
                    return Release(
                        version = version,
                        notes = json.optString("body").trim(),
                        assetUrl = a.getString("url"),
                        assetName = name,
                        size = a.optLong("size"),
                    )
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadApk(release: Release, onProgress: (Float?) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, release.assetName)

        // API отдаёт файл перенаправлением на хранилище. Ключ туда не передаём — его там не ждут.
        var conn = open(release.assetUrl, "application/octet-stream")
        conn.instanceFollowRedirects = false
        var hops = 0
        while (conn.responseCode in 300..399) {
            val location = conn.getHeaderField("Location") ?: error("Пустое перенаправление")
            conn.disconnect()
            conn = (URL(location).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Chtets")
            }
            if (++hops > 5) error("Слишком много перенаправлений")
        }
        if (conn.responseCode != 200) error("код ${conn.responseCode}")

        val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
        try {
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > (256 shl 10)) {
                            last = done
                            onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (release.size > 0 && target.length() != release.size) error("файл скачался не полностью")
        return target
    }

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Chtets")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (BuildConfig.UPDATE_TOKEN.isNotEmpty()) setRequestProperty("Authorization", "Bearer ${BuildConfig.UPDATE_TOKEN}")
        }

    // ---------------- установка ----------------

    /** Можно ли сейчас ставить приложения (Android спрашивает это один раз). */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    private var pendingApk: File? = null

    private fun install(file: File) {
        if (!canInstall()) {
            pendingApk = file
            _state.value = UpdateState.Failed("NEED_PERMISSION")
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /** После выдачи разрешения на установку — продолжить с уже скачанным файлом. */
    fun resumeInstall() {
        val f = pendingApk ?: return
        if (canInstall()) {
            pendingApk = null
            _state.value = UpdateState.Idle
            install(f)
        }
    }

    companion object {
        /** 0.1.12 новее 0.1.9 */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
            val b = current.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
