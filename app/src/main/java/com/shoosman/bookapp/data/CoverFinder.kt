package com.shoosman.bookapp.data

import com.shoosman.bookapp.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Поиск обложки по названию и автору: сначала Литрес (у него открытый поиск), потом Author.Today.
 * Возвращает картинку и ссылку на страницу книги.
 */
object CoverFinder {

    class Found(val cover: ByteArray, val pageUrl: String, val source: String)

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    fun find(title: String, author: String): Found? {
        val queries = titleVariants(title)
        val surname = surname(author)
        for (q in queries) {
            runCatching { litres(q, surname) }.getOrNull()?.let { return it }
        }
        for (q in queries) {
            runCatching { authorToday(q, surname) }.getOrNull()?.let { return it }
        }
        return null
    }

    // ---------------- Литрес ----------------

    /** id книги на Литресе по названию и автору. */
    fun litresId(title: String, author: String): Long? {
        val surname = surname(author)
        for (q in titleVariants(title)) {
            litresMatch(q, surname)?.let { return it.optLong("id").takeIf { id -> id > 0 } }
        }
        return null
    }

    /** Обложка только с Author.Today (когда на Литресе книги нет). */
    fun fromAuthorToday(title: String, author: String): Found? {
        val surname = surname(author)
        for (q in titleVariants(title)) runCatching { authorToday(q, surname) }.getOrNull()?.let { return it }
        return null
    }

    /** Страница книги на Литресе, если она там есть (для «Где купить»). */
    fun litresPage(title: String, author: String): String? {
        val surname = surname(author)
        for (q in titleVariants(title)) {
            litresMatch(q, surname)?.let { return "https://www.litres.ru" + it.optString("url") }
        }
        return null
    }

    private fun litres(query: String, surname: String): Found? {
        val best = litresMatch(query, surname) ?: return null
        val id = best.optLong("id")
        val coverPath = best.optString("cover_url").ifBlank { return null }
        // Крупная версия обложки; если её нет — та, что пришла в ответе.
        val bytes = download("https://cdn.litres.ru/pub/c/cover_415/$id.jpg")
            ?: download("https://cdn.litres.ru$coverPath")
            ?: return null
        return Found(bytes, "https://www.litres.ru" + best.optString("url"), "Литрес")
    }

    private fun litresMatch(query: String, surname: String): JSONObject? {
        val url = "https://api.litres.ru/foundation/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", listOf(query, surname).filter { it.isNotBlank() }.joinToString(" "))
            .addQueryParameter("types", "text_book")
            .addQueryParameter("types", "audiobook")
            .addQueryParameter("limit", "20")
            .build()
        val json = JSONObject(get(url.toString()) ?: return null)
        val data = json.optJSONObject("payload")?.optJSONArray("data") ?: return null
        val candidates = ArrayList<Pair<Int, JSONObject>>()
        for (i in 0 until data.length()) {
            val inst = data.optJSONObject(i)?.optJSONObject("instance") ?: continue
            val persons = inst.optJSONArray("persons")
            val authors = buildList {
                if (persons != null) for (j in 0 until persons.length()) {
                    val p = persons.optJSONObject(j) ?: continue
                    if (p.optString("role") == "author") add(p.optString("full_name"))
                }
            }.joinToString(" ")
            if (surname.isNotBlank() && !norm(authors).contains(surname)) continue
            val score = titleScore(query, inst.optString("title"))
            if (score > 0) candidates.add(score to inst)
        }
        return candidates.maxByOrNull { it.first }?.second
    }

    // ---------------- Author.Today ----------------

    private fun authorToday(query: String, surname: String): Found? {
        val url = "https://author.today/search".toHttpUrl().newBuilder()
            .addQueryParameter("category", "works")
            .addQueryParameter("q", query)
            .build()
        val html = get(url.toString()) ?: return null
        val doc = Jsoup.parse(html, "https://author.today/")
        // Вёрстка меняется, поэтому ищем по смыслу: ссылка на /work/ID, рядом обложка и автор.
        var best: Triple<Int, String, String>? = null
        for (a in doc.select("a[href*=/work/]")) {
            val href = a.absUrl("href")
            if (!Regex("""/work/\d+$""").containsMatchIn(href)) continue
            val text = a.text().ifBlank { a.attr("title") }
            if (text.isBlank()) continue
            val score = titleScore(query, text)
            if (score <= 0) continue
            var box = a.parent()
            var img: String? = null
            var authorOk = surname.isBlank()
            repeat(6) {
                val b = box ?: return@repeat
                if (img == null) {
                    img = b.select("img").map { it.absUrl("data-src").ifBlank { it.absUrl("src") } }
                        .firstOrNull { it.contains("cm.author.today") }
                }
                if (!authorOk && norm(b.text()).contains(surname)) authorOk = true
                if (img != null && authorOk) return@repeat
                box = b.parent()
            }
            val cover = img ?: continue
            if (!authorOk) continue
            if (best == null || score > best!!.first) best = Triple(score, cover, href)
        }
        val (_, coverUrl, page) = best ?: return null
        val full = coverUrl.substringBefore('?')
        val bytes = download(full) ?: download(coverUrl) ?: return null
        return Found(bytes, page, "Author.Today")
    }

    // ---------------- общее ----------------

    /** Названия для поиска: как есть, без пояснений в скобках, и альтернативное «в кавычках». */
    private fun titleVariants(title: String): List<String> {
        val out = LinkedHashSet<String>()
        val base = title.split('(', '«').first().trim().trimEnd('.', ',', ':', '!', '?')
        if (base.isNotBlank()) out.add(base)
        Regex("""«([^»]+)»""").find(title)?.let { out.add(it.groupValues[1].trim()) }
        out.add(title.trim())
        return out.toList()
    }

    /** >0 — подходит. Точное совпадение лучше, «Том 1»/«Книга 1» лучше остальных томов. */
    private fun titleScore(query: String, found: String): Int {
        val q = norm(query)
        val f = norm(found)
        if (q.isEmpty() || f.isEmpty()) return 0
        return when {
            f == q -> 100
            f.startsWith("$q ") -> 60 + if ((" " + f.removePrefix(q).trim() + " ").contains(" 1 ")) 20 else 0
            q.startsWith("$f ") -> 40
            f.contains(q) && q.length >= 8 -> 30
            else -> 0
        }
    }

    fun norm(s: String): String =
        s.lowercase().replace('ё', 'е').replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    private fun surname(author: String): String =
        norm(author.substringBefore(',').substringBefore(" и др")).split(' ').lastOrNull { it.length > 1 }.orEmpty()

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "ru-RU,ru;q=0.9").build()
        return Http.client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    fun download(url: String): ByteArray? {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        return runCatching {
            Http.client.newCall(req).execute().use { r ->
                val type = r.body?.contentType()?.type
                if (!r.isSuccessful || (type != null && type != "image")) null
                else r.body?.bytes()?.takeIf { it.size > 2000 }
            }
        }.getOrNull()
    }
}
