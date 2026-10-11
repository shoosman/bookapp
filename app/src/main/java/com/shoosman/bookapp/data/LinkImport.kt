package com.shoosman.bookapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.net.URL

/** Данные книги, найденные по ссылке или введённые вручную. */
data class BookInfo(
    val title: String = "",
    val author: String = "",
    val annotation: String = "",
    val url: String = "",
    val coverUrl: String = "",
    val cover: ByteArray? = null,
    val series: String = "",
    val seriesIndex: Float = 0f,
)

/**
 * Достаёт название, автора, обложку и аннотацию со страницы книги.
 *
 * Порядок источников: разметка schema.org (JSON-LD и microdata) → OpenGraph и мета-теги →
 * заголовок страницы. Поверх — поправки для Литреса, Author.Today, Яндекс Книг, LiveLib,
 * Фантлаба, Лабиринта и похожих сайтов. Всё найденное потом можно поправить руками.
 */
object LinkImport {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    /** Вытащить первую ссылку из текста (когда делятся из браузера, там бывает «Название https://…»). */
    fun extractUrl(text: String): String? =
        Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', '»', '"')

    suspend fun fetch(rawUrl: String): BookInfo = withContext(Dispatchers.IO) {
        val url = extractUrl(rawUrl) ?: rawUrl.trim().let { if (it.startsWith("http")) it else "https://$it" }
        val doc = Jsoup.connect(url)
            .userAgent(UA)
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.5")
            .timeout(20_000)
            .followRedirects(true)
            .maxBodySize(5 shl 20)
            .get()
        val host = runCatching { URL(doc.location()).host.removePrefix("www.").lowercase() }.getOrDefault("")

        var info = fromJsonLd(doc) ?: BookInfo()
        info = info.fillFrom(fromMicrodata(doc))
        info = info.fillFrom(fromMeta(doc))
        info = siteFixes(host, doc, info)
        info = info.copy(
            title = cleanTitle(info.title, info.author),
            author = cleanAuthor(info.author),
            annotation = info.annotation.trim().take(4000),
            url = doc.location(),
            coverUrl = info.coverUrl.let { if (it.isNotBlank()) absolute(doc, it) else it },
        )
        val cover = info.coverUrl.takeIf { it.isNotBlank() }?.let { runCatching { download(it, url) }.getOrNull() }
        if (info.title.isBlank()) error("Не удалось найти название книги на этой странице. Заполните поля вручную.")
        info.copy(cover = cover)
    }

    private fun BookInfo.fillFrom(other: BookInfo?): BookInfo {
        if (other == null) return this
        return copy(
            title = title.ifBlank { other.title },
            author = author.ifBlank { other.author },
            annotation = annotation.ifBlank { other.annotation },
            coverUrl = coverUrl.ifBlank { other.coverUrl },
        )
    }

    // ---------------- schema.org ----------------

    private fun fromJsonLd(doc: Document): BookInfo? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val raw = script.data().trim()
            val nodes = runCatching {
                if (raw.startsWith("[")) JSONArray(raw).let { a -> (0 until a.length()).map { a.get(it) } }
                else listOf(JSONObject(raw))
            }.getOrNull() ?: continue
            val flat = ArrayList<JSONObject>()
            fun collect(o: Any?) {
                when (o) {
                    is JSONObject -> {
                        flat.add(o)
                        o.optJSONArray("@graph")?.let { g -> for (i in 0 until g.length()) collect(g.get(i)) }
                        o.optJSONObject("mainEntity")?.let { collect(it) }
                        o.optJSONObject("workExample")?.let { collect(it) }
                    }
                    is JSONArray -> for (i in 0 until o.length()) collect(o.get(i))
                }
            }
            nodes.forEach { collect(it) }
            val book = flat.firstOrNull { typeOf(it).any { t -> t == "book" || t == "audiobook" } }
                ?: flat.firstOrNull { typeOf(it).contains("product") && it.has("name") }
                ?: continue
            return BookInfo(
                title = book.optString("name"),
                author = names(book.opt("author")).ifBlank { names(book.opt("creator")) },
                annotation = Jsoup.parse(book.optString("description")).text(),
                coverUrl = imageOf(book.opt("image")),
            )
        }
        return null
    }

    private fun typeOf(o: JSONObject): List<String> = when (val t = o.opt("@type")) {
        is String -> listOf(t.lowercase())
        is JSONArray -> (0 until t.length()).map { t.optString(it).lowercase() }
        else -> emptyList()
    }

    private fun names(v: Any?): String = when (v) {
        is String -> v
        is JSONObject -> v.optString("name")
        is JSONArray -> (0 until v.length()).map { names(v.get(it)) }.filter { it.isNotBlank() }.joinToString(", ")
        else -> ""
    }

    private fun imageOf(v: Any?): String = when (v) {
        is String -> v
        is JSONObject -> v.optString("url").ifBlank { v.optString("contentUrl") }
        is JSONArray -> if (v.length() > 0) imageOf(v.get(0)) else ""
        else -> ""
    }

    private fun fromMicrodata(doc: Document): BookInfo? {
        val scope = doc.selectFirst("[itemtype*=schema.org/Book]") ?: return null
        fun prop(name: String) = scope.selectFirst("[itemprop=$name]")
        val img = prop("image")
        return BookInfo(
            title = prop("name")?.let { it.attr("content").ifBlank { it.text() } }.orEmpty(),
            author = scope.select("[itemprop=author]").joinToString(", ") { a ->
                a.selectFirst("[itemprop=name]")?.text() ?: a.attr("content").ifBlank { a.text() }
            },
            annotation = prop("description")?.let { it.attr("content").ifBlank { it.text() } }.orEmpty(),
            coverUrl = img?.let { it.attr("content").ifBlank { it.attr("src") } }.orEmpty(),
        )
    }

    // ---------------- мета-теги ----------------

    private fun fromMeta(doc: Document): BookInfo {
        fun meta(vararg keys: String): String {
            for (k in keys) {
                val v = doc.selectFirst("meta[property=$k], meta[name=$k]")?.attr("content")
                if (!v.isNullOrBlank()) return v
            }
            return ""
        }
        return BookInfo(
            title = meta("og:title", "twitter:title").ifBlank { doc.title() },
            author = meta("book:author", "author", "og:book:author"),
            annotation = meta("og:description", "description", "twitter:description"),
            coverUrl = meta("og:image", "og:image:url", "twitter:image"),
        )
    }

    // ---------------- поправки под сайты ----------------

    private fun siteFixes(host: String, doc: Document, info: BookInfo): BookInfo {
        fun text(sel: String) = doc.select(sel).joinToString(", ") { it.text().trim() }.trim()
        return when {
            host.endsWith("author.today") -> info.copy(
                title = text("h1.book-title, .book-meta-panel h1").ifBlank { info.title },
                author = text(".book-authors span[itemprop=author] a, .book-authors a").ifBlank { info.author },
                annotation = text(".annotation .rich-content").ifBlank { info.annotation },
            )
            host.endsWith("fantlab.ru") -> info.copy(
                title = text("h1 [itemprop=name], h1").ifBlank { info.title },
                author = text("[itemprop=author] [itemprop=name], [itemprop=author] a").ifBlank { info.author },
            )
            host.endsWith("litnet.com") || host.endsWith("litmarket.ru") || host.endsWith("rulate.ru") ||
                host.endsWith("ficbook.net") || host.endsWith("prodaman.ru") -> info.copy(
                title = text("h1").ifBlank { info.title },
                author = info.author.ifBlank { text("a[href*=/author], a[href*=/authors/], .author a, .book-author a") },
            )
            host.endsWith("labirint.ru") -> info.copy(
                author = info.author.ifBlank { text(".authors a, a[data-event-label=author]") },
            )
            host.endsWith("livelib.ru") -> info.copy(
                author = info.author.ifBlank { text("a.bc-author__link, .bc-author a") },
            )
            else -> info
        }
    }

    /** Сайты добавляют к названию автора и хвосты вида «— читать онлайн», «| Литрес». */
    private fun cleanTitle(raw: String, author: String): String {
        var t = raw.trim()
        val tails = listOf(
            " | ", " — читать", " - читать", " – читать", ", читать", " читать онлайн", " — скачать", " - скачать",
            " – скачать", " скачать", " — купить", " - купить", " – купить", " купить", " — Литрес", " — LiveLib",
            " - LiveLib", " — Яндекс", " (", " — отзывы", " - отзывы",
        )
        for (tail in tails) {
            val i = t.indexOf(tail, ignoreCase = true)
            if (i > 2) t = t.substring(0, i)
        }
        // «Автор — Название» или «Название — Автор»
        val parts = t.split(" — ", " – ", " - ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 2 && author.isNotBlank()) {
            val a = author.substringBefore(',').trim().lowercase()
            val last = a.split(' ').lastOrNull().orEmpty()
            when {
                parts[0].lowercase().contains(last) -> t = parts[1]
                parts[1].lowercase().contains(last) -> t = parts[0]
            }
        }
        return t.trim().trim('«', '»', '"').trim()
    }

    private fun cleanAuthor(raw: String): String =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(4).joinToString(", ")

    private fun absolute(doc: Document, url: String): String =
        if (url.startsWith("//")) "https:$url" else runCatching { URL(URL(doc.location()), url).toString() }.getOrDefault(url)

    private fun download(url: String, referer: String): ByteArray? {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", referer)
            }
            try {
                when (conn.responseCode) {
                    in 300..399 -> current = URL(URL(current), conn.getHeaderField("Location") ?: return null).toString()
                    200 -> {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        return bytes.takeIf { it.size in 500..(8 shl 20) }
                    }
                    else -> return null
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }
}
