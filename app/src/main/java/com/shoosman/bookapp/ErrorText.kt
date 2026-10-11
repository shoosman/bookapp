package com.shoosman.bookapp

import com.shoosman.bookapp.sync.GitHubException
import org.json.JSONException
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Понятный текст ошибки на русском — вместо технических сообщений на английском. */
object ErrorText {

    private fun hasCyrillic(s: String?) = s != null && s.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }

    /** Сообщения GitHub API (они приходят по-английски). */
    private val github = listOf(
        "bad credentials" to "вход в GitHub больше не действует — подключите GitHub заново",
        "api rate limit exceeded" to "слишком много запросов к GitHub — попробую позже, обычно через час",
        "secondary rate limit" to "GitHub просит подождать — попробую позже",
        "resource not accessible by integration" to "у приложения нет доступа к репозиторию — проверьте, что Chtets Sync установлено для него в настройках GitHub",
        "repository access blocked" to "доступ к репозиторию заблокирован на GitHub",
        "git repository is empty" to "репозиторий пустой — создайте в нём любой файл, например README",
        "update is not a fast forward" to "библиотеку одновременно изменило другое устройство — повторю синхронизацию",
        "reference does not exist" to "в репозитории нет нужной ветки",
        "not found" to "не найдено на GitHub",
        "server error" to "у GitHub временный сбой — попробую позже",
        "authorization_pending" to "ждём подтверждения входа на GitHub",
        "incorrect_device_code" to "код входа неверный — начните вход заново",
        "unsupported_grant_type" to "GitHub не принял способ входа",
    )

    /** Перевести готовую строку (например, от GitHub); русскую оставить как есть. */
    fun text(message: String?, fallback: String = "Что-то пошло не так"): String {
        if (message.isNullOrBlank()) return fallback
        val lower = message.lowercase()
        github.firstOrNull { lower.contains(it.first) }?.let { (_, ru) ->
            // Сохраняем русское начало («GitHub отказал в доступе: …»), если оно есть
            val prefix = message.substringBefore(':', "").takeIf { hasCyrillic(it) }
            return (prefix?.let { "$it: " } ?: "") + ru.replaceFirstChar { it.uppercase() }
        }
        return if (hasCyrillic(message)) message else "$fallback (техническая причина: $message)"
    }

    fun httpCode(code: Int): String = when (code) {
        401, 403 -> "Сайт не пустил (код $code): нужен вход или доступ закрыт."
        404 -> "Страница не найдена (код 404). Проверьте ссылку."
        429 -> "Сайт просит подождать (код 429): слишком много запросов."
        in 500..599 -> "На сайте временный сбой (код $code). Попробуйте позже."
        else -> "Сайт ответил ошибкой (код $code)."
    }

    /** Текст для пользователя по исключению. [what] — что делали: «Синхронизация», «Скачивание»… */
    fun ru(t: Throwable, fallback: String = "Что-то пошло не так"): String {
        // Ищем по цепочке причин самую понятную
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth < 6) {
            when (e) {
                is UnknownHostException -> return "Нет интернета или сайт недоступен: не удалось найти адрес сервера. Проверьте связь или VPN."
                is SocketTimeoutException -> return "Сервер слишком долго не отвечает. Попробуйте позже."
                is ConnectException, is NoRouteToHostException -> return "Не удалось подключиться к серверу. Проверьте интернет."
                is SSLException -> return "Не удалось установить защищённое соединение — возможно, мешает провайдер, VPN или неверное время на телефоне."
                is InterruptedIOException -> return "Соединение прервалось. Попробуйте ещё раз."
                is SocketException -> return "Связь оборвалась. Попробуйте ещё раз."
                is GitHubException -> return text(e.message, "Ошибка GitHub")
                is org.jsoup.HttpStatusException -> return httpCode(e.statusCode)
                is JSONException -> return "Сервер прислал непонятный ответ. Попробуйте позже."
                is FileNotFoundException -> return if (hasCyrillic(e.message)) e.message!! else "Файл не найден или недоступен."
                is SecurityException -> return "Нет доступа к файлу. Выберите его ещё раз."
                is OutOfMemoryError -> return "Не хватило памяти телефона. Закройте другие приложения и попробуйте ещё раз."
            }
            if (hasCyrillic(e.message)) return text(e.message, fallback)
            e = e.cause
            depth++
        }
        return text(t.message, fallback).let {
            if (t.message.isNullOrBlank()) "$fallback (техническая причина: ${t.javaClass.simpleName})" else it
        }
    }
}
