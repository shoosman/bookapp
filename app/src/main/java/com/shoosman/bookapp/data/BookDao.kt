package com.shoosman.bookapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC, addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE books SET readPos = :pos, lastOpenedAt = :time WHERE id = :id")
    suspend fun setReadPos(id: Long, pos: Int, time: Long)

    @Query("UPDATE books SET audioTrack = :track, audioPosMs = :pos, audioGlobalMs = :global, lastOpenedAt = :time WHERE id = :id")
    suspend fun setAudioPos(id: Long, track: Int, pos: Long, global: Long, time: Long)

    @Query("UPDATE books SET status = :status, finishedAt = :finishedAt WHERE id = :id")
    suspend fun setStatus(id: Long, status: Int, finishedAt: Long)

    @Query("UPDATE books SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: Long, rating: Int)

    @Query("UPDATE books SET speed = :speed WHERE id = :id")
    suspend fun setSpeed(id: Long, speed: Float)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)

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
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarks(bookId: Long)

    // --- точки синхронизации ---
    @Query("SELECT * FROM anchors WHERE bookId = :bookId ORDER BY audioMs")
    suspend fun anchors(bookId: Long): List<AnchorEntity>

    @Query("SELECT * FROM anchors WHERE bookId = :bookId ORDER BY audioMs")
    fun observeAnchors(bookId: Long): Flow<List<AnchorEntity>>

    @Insert
    suspend fun insertAnchor(anchor: AnchorEntity): Long

    @Query("DELETE FROM anchors WHERE bookId = :bookId")
    suspend fun deleteAnchors(bookId: Long)
}
