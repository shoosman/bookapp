package com.shoosman.bookapp.sync

import com.shoosman.bookapp.data.BookStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Книга в облаке — файл books/<id>.json. Формат открытый: его могут читать и другие приложения
 * (на Mac, в браузере). Позиции чтения хранятся и в символах (для Чтеца), и в процентах (для всех).
 */
data class BookDoc(
    val id: String,
    // описание
    val title: String,
    val author: String,
    val annotation: String,
    val sourceUrl: String,
    val addedAt: Long,
    val cover: String?,
    /** Цикл и номер в нём — часть описания книги (metaUpdatedAt). */
    val series: String,
    val seriesIndex: Double,
    /** Жанры — тоже часть описания. */
    val genres: List<String>,
    val metaTs: Long,
    // полка
    val status: String,
    val rating: Int,
    val notes: String,
    val startedAt: Long,
    val finishedAt: Long,
    val shelfTs: Long,
    // текст
    val text: TextDoc?,
    val textTs: Long,
    // прогресс
    val textChar: Int,
    val textPercent: Double,
    val audioMs: Long,
    val audioPercent: Double,
    val progressTs: Long,
    // аудио — только для информации, файлы остаются на устройстве
    val audioTotalMs: Long,
    val bookmarks: List<MarkDoc>,
    val anchors: List<AnchorDoc>,
    val deleted: Boolean,
) {
    data class TextDoc(val path: String, val name: String, val format: String, val sha: String)
    data class MarkDoc(val id: String, val charPos: Int, val audioMs: Long, val snippet: String, val note: String, val createdAt: Long, val deleted: Boolean)
    data class AnchorDoc(val id: String, val audioMs: Long, val charPos: Int, val deleted: Boolean)

    fun toJson(): String {
        val o = JSONObject()
        o.put("version", 1)
        o.put("id", id)
        o.put("title", title)
        o.put("author", author)
        o.put("annotation", annotation)
        o.put("sourceUrl", sourceUrl)
        o.put("addedAt", addedAt)
        o.put("cover", cover ?: JSONObject.NULL)
        o.put("series", series)
        o.put("seriesIndex", seriesIndex)
        o.put("genres", JSONArray().apply { genres.forEach { put(it) } })
        o.put("metaUpdatedAt", metaTs)
        o.put("status", status)
        o.put("rating", rating)
        o.put("notes", notes)
        o.put("startedAt", startedAt)
        o.put("finishedAt", finishedAt)
        o.put("shelfUpdatedAt", shelfTs)
        o.put(
            "text",
            text?.let { JSONObject().put("path", it.path).put("name", it.name).put("format", it.format).put("sha", it.sha) }
                ?: JSONObject.NULL,
        )
        o.put("textUpdatedAt", textTs)
        o.put(
            "progress",
            JSONObject()
                .put("textChar", textChar)
                .put("textPercent", round(textPercent))
                .put("audioMs", audioMs)
                .put("audioPercent", round(audioPercent))
                .put("updatedAt", progressTs),
        )
        o.put("audioTotalMs", audioTotalMs)
        o.put("bookmarks", JSONArray().apply {
            bookmarks.sortedBy { it.createdAt }.forEach {
                put(
                    JSONObject().put("id", it.id).put("charPos", it.charPos).put("audioMs", it.audioMs)
                        .put("snippet", it.snippet).put("note", it.note).put("createdAt", it.createdAt).put("deleted", it.deleted),
                )
            }
        })
        o.put("anchors", JSONArray().apply {
            anchors.sortedBy { it.id }.forEach {
                put(JSONObject().put("id", it.id).put("audioMs", it.audioMs).put("charPos", it.charPos).put("deleted", it.deleted))
            }
        })
        o.put("deleted", deleted)
        return o.toString(2)
    }

    companion object {
        private fun round(v: Double) = Math.round(v * 10000) / 10000.0

        fun statusName(status: Int) = when (status) {
            BookStatus.READING -> "reading"
            BookStatus.FINISHED -> "finished"
            BookStatus.DROPPED -> "dropped"
            else -> "want"
        }

        fun statusCode(name: String) = when (name) {
            "reading" -> BookStatus.READING
            "finished" -> BookStatus.FINISHED
            "dropped" -> BookStatus.DROPPED
            else -> BookStatus.WANT
        }

        fun parse(json: String): BookDoc {
            val o = JSONObject(json)
            val p = o.optJSONObject("progress") ?: JSONObject()
            val t = o.optJSONObject("text")
            fun str(key: String) = if (o.isNull(key)) "" else o.optString(key)
            return BookDoc(
                id = o.getString("id"),
                title = str("title"),
                author = str("author"),
                annotation = str("annotation"),
                sourceUrl = str("sourceUrl"),
                addedAt = o.optLong("addedAt"),
                cover = if (o.isNull("cover")) null else o.optString("cover").ifBlank { null },
                series = str("series"),
                genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
                seriesIndex = (o.optDouble("seriesIndex", 0.0).takeIf { !it.isNaN() } ?: 0.0).let { Math.round(it * 100.0) / 100.0 },
                metaTs = o.optLong("metaUpdatedAt"),
                status = o.optString("status", "want"),
                rating = o.optInt("rating"),
                notes = str("notes"),
                startedAt = o.optLong("startedAt"),
                finishedAt = o.optLong("finishedAt"),
                shelfTs = o.optLong("shelfUpdatedAt"),
                text = t?.let { TextDoc(it.optString("path"), it.optString("name"), it.optString("format"), it.optString("sha")) },
                textTs = o.optLong("textUpdatedAt"),
                textChar = p.optInt("textChar"),
                textPercent = p.optDouble("textPercent", 0.0),
                audioMs = p.optLong("audioMs"),
                audioPercent = p.optDouble("audioPercent", 0.0),
                progressTs = p.optLong("updatedAt"),
                audioTotalMs = o.optLong("audioTotalMs"),
                bookmarks = o.optJSONArray("bookmarks").objects().map {
                    MarkDoc(
                        it.optString("id"), it.optInt("charPos", -1), it.optLong("audioMs", -1), it.optString("snippet"),
                        it.optString("note"), it.optLong("createdAt"), it.optBoolean("deleted"),
                    )
                },
                anchors = o.optJSONArray("anchors").objects().map {
                    AnchorDoc(it.optString("id"), it.optLong("audioMs"), it.optInt("charPos"), it.optBoolean("deleted"))
                },
                deleted = o.optBoolean("deleted"),
            )
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        /**
         * Слияние двух версий одной книги. Каждая группа полей берётся из той версии,
         * где её меняли позже; закладки и привязки объединяются, удаление побеждает.
         */
        fun merge(local: BookDoc, remote: BookDoc): BookDoc {
            val metaR = remote.metaTs > local.metaTs
            val shelfR = remote.shelfTs > local.shelfTs
            val textR = remote.textTs > local.textTs
            val progR = remote.progressTs > local.progressTs
            val m = if (metaR) remote else local
            val sh = if (shelfR) remote else local
            val tx = if (textR) remote else local
            val pr = if (progR) remote else local
            return BookDoc(
                id = local.id,
                title = m.title, author = m.author, annotation = m.annotation, sourceUrl = m.sourceUrl,
                addedAt = minOf(local.addedAt.takeIf { it > 0 } ?: Long.MAX_VALUE, remote.addedAt.takeIf { it > 0 } ?: Long.MAX_VALUE)
                    .let { if (it == Long.MAX_VALUE) 0 else it },
                cover = m.cover, series = m.series, seriesIndex = m.seriesIndex, genres = m.genres, metaTs = m.metaTs,
                status = sh.status, rating = sh.rating, notes = sh.notes, startedAt = sh.startedAt, finishedAt = sh.finishedAt,
                shelfTs = sh.shelfTs,
                text = tx.text, textTs = tx.textTs,
                textChar = pr.textChar, textPercent = pr.textPercent, audioMs = pr.audioMs, audioPercent = pr.audioPercent,
                progressTs = pr.progressTs,
                audioTotalMs = if (local.audioTotalMs > 0) local.audioTotalMs else remote.audioTotalMs,
                bookmarks = mergeBy(local.bookmarks, remote.bookmarks, { it.id }, { it.deleted }) { it.copy(deleted = true) },
                anchors = mergeBy(local.anchors, remote.anchors, { it.id }, { it.deleted }) { it.copy(deleted = true) },
                deleted = local.deleted || remote.deleted,
            )
        }

        private fun <T> mergeBy(a: List<T>, b: List<T>, key: (T) -> String, isDeleted: (T) -> Boolean, markDeleted: (T) -> T): List<T> {
            val out = LinkedHashMap<String, T>()
            for (x in a) out[key(x)] = x
            for (y in b) {
                val x = out[key(y)]
                out[key(y)] = when {
                    x == null -> y
                    isDeleted(x) || isDeleted(y) -> markDeleted(x)
                    else -> x
                }
            }
            return out.values.toList()
        }
    }
}
