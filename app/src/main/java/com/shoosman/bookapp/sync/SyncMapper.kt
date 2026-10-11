package com.shoosman.bookapp.sync

import com.shoosman.bookapp.data.AnchorEntity
import com.shoosman.bookapp.data.TrackEntity
import com.shoosman.bookapp.text.BookContent

/**
 * Соответствие «время в аудио ↔ место в тексте».
 *
 * Строится из опорных точек и линейно интерполирует между ними:
 *  - начало и конец книги;
 *  - начала глав и начала аудиофайлов, если их количество совпадает;
 *  - точки, которые пользователь поставил вручную (они важнее автоматических).
 */
class SyncMapper private constructor(
    private val points: List<Point>,
    private val totalChars: Int,
    private val totalMs: Long,
) {
    data class Point(val ms: Long, val char: Int)

    val userPointCount: Int get() = points.size

    fun charAt(ms: Long): Int {
        if (points.size < 2) return 0
        var i = points.indexOfLast { it.ms <= ms }
        i = i.coerceIn(0, points.size - 2)
        val a = points[i]
        val b = points[i + 1]
        if (b.ms == a.ms) return a.char
        val t = ((ms - a.ms).toDouble() / (b.ms - a.ms)).coerceIn(0.0, 1.0)
        return (a.char + (b.char - a.char) * t).toInt().coerceIn(0, totalChars)
    }

    fun msAt(char: Int): Long {
        if (points.size < 2) return 0
        var i = points.indexOfLast { it.char <= char }
        i = i.coerceIn(0, points.size - 2)
        val a = points[i]
        val b = points[i + 1]
        if (b.char == a.char) return a.ms
        val t = ((char - a.char).toDouble() / (b.char - a.char)).coerceIn(0.0, 1.0)
        return (a.ms + (b.ms - a.ms) * t).toLong().coerceIn(0, totalMs)
    }

    companion object {
        fun build(content: BookContent, tracks: List<TrackEntity>, anchors: List<AnchorEntity>): SyncMapper {
            val totalMs = tracks.sumOf { it.durationMs }
            val totalChars = content.totalChars

            val result = ArrayList<Point>()
            // Сначала ручные точки: в порядке времени, отбрасывая противоречащие более ранним.
            for (a in anchors.sortedBy { it.audioMs }) {
                insertIfMonotone(result, Point(a.audioMs, a.charPos))
            }
            // Затем автоматические.
            insertIfMonotone(result, Point(0, 0))
            insertIfMonotone(result, Point(totalMs, totalChars))
            for (p in chapterTrackPoints(content, tracks)) insertIfMonotone(result, p)

            return SyncMapper(result, totalChars, totalMs)
        }

        private fun insertIfMonotone(list: MutableList<Point>, p: Point) {
            if (list.any { it.ms == p.ms }) return
            val idx = list.indexOfFirst { it.ms > p.ms }.let { if (it < 0) list.size else it }
            val prev = list.getOrNull(idx - 1)
            val next = list.getOrNull(idx)
            if (prev != null && p.char < prev.char) return
            if (next != null && p.char > next.char) return
            list.add(idx, p)
        }

        /** Если число глав совпадает с числом аудиофайлов — сопоставляем их начала. */
        private fun chapterTrackPoints(content: BookContent, tracks: List<TrackEntity>): List<Point> {
            if (tracks.size < 2) return emptyList()
            val starts = LongArray(tracks.size)
            for (i in 1 until tracks.size) starts[i] = starts[i - 1] + tracks[i - 1].durationMs

            val all = content.chapters
            val minDepth = all.minOfOrNull { it.depth } ?: 0
            val top = all.filter { it.depth == minDepth }
            val leaves = all.filterIndexed { i, c -> i == all.lastIndex || all[i + 1].depth <= c.depth }

            for (candidate in listOf(all, leaves, top)) {
                if (candidate.size == tracks.size) {
                    return candidate.mapIndexed { i, c -> Point(starts[i], c.start) }
                }
                // Частый случай: первый файл — вступление от издательства.
                if (candidate.size == tracks.size - 1) {
                    return candidate.mapIndexed { i, c -> Point(starts[i + 1], c.start) }
                }
            }
            return emptyList()
        }
    }
}
