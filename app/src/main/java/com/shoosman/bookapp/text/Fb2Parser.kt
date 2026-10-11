package com.shoosman.bookapp.text

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Разбор FictionBook 2 (FB2). */
object Fb2Parser {

    private val paragraphTags = setOf("p", "v", "subtitle", "text-author")

    fun parse(input: InputStream): ParsedBook {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setInput(input, null)

        val builder = ContentBuilder()
        val stack = ArrayList<String>()

        val title = StringBuilder()
        val authors = ArrayList<String>()
        val authorParts = HashMap<String, String>()
        val annotation = StringBuilder()
        var coverId: String? = null
        var coverBytes: ByteArray? = null
        var series = ""
        var seriesIndex = 0f

        var inTitleInfo = false
        var inBody = false
        var notesBody = false
        var sectionDepth = 0
        var current: StringBuilder? = null
        var titleLines: MutableList<String>? = null
        var binarySb: StringBuilder? = null

        fun contentAllowed() = inBody && !notesBody

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name
                        stack.add(name)
                        when (name) {
                            "title-info" -> inTitleInfo = true
                            "sequence" -> if (inTitleInfo && series.isEmpty()) {
                                series = parser.getAttributeValue(null, "name")?.trim().orEmpty()
                                seriesIndex = parser.getAttributeValue(null, "number")?.trim()?.replace(',', '.')?.toFloatOrNull() ?: 0f
                            }
                            "body" -> {
                                inBody = true
                                val bodyName = parser.getAttributeValue(null, "name")
                                notesBody = bodyName == "notes" || bodyName == "comments"
                            }
                            "section" -> if (contentAllowed()) sectionDepth++
                            "title" -> if (contentAllowed()) titleLines = ArrayList()
                            "image" -> if (inTitleInfo && "coverpage" in stack) {
                                coverId = hrefOf(parser)?.removePrefix("#")
                            }
                            "binary" -> {
                                val id = parser.getAttributeValue(null, "id")
                                if (id != null && id == coverId) binarySb = StringBuilder()
                            }
                            in paragraphTags -> {
                                val inAnnotation = inTitleInfo && "annotation" in stack
                                if (contentAllowed() || inAnnotation) current = StringBuilder()
                            }
                        }
                    }

                    XmlPullParser.TEXT -> {
                        val text = parser.text ?: ""
                        val top = stack.lastOrNull()
                        when {
                            current != null -> current!!.append(text)
                            binarySb != null -> binarySb!!.append(text)
                            inTitleInfo && top == "book-title" -> title.append(text)
                            inTitleInfo && "author" in stack &&
                                (top == "first-name" || top == "middle-name" || top == "last-name") ->
                                authorParts[top] = (authorParts[top] ?: "") + text
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val name = parser.name
                        when (name) {
                            in paragraphTags -> {
                                val sb = current
                                if (sb != null) {
                                    current = null
                                    val text = sb.toString()
                                    val lines = titleLines
                                    when {
                                        inTitleInfo && "annotation" in stack ->
                                            annotation.append(ContentBuilder.clean(text)).append('\n')
                                        lines != null -> lines.add(text)
                                        contentAllowed() -> builder.para(kindFor(name, stack), text)
                                    }
                                }
                            }
                            "title" -> {
                                val lines = titleLines
                                if (lines != null) {
                                    titleLines = null
                                    val cleaned = lines.map { ContentBuilder.clean(it) }.filter { it.isNotEmpty() }
                                    if (cleaned.isNotEmpty()) {
                                        builder.chapter(cleaned.joinToString(". "), (sectionDepth - 1).coerceAtLeast(0))
                                        cleaned.forEach { builder.para(ParaKind.TITLE, it) }
                                    }
                                }
                            }
                            "section" -> if (contentAllowed()) sectionDepth--
                            "author" -> if (inTitleInfo) {
                                val full = listOfNotNull(
                                    authorParts["first-name"], authorParts["middle-name"], authorParts["last-name"],
                                ).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                                if (full.isNotEmpty()) authors.add(full)
                                authorParts.clear()
                            }
                            "title-info" -> inTitleInfo = false
                            "body" -> {
                                inBody = false
                                notesBody = false
                            }
                            "binary" -> {
                                val sb = binarySb
                                if (sb != null) {
                                    binarySb = null
                                    coverBytes = runCatching {
                                        Base64.decode(sb.toString().replace(Regex("\\s"), ""), Base64.DEFAULT)
                                    }.getOrNull()
                                }
                            }
                        }
                        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // Повреждённый файл: если что-то уже прочитали — показываем хотя бы это.
            if (builder.paragraphCount == 0) throw e
        }

        return ParsedBook(
            title = ContentBuilder.clean(title.toString()),
            author = authors.distinct().joinToString(", "),
            annotation = annotation.toString().trim(),
            cover = coverBytes,
            format = "FB2",
            content = builder.build(),
            series = series,
            seriesIndex = seriesIndex,
        )
    }

    private fun hrefOf(parser: XmlPullParser): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).endsWith("href")) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun kindFor(tag: String, stack: List<String>): ParaKind = when {
        tag == "v" -> ParaKind.POEM
        tag == "subtitle" -> ParaKind.SUBTITLE
        tag == "text-author" -> ParaKind.AUTHOR
        "epigraph" in stack -> ParaKind.EPIGRAPH
        "cite" in stack -> ParaKind.EPIGRAPH
        else -> ParaKind.TEXT
    }
}
