package com.shoosman.bookapp.update

import com.shoosman.bookapp.ErrorText
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

    private val packageInfo = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()

    val currentVersion: String = packageInfo?.versionName ?: "0"

    private val currentVersionCode: Long =
        if (Build.VERSION.SDK_INT >= 28) packageInfo?.longVersionCode ?: 0L
        else @Suppress("DEPRECATION") (packageInfo?.versionCode?.toLong() ?: 0L)

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
                _state.value = if (manual) UpdateState.Failed(ErrorText.ru(it, "Не удалось проверить обновления")) else UpdateState.Idle
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
                _state.value = UpdateState.Failed("Не удалось скачать обновление. " + ErrorText.ru(it))
            }
        }
    }

    // ---------------- сеть ----------------
    // Схема: у репозитория есть релиз с постоянным тегом `latest`, в нём лежат
    // version.json и chtets.apk. Ссылки на них не меняются от версии к версии.

    private val base = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/latest"

    private fun fetchLatest(): Release? {
        val text = get("$base/version.json?t=${System.currentTimeMillis()}") { conn ->
            conn.inputStream.bufferedReader().use { it.readText() }
        }
        val json = JSONObject(text)
        val code = json.getLong("versionCode")
        if (code <= currentVersionCode) return null
        return Release(
            version = json.optString("versionName", code.toString()),
            notes = json.optString("notes").trim(),
            assetUrl = "$base/chtets.apk?t=${System.currentTimeMillis()}",
            assetName = "chtets.apk",
            size = json.optLong("size", 0L),
        )
    }

    private fun downloadApk(release: Release, onProgress: (Float?) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, release.assetName)
        get(release.assetUrl) { conn ->
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
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
            if (total > 0 && target.length() != total) error("файл скачался не полностью")
        }
        return target
    }

    /**
     * GET с ручным проходом редиректов: GitHub отдаёт файлы релиза через 302 на другой домен,
     * а HttpURLConnection сам между доменами не переходит.
     */
    private fun <T> get(url: String, read: (HttpURLConnection) -> T): T {
        var current = url
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                useCaches = false
                setRequestProperty("User-Agent", "Chtets")
            }
            try {
                when (val code = conn.responseCode) {
                    in 300..399 -> current = URL(URL(current), conn.getHeaderField("Location") ?: error("пустой редирект")).toString()
                    200 -> return read(conn)
                    404 -> error("на GitHub пока нет релиза с обновлениями")
                    else -> error("GitHub ответил кодом $code")
                }
            } finally {
                conn.disconnect()
            }
        }
        error("слишком много перенаправлений")
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
}
