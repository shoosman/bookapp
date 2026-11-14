package com.shoosman.bookapp.data

import com.shoosman.bookapp.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.TimeUnit

/** Адаптеры открытых веб-каталогов аудиокниг. Не требуют OPDS.
 * Скачивание доступных файлов выполняется только по ссылкам, опубликованным сайтом.
 */
object AudioSources {
    data class Provider(val title: String, val host: String)
    val providers = listOf(
        Provider("KnigaHub", "knigahub.com"),
        Provider("Aknigi24", "aknigi24.com"),
        Provider("Abook", "tor.abook.casa"),
    )

    private val client by lazy { Http.client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build() }
    private fun encoded(q: String) = URLEncoder.encode(q, "UTF-8")
    private fun pathEncoded(q: String) = encoded(q).replace("+", "%20")
    private fun abs(base: String, href: String): String? = runCatching {
        val result = URL(URL(base), href)
        if (result.protocol != "https" && result.protocol != "http") return@runCatching null
        result.toString()
    }.getOrNull()

    private fun read(url: String): String {
        val req = Request.Builder().url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
            .build()
        return client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val type = response.header("Content-Type").orEmpty()
            if (type.isNotBlank() && !type.contains("html", true)) error("Unexpected response: $type")
            response.body?.string().orEmpty()
        }
    }

    private fun addresses(provider: Provider, q: String): List<Pair<String, String?>> {
        val normalized = BookSearch.normalize(q)
        val tokens = normalized.split(' ').filter { it.isNotBlank() }
        val reversed = tokens.reversed().joinToString(" ")
        return when (provider.host) {
            "knigahub.com" -> (
                (if (tokens.size in 2..3) listOf(q, reversed).distinct().map {
                    "https://knigahub.com/author/${pathEncoded(it)}" to it
                } else emptyList()) +
                listOf(
                    "https://knigahub.com/search?query=${encoded(q)}" to null,
                    "https://knigahub.com/search?q=${encoded(q)}" to null,
                    "https://knigahub.com/?s=${encoded(q)}" to null,
                )
            )
            "aknigi24.com" -> listOf(
                "https://aknigi24.com/search?q=${encoded(q)}" to null,
                "https://aknigi24.com/search?query=${encoded(q)}" to null,
                "https://aknigi24.com/?q=${encoded(q)}" to null,
            )
            else -> listOf("https://tor.abook.casa/?s=${encoded(q)}" to null)
        }
    }

    private fun matchesHost(url: String, host: String): Boolean =
        runCatching { URL(url).host.removePrefix("www.") == host }.getOrDefault(false)

    private fun isBookUrl(provider: Provider, url: String): Boolean {
        val path = runCatching { URL(url).path }.getOrDefault("")
        return when (provider.host) {
            "knigahub.com" -> path.startsWith("/book-")
            "aknigi24.com" -> path.startsWith("/book/") && !path.endsWith("/download")
            else -> {
                val excluded = listOf("/category/", "/tag/", "/author/", "/page/", "/wp-", "/feed/")
                path.length > 8 && path != "/" && excluded.none { path.startsWith(it) }
            }
        }
    }

    private fun ancestorAuthor(link: Element): String {
        var node: Element? = link
        repeat(5) {
            node = node?.parent()
            val author = node?.selectFirst("a[href*=/author/], a[href*=/authors/]")
            if (author != null && author.text().isNotBlank()) return author.text().trim()
        }
        return ""
    }

    private fun ancestorCover(link: Element, base: String): String? {
        var node: Element? = link
        repeat(4) {
            node = node?.parent()
            val img = node?.selectFirst("img[data-src], img[src]")
            val path = img?.attr("data-src")?.ifBlank { img.attr("src") }.orEmpty()
            if (path.isNotBlank()) return abs(base, path)
        }
        return null
    }

    private fun entries(provider: Provider, url: String, html: String, authorHint: String?, q: String): List<OpdsEntry> {
        val doc = Jsoup.parse(html, url)
        val pageAuthor = authorHint?.takeIf {
            doc.selectFirst("h1")?.text()?.let { title -> BookSearch.matches(it, title) } == true
        }
        val out = LinkedHashMap<String, OpdsEntry>()
        for (link in doc.select("a[href]")) {
            val bookUrl = abs(url, link.attr("href")) ?: continue
            if (!matchesHost(bookUrl, provider.host) || !isBookUrl(provider, bookUrl)) continue
            val rawTitle = link.text().trim().replace(Regex("""\\s+"""), " ")
            if (rawTitle.length !in 3..180) continue
            val author = pageAuthor ?: ancestorAuthor(link)
            if (!BookSearch.matches(q, rawTitle, author)) continue
            val title = rawTitle.substringBeforeLast(" - ", rawTitle).ifBlank { rawTitle }
            if (title.length < 3) continue
            val key = bookUrl.substringBefore('?').trimEnd('/')
            out.putIfAbsent(key, OpdsEntry(
                id = "audio:$bookUrl",
                title = title,
                authors = listOf(author).filter { it.isNotBlank() },
                summary = "Источник: ${provider.title}. Наличие файлов проверяется на странице книги.",
                coverUrl = ancestorCover(link, url),
                navUrl = null,
                genres = listOf("Аудиокнига", provider.title),
                series = "",
                seriesIndex = 0f,
                isBook = true,
                related = emptyList(),
            ))
            if (out.size >= 60) break
        }
        return out.values.toList()
    }

    /** Один неработающий сервер не останавливает остальные источники. */
    suspend fun search(provider: Provider, query: String): List<OpdsEntry> = withContext(Dispatchers.IO) {
        val all = LinkedHashMap<String, OpdsEntry>()
        for ((url, authorHint) in addresses(provider, query)) {
            try {
                val found = entries(provider, url, read(url), authorHint, query)
                found.forEach { all[it.id] = it }
                // Авторская страница KnigaHub подтверждена; не перебираем догадки, если она дала результаты.
                if (found.isNotEmpty()) break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Перебираем альтернативные URL поиска конкретного сервиса.
            }
        }
        all.values.toList()
    }

    fun bookPage(entry: OpdsEntry): String? = entry.id.takeIf { it.startsWith("audio:https://") }
        ?.removePrefix("audio:")

    fun sourceName(entry: OpdsEntry): String? =
        bookPage(entry)?.let { url -> providers.firstOrNull { matchesHost(url, it.host) }?.title }

    /** Публичные прямые ссылки на файлы (без извлечения закрытых плеерных потоков). */
    suspend fun directFiles(entry: OpdsEntry): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val page = bookPage(entry) ?: return@withContext emptyList()
        try {
            val doc = Jsoup.parse(read(page), page)
            doc.select("a[href]").mapNotNull { link ->
                val target = abs(page, link.attr("href")) ?: return@mapNotNull null
                val clean = target.substringBefore('?').lowercase()
                val mime = when {
                    clean.endsWith(".mp3") -> "audio/mpeg"
                    clean.endsWith(".m4b") || clean.endsWith(".m4a") -> "audio/mp4"
                    clean.endsWith(".ogg") -> "audio/ogg"
                    clean.endsWith(".zip") -> "application/zip"
                    else -> null
                }
                mime?.let { it to target }
            }.distinctBy { it.second }.take(10)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Подтверждённый на сайте Aknigi24 путь к странице скачивания. */
    fun downloadPage(entry: OpdsEntry): String? {
        val url = bookPage(entry) ?: return null
        return if (matchesHost(url, "aknigi24.com")) url.trimEnd('/') + "/download" else url
    }
}
