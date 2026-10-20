package com.shoosman.bookapp.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.shoosman.bookapp.CrashLog
import com.shoosman.bookapp.data.AudioChapter
import com.shoosman.bookapp.data.BookRepository
import com.shoosman.bookapp.data.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PlayerUi(
    val bookId: Long = -1,
    val title: String = "",
    val author: String = "",
    val coverPath: String? = null,
    val isPlaying: Boolean = false,
    val track: Int = 0,
    val trackCount: Int = 0,
    val trackTitle: String = "",
    val posMs: Long = 0,
    val durMs: Long = 0,
    val globalMs: Long = 0,
    val totalMs: Long = 0,
    val speed: Float = 1f,
    val sleepLeftMs: Long = 0,
    val sleepEndOfChapter: Boolean = false,
    /** Текущая глава аудио (см. BookRepository.audioChapters). */
    val chapter: Int = 0,
    val chapterCount: Int = 0,
    val chapterTitle: String = "",
    val chapterStartMs: Long = 0,
    val chapterEndMs: Long = 0,
    /** Почему не играет (ошибка плеера), по-русски; null — всё в порядке. */
    val error: String? = null,
) {
    /** Позиция и длительность внутри текущей главы. */
    val chapterPosMs: Long get() = (globalMs - chapterStartMs).coerceAtLeast(0)
    val chapterDurMs: Long get() = (chapterEndMs - chapterStartMs).coerceAtLeast(0)
}

/** Связь интерфейса с фоновым плеером. Живёт всё время работы приложения. */
class PlayerConnection(private val app: Context, private val repo: BookRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var tickerJob: Job? = null
    private var sleepJob: Job? = null
    private var lastSave = 0L

    private var tracks: List<TrackEntity> = emptyList()
    private var starts: LongArray = LongArray(0)
    private var chapters: List<AudioChapter> = emptyList()
    private var sleepChapter = -1

    /** Главы текущей книги — для списка глав в плеере. */
    val currentChapters: List<AudioChapter> get() = chapters
    val hasChapters: Boolean get() = chapters.isNotEmpty() && _ui.value.bookId >= 0

    /** Шаг перемотки из настроек (нужен и шторке). */
    var skipBackMs = 15_000L
        private set
    var skipForwardMs = 30_000L
        private set

    fun setSkip(backSec: Int, forwardSec: Int) {
        val changed = skipBackMs != backSec * 1000L || skipForwardMs != forwardSec * 1000L
        skipBackMs = backSec * 1000L
        skipForwardMs = forwardSec * 1000L
        // Кнопки в шторке показывают число секунд — перерисовываем
        if (changed) android.os.Handler(android.os.Looper.getMainLooper()).post { PlaybackService.current?.refreshButtons() }
    }

    /**
     * Границы текущей главы внутри файла — для шторки, экрана блокировки и машины,
     * чтобы там был таймлайн главы, а не всего файла. null — глава совпадает с файлом.
     */
    fun chapterWindowInItem(index: Int, posInItem: Long, itemDurMs: Long): LongArray? {
        if (chapters.isEmpty()) return null
        val trackStart = starts.getOrNull(index) ?: return null
        val g = trackStart + posInItem.coerceAtLeast(0)
        val ch = chapters.lastOrNull { it.startMs <= g } ?: return null
        val itemEnd = if (itemDurMs > 0) itemDurMs else (tracks.getOrNull(index)?.durationMs ?: 0L)
        val s = (ch.startMs - trackStart).coerceAtLeast(0)
        val e = (ch.endMs - trackStart).let { if (itemEnd > 0) it.coerceAtMost(itemEnd) else it }
        if (e - s < 1000) return null
        if (s <= 500 && itemEnd > 0 && e >= itemEnd - 500) return null
        return longArrayOf(s, e)
    }

    /** Позицию читаем прямо из плеера: шторке отдаётся время главы, а приложению нужно время файла. */
    private fun raw(c: Player): Player = PlaybackService.raw ?: c
    private var notifiedChapter = -1

    /** Перемотка из шторки/экрана блокировки. */
    fun skipBackFromSystem(fallback: Player) = skipFromSystem(-skipBackMs, fallback)
    fun skipForwardFromSystem(fallback: Player) = skipFromSystem(skipForwardMs, fallback)

    private fun skipFromSystem(delta: Long, fallback: Player) {
        if (controller != null && _ui.value.bookId >= 0) {
            refresh()
            skip(delta)
        } else {
            fallback.seekTo((fallback.currentPosition + delta).coerceAtLeast(0))
        }
    }

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && _ui.value.error != null) _ui.update { it.copy(error = null) }
            refresh()
            if (!isPlaying) save(force = true)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refresh()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            refresh()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val track = controller?.currentMediaItemIndex ?: -1
            CrashLog.note("Плеер: ошибка ${error.errorCodeName}, файл ${track + 1}: ${error.cause?.javaClass?.simpleName} ${error.cause?.message?.take(120)}")
            CrashLog.describe(error, "Ошибка воспроизведения")
            val msg = when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                    "Аудиофайл не найден: его удалили или переместили. Прикрепите аудио заново на странице книги («Файлы книги»)."
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                    "Нет доступа к аудиофайлу. Прикрепите аудио заново на странице книги — тогда оно перенесётся в приложение."
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                    "Не получается прочитать аудиофайл ${if (track >= 0) "№${track + 1} " else ""}— он повреждён или в неподдерживаемом формате."
                androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ->
                    "Звук сейчас занят другим приложением или устройством. Попробуйте ещё раз."
                else -> "Не удалось воспроизвести: " + com.shoosman.bookapp.ErrorText.ru(error) + " (${error.errorCodeName})"
            }
            _ui.update { it.copy(error = msg, isPlaying = false) }
        }
    }

    /** Подключиться к сервису; если он уже что-то играет — подхватить книгу. */
    fun connect() {
        scope.launch { runCatching { ctrl() } }
    }

    private suspend fun ctrl(): MediaController {
        controller?.let { return it }
        val f = future ?: MediaController.Builder(
            app, SessionToken(app, ComponentName(app, PlaybackService::class.java)),
        ).buildAsync().also { future = it }
        val c = try {
            suspendCancellableCoroutine<MediaController> { cont ->
                f.addListener({
                    try {
                        cont.resume(f.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }, ContextCompat.getMainExecutor(app))
            }
        } catch (e: Exception) {
            future = null
            throw e
        }
        if (controller == null) {
            controller = c
            c.addListener(listener)
            restoreFromController(c)
            startTicker()
        }
        return controller!!
    }

    private suspend fun restoreFromController(c: MediaController) {
        val mediaId = c.currentMediaItem?.mediaId ?: return
        val bookId = mediaId.substringBefore(':').toLongOrNull() ?: return
        val book = repo.book(bookId) ?: return
        val tr = repo.tracks(bookId)
        if (tr.size != c.mediaItemCount) return
        setTracks(tr)
        chapters = repo.audioChapters(bookId)
        _ui.value = PlayerUi(
            bookId = bookId, title = book.title, author = book.author, coverPath = book.coverPath,
            trackCount = tr.size, totalMs = totalOf(tr), speed = c.playbackParameters.speed,
        )
        refresh()
    }

    private fun setTracks(tr: List<TrackEntity>) {
        tracks = tr
        starts = LongArray(tr.size)
        for (i in 1 until tr.size) starts[i] = starts[i - 1] + tr[i - 1].durationMs
    }

    private fun totalOf(tr: List<TrackEntity>) = tr.sumOf { it.durationMs }

    /**
     * Загрузить книгу в плеер.
     * @param startGlobalMs позиция от начала книги; null — продолжить с сохранённого места.
     */
    suspend fun open(bookId: Long, startGlobalMs: Long? = null, play: Boolean = false) {
        val book = repo.book(bookId) ?: return
        val tr = repo.tracks(bookId)
        if (tr.isEmpty()) return
        CrashLog.note("Плеер: книга $bookId, файлов ${tr.size}, старт ${startGlobalMs ?: "сохранённый"}, play=$play")
        val c = ctrl()
        if (_ui.value.bookId != bookId || c.mediaItemCount != tr.size) {
            save(force = true)
            setTracks(tr)
            chapters = repo.audioChapters(bookId)
            val artwork = book.coverPath?.let { Uri.fromFile(File(it)) }
            val items = tr.map { t ->
                MediaItem.Builder()
                    .setMediaId("$bookId:${t.idx}")
                    .setUri(Uri.parse(t.uri))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(t.title)
                            .setArtist(book.author)
                            .setAlbumTitle(book.title)
                            .setArtworkUri(artwork)
                            .build(),
                    )
                    .build()
            }
            val (index, pos) = if (startGlobalMs != null) {
                locate(startGlobalMs)
            } else {
                book.audioTrack.coerceIn(0, tr.size - 1) to book.audioPosMs
            }
            c.setMediaItems(items, index, pos)
            c.prepare()
            c.setPlaybackSpeed(book.speed)
            _ui.value = PlayerUi(
                bookId = bookId, title = book.title, author = book.author, coverPath = book.coverPath,
                trackCount = tr.size, totalMs = totalOf(tr), speed = book.speed,
                sleepLeftMs = _ui.value.sleepLeftMs, sleepEndOfChapter = _ui.value.sleepEndOfChapter,
            )
        } else if (startGlobalMs != null) {
            seekGlobal(startGlobalMs)
        }
        if (play) startPlayback(c)
        repo.markOpened(bookId)
        refresh()
    }

    /** Файлы книги поменялись (перенос, замена аудио) — выгрузить её из плеера. */
    fun forget(bookId: Long) {
        if (_ui.value.bookId != bookId) return
        save(force = true)
        controller?.run {
            stop()
            clearMediaItems()
        }
        tracks = emptyList()
        starts = LongArray(0)
        chapters = emptyList()
        _ui.value = PlayerUi()
    }

    private fun locate(globalMs: Long): Pair<Int, Long> {
        if (starts.isEmpty()) return 0 to 0L
        var i = starts.indexOfLast { it <= globalMs }
        if (i < 0) i = 0
        return i to (globalMs - starts[i]).coerceAtLeast(0)
    }

    fun toggle() {
        val c = controller ?: return
        CrashLog.note(if (c.isPlaying) "Плеер: пауза" else "Плеер: играть")
        if (c.isPlaying) c.pause() else startPlayback(c)
    }

    fun play() { controller?.let { startPlayback(it) } }

    /** После ошибки или остановки плеер «спит» — без prepare() кнопка ▶ ничего не делает. */
    private fun startPlayback(c: Player) {
        CrashLog.note("Плеер: ▶ (состояние ${c.playbackState}, файлов ${c.mediaItemCount}, ошибка ${c.playerError?.errorCodeName ?: "нет"})")
        if (c.mediaItemCount == 0) {
            _ui.update { it.copy(error = "В плеере нет загруженной книги. Откройте книгу заново.") }
            return
        }
        if (c.playerError != null || c.playbackState == Player.STATE_IDLE) {
            _ui.update { it.copy(error = null) }
            c.prepare()
        }
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
        c.play()
    }
    fun pause() { controller?.pause() }

    fun seekGlobal(globalMs: Long) {
        val c = controller ?: return
        val total = _ui.value.totalMs
        val target = if (total > 0) globalMs.coerceIn(0, total - 500) else globalMs.coerceAtLeast(0)
        val (i, pos) = locate(target)
        c.seekTo(i, pos)
        refresh()
    }

    fun seekInTrack(posMs: Long) {
        val c = controller ?: return
        c.seekTo(c.currentMediaItemIndex, posMs.coerceAtLeast(0))
        refresh()
    }

    fun skip(deltaMs: Long) = seekGlobal(_ui.value.globalMs + deltaMs)

    fun previousTrack() {
        val c = controller ?: return
        if (raw(c).currentPosition > 3000 || c.currentMediaItemIndex == 0) c.seekTo(c.currentMediaItemIndex, 0) else c.seekToPreviousMediaItem()
        refresh()
    }

    fun nextTrack() {
        controller?.seekToNextMediaItem()
        refresh()
    }

    fun setSpeed(speed: Float) {
        val c = controller ?: return
        CrashLog.note("Скорость: $speed")
        c.setPlaybackSpeed(speed)
        val id = _ui.value.bookId
        _ui.update { it.copy(speed = speed) }
        if (id >= 0) repo.appScope.launch { repo.setSpeed(id, speed) }
    }

    // ---------------- главы ----------------

    /** Пересчитать главы (например, после новой точки синхронизации). */
    fun refreshChapters(bookId: Long) {
        if (_ui.value.bookId != bookId) return
        scope.launch {
            chapters = repo.audioChapters(bookId)
            refresh()
        }
    }

    private fun chapterIndexAt(globalMs: Long): Int {
        if (chapters.isEmpty()) return -1
        var lo = 0
        var hi = chapters.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (chapters[mid].startMs <= globalMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** В начало главы, а если мы и так в её начале — в предыдущую. */
    fun previousChapter() {
        val s = _ui.value
        val i = chapterIndexAt(s.globalMs)
        if (i < 0) return previousTrack()
        val target = if (s.globalMs - chapters[i].startMs > 3000 || i == 0) chapters[i].startMs else chapters[i - 1].startMs
        CrashLog.note("Плеер: предыдущая глава")
        seekGlobal(target)
    }

    fun nextChapter() {
        val i = chapterIndexAt(_ui.value.globalMs)
        if (i < 0) return nextTrack()
        val next = chapters.getOrNull(i + 1) ?: return
        CrashLog.note("Плеер: следующая глава")
        seekGlobal(next.startMs)
    }

    fun goToChapter(index: Int) {
        val ch = chapters.getOrNull(index) ?: return
        seekGlobal(ch.startMs)
    }

    fun seekInChapter(posMs: Long) = seekGlobal(_ui.value.chapterStartMs + posMs.coerceAtLeast(0))

    // ---------------- таймер сна ----------------

    fun setSleepMinutes(minutes: Int) {
        CrashLog.note("Таймер сна: $minutes мин")
        setSleepMillis(minutes * 60_000L)
    }

    /** Добавить минут к идущему таймеру (или запустить, если его нет). */
    fun extendSleep(minutes: Int) {
        val left = _ui.value.sleepLeftMs
        if (left > 0) setSleepMillis(left + minutes * 60_000L) else setSleepMinutes(minutes)
    }

    private fun setSleepMillis(durationMs: Long) {
        cancelSleep()
        val end = SystemClock.elapsedRealtime() + durationMs
        sleepJob = scope.launch {
            while (isActive) {
                val left = end - SystemClock.elapsedRealtime()
                _ui.update { it.copy(sleepLeftMs = left.coerceAtLeast(0)) }
                if (left <= 0) break
                // Последние 10 секунд плавно убавляем громкость.
                if (left < 10_000) controller?.volume = (left / 10_000f).coerceIn(0f, 1f)
                delay(250)
            }
            controller?.pause()
            controller?.volume = 1f
            _ui.update { it.copy(sleepLeftMs = 0) }
            sleepJob = null
        }
    }

    fun setSleepEndOfChapter() {
        cancelSleep()
        CrashLog.note("Таймер сна: до конца главы")
        sleepChapter = _ui.value.chapter
        _ui.update { it.copy(sleepEndOfChapter = true) }
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        controller?.volume = 1f
        sleepChapter = -1
        _ui.update { it.copy(sleepLeftMs = 0, sleepEndOfChapter = false) }
    }

    // ---------------- состояние ----------------

    private fun startTicker() {
        if (tickerJob != null) return
        tickerJob = scope.launch {
            while (isActive) {
                refresh()
                delay(500)
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        if (_ui.value.bookId < 0) return
        val r = raw(c)
        val idx = r.currentMediaItemIndex.coerceAtLeast(0)
        val pos = r.currentPosition.coerceAtLeast(0)
        val known = tracks.getOrNull(idx)?.durationMs ?: 0L
        val dur = if (known > 0) known else r.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val global = (starts.getOrNull(idx) ?: 0L) + pos
        val ci = chapterIndexAt(global)
        val ch = chapters.getOrNull(ci)
        val trackStart = starts.getOrNull(idx) ?: 0L
        _ui.update {
            it.copy(
                isPlaying = c.isPlaying,
                track = idx,
                trackTitle = tracks.getOrNull(idx)?.title.orEmpty(),
                posMs = pos,
                durMs = dur,
                globalMs = global,
                speed = c.playbackParameters.speed,
                chapter = ci.coerceAtLeast(0),
                chapterCount = chapters.size,
                chapterTitle = ch?.title ?: tracks.getOrNull(idx)?.title.orEmpty(),
                chapterStartMs = ch?.startMs ?: trackStart,
                chapterEndMs = ch?.endMs ?: (trackStart + dur),
            )
        }
        // Глава сменилась — шторка должна показать новое название и длительность
        if (ci != notifiedChapter) {
            notifiedChapter = ci
            PlaybackService.current?.chapterChanged()
        }
        // Таймер «до конца главы»: глава сменилась — останавливаемся в начале новой.
        if (_ui.value.sleepEndOfChapter && sleepChapter >= 0 && ci > sleepChapter && c.isPlaying) {
            c.pause()
            cancelSleep()
        }
        if (c.isPlaying && SystemClock.elapsedRealtime() - lastSave > 5_000) save()
    }

    private fun save(force: Boolean = false) {
        val s = _ui.value
        if (s.bookId < 0) return
        if (!force && SystemClock.elapsedRealtime() - lastSave < 5_000) return
        lastSave = SystemClock.elapsedRealtime()
        repo.appScope.launch { repo.saveAudioPos(s.bookId, s.track, s.posMs, s.globalMs) }
    }
}
