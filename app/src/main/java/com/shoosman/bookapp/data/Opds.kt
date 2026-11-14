package com.shoosman.bookapp.data

import android.content.Context
import com.shoosman.bookapp.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.json.JSONObject
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
    /** Файлы книги (тип → адрес), доступные в OPDS-каталоге. */
    val downloads: List<Pair<String, String>> = emptyList(),
)

/** Каталог OPDS из списка пользователя. */
data class OpdsCatalog(val name: String, val url: String) {
    val host: String get() = runCatching { java.net.URL(url).host.removePrefix("www.") }.getOrDefault("")
    val isFlibustaLike: Boolean get() = Regex("""flibusta|coollib""").containsMatchIn(host)
    val isLibriVox: Boolean get() = host == "librivox.org" && url.contains("/api/feed/audiobooks")
}

data class OpdsFeed(val title: String, val entries: List<OpdsEntry>, val nextUrl: String?)

/**
 * Каталог книг в формате OPDS (Флибуста и любые другие).
 * Читает ссылки на файлы, предоставленные каталогом.
 */
object Opds {
    private const val PREFS = "catalog"
    private const val KEY_LIST = "catalogs"
    const val DEFAULT_HOST = "flibusta.is"

    /** LibriVox использует собственный публичный JSON API с MP3 ZIP-архивами. */
    val libriVox = OpdsCatalog(
        "LibriVox · аудиокниги",
        "https://librivox.org/api/feed/audiobooks/?format=json&limit=50&sort_order=desc&coverart=1",
    )

    /** Готовые OPDS-каталоги; LibriVox больше не предлагается автоматически. */
    val presets = listOf(
        OpdsCatalog("Флибуста", "https://flibusta.is/opds"),
        OpdsCatalog("Project Gutenberg", "https://m.gutenberg.org/ebooks.opds/"),
    )

    fun catalogs(ctx: Context): List<OpdsCatalog> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getString(KEY_LIST, null)
        val saved = if (raw == null) {
            val host = p.getString("host", null)?.ifBlank { null } ?: DEFAULT_HOST
            listOf(OpdsCatalog("Флибуста", "https://$host/opds"))
        } else runCatching {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it).let { o ->
                OpdsCatalog(o.getString("name"), o.getString("url"))
            } }
        }.getOrDefault(emptyList())

        // Однократный откат автоматически добавленного LibriVox.
        // Не трогаем остальные OPDS-каталоги и настройки пользователя.
        if (!p.getBoolean("librivox-removed", false)) {
            val result = saved.filterNot { it.isLibriVox }
            if (result.size != saved.size) saveCatalogs(ctx, result)
            p.edit().putBoolean("librivox-removed", true).apply()
            return result
        }
        return saved
    }

    fun saveCatalogs(ctx: Context, list: List<OpdsCatalog>) {
        val a = org.json.JSONArray()
        list.forEach { a.put(org.json.JSONObject().put("name", it.name).put("url", it.url)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LIST, a.toString()).apply()
    }

    /** Адрес вида «flibusta.site» или полный — превращаем в адрес каталога. */
    fun normalizeUrl(input: String): String {
        var u = input.trim()
        if (!u.startsWith("http")) u = "https://$u"
        val path = runCatching { java.net.URL(u).path }.getOrDefault("")
        if ((path.isEmpty() || path == "/") && Regex("""flibusta|coollib""").containsMatchIn(u)) u = u.trimEnd('/') + "/opds"
        return u
    }

    private val searchTemplates = HashMap<String, String?>()
    private val noSearchTemplates = HashSet<String>()

    /** Шаблон поиска каталога ({searchTerms}); null — каталог поиск не поддерживает. */
    suspend fun searchTemplate(c: OpdsCatalog): String? = withContext(Dispatchers.IO) {
        synchronized(searchTemplates) {
            searchTemplates[c.url]?.let { return@withContext it }
            if (c.url in noSearchTemplates) return@withContext null
        }
        if (c.isLibriVox) {
            return@withContext c.url.substringBefore("&offset=")
                .substringBefore("&title=")
                .substringBefore("&author=") + "&title={searchTerms}"
        }
        if (c.isFlibustaLike) {
            val base = java.net.URL(c.url).let { "${it.protocol}://${it.host}" }
            return@withContext "$base/opds/search?searchType=books&searchTerm={searchTerms}".also { synchronized(searchTemplates) { searchTemplates[c.url] = it } }
        }
        val t = runCatching {
            val xml = fetch(c.url)
            val doc = Jsoup.parse(xml, c.url, Parser.xmlParser())
            val links = doc.select("feed > link[rel=search]")
            links.firstOrNull { it.attr("href").contains("{searchTerms}") }?.let { abs(c.url, it.attr("href")) }
                ?: links.firstOrNull { it.attr("type").contains("opensearchdescription") }?.let { l ->
                    val descUrl = abs(c.url, l.attr("href"))
                    val desc = Jsoup.parse(fetch(descUrl), descUrl, Parser.xmlParser())
                    val urls = desc.select("Url")
                    (urls.firstOrNull { it.attr("type").contains("atom") } ?: urls.firstOrNull())?.attr("template")?.let { abs(descUrl, it) }
                }
        }.getOrNull()
        synchronized(searchTemplates) {
            if (t != null) searchTemplates[c.url] = t else noSearchTemplates.add(c.url)
        }
        t
    }

    fun searchUrl(template: String, query: String, authors: Boolean = false): String {
        val t = if (authors) template.replace("searchType=books", "searchType=authors") else template
        return t.replace("{searchTerms}", URLEncoder.encode(query.trim(), "UTF-8"))
            .replace(Regex("""\{[^}]+\?\}"""), "")
    }

    /**
     * Ищем по названию и автору. В Flibusta поиск автора выдаёт навигационные
     * записи, поэтому заходим в подходящие записи и забираем реальные книги.
     */
    suspend fun searchBooks(c: OpdsCatalog, query: String): List<OpdsEntry> = supervisorScope {
        val t = searchTemplate(c) ?: return@supervisorScope emptyList()
        val q = query.trim()
        if (q.isEmpty()) return@supervisorScope emptyList()
        val parts = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        val reverse = parts.reversed().joinToString(" ")
        val urls = when {
            c.isLibriVox -> {
                val base = c.url.substringBefore("&offset=").substringBefore("&title=").substringBefore("&author=")
                val names = listOfNotNull(parts.firstOrNull(), parts.lastOrNull()).distinct()
                listOf(searchUrl(t, q)) + names.map { name ->
                    "$base&author=" + URLEncoder.encode(name, "UTF-8")
                }
            }
            c.isFlibustaLike -> listOf(searchUrl(t, q)) +
                listOf(q, reverse).distinct().map { searchUrl(t, it, authors = true) }
            else -> listOf(searchUrl(t, q))
        }.distinct()

        val results = urls.map { url ->
            async(Dispatchers.IO) {
                try {
                    val entries = load(url).entries
                    if (!c.isFlibustaLike || !url.contains("searchType=authors")) {
                        entries.filter { it.isBook }
                    } else {
                        val matches = entries.filter { entry ->
                            !entry.isBook && entry.navUrl != null && BookSearch.matches(q, entry.title)
                        }.take(4)
                        val direct = entries.filter { it.isBook }
                        direct + matches.flatMap { entry ->
                            try {
                                val authorsPage = load(entry.navUrl!!).entries
                                authorsPage.filter { it.isBook }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
        results.distinctBy { it.id.ifBlank { it.title + "|" + it.authors.joinToString() } }
    }

    private fun isLibriVoxUrl(url: String): Boolean =
        runCatching {
            val u = java.net.URL(url)
            u.host.removePrefix("www.") == "librivox.org" && u.path.contains("/api/feed/audiobooks")
        }.getOrDefault(false)

    private fun parseLibriVox(json: String, url: String): OpdsFeed {
        val books = JSONObject(json).optJSONArray("books") ?: org.json.JSONArray()
        val entries = (0 until books.length()).map { index ->
            val b = books.getJSONObject(index)
            val people = b.optJSONArray("authors") ?: org.json.JSONArray()
            val authors = (0 until people.length()).map { j ->
                val a = people.getJSONObject(j)
                listOf(a.optString("first_name"), a.optString("last_name"))
                    .filter { it.isNotBlank() }.joinToString(" ").trim()
            }.filter { it.isNotEmpty() }
            val zip = b.optString("url_zip_file")
            val language = b.optString("language")
            OpdsEntry(
                id = "librivox:" + b.optString("id"),
                title = b.optString("title"),
                authors = authors,
                summary = b.optString("description"),
                coverUrl = b.optString("coverart_thumbnail").takeIf { it.startsWith("http") }
                    ?: b.optString("coverart_jpg").takeIf { it.startsWith("http") },
                navUrl = null,
                genres = listOf("Аудиокнига", language).filter { it.isNotBlank() },
                series = "",
                seriesIndex = 0f,
                isBook = true,
                related = emptyList(),
                downloads = if (zip.startsWith("https://") || zip.startsWith("http://"))
                    listOf("application/zip" to zip) else emptyList(),
            )
        }
        val limit = Regex("""[?&]limit=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 50
        val offset = Regex("""[?&]offset=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val nextOffset = offset + limit
        val next = if (books.length() >= limit) {
            if (url.contains(Regex("""[?&]offset=\d+""")))
                url.replace(Regex("""([?&])offset=\d+""")) { m -> "${m.groupValues[1]}offset=$nextOffset" }
            else url + "&offset=$nextOffset"
        } else null
        return OpdsFeed("LibriVox · аудиокниги", entries, next)
    }

    private fun fetch(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Chtets/1.0 (OPDS)")
            .header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.5")
            .build()
        return Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("Каталог ответил ${r.code}. Если сайт недоступен, укажите другое зеркало.")
            r.body?.string().orEmpty()
        }
    }

    suspend fun load(url: String, forceRefresh: Boolean = false): OpdsFeed = withContext(Dispatchers.IO) {
        val xml = fetch(url)
        if (isLibriVoxUrl(url)) return@withContext parseLibriVox(xml, url)
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
            downloads = links.filter { it.attr("rel").startsWith("http://opds-spec.org/acquisition") }
                .map { it.attr("type") to abs(base, it.attr("href")) },
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
