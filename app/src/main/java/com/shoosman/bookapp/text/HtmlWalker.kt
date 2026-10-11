package com.shoosman.bookapp.text

import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Превращает HTML-документ в абзацы книги.
 *
 * @param anchors заголовки глав, которые начинаются у элементов с данным id (из оглавления EPUB)
 * @param headingsMakeChapters создавать главы по заголовкам h1–h3, если оглавления нет
 */
class HtmlWalker(
    private val builder: ContentBuilder,
    private val anchors: Map<String, Pair<String, Int>> = emptyMap(),
    private val headingsMakeChapters: Boolean = true,
) {
    private val usedAnchors = HashSet<String>()

    fun walk(root: Element) = walk(root, ParaKind.TEXT)

    private fun walk(el: Element, kind: ParaKind) {
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotBlank()) builder.para(kind, buf.toString())
            buf.setLength(0)
        }

        for (node in el.childNodes()) {
            when (node) {
                is TextNode -> buf.append(node.wholeText)
                is Element -> {
                    val tag = node.normalName()
                    val id = node.id()
                    if (id.isNotEmpty() && id !in usedAnchors) {
                        val anchor = anchors[id]
                        if (anchor != null) {
                            flush()
                            usedAnchors.add(id)
                            builder.chapter(anchor.first, anchor.second)
                        }
                    }
                    when {
                        tag in SKIP -> Unit
                        tag == "br" -> flush()
                        tag in HEADINGS -> {
                            flush()
                            heading(node, tag)
                        }
                        tag in BLOCKS -> {
                            flush()
                            walk(node, if (tag == "blockquote") ParaKind.EPIGRAPH else kind)
                        }
                        hasBlockInside(node) -> {
                            flush()
                            walk(node, kind)
                        }
                        else -> buf.append(node.wholeText())
                    }
                }
            }
        }
        flush()
    }

    private fun heading(node: Element, tag: String) {
        val text = ContentBuilder.clean(node.wholeText())
        if (text.isEmpty()) return
        if (headingsMakeChapters && tag in CHAPTER_HEADINGS) {
            builder.chapter(text, CHAPTER_HEADINGS.indexOf(tag))
        }
        builder.para(if (tag == "h1" || tag == "h2") ParaKind.TITLE else ParaKind.SUBTITLE, text)
    }

    private fun hasBlockInside(node: Element): Boolean =
        node.allElements.drop(1).any { it.normalName() in BLOCKS || it.normalName() in HEADINGS }

    companion object {
        private val SKIP = setOf("script", "style", "head", "title", "meta", "link", "img", "svg", "image", "audio", "video", "sup", "noscript")
        private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        private val CHAPTER_HEADINGS = listOf("h1", "h2", "h3")
        private val BLOCKS = setOf(
            "p", "div", "li", "ul", "ol", "blockquote", "pre", "dt", "dd", "dl",
            "table", "tbody", "thead", "tr", "td", "th", "section", "article", "header",
            "footer", "aside", "figure", "figcaption", "main", "center", "nav", "hr", "body",
        )
    }
}
