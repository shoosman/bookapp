package com.shoosman.bookapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE deleted = 0 ORDER BY lastOpenedAt DESC, addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id AND deleted = 0")
    fun observe(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET readPos = :pos, lastOpenedAt = :time, progressUpdatedAt = :time WHERE id = :id")
    suspend fun setReadPos(id: Long, pos: Int, time: Long)

    @Query("UPDATE books SET audioTrack = :track, audioPosMs = :pos, audioGlobalMs = :global, lastOpenedAt = :time, progressUpdatedAt = :time WHERE id = :id")
    suspend fun setAudioPos(id: Long, track: Int, pos: Long, global: Long, time: Long)

    @Query("UPDATE books SET rating = :rating, shelfUpdatedAt = :time WHERE id = :id")
    suspend fun setRating(id: Long, rating: Int, time: Long)

    @Query("UPDATE books SET notes = :notes, shelfUpdatedAt = :time WHERE id = :id")
    suspend fun setNotes(id: Long, notes: String, time: Long)

    @Query("UPDATE books SET speed = :speed WHERE id = :id")
    suspend fun setSpeed(id: Long, speed: Float)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)

    // --- для синхронизации: всё, включая удалённое ---
    @Query("SELECT * FROM books")
    suspend fun allForSync(): List<BookEntity>

    @Query("SELECT * FROM books WHERE uid = :uid")
    suspend fun byUid(uid: String): BookEntity?

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId")
    suspend fun allBookmarks(bookId: Long): List<BookmarkEntity>

    @Query("SELECT * FROM anchors WHERE bookId = :bookId")
    suspend fun allAnchors(bookId: Long): List<AnchorEntity>

    @Update
    suspend fun updateBookmark(bookmark: BookmarkEntity)

    @Update
    suspend fun updateAnchor(anchor: AnchorEntity)

    /** Сигнал «в библиотеке что-то поменялось» — для автосинхронизации. */
    @Query("SELECT (SELECT COUNT(*) FROM books) + (SELECT COUNT(*) FROM bookmarks) + (SELECT COUNT(*) FROM anchors)")
    fun changes(): Flow<Int>

    // --- аудиофайлы ---
    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY idx")
    suspend fun tracks(bookId: Long): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY idx")
    fun observeTracks(bookId: Long): Flow<List<TrackEntity>>

    @Insert
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE bookId = :bookId")
    suspend fun deleteTracks(bookId: Long)

    // --- закладки ---
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND deleted = 0 ORDER BY createdAt DESC")
    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("UPDATE bookmarks SET deleted = 1 WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    // --- точки синхронизации ---
    @Query("SELECT * FROM anchors WHERE bookId = :bookId AND deleted = 0 ORDER BY audioMs")
    suspend fun anchors(bookId: Long): List<AnchorEntity>

    @Query("SELECT * FROM anchors WHERE bookId = :bookId AND deleted = 0 ORDER BY audioMs")
    fun observeAnchors(bookId: Long): Flow<List<AnchorEntity>>

    @Insert
    suspend fun insertAnchor(anchor: AnchorEntity): Long

    @Query("UPDATE anchors SET deleted = 1 WHERE bookId = :bookId")
    suspend fun deleteAnchors(bookId: Long)
}
