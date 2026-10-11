package com.shoosman.bookapp.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.shoosman.bookapp.sync.SyncMapper
import com.shoosman.bookapp.text.BookContent
import com.shoosman.bookapp.text.BookParser
import com.shoosman.bookapp.text.ParsedBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class BookRepository(private val ctx: Context, db: AppDatabase) {

    val dao: BookDao = db.bookDao()

    /** Для записи, которая должна завершиться даже после ухода с экрана. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val booksDir = File(ctx.filesDir, "books").apply { mkdirs() }
    private var cached: Pair<String, BookContent>? = null

    suspend fun book(id: Long): BookEntity? = dao.get(id)
    suspend fun tracks(id: Long): List<TrackEntity> = dao.tracks(id)

    // ---------------- текст ----------------

    suspend fun content(book: BookEntity): BookContent? = withContext(Dispatchers.IO) {
        val path = book.textPath ?: return@withContext null
        cached?.let { (p, c) -> if (p == path) return@withContext c }
        val c = BookContent.read(File(path))
        cached = path to c
        c
    }

    suspend fun createFromText(uri: Uri): Long = withContext(Dispatchers.IO) {
        val dir = newBookDir()
        try {
            val parsed = importText(uri, dir)
            dao.insert(
                BookEntity(
                    title = parsed.title,
                    author = parsed.author,
                    annotation = parsed.annotation,
                    dir = dir.absolutePath,
                    coverPath = coverFile(dir).takeIf { it.exists() }?.absolutePath,
                    textPath = File(dir, CONTENT).absolutePath,
                    textFormat = parsed.format,
                    totalChars = parsed.content.totalChars,
                    chapterCount = parsed.content.chapters.size,
                ),
            )
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    suspend fun attachText(bookId: Long, uri: Uri) = withContext(Dispatchers.IO) {
        val book = dao.get(bookId) ?: return@withContext
        val dir = File(book.dir).apply { mkdirs() }
        val hadCover = book.coverPath != null
        val parsed = importText(uri, dir, keepCover = hadCover)
        cached = null
        dao.deleteAnchors(bookId)
        dao.update(
            book.copy(
                title = if (book.textPath == null && parsed.title.isNotBlank()) parsed.title else book.title,
                author = book.author.ifBlank { parsed.author },
                annotation = book.annotation.ifBlank { parsed.annotation },
                coverPath = book.coverPath ?: coverFile(dir).takeIf { it.exists() }?.absolutePath,
                textPath = File(dir, CONTENT).absolutePath,
                textFormat = parsed.format,
                totalChars = parsed.content.totalChars,
                chapterCount = parsed.content.chapters.size,
                readPos = 0,
            ),
        )
    }

    private fun importText(uri: Uri, dir: File, keepCover: Boolean = false): ParsedBook {
        val name = displayName(uri)
        val src = File(dir, "source")
        val input = ctx.contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл")
        input.use { i -> src.outputStream().use { i.copyTo(it) } }
        val parsed = BookParser.parse(src, name)
        if (parsed.content.paragraphs.isEmpty()) error("В файле не найден текст")
        parsed.content.write(File(dir, CONTENT))
        if (!keepCover) parsed.cover?.let { coverFile(dir).writeBytes(it) }
        File(dir, "source.name").writeText(name)
        return parsed
    }

    // ---------------- аудио ----------------

    private class AudioInfo(val uri: Uri, val title: String, val durationMs: Long, val album: String?, val artist: String?, val picture: ByteArray?)

    suspend fun createFromAudio(uris: List<Uri>): Long = withContext(Dispatchers.IO) {
        uris.forEach { persist(it) }
        createAudioBook(sortNatural(uris.map { it to displayName(it) }).map { it.first }, null)
    }

    suspend fun createFromAudioTree(tree: Uri): Long = withContext(Dispatchers.IO) {
        persist(tree)
        val folder = DocumentFile.fromTreeUri(ctx, tree)
        createAudioBook(audioInTree(tree), folder?.name)
    }

    suspend fun attachAudio(bookId: Long, uris: List<Uri>) = withContext(Dispatchers.IO) {
        uris.forEach { persist(it) }
        replaceTracks(bookId, sortNatural(uris.map { it to displayName(it) }).map { it.first })
    }

    suspend fun attachAudioTree(bookId: Long, tree: Uri) = withContext(Dispatchers.IO) {
        persist(tree)
        replaceTracks(bookId, audioInTree(tree))
    }

    private suspend fun createAudioBook(uris: List<Uri>, folderName: String?): Long {
        if (uris.isEmpty()) error("Не найдено аудиофайлов")
        val infos = uris.map { readAudio(it) }
        val dir = newBookDir()
        val first = infos.first()
        first.picture?.let { coverFile(dir).writeBytes(it) }
        val title = first.album?.takeIf { it.isNotBlank() } ?: folderName
            ?: if (infos.size == 1) first.title else displayName(first.uri).substringBeforeLast('.')
        val id = dao.insert(
            BookEntity(
                title = title,
                author = first.artist.orEmpty(),
                dir = dir.absolutePath,
                coverPath = coverFile(dir).takeIf { it.exists() }?.absolutePath,
                audioTotalMs = infos.sumOf { it.durationMs },
            ),
        )
        dao.insertTracks(infos.mapIndexed { i, a -> TrackEntity(bookId = id, idx = i, uri = a.uri.toString(), title = a.title, durationMs = a.durationMs) })
        return id
    }

    private suspend fun replaceTracks(bookId: Long, uris: List<Uri>) {
        if (uris.isEmpty()) error("Не найдено аудиофайлов")
        val book = dao.get(bookId) ?: return
        val infos = uris.map { readAudio(it) }
        var cover = book.coverPath
        if (cover == null) {
            infos.first().picture?.let {
                val f = coverFile(File(book.dir))
                f.parentFile?.mkdirs()
                f.writeBytes(it)
                cover = f.absolutePath
            }
        }
        dao.deleteTracks(bookId)
        dao.deleteAnchors(bookId)
        dao.insertTracks(infos.mapIndexed { i, a -> TrackEntity(bookId = bookId, idx = i, uri = a.uri.toString(), title = a.title, durationMs = a.durationMs) })
        dao.update(
            book.copy(
                author = book.author.ifBlank { infos.first().artist.orEmpty() },
                coverPath = cover,
                audioTotalMs = infos.sumOf { it.durationMs },
                audioTrack = 0, audioPosMs = 0, audioGlobalMs = 0,
            ),
        )
    }

    private fun audioInTree(tree: Uri): List<Uri> {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        val found = ArrayList<Pair<Uri, String>>()
        fun scan(dir: DocumentFile, prefix: String) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (f.isDirectory) scan(f, "$prefix$name/")
                else if (isAudio(name, f.type)) found.add(f.uri to "$prefix$name")
            }
        }
        scan(root, "")
        return sortNatural(found).map { it.first }
    }

    private fun isAudio(name: String, mime: String?): Boolean {
        if (mime?.startsWith("audio/") == true) return true
        return name.substringAfterLast('.', "").lowercase() in AUDIO_EXT
    }

    private fun readAudio(uri: Uri): AudioInfo {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            AudioInfo(
                uri = uri,
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                    ?: displayName(uri).substringBeforeLast('.'),
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                picture = r.embeddedPicture,
            )
        } catch (e: Exception) {
            AudioInfo(uri, displayName(uri).substringBeforeLast('.'), 0L, null, null, null)
        } finally {
            runCatching { r.release() }
        }
    }

    private fun persist(uri: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    // ---------------- прочее ----------------

    suspend fun saveReadPos(bookId: Long, pos: Int) = dao.setReadPos(bookId, pos, System.currentTimeMillis())

    suspend fun saveAudioPos(bookId: Long, track: Int, posMs: Long, globalMs: Long) =
        dao.setAudioPos(bookId, track, posMs, globalMs, System.currentTimeMillis())

    suspend fun setStatus(bookId: Long, status: Int) =
        dao.setStatus(bookId, status, if (status == BookStatus.FINISHED) System.currentTimeMillis() else 0L)

    /** При первом открытии книга с полки «Хочу прочитать» переезжает в «Читаю». */
    suspend fun markOpened(bookId: Long) {
        val b = dao.get(bookId) ?: return
        if (b.status == BookStatus.WANT) dao.setStatus(bookId, BookStatus.READING, 0L)
        dao.touch(bookId, System.currentTimeMillis())
    }

    suspend fun setRating(bookId: Long, rating: Int) = dao.setRating(bookId, rating)
    suspend fun setSpeed(bookId: Long, speed: Float) = dao.setSpeed(bookId, speed)

    suspend fun rename(bookId: Long, title: String, author: String) {
        val b = dao.get(bookId) ?: return
        dao.update(b.copy(title = title.trim().ifBlank { b.title }, author = author.trim()))
    }

    suspend fun addBookmark(bookId: Long, charPos: Int, audioMs: Long, snippet: String) =
        dao.insertBookmark(BookmarkEntity(bookId = bookId, charPos = charPos, audioMs = audioMs, snippet = snippet.take(160)))

    suspend fun deleteBookmark(id: Long) = dao.deleteBookmark(id)

    suspend fun addAnchor(bookId: Long, audioMs: Long, charPos: Int) =
        dao.insertAnchor(AnchorEntity(bookId = bookId, audioMs = audioMs, charPos = charPos))

    suspend fun clearAnchors(bookId: Long) = dao.deleteAnchors(bookId)

    suspend fun mapper(bookId: Long): SyncMapper? {
        val book = dao.get(bookId) ?: return null
        val tracks = dao.tracks(bookId)
        if (tracks.isEmpty()) return null
        val content = content(book) ?: return null
        return SyncMapper.build(content, tracks, dao.anchors(bookId))
    }

    suspend fun delete(bookId: Long) = withContext(Dispatchers.IO) {
        val book = dao.get(bookId) ?: return@withContext
        dao.deleteTracks(bookId)
        dao.deleteBookmarks(bookId)
        dao.deleteAnchors(bookId)
        dao.delete(bookId)
        if (cached?.first == book.textPath) cached = null
        File(book.dir).deleteRecursively()
    }

    fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book"
    }

    private fun newBookDir(): File = File(booksDir, UUID.randomUUID().toString()).apply { mkdirs() }
    private fun coverFile(dir: File) = File(dir, "cover.img")

    companion object {
        private const val CONTENT = "content.bin"
        private val AUDIO_EXT = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "mka", "webm")

        private val chunk = Regex("\\d+|\\D+")

        /** Сортировка «как у людей»: 2.mp3 раньше 10.mp3. */
        fun <T> sortNatural(items: List<Pair<T, String>>): List<Pair<T, String>> =
            items.sortedWith { a, b -> compareNatural(a.second, b.second) }

        fun compareNatural(a: String, b: String): Int {
            val pa = chunk.findAll(a.lowercase()).map { it.value }.toList()
            val pb = chunk.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val cmp = if (x[0].isDigit() && y[0].isDigit()) {
                    val nx = x.trimStart('0')
                    val ny = y.trimStart('0')
                    if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
                } else {
                    x.compareTo(y)
                }
                if (cmp != 0) return cmp
            }
            return pa.size - pb.size
        }
    }
}
