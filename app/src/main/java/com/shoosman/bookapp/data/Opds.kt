package com.shoosman.bookapp.data

import android.content.Context
import com.shoosman.bookapp.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder

/** Запись каталога: раздел (куда можно зайти) или книга (только описание — без скачивания). */
data class OpdsEntry(
    val id: String,
    val title: String,
    val authors: List<String>,
    val summary: String,
    val coverUrl: String?,
    /** Куда ведёт раздел. У книги — null. */
    val navUrl: String?,
    val genres: List<String>,
    val series: String,
    val seriesIndex: Float,
    val isBook: Boolean,
    /** Ссылки «Все книги автора», «Книги серии» и т. п. */
    val related: List<Pair<String, String>>,
)

data class OpdsFeed(val title: String, val entries: List<OpdsEntry>, val nextUrl: String?)

/**
 * Каталог книг в формате OPDS (Флибуста и любые другие). Ссылки на скачивание файлов
 * намеренно не читаются: каталог — чтобы находить книги и покупать их в магазинах.
 */
object Opds {
    private const val PREFS = "catalog"
    private const val KEY_HOST = "host"
    const val DEFAULT_HOST = "flibusta.is"

    fun host(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST, DEFAULT_HOST)!!.ifBlank { DEFAULT_HOST }

    fun setHost(ctx: Context, host: String) {
        val clean = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HOST, clean).apply()
    }

    fun rootUrl(ctx: Context) = "https://${host(ctx)}/opds"

    fun searchUrl(ctx: Context, query: String, authors: Boolean = false): String =
        "https://${host(ctx)}/opds/search?searchType=${if (authors) "authors" else "books"}&searchTerm=" +
            URLEncoder.encode(query.trim(), "UTF-8")

    suspend fun load(url: String): OpdsFeed = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Chtets/1.0 (OPDS)")
            .header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.5")
            .build()
        val xml = Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("Каталог ответил ${r.code}. Если сайт недоступен, укажите другое зеркало.")
            r.body?.string().orEmpty()
        }
        if (!xml.trimStart().startsWith("<?xml") && !xml.contains("<feed")) {
            error("По этому адресу нет каталога OPDS. Проверьте адрес зеркала.")
        }
        parse(xml, url)
    }

    fun parse(xml: String, base: String): OpdsFeed {
        val doc = Jsoup.parse(xml, base, Parser.xmlParser())
        val feed = doc.selectFirst("feed") ?: error("Пустой ответ каталога")
        val title = feed.children().firstOrNull { it.tagName() == "title" }?.text().orEmpty()
        val next = feed.children().filter { it.tagName() == "link" && it.attr("rel") == "next" }
            .firstOrNull()?.let { abs(base, it.attr("href")) }
        val entries = feed.select("entry").map { entry(it, base) }
        return OpdsFeed(title, entries, next)
    }

    private fun entry(e: Element, base: String): OpdsEntry {
        val links = e.children().filter { it.tagName() == "link" }
        val isAcq = links.any { it.attr("rel").startsWith("http://opds-spec.org/acquisition") }
        val cover = links.firstOrNull { it.attr("rel") == "http://opds-spec.org/image" }
            ?: links.firstOrNull { it.attr("rel") == "http://opds-spec.org/image/thumbnail" || it.attr("rel") == "x-stanza-cover-image" }
        val nav = links.firstOrNull {
            val rel = it.attr("rel")
            val type = it.attr("type")
            type.contains("atom+xml") && (rel.isEmpty() || rel == "subsection" || rel == "alternate" || rel.startsWith("http://opds-spec.org/sort") || rel == "start")
        }
        val related = links.filter { it.attr("rel") == "related" && it.attr("type").contains("atom+xml") }
            .map { (it.attr("title").ifBlank { "Похожие" }) to abs(base, it.attr("href")) }
        val authors = e.children().filter { it.tagName() == "author" }
            .mapNotNull { a -> a.children().firstOrNull { it.tagName() == "name" }?.text()?.trim()?.takeIf { it.isNotEmpty() } }
        val genres = e.children().filter { it.tagName() == "category" }
            .map { it.attr("label").ifBlank { it.attr("term") } }.filter { it.isNotBlank() }
        val contentHtml = (e.children().firstOrNull { it.tagName() == "content" } ?: e.children().firstOrNull { it.tagName() == "summary" })
            ?.let { if (it.attr("type").contains("html")) it.text() else it.html() }.orEmpty()
        val text = Jsoup.parse(contentHtml.replace("<br>", "\n").replace("<br/>", "\n")).wholeText()
        val (series, idx) = seriesOf(text)
        val isBook = isAcq || (authors.isNotEmpty() && nav == null)
        return OpdsEntry(
            id = e.children().firstOrNull { it.tagName() == "id" }?.text().orEmpty(),
            title = e.children().firstOrNull { it.tagName() == "title" }?.text()?.trim().orEmpty(),
            authors = authors,
            summary = cleanSummary(text),
            coverUrl = cover?.attr("href")?.takeIf { it.isNotBlank() }?.let { abs(base, it) },
            navUrl = if (isBook) null else nav?.let { abs(base, it.attr("href")) },
            genres = genres,
            series = series,
            seriesIndex = idx,
            isBook = isBook,
            related = related,
        )
    }

    /** «Серия: Искажающие реальность #3» в описании книги. */
    private fun seriesOf(text: String): Pair<String, Float> {
        val m = Regex("""Серия:\s*([^\n#]+?)\s*(?:#\s*(\d+(?:[.,]\d+)?))?\s*(?:\n|$)""").find(text) ?: return "" to 0f
        return m.groupValues[1].trim() to (m.groupValues[2].replace(',', '.').toFloatOrNull() ?: 0f)
    }

    /** Из описания убираем служебные строки про формат, размер файла и т. п. */
    private fun cleanSummary(text: String): String =
        text.lines()
            .map { it.trim() }
            .filterNot { l ->
                l.startsWith("Формат:") || l.startsWith("Размер:") || l.startsWith("Язык:") ||
                    l.startsWith("Год издания:") || l.startsWith("Скачиваний") || l.startsWith("Серия:")
            }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    private fun abs(base: String, href: String): String =
        runCatching { java.net.URL(java.net.URL(base), href).toString() }.getOrDefault(href)
}
