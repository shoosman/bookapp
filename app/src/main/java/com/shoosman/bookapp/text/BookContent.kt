package com.shoosman.bookapp.text

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

enum class ParaKind { TITLE, SUBTITLE, TEXT, EPIGRAPH, POEM, AUTHOR }

/** Абзац книги. [start] — смещение первого символа от начала всей книги. */
class Paragraph(val kind: ParaKind, val text: String, val start: Int)

/** Глава: заголовок, уровень вложенности и номер абзаца, с которого она начинается. */
class Chapter(val title: String, val depth: Int, val paragraph: Int, val start: Int)

class BookContent(val paragraphs: List<Paragraph>, val chapters: List<Chapter>) {

    val totalChars: Int = paragraphs.lastOrNull()?.let { it.start + it.text.length } ?: 0

    /** Номер абзаца, в котором находится символ [charPos]. */
    fun paragraphAt(charPos: Int): Int {
        if (paragraphs.isEmpty()) return 0
        var lo = 0
        var hi = paragraphs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (paragraphs[mid].start <= charPos) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Номер главы, в которой находится символ [charPos], или -1. */
    fun chapterAt(charPos: Int): Int {
        var result = -1
        for (i in chapters.indices) {
            if (chapters[i].start <= charPos) result = i else break
        }
        return result
    }

    fun write(file: File) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(VERSION)
            out.writeInt(paragraphs.size)
            for (p in paragraphs) {
                out.writeByte(p.kind.ordinal)
                writeString(out, p.text)
            }
            out.writeInt(chapters.size)
            for (c in chapters) {
                writeString(out, c.title)
                out.writeInt(c.depth)
                out.writeInt(c.paragraph)
            }
        }
    }

    companion object {
        private const val VERSION = 1
        private val kinds = ParaKind.entries

        fun read(file: File): BookContent =
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
                val version = input.readInt()
                require(version == VERSION) { "Неизвестная версия файла книги" }
                val count = input.readInt()
                val paragraphs = ArrayList<Paragraph>(count)
                var pos = 0
                repeat(count) {
                    val kind = kinds[input.readByte().toInt()]
                    val text = readString(input)
                    paragraphs.add(Paragraph(kind, text, pos))
                    pos += text.length + 1
                }
                val chapterCount = input.readInt()
                val chapters = ArrayList<Chapter>(chapterCount)
                repeat(chapterCount) {
                    val title = readString(input)
                    val depth = input.readInt()
                    val para = input.readInt()
                    val start = paragraphs.getOrNull(para)?.start ?: pos
                    chapters.add(Chapter(title, depth, para, start))
                }
                BookContent(paragraphs, chapters)
            }

        private fun writeString(out: DataOutputStream, s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        private fun readString(input: DataInputStream): String {
            val size = input.readInt()
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}

/** Собирает текст книги абзац за абзацем, попутно считая смещения и главы. */
class ContentBuilder {
    private val paragraphs = ArrayList<Paragraph>()
    private val chapters = ArrayList<Chapter>()
    private var pos = 0

    val paragraphCount: Int get() = paragraphs.size

    fun chapter(title: String, depth: Int = 0) {
        val t = clean(title).ifBlank { "Глава ${chapters.size + 1}" }
        // Две главы подряд без текста между ними — оставляем последнюю.
        val last = chapters.lastOrNull()
        if (last != null && last.paragraph == paragraphs.size && last.depth >= depth) {
            chapters[chapters.size - 1] = Chapter(t, depth, paragraphs.size, pos)
            return
        }
        chapters.add(Chapter(t, depth, paragraphs.size, pos))
    }

    fun para(kind: ParaKind, raw: String) {
        val t = clean(raw)
        if (t.isEmpty()) return
        paragraphs.add(Paragraph(kind, t, pos))
        pos += t.length + 1
    }

    fun build(): BookContent {
        val valid = chapters.filter { it.paragraph < paragraphs.size }.toMutableList()
        if (valid.isEmpty() && paragraphs.isNotEmpty()) valid.add(Chapter("Начало", 0, 0, 0))
        return BookContent(paragraphs, valid)
    }

    companion object {
        private val spaces = Regex("[\\s\\u00A0\\u2007\\u202F]+")
        fun clean(s: String): String = s.replace(spaces, " ").trim()
    }
}

class ParsedBook(
    val title: String,
    val author: String,
    val annotation: String,
    val cover: ByteArray?,
    val format: String,
    val content: BookContent,
)
