package com.shoosman.bookapp.text

import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/** Разбор EPUB 2 и EPUB 3. */
object EpubParser {

    private class Item(val id: String, val path: String, val type: String, val props: String)

    /** Запись оглавления: файл, якорь внутри файла, заголовок, уровень. */
    private class TocEntry(val path: String, val fragment: String, val title: String, val depth: Int)

    fun parse(file: File): ParsedBook = ZipFile(file).use { zip ->
        fun read(path: String): ByteArray? =
            zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }

        val container = read("META-INF/container.xml")?.toString(Charsets.UTF_8)
            ?: error("Это не похоже на EPUB: нет META-INF/container.xml")
        val opfPath = Jsoup.parse(container, "", Parser.xmlParser())
            .getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
            ?: error("В EPUB не найден файл описания книги")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = Jsoup.parse(
            read(opfPath)?.toString(Charsets.UTF_8) ?: error("Не удалось прочитать $opfPath"),
            "", Parser.xmlParser(),
        )

        fun byTag(vararg names: String): List<Element> =
            names.flatMap { opf.getElementsByTag(it).toList() }

        val title = byTag("dc:title", "title").firstOrNull()?.text()?.trim().orEmpty()
        val author = byTag("dc:creator", "creator")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
        val description = byTag("dc:description", "description").firstOrNull()
            ?.let { Jsoup.parse(it.text()).text() }.orEmpty()

        val items = LinkedHashMap<String, Item>()
        for (el in byTag("item", "opf:item")) {
            val id = el.attr("id")
            items[id] = Item(id, resolve(opfDir, el.attr("href")), el.attr("media-type"), el.attr("properties"))
        }
        val spineEl = byTag("spine", "opf:spine").firstOrNull()
        val spine = byTag("itemref", "opf:itemref").mapNotNull { items[it.attr("idref")] }

        // --- оглавление ---
        val toc = ArrayList<TocEntry>()
        val navItem = items.values.firstOrNull { "nav" in it.props.split(' ') }
        val ncxItem = spineEl?.attr("toc")?.let { items[it] }
            ?: items.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
        if (navItem != null) {
            read(navItem.path)?.let { bytes ->
                val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, "")
                val nav = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: doc.selectFirst("nav")
                val base = navItem.path.substringBeforeLast('/', "")
                nav?.select("a[href]")?.forEach { a ->
                    val href = a.attr("href")
                    val depth = a.parents().count { it.normalName() == "ol" || it.normalName() == "ul" } - 1
                    toc.add(TocEntry(resolve(base, href.substringBefore('#')), href.substringAfter('#', ""), a.text(), depth.coerceAtLeast(0)))
                }
            }
        }
        if (toc.isEmpty() && ncxItem != null) {
            read(ncxItem.path)?.let { bytes ->
                val doc = Jsoup.parse(bytes.toString(Charsets.UTF_8), "", Parser.xmlParser())
                val base = ncxItem.path.substringBeforeLast('/', "")
                for (np in doc.getElementsByTag("navPoint")) {
                    val label = np.getElementsByTag("text").firstOrNull()?.text().orEmpty()
                    val src = np.getElementsByTag("content").firstOrNull()?.attr("src") ?: continue
                    val depth = np.parents().count { it.normalName().equals("navpoint", true) }
                    toc.add(TocEntry(resolve(base, src.substringBefore('#')), src.substringAfter('#', ""), label, depth))
                }
            }
        }

        // --- обложка ---
        val coverItem = items.values.firstOrNull { "cover-image" in it.props.split(' ') }
            ?: byTag("meta", "opf:meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")?.let { items[it] }
            ?: items.values.firstOrNull { it.type.startsWith("image") && it.id.contains("cover", true) }
        val cover = coverItem?.takeIf { it.type.startsWith("image") }?.let { read(it.path) }

        // --- текст ---
        val builder = ContentBuilder()
        val tocByPath = toc.groupBy { it.path }
        for (item in spine) {
            if (!item.type.contains("html") && !item.path.endsWith("html", true) && !item.path.endsWith("htm", true)) continue
            val bytes = read(item.path) ?: continue
            val doc: Document = Jsoup.parse(ByteArrayInputStream(bytes), null, "")
            val entries = tocByPath[item.path].orEmpty()
            val anchors = HashMap<String, Pair<String, Int>>()
            for (e in entries) {
                if (e.fragment.isEmpty()) {
                    builder.chapter(e.title, e.depth)
                } else {
                    anchors.putIfAbsent(e.fragment, e.title to e.depth)
                }
            }
            HtmlWalker(builder, anchors, headingsMakeChapters = toc.isEmpty()).walk(doc.body())
        }

        ParsedBook(title, author, description, cover, "EPUB", builder.build())
    }

    /** Склеивает относительный путь внутри архива и раскодирует %20 и подобное. */
    fun resolve(base: String, href: String): String {
        val decoded = Uri.decode(href)
        val parts = ArrayList<String>()
        if (!decoded.startsWith("/") && base.isNotEmpty()) parts.addAll(base.split('/'))
        for (seg in decoded.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }
}
