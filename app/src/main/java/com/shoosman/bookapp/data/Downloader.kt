package com.shoosman.bookapp.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.URLUtil
import com.shoosman.bookapp.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Скачанный файл во временной папке. */
class Downloaded(val file: File, val name: String, val mime: String?)

/** Скачивание файлов книг: по прямой ссылке или из встроенного браузера (с его входом на сайт). */
object Downloader {

    private val FILE_EXT = Regex("\\.(fb2|fb2\\.zip|epub|txt|docx|zip|mp3|m4b|m4a|aac|ogg|opus|flac)(\\?.*)?$", RegexOption.IGNORE_CASE)

    /** Похоже ли на прямую ссылку на файл книги или аудио. */
    fun looksLikeFile(url: String): Boolean = runCatching {
        FILE_EXT.containsMatchIn(java.net.URL(url).path)
    }.getOrDefault(false)

    suspend fun download(
        ctx: Context,
        url: String,
        userAgent: String? = null,
        contentDisposition: String? = null,
        mime: String? = null,
        referer: String? = null,
        onProgress: OnProgress = {},
    ): Downloaded = withContext(Dispatchers.IO) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            error("Этот сайт отдаёт файл особым способом. Скачайте его обычным браузером и добавьте через «Текст книги» или «Аудиокнига — файлы».")
        }
        val req = Request.Builder().url(url).apply {
            header("User-Agent", userAgent ?: "Mozilla/5.0 (Linux; Android 14) Chrome/126.0 Mobile Safari/537.36")
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.let { header("Cookie", it) }
            referer?.let { header("Referer", it) }
        }.build()

        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Сайт не отдал файл (код ${resp.code}). Возможно, нужно войти в аккаунт.")
            val body = resp.body ?: error("Пустой ответ сайта")
            val type = mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: body.contentType()?.let { "${it.type}/${it.subtype}" }
            if (type == "text/html") error("По ссылке открывается страница, а не файл. Нажмите на ней кнопку скачивания.")
            val cd = contentDisposition ?: resp.header("Content-Disposition")
            val name = URLUtil.guessFileName(resp.request.url.toString(), cd, type).let { decodeName(it) }
            val root = File(ctx.cacheDir, "downloads")
            // Остатки прошлых скачиваний старше суток.
            root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.deleteRecursively() }
            val dir = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
            val out = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (done - last > (512 shl 10)) {
                            last = done
                            onProgress(ImportProgress("Скачиваю «$name»", if (total > 0) done.toFloat() / total else null))
                        }
                    }
                }
            }
            Downloaded(out, name, type)
        }
    }

    /** Имена вида %D0%9A%D0%BD… и «filename*=UTF-8''…» превращаем в нормальные. */
    private fun decodeName(name: String): String =
        runCatching { java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") }.getOrDefault(name)
}
