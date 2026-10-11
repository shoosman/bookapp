package com.shoosman.bookapp.text

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** Определяет формат файла и вызывает нужный разборщик. */
object BookParser {

    val unsupportedHint = "Пока поддерживаются FB2, FB2.ZIP, EPUB, TXT, DOCX и HTML. PDF, MOBI и другие форматы появятся в следующей версии."

    fun parse(file: File, displayName: String): ParsedBook {
        val name = displayName.lowercase()
        val head = ByteArray(1024)
        val headLen = file.inputStream().use { it.read(head) }.coerceAtLeast(0)
        val headText = String(head, 0, headLen, Charsets.ISO_8859_1)
        val isZip = headLen >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()

        val book = when {
            isZip -> parseZip(file, name)
            headText.contains("<FictionBook") || name.endsWith(".fb2") -> file.inputStream().buffered().use { Fb2Parser.parse(it) }
            name.endsWith(".pdf") || headText.startsWith("%PDF") -> error(unsupportedHint)
            name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xhtml") ||
                headText.contains("<html", ignoreCase = true) -> parseHtml(file.readBytes(), "HTML")
            name.endsWith(".txt") || looksLikeText(head, headLen) -> TxtParser.parse(decodeText(file.readBytes()))
            else -> error(unsupportedHint)
        }
        val fallbackTitle = displayName.substringBeforeLast('.').removeSuffix(".fb2")
        return ParsedBook(
            title = book.title.ifBlank { fallbackTitle },
            author = book.author,
            annotation = book.annotation,
            cover = book.cover,
            format = book.format,
            content = book.content,
        )
    }

    private fun parseZip(file: File, name: String): ParsedBook {
        val kind = ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            when {
                "META-INF/container.xml" in names -> "epub"
                "word/document.xml" in names -> "docx"
                names.any { it.lowercase().endsWith(".fb2") } -> "fb2"
                names.any { it.lowercase().endsWith(".epub") } -> "inner-epub"
                names.any { it.lowercase().endsWith(".txt") } -> "txt"
                else -> "unknown"
            }
        }
        return when (kind) {
            "epub" -> EpubParser.parse(file)
            "docx" -> ZipFile(file).use { zip -> DocxParser.parse(zip) }
            "fb2" -> ZipFile(file).use { zip ->
                val entry = zip.entries().asSequence().first { it.name.lowercase().endsWith(".fb2") }
                zip.getInputStream(entry).buffered().use { Fb2Parser.parse(it) }
            }
            "inner-epub" -> {
                val tmp = File(file.parentFile, "inner.epub")
                ZipFile(file).use { zip ->
                    val entry = zip.entries().asSequence().first { it.name.lowercase().endsWith(".epub") }
                    zip.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                }
                try { EpubParser.parse(tmp) } finally { tmp.delete() }
            }
            "txt" -> ZipFile(file).use { zip ->
                val entry = zip.entries().asSequence().first { it.name.lowercase().endsWith(".txt") }
                TxtParser.parse(decodeText(zip.getInputStream(entry).use { it.readBytes() }))
            }
            else -> error("В архиве не найдено книги. " + unsupportedHint)
        }.let { if (name.endsWith(".zip") && it.format == "FB2") it.withFormat("FB2.ZIP") else it }
    }

    private fun ParsedBook.withFormat(f: String) = ParsedBook(title, author, annotation, cover, f, content)

    private fun parseHtml(bytes: ByteArray, format: String): ParsedBook {
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, "")
        val builder = ContentBuilder()
        HtmlWalker(builder).walk(doc.body())
        return ParsedBook(doc.title(), "", "", null, format, builder.build())
    }

    private fun looksLikeText(head: ByteArray, len: Int): Boolean {
        if (len == 0) return false
        var bad = 0
        for (i in 0 until len) {
            val b = head[i].toInt() and 0xFF
            if (b < 0x09 || (b in 0x0E..0x1F)) bad++
        }
        return bad * 20 < len
    }

    /** UTF-8 (с BOM или без), UTF-16 или windows-1251 — самые частые кодировки русских TXT. */
    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1251"))
        }
    }
}

object TxtParser {
    private val chapterRegex = Regex(
        "^(глава|часть|пролог|эпилог|послесловие|предисловие|книга|chapter|part|prologue|epilogue)([\\s.:\\-—]|$).*",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): ParsedBook {
        val builder = ContentBuilder()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val nonBlank = lines.filter { it.isNotBlank() }
        val blank = lines.size - nonBlank.size
        val avgLen = if (nonBlank.isEmpty()) 0 else nonBlank.sumOf { it.length } / nonBlank.size
        // Текст с жёсткими переносами строк: абзацы разделены пустыми строками.
        val hardWrapped = nonBlank.size > 20 && blank * 8 > nonBlank.size && avgLen in 45..90

        fun emit(raw: String) {
            val t = raw.trim()
            if (t.isEmpty()) return
            if (t.length <= 80 && chapterRegex.matches(t)) {
                builder.chapter(t)
                builder.para(ParaKind.TITLE, t)
            } else {
                builder.para(ParaKind.TEXT, t)
            }
        }

        if (hardWrapped) {
            val sb = StringBuilder()
            for (line in lines) {
                if (line.isBlank()) {
                    emit(sb.toString()); sb.setLength(0)
                } else {
                    sb.append(line.trim()).append(' ')
                }
            }
            emit(sb.toString())
        } else {
            lines.forEach { emit(it) }
        }
        return ParsedBook("", "", "", null, "TXT", builder.build())
    }
}

object DocxParser {
    fun parse(zip: ZipFile): ParsedBook {
        val entry = zip.getEntry("word/document.xml") ?: error("Повреждённый DOCX")
        val xml = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val builder = ContentBuilder()
        for (p in doc.getElementsByTag("w:p")) {
            val text = p.getElementsByTag("w:t").joinToString("") { it.wholeText() }
            if (text.isBlank()) continue
            val style = p.getElementsByTag("w:pStyle").firstOrNull()?.attr("w:val").orEmpty().lowercase()
            val isHeading = style.startsWith("heading") || style.startsWith("title") || style.contains("заголов")
            if (isHeading) {
                val level = style.filter { it.isDigit() }.toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
                builder.chapter(text, level)
                builder.para(ParaKind.TITLE, text)
            } else {
                builder.para(ParaKind.TEXT, text)
            }
        }
        var title = ""
        var author = ""
        zip.getEntry("docProps/core.xml")?.let { core ->
            val coreDoc = Jsoup.parse(zip.getInputStream(core).use { it.readBytes().toString(Charsets.UTF_8) }, "", Parser.xmlParser())
            title = coreDoc.getElementsByTag("dc:title").firstOrNull()?.text().orEmpty()
            author = coreDoc.getElementsByTag("dc:creator").firstOrNull()?.text().orEmpty()
        }
        return ParsedBook(title, author, "", null, "DOCX", builder.build())
    }
}
