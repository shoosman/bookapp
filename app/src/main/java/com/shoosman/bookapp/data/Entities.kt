package com.shoosman.bookapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object BookStatus {
    const val WANT = 0
    const val READING = 1
    const val FINISHED = 2
    const val DROPPED = 3

    /** Порядок полок в интерфейсе. */
    val ordered = listOf(READING, WANT, FINISHED, DROPPED)

    fun label(status: Int): String = when (status) {
        WANT -> "Хочу прочитать"
        READING -> "Читаю"
        FINISHED -> "Прочитано"
        DROPPED -> "Брошено"
        else -> ""
    }
}

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val annotation: String = "",
    /** Папка книги во внутренней памяти приложения. */
    val dir: String,
    val coverPath: String? = null,
    /** Разобранный текст (content.bin) или null, если текста нет. */
    val textPath: String? = null,
    val textFormat: String? = null,
    val totalChars: Int = 0,
    val chapterCount: Int = 0,
    val status: Int = BookStatus.WANT,
    val rating: Int = 0,
    /** Позиция чтения: смещение в символах от начала книги. */
    val readPos: Int = 0,
    val audioTrack: Int = 0,
    val audioPosMs: Long = 0,
    /** Позиция в аудио от начала всей книги. */
    val audioGlobalMs: Long = 0,
    val audioTotalMs: Long = 0,
    val speed: Float = 1f,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0,
    val finishedAt: Long = 0,
    // --- с версии 2: список прочитанного и синхронизация ---
    /** Постоянный id книги для синхронизации между устройствами. */
    val uid: String = java.util.UUID.randomUUID().toString(),
    val notes: String = "",
    val startedAt: Long = 0,
    /** Ссылка на страницу книги (Литрес, LiveLib…). */
    val sourceUrl: String = "",
    /** Когда менялись описание книги: название, автор, аннотация, ссылка, обложка. */
    val metaUpdatedAt: Long = System.currentTimeMillis(),
    /** Когда менялись полка, оценка, заметки, даты. */
    val shelfUpdatedAt: Long = System.currentTimeMillis(),
    /** Когда добавляли, заменяли или удаляли текст книги. */
    val textUpdatedAt: Long = System.currentTimeMillis(),
    /** Когда менялся прогресс чтения/прослушивания. */
    val progressUpdatedAt: Long = 0,
    /** Книга удалена — запись остаётся, чтобы удаление дошло до других устройств. */
    val deleted: Boolean = false,
    // --- с версии 3: циклы ---
    /** Название цикла («Искажающие реальность»), пусто — вне цикла. */
    val series: String = "",
    /** Номер в цикле; 0 — неизвестен. Дробный — для частей (8.1, 8.2). */
    val seriesIndex: Float = 0f,
)

val BookEntity.hasText: Boolean get() = textPath != null
val BookEntity.hasAudio: Boolean get() = audioTotalMs > 0
val BookEntity.textProgress: Float
    get() = if (totalChars > 0) (readPos.toFloat() / totalChars).coerceIn(0f, 1f) else 0f
val BookEntity.audioProgress: Float
    get() = if (audioTotalMs > 0) (audioGlobalMs.toFloat() / audioTotalMs).coerceIn(0f, 1f) else 0f
val BookEntity.progress: Float get() = maxOf(textProgress, audioProgress)

@Entity(tableName = "tracks", indices = [Index("bookId")])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val idx: Int,
    val uri: String,
    val title: String,
    val durationMs: Long,
)

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** -1, если у книги нет текста. */
    val charPos: Int,
    /** -1, если у книги нет аудио. */
    val audioMs: Long,
    val snippet: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val uid: String = java.util.UUID.randomUUID().toString(),
    val deleted: Boolean = false,
)

/** Точка привязки «это место в аудио = это место в тексте», которую задал пользователь. */
@Entity(tableName = "anchors", indices = [Index("bookId")])
data class AnchorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val audioMs: Long,
    val charPos: Int,
    val uid: String = java.util.UUID.randomUUID().toString(),
    val deleted: Boolean = false,
)
