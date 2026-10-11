package com.shoosman.bookapp.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.shoosman.bookapp.data.AppSettings
import com.shoosman.bookapp.text.BookContent
import com.shoosman.bookapp.text.ParaKind

/** Кусок абзаца на странице: символы [from, to). [hyphen] — слово переносится на следующую страницу. */
class Slice(val para: Int, val from: Int, val to: Int, val hyphen: Boolean)

class Page(val slices: List<Slice>)

/**
 * Раскладывает текст книги по страницам заданного размера.
 *
 * Книга делится на разделы (главы, длинные главы — на куски по [CHUNK] абзацев),
 * и страницы считаются по одному разделу, когда он понадобится. Так даже огромная
 * книга открывается сразу, а не после разметки всего текста.
 */
class Paginator(
    private val c: BookContent,
    private val s: AppSettings,
    private val widthPx: Int,
    private val heightPx: Int,
    private val density: Density,
    private val measurer: TextMeasurer,
) {
    val sections: List<IntRange> = buildSections()
    private val cache = HashMap<Int, List<Page>>()

    private fun buildSections(): List<IntRange> {
        val n = c.paragraphs.size
        if (n == 0) return listOf(0 until 0)
        val starts = sortedSetOf(0)
        c.chapters.forEach { if (it.paragraph in 0 until n) starts.add(it.paragraph) }
        val list = starts.toList()
        val result = ArrayList<IntRange>()
        for (i in list.indices) {
            val from = list[i]
            val to = if (i + 1 < list.size) list[i + 1] else n
            var a = from
            while (a < to) {
                val b = minOf(a + CHUNK, to)
                result.add(a until b)
                a = b
            }
        }
        return result
    }

    fun sectionOfParagraph(para: Int): Int {
        var lo = 0
        var hi = sections.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (sections[mid].first <= para) lo = mid else hi = mid - 1
        }
        return lo
    }

    @Synchronized
    fun pages(section: Int): List<Page> = cache.getOrPut(section) { layout(sections[section]) }

    /** Раздел и страница, на которой находится символ [char]. */
    fun locate(char: Int): Pair<Int, Int> {
        val para = c.paragraphAt(char)
        val section = sectionOfParagraph(para)
        val offset = char - (c.paragraphs.getOrNull(para)?.start ?: 0)
        val pages = pages(section)
        var index = 0
        for (i in pages.indices) {
            val first = pages[i].slices.firstOrNull() ?: continue
            if (first.para < para || (first.para == para && first.from <= offset)) index = i else break
        }
        return section to index
    }

    fun startChar(page: Page): Int {
        val first = page.slices.firstOrNull() ?: return 0
        return c.paragraphs[first.para].start + first.from
    }

    fun endChar(page: Page): Int {
        val last = page.slices.lastOrNull() ?: return 0
        return c.paragraphs[last.para].start + last.to
    }

    private fun layout(range: IntRange): List<Page> {
        val pages = ArrayList<Page>()
        var current = ArrayList<Slice>()
        var used = 0f

        fun flush() {
            if (current.isNotEmpty()) {
                pages.add(Page(current))
                current = ArrayList()
                used = 0f
            }
        }

        with(density) {
            for (pi in range) {
                val p = c.paragraphs[pi]
                val text = p.text
                var from = 0
                while (from < text.length) {
                    val look = paraLook(p.kind, s, Color.Unspecified, continuation = from > 0)
                    val top = look.top.toPx()
                    val bottom = look.bottom.toPx()
                    val width = (widthPx - look.start.toPx()).toInt().coerceAtLeast(1)
                    val measured = measurer.measure(
                        text.substring(from),
                        look.style,
                        constraints = Constraints(maxWidth = width),
                    )
                    val available = heightPx - used
                    if (top + measured.size.height + bottom <= available) {
                        current.add(Slice(pi, from, text.length, false))
                        used += top + measured.size.height + bottom
                        break
                    }

                    // Не влезает целиком: сколько строк поместится?
                    val space = available - top
                    var lines = 0
                    while (lines < measured.lineCount && measured.getLineBottom(lines) <= space) lines++

                    val isHeading = p.kind == ParaKind.TITLE || p.kind == ParaKind.SUBTITLE
                    // Заголовки не разрываем, и не оставляем одну сиротливую строку внизу страницы.
                    if ((isHeading || (lines == 1 && measured.lineCount > 2)) && current.isNotEmpty()) {
                        flush()
                        continue
                    }
                    if (lines == 0) {
                        if (current.isNotEmpty()) {
                            flush()
                            continue
                        }
                        lines = 1 // строка выше страницы — всё равно ставим
                    }
                    if (lines >= measured.lineCount) {
                        current.add(Slice(pi, from, text.length, false))
                        flush()
                        break
                    }

                    var end = from + measured.getLineEnd(lines - 1)
                    if (end <= from) end = from + 1
                    val hyphen = end < text.length && text[end - 1].isLetter() && text[end].isLetter()
                    current.add(Slice(pi, from, end, hyphen))
                    flush()
                    from = end
                    while (from < text.length && text[from] == ' ') from++
                }
            }
        }
        flush()
        if (pages.isEmpty()) pages.add(Page(emptyList()))
        return pages
    }

    companion object {
        private const val CHUNK = 120
    }
}
