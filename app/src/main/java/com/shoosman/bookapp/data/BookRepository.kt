package com.shoosman.bookapp.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.shoosman.bookapp.audio.EmbeddedChapters
import com.shoosman.bookapp.sync.SyncMapper
import com.shoosman.bookapp.text.BookContent
import com.shoosman.bookapp.text.BookParser
import com.shoosman.bookapp.text.ParsedBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/** Глава аудиокниги: время от начала всей книги. */
class AudioChapter(val title: String, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

class ImportProgress(val text: String, val fraction: Float?)

/** Результат добавления: id книги и предупреждение (например, исходник не удалось удалить). */
class ImportResult(val bookId: Long, val notice: String?)

typealias OnProgress = (ImportProgress) -> Unit

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

    /** Добавить книгу из текстового файла. Файл переносится в приложение. */
    suspend fun createFromText(uri: Uri, onProgress: OnProgress = {}): ImportResult = withContext(Dispatchers.IO) {
        onProgress(ImportProgress("Читаю книгу…", null))
        val dir = newBookDir()
        val id = try {
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
        ImportResult(id, deleteOriginals(listOf(uri), null, isAudio = false))
    }

    /** Прикрепить или заменить текст у книги. Возвращает предупреждение, если оно есть. */
    suspend fun attachText(bookId: Long, uri: Uri, onProgress: OnProgress = {}): String? = withContext(Dispatchers.IO) {
        val book = dao.get(bookId) ?: return@withContext null
        onProgress(ImportProgress("Читаю книгу…", null))
        val dir = File(book.dir).apply { mkdirs() }
        val parsed = importText(uri, dir, keepCover = book.coverPath != null)
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
        deleteOriginals(listOf(uri), null, isAudio = false)
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

    /** Добавить аудиокнигу из файлов. Файлы переносятся в приложение. */
    suspend fun createFromAudio(uris: List<Uri>, onProgress: OnProgress = {}): ImportResult = withContext(Dispatchers.IO) {
        val sources = sortNatural(uris.map { it to displayName(it) }).map { it.first }
        createAudioBook(sources, null, null, onProgress)
    }

    /** Добавить аудиокнигу из папки. Аудиофайлы переносятся в приложение, пустая папка удаляется. */
    suspend fun createFromAudioTree(tree: Uri, onProgress: OnProgress = {}): ImportResult = withContext(Dispatchers.IO) {
        onProgress(ImportProgress("Ищу аудиофайлы…", null))
        val folder = DocumentFile.fromTreeUri(ctx, tree)
        createAudioBook(audioInTree(tree), folder?.name, tree, onProgress)
    }

    suspend fun attachAudio(bookId: Long, uris: List<Uri>, onProgress: OnProgress = {}): String? = withContext(Dispatchers.IO) {
        val sources = sortNatural(uris.map { it to displayName(it) }).map { it.first }
        replaceTracks(bookId, sources, null, onProgress)
    }

    suspend fun attachAudioTree(bookId: Long, tree: Uri, onProgress: OnProgress = {}): String? = withContext(Dispatchers.IO) {
        onProgress(ImportProgress("Ищу аудиофайлы…", null))
        replaceTracks(bookId, audioInTree(tree), tree, onProgress)
    }

    /** Для книг, добавленных раньше: перенести аудио, которое лежит снаружи, внутрь приложения. */
    suspend fun moveAudioIntoApp(bookId: Long, onProgress: OnProgress = {}): String? = withContext(Dispatchers.IO) {
        val book = dao.get(bookId) ?: return@withContext null
        val tracks = dao.tracks(bookId)
        if (tracks.none { isExternal(it.uri) }) return@withContext null
        val sources = tracks.map { Uri.parse(it.uri) }
        val (folder, files) = copyAudio(sources, File(book.dir), onProgress)
        dao.deleteTracks(bookId)
        dao.insertTracks(tracks.mapIndexed { i, t -> t.copy(id = 0, uri = Uri.fromFile(files[i]).toString()) })
        removeOldAudio(File(book.dir), keep = folder)
        deleteOriginals(sources.filter { isExternal(it.toString()) }, null, isAudio = true)
    }

    fun isExternal(uri: String) = !uri.startsWith("file:")

    private suspend fun createAudioBook(sources: List<Uri>, folderName: String?, tree: Uri?, onProgress: OnProgress): ImportResult {
        if (sources.isEmpty()) error("Не найдено аудиофайлов")
        val names = sources.map { displayName(it) }
        val dir = newBookDir()
        val id = try {
            val (_, files) = copyAudio(sources, dir, onProgress)
            onProgress(ImportProgress("Читаю данные аудио…", null))
            val infos = files.mapIndexed { i, f -> readAudio(Uri.fromFile(f), names[i]) }
            val first = infos.first()
            first.picture?.let { coverFile(dir).writeBytes(it) }
            val title = first.album?.takeIf { it.isNotBlank() } ?: folderName
                ?: if (infos.size == 1) first.title else names.first().substringBeforeLast('.')
            val bookId = dao.insert(
                BookEntity(
                    title = title,
                    author = first.artist.orEmpty(),
                    dir = dir.absolutePath,
                    coverPath = coverFile(dir).takeIf { it.exists() }?.absolutePath,
                    audioTotalMs = infos.sumOf { it.durationMs },
                ),
            )
            dao.insertTracks(infos.mapIndexed { i, a -> TrackEntity(bookId = bookId, idx = i, uri = a.uri.toString(), title = a.title, durationMs = a.durationMs) })
            bookId
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
        return ImportResult(id, deleteOriginals(sources, tree, isAudio = true))
    }

    private suspend fun replaceTracks(bookId: Long, sources: List<Uri>, tree: Uri?, onProgress: OnProgress): String? {
        if (sources.isEmpty()) error("Не найдено аудиофайлов")
        val book = dao.get(bookId) ?: return null
        val dir = File(book.dir).apply { mkdirs() }
        val names = sources.map { displayName(it) }
        val (folder, files) = copyAudio(sources, dir, onProgress)
        onProgress(ImportProgress("Читаю данные аудио…", null))
        val infos = files.mapIndexed { i, f -> readAudio(Uri.fromFile(f), names[i]) }
        var cover = book.coverPath
        if (cover == null) {
            infos.first().picture?.let {
                val f = coverFile(dir)
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
        removeOldAudio(dir, keep = folder)
        return deleteOriginals(sources, tree, isAudio = true)
    }

    /**
     * Копирует аудиофайлы в новую папку книги и проверяет размер каждой копии.
     * При любой ошибке копия удаляется, оригиналы остаются нетронутыми.
     */
    private fun copyAudio(sources: List<Uri>, dir: File, onProgress: OnProgress): Pair<File, List<File>> {
        val folder = File(dir, "audio-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val sizes = sources.map { querySize(it) }
            val total = sizes.sum()
            val free = StatFs(ctx.filesDir.path).availableBytes
            if (total > 0 && free < total + (64L shl 20)) {
                error("Не хватает места на телефоне: нужно ${fmtSize(total)}, свободно ${fmtSize(free)}")
            }
            var done = 0L
            var lastReport = 0L
            val buffer = ByteArray(1 shl 16)
            val files = sources.mapIndexed { i, uri ->
                val out = File(folder, "%03d_%s".format(i, sanitize(displayName(uri))))
                val input = ctx.contentResolver.openInputStream(uri) ?: error("Не удалось открыть ${displayName(uri)}")
                input.use { inp ->
                    out.outputStream().use { o ->
                        while (true) {
                            val n = inp.read(buffer)
                            if (n < 0) break
                            o.write(buffer, 0, n)
                            done += n
                            if (done - lastReport > (1L shl 20)) {
                                lastReport = done
                                onProgress(
                                    ImportProgress(
                                        "Переношу аудио: файл ${i + 1} из ${sources.size}",
                                        if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null,
                                    ),
                                )
                            }
                        }
                    }
                }
                if (sizes[i] > 0 && out.length() != sizes[i]) error("Файл ${displayName(uri)} скопировался не полностью")
                out
            }
            return folder to files
        } catch (e: Throwable) {
            folder.deleteRecursively()
            throw e
        }
    }

    private fun removeOldAudio(dir: File, keep: File) {
        dir.listFiles()?.filter { it.isDirectory && it.name.startsWith("audio-") && it != keep }?.forEach { it.deleteRecursively() }
    }

    /**
     * Удаляет исходные файлы после успешного переноса.
     * Возвращает предупреждение, если удалить получилось не всё.
     */
    private fun deleteOriginals(sources: List<Uri>, tree: Uri?, isAudio: Boolean): String? {
        var failed = 0
        for (uri in sources) {
            val ok = runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }.getOrDefault(false)
            if (!ok) failed++
        }
        if (tree != null) runCatching { removeEmptyFolders(DocumentFile.fromTreeUri(ctx, tree)) }
        if (failed == 0) return null
        return when {
            !isAudio -> "Книга добавлена, но исходный файл удалить не удалось. Удалите его вручную, если он больше не нужен."
            failed == sources.size -> "Аудио перенесено в приложение, но исходные файлы удалить не удалось. Удалите их вручную, если они больше не нужны."
            else -> "Аудио перенесено в приложение, но $failed из ${sources.size} исходных файлов удалить не удалось. Удалите их вручную."
        }
    }

    private fun removeEmptyFolders(dir: DocumentFile?) {
        if (dir == null || !dir.isDirectory) return
        dir.listFiles().filter { it.isDirectory }.forEach { removeEmptyFolders(it) }
        if (dir.listFiles().isEmpty()) dir.delete()
    }

    private fun querySize(uri: Uri): Long = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

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

    private fun readAudio(uri: Uri, originalName: String): AudioInfo {
        val fallback = originalName.substringBeforeLast('.')
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            AudioInfo(
                uri = uri,
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: fallback,
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                picture = r.embeddedPicture,
            )
        } catch (e: Exception) {
            AudioInfo(uri, fallback, 0L, null, null, null)
        } finally {
            runCatching { r.release() }
        }
    }

    private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)

    private fun fmtSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.1f ГБ".format(mb / 1024) else "%.0f МБ".format(mb)
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
        return SyncMapper.build(content, tracks, dao.anchors(bookId), embeddedMarks(book, tracks).map { it.startMs })
    }

    // ---------------- главы аудио ----------------

    /**
     * Главы, зашитые в аудиофайлы, в мс от начала книги. Разбираются один раз и
     * запоминаются в папке книги, пока набор файлов не поменяется.
     */
    suspend fun embeddedMarks(book: BookEntity, tracks: List<TrackEntity>): List<AudioChapter> = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext emptyList()
        val key = tracks.joinToString("|") { it.uri }.hashCode().toString()
        val cache = File(book.dir, "chapters.cache")
        runCatching {
            val lines = cache.readLines()
            if (lines.firstOrNull() == key) {
                return@withContext lines.drop(1).mapNotNull { line ->
                    val parts = line.split('\t', limit = 2)
                    val ms = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                    AudioChapter(parts.getOrElse(1) { "" }, ms, 0)
                }
            }
        }

        var offset = 0L
        val perTrack = tracks.map { t ->
            val marks = runCatching {
                ctx.contentResolver.openFileDescriptor(Uri.parse(t.uri), "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { EmbeddedChapters.read(it) }
                }
            }.getOrNull().orEmpty()
            val start = offset
            offset += t.durationMs
            Triple(t, start, marks)
        }
        val result = ArrayList<AudioChapter>()
        if (perTrack.any { it.third.isNotEmpty() }) {
            for ((t, start, marks) in perTrack) {
                if (marks.isEmpty()) {
                    result.add(AudioChapter(t.title, start, 0))
                } else {
                    marks.forEach { result.add(AudioChapter(it.title, start + it.startMs, 0)) }
                }
            }
        }
        runCatching {
            cache.writeText(key + "\n" + result.joinToString("\n") { "${it.startMs}\t${it.title.replace('\n', ' ')}" })
        }
        result
    }

    /**
     * Главы для плеера. Порядок выбора:
     * 1. главы, зашитые в аудиофайлы (M4B, MP3 с разметкой);
     * 2. отдельные аудиофайлы, если их не меньше, чем глав в тексте (или текста нет);
     * 3. главы текста, пересчитанные во время через синхронизацию.
     */
    suspend fun audioChapters(bookId: Long): List<AudioChapter> = withContext(Dispatchers.IO) {
        val book = dao.get(bookId) ?: return@withContext emptyList()
        val tracks = dao.tracks(bookId)
        if (tracks.isEmpty()) return@withContext emptyList()
        val total = tracks.sumOf { it.durationMs }

        val starts = ArrayList<Pair<Long, String>>()
        val embedded = embeddedMarks(book, tracks)
        val content = runCatching { content(book) }.getOrNull()
        if (embedded.size >= 2) {
            embedded.forEach { starts.add(it.startMs to it.title) }
        } else if (content == null || content.chapters.size < 2 || tracks.size >= content.chapters.size) {
            var t = 0L
            tracks.forEach {
                starts.add(t to it.title)
                t += it.durationMs
            }
        } else {
            val mapper = SyncMapper.build(content, tracks, dao.anchors(bookId), emptyList())
            content.chapters.forEach { starts.add(mapper.msAt(it.start) to it.title) }
        }

        // Наводим порядок: по возрастанию, без почти совпадающих, первая — с нуля.
        val clean = ArrayList<Pair<Long, String>>()
        for ((ms, title) in starts.sortedBy { it.first }) {
            val last = clean.lastOrNull()
            if (last != null && ms - last.first < 1500) continue
            clean.add(ms.coerceIn(0, total) to title.ifBlank { "Глава ${clean.size + 1}" })
        }
        if (clean.isEmpty()) return@withContext emptyList()
        if (clean[0].first in 1..3000) clean[0] = 0L to clean[0].second
        else if (clean[0].first > 3000) clean.add(0, 0L to "Начало")

        clean.mapIndexed { i, (ms, title) ->
            AudioChapter(title, ms, clean.getOrNull(i + 1)?.first ?: total)
        }.filter { it.endMs > it.startMs }
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
