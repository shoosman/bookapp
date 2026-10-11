package com.shoosman.bookapp.sync

import com.shoosman.bookapp.data.AnchorEntity
import com.shoosman.bookapp.data.TrackEntity
import com.shoosman.bookapp.text.BookContent
import com.shoosman.bookapp.text.ParaKind

/**
 * Соответствие «время в аудио ↔ место в тексте».
 *
 * Строится из опорных точек и интерполирует между ними. Опоры:
 *  - начало и конец книги;
 *  - начала глав ↔ начала аудиофайлов или глав, зашитых в аудио (если их число совпадает);
 *  - точки, которые пользователь поставил вручную (они важнее автоматических).
 *
 * Интерполяция идёт не по символам, а по «весу» текста: чтец делает паузы между абзацами,
 * перед заголовками и на знаках препинания. Без этого в тексте с короткими абзацами
 * (диалоги) подсветка убегает вперёд.
 */
class SyncMapper private constructor(
    private val c: BookContent,
    private val weightStart: LongArray,
    private val points: List<Point>,
    private val totalMs: Long,
) {
    private class Point(val ms: Long, val weight: Long)

    fun charAt(ms: Long): Int = weightToChar(interpolate(ms))

    fun msAt(char: Int): Long {
        if (points.size < 2) return 0
        val w = charToWeight(char)
        var i = points.indexOfLast { it.weight <= w }
        i = i.coerceIn(0, points.size - 2)
        val a = points[i]
        val b = points[i + 1]
        if (b.weight == a.weight) return a.ms
        val t = ((w - a.weight).toDouble() / (b.weight - a.weight)).coerceIn(0.0, 1.0)
        return (a.ms + (b.ms - a.ms) * t).toLong().coerceIn(0, totalMs)
    }

    private fun interpolate(ms: Long): Long {
        if (points.size < 2) return 0
        var i = points.indexOfLast { it.ms <= ms }
        i = i.coerceIn(0, points.size - 2)
        val a = points[i]
        val b = points[i + 1]
        if (b.ms == a.ms) return a.weight
        val t = ((ms - a.ms).toDouble() / (b.ms - a.ms)).coerceIn(0.0, 1.0)
        return (a.weight + (b.weight - a.weight) * t).toLong()
    }

    private fun charToWeight(char: Int): Long {
        if (c.paragraphs.isEmpty()) return 0
        val i = c.paragraphAt(char)
        val p = c.paragraphs[i]
        val offset = (char - p.start).coerceIn(0, p.text.length)
        return weightStart[i] + localWeight(p.text, offset)
    }

    private fun weightToChar(w: Long): Int {
        if (c.paragraphs.isEmpty()) return 0
        var lo = 0
        var hi = c.paragraphs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (weightStart[mid] <= w) lo = mid else hi = mid - 1
        }
        val p = c.paragraphs[lo]
        val rest = w - weightStart[lo]
        if (rest <= 0) return p.start
        // Идём по абзацу, пока не наберём нужный вес.
        var acc = 0L
        for (k in p.text.indices) {
            acc += charWeight(p.text, k)
            if (acc >= rest) return p.start + k
        }
        return p.start + p.text.length
    }

    companion object {
        /** Пауза перед абзацем, в «символах» речи (≈15 символов в секунду). */
        private fun pauseBefore(kind: ParaKind): Long = when (kind) {
            ParaKind.TITLE -> 45
            ParaKind.SUBTITLE -> 30
            ParaKind.POEM -> 6
            else -> 12
        }

        private fun charWeight(s: String, k: Int): Long {
            val ch = s[k]
            val next = if (k + 1 < s.length) s[k + 1] else ' '
            return when {
                (ch == '.' || ch == '!' || ch == '?' || ch == '…') && next == ' ' -> 6
                ch == ',' || ch == ';' || ch == ':' -> 2
                ch == '—' || ch == '–' -> 3
                else -> 1
            }
        }

        private fun localWeight(s: String, upTo: Int): Long {
            var w = 0L
            for (k in 0 until upTo.coerceAtMost(s.length)) w += charWeight(s, k)
            return w
        }

        fun build(
            content: BookContent,
            tracks: List<TrackEntity>,
            anchors: List<AnchorEntity>,
            /** Начала глав, зашитых в аудио (мс от начала книги). */
            audioMarks: List<Long> = emptyList(),
        ): SyncMapper {
            val totalMs = tracks.sumOf { it.durationMs }
            val ps = content.paragraphs
            val weightStart = LongArray(ps.size)
            var w = 0L
            for (i in ps.indices) {
                w += pauseBefore(ps[i].kind)
                weightStart[i] = w
                w += localWeight(ps[i].text, ps[i].text.length)
            }
            val totalWeight = w

            fun weightOf(char: Int): Long {
                if (ps.isEmpty()) return 0
                val i = content.paragraphAt(char)
                return weightStart[i] + localWeight(ps[i].text, (char - ps[i].start).coerceIn(0, ps[i].text.length))
            }

            val result = ArrayList<Point>()
            // Сначала ручные точки: по времени, отбрасывая противоречащие более ранним.
            for (a in anchors.sortedBy { it.audioMs }) insert(result, Point(a.audioMs, weightOf(a.charPos)))
            // Затем автоматические.
            insert(result, Point(0, 0))
            insert(result, Point(totalMs, totalWeight))
            for ((ms, char) in chapterPoints(content, tracks, audioMarks)) insert(result, Point(ms, weightOf(char)))

            return SyncMapper(content, weightStart, result, totalMs)
        }

        private fun insert(list: MutableList<Point>, p: Point) {
            if (list.any { it.ms == p.ms }) return
            val idx = list.indexOfFirst { it.ms > p.ms }.let { if (it < 0) list.size else it }
            val prev = list.getOrNull(idx - 1)
            val next = list.getOrNull(idx)
            if (prev != null && p.weight < prev.weight) return
            if (next != null && p.weight > next.weight) return
            list.add(idx, p)
        }

        /**
         * Пары «время ↔ символ» для начал глав. Сначала пробуем главы, зашитые в аудио,
         * потом начала аудиофайлов — если их количество совпадает с главами текста.
         */
        private fun chapterPoints(content: BookContent, tracks: List<TrackEntity>, audioMarks: List<Long>): List<Pair<Long, Int>> {
            val all = content.chapters
            if (all.isEmpty()) return emptyList()
            val minDepth = all.minOf { it.depth }
            val top = all.filter { it.depth == minDepth }
            val leaves = all.filterIndexed { i, c -> i == all.lastIndex || all[i + 1].depth <= c.depth }

            val starts = LongArray(tracks.size)
            for (i in 1 until tracks.size) starts[i] = starts[i - 1] + tracks[i - 1].durationMs

            for (marks in listOf(audioMarks, starts.toList())) {
                if (marks.size < 2) continue
                for (candidate in listOf(all, leaves, top)) {
                    if (candidate.size == marks.size) {
                        return candidate.mapIndexed { i, c -> marks[i] to c.start }
                    }
                    // Частый случай: первая часть — вступление от издательства.
                    if (candidate.size == marks.size - 1) {
                        return candidate.mapIndexed { i, c -> marks[i + 1] to c.start }
                    }
                }
            }
            return emptyList()
        }
    }
}
