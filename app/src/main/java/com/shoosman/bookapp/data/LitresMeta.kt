package com.shoosman.bookapp.data

import com.shoosman.bookapp.net.Http
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** Описание книги с Литреса: жанры, цикл и номер тома, аннотация, обложка, тома цикла. */
object LitresMeta {

    class Art(
        val id: Long,
        val title: String,
        val authors: String,
        val pageUrl: String,
        val coverUrl: String,
        val genres: List<String>,
        val seriesId: Long,
        val seriesName: String,
        val seriesOrder: Float,
        val annotation: String,
        val isAudio: Boolean,
    )

    private const val API = "https://api.litres.ru/foundation/api"

    /** Служебные теги Литреса, которые жанрами не являются. */
    private val serviceTags = setOf("литрес авторы", "литрес: черновик", "черновик", "самиздат", "бестселлер", "новинка", "литрес")

    /** id книги из ссылки вида …/trinadcatyy-68829765/ */
    fun idFromUrl(url: String): Long? {
        if (!url.contains("litres.")) return null
        return Regex("""-(\d{5,})/?(?:[?#].*)?$""").find(url)?.groupValues?.get(1)?.toLongOrNull()
    }

    fun art(id: Long): Art? {
        val json = get("$API/arts/$id") ?: return null
        val data = JSONObject(json).optJSONObject("payload")?.optJSONObject("data") ?: return null
        return parseArt(data, full = true)
    }

    /** Все тома цикла: один на номер, текст предпочтительнее аудио. */
    fun seriesArts(seriesId: Long): List<Art> {
        val out = ArrayList<Art>()
        var url: String? = "$API/series/$seriesId/arts?limit=100"
        var pages = 0
        while (url != null && pages < 5) {
            val json = JSONObject(get(url) ?: break)
            val payload = json.optJSONObject("payload") ?: break
            val data = payload.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) data.optJSONObject(i)?.let { o -> parseArt(o, full = false)?.let { out.add(it) } }
            val next = payload.optJSONObject("pagination")?.optString("next_page")?.takeIf { it.isNotBlank() && it != "null" }
            url = next?.let { if (it.startsWith("http")) it else "https://api.litres.ru$it" }
            pages++
        }
        return out
            .groupBy { if (it.seriesOrder > 0f) it.seriesOrder.toString() else "t:" + CoverFinder.norm(it.title) }
            .map { (_, v) -> v.firstOrNull { !it.isAudio } ?: v.first() }
            .sortedWith(compareBy({ if (it.seriesOrder > 0f) it.seriesOrder else Float.MAX_VALUE }, { it.title }))
    }

    private fun parseArt(o: JSONObject, full: Boolean): Art? {
        val id = o.optLong("id").takeIf { it > 0 } ?: return null
        val persons = o.optJSONArray("persons") ?: JSONArray()
        val authors = (0 until persons.length()).mapNotNull { persons.optJSONObject(it) }
            .filter { it.optString("role") == "author" }.map { it.optString("full_name") }.distinct().joinToString(", ")
        val genres = ArrayList<String>()
        o.optJSONArray("genres")?.let { a ->
            val list = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            list.sortedByDescending { it.optBoolean("is_main") }.forEach { genres.add(it.optString("name")) }
        }
        o.optJSONArray("tags")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }
                .filter { it.isNotBlank() && it.lowercase() !in serviceTags }
                .forEach { genres.add(it) }
        }
        val series = o.optJSONArray("series")?.optJSONObject(0)
        val annotation = if (full) {
            o.optString("html_annotation").ifBlank { o.optString("html_annotation_litres") }
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { html -> Jsoup.parse(html.replace("</p>", "</p>\n")).wholeText().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n") }
                .orEmpty()
        } else ""
        val type = o.optInt("art_type", -1)
        val url = o.optString("url")
        return Art(
            id = id,
            title = o.optString("title"),
            authors = authors,
            pageUrl = if (url.startsWith("http")) url else "https://www.litres.ru$url",
            coverUrl = "https://cdn.litres.ru/pub/c/cover_415/$id.jpg",
            genres = genres.map { g -> g.replaceFirstChar { it.uppercase() } }.distinct().take(10),
            seriesId = series?.optLong("id") ?: 0L,
            seriesName = series?.optString("name").orEmpty().takeIf { it != "null" }.orEmpty(),
            seriesOrder = series?.optDouble("art_order", 0.0)?.takeIf { !it.isNaN() }?.toFloat() ?: 0f,
            annotation = annotation,
            isAudio = type == 1 || url.contains("/audiobook/"),
        )
    }

    private fun get(url: String): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/126.0 Mobile Safari/537.36")
            .header("Accept", "application/json")
            .build()
        return runCatching { Http.client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } }.getOrNull()
    }
}
