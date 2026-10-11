package com.shoosman.bookapp.data

import android.content.Context
import com.shoosman.bookapp.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Дополнение книг с сайтов: обложка, жанры, цикл и номер тома, аннотация, ссылка.
 * Сначала Литрес (точно — по ссылке, иначе по названию и автору), обложка — ещё и с Author.Today.
 */
class Enricher(private val ctx: Context, private val repo: BookRepository) {

    class Progress(val done: Int, val total: Int, val updated: Int, val added: Int, val running: Boolean)

    private val _state = MutableStateFlow<Progress?>(null)
    val state: StateFlow<Progress?> = _state

    private val prefs get() = ctx.getSharedPreferences("enrich", Context.MODE_PRIVATE)

    fun needs(b: BookEntity): Boolean =
        !b.deleted && (b.coverPath == null || b.genres.isBlank() || b.series.isBlank() || b.seriesIndex == 0f || b.annotation.isBlank())

    /** Запись-цикл с «Полки 18+»: в ней описан весь цикл, а не один том. */
    private fun isCyclePlaceholder(b: BookEntity) =
        b.status == BookStatus.WANT && b.textPath == null && b.audioTotalMs == 0L && b.notes.contains("Где: ")

    /** Почему у книги нет обложки — чтобы было видно: её нет нигде или помешала ошибка. */
    class CoverStatus(val notFound: Boolean, val message: String, val at: Long)

    private val statusPrefs get() = ctx.getSharedPreferences("cover_status", Context.MODE_PRIVATE)

    fun coverStatus(uid: String): CoverStatus? {
        val raw = statusPrefs.getString(uid, null) ?: return null
        val parts = raw.split('|', limit = 3)
        if (parts.size < 3) return null
        return CoverStatus(parts[0] == "notfound", parts[2], parts[1].toLongOrNull() ?: 0L)
    }

    private fun setCoverStatus(uid: String, status: CoverStatus?) {
        statusPrefs.edit().apply {
            if (status == null) remove(uid)
            else putString(uid, (if (status.notFound) "notfound" else "error") + "|" + status.at + "|" + status.message)
        }.apply()
    }

    /** true — что-то нашлось и сохранилось. */
    suspend fun enrichOne(bookId: Long): Boolean = withContext(Dispatchers.IO) {
        val b = repo.dao.get(bookId) ?: return@withContext false
        val errors = ArrayList<String>()
        fun <T> attempt(source: String, block: () -> T?): T? = try {
            block()
        } catch (e: Exception) {
            errors.add("$source: " + com.shoosman.bookapp.ErrorText.ru(e))
            null
        }
        val artId = LitresMeta.idFromUrl(b.sourceUrl) ?: attempt("Литрес") { CoverFinder.litresId(b.title, b.author) }
        val art = artId?.let { id -> attempt("Литрес") { LitresMeta.art(id) } }
        var cover: ByteArray? = null
        if (b.coverPath == null) {
            cover = art?.let { a ->
                CoverFinder.download(a.coverUrl).also { if (it == null) errors.add("Литрес: обложка нашлась, но не скачалась") }
            }
                ?: attempt("Author.Today") { CoverFinder.fromAuthorToday(b.title, b.author)?.cover }
                ?: attempt("Фантлаб") { CoverFinder.fromFantlab(b.title, b.author)?.cover }
            setCoverStatus(
                b.uid,
                when {
                    cover != null -> null
                    errors.isNotEmpty() -> CoverStatus(false, errors.joinToString("\n"), System.currentTimeMillis())
                    else -> CoverStatus(true, "Не нашлась ни на Литресе, ни на Author.Today, ни на Фантлабе.", System.currentTimeMillis())
                },
            )
        }
        if (art == null && cover == null) return@withContext false

        val fresh = repo.dao.get(bookId) ?: return@withContext false
        var n = fresh
        if (cover != null && n.coverPath == null) {
            val f = repo.coverFile(File(n.dir).apply { mkdirs() })
            f.writeBytes(cover)
            n = n.copy(coverPath = f.absolutePath)
        }
        if (art != null) {
            if (n.genres.isBlank() && art.genres.isNotEmpty()) n = n.copy(genres = art.genres.joinToString("; "))
            if (n.annotation.isBlank() && art.annotation.isNotBlank()) n = n.copy(annotation = art.annotation.take(4000))
            if (n.sourceUrl.isBlank()) n = n.copy(sourceUrl = art.pageUrl)
            if (art.seriesName.isNotBlank()) {
                if (n.series.isBlank()) {
                    n = n.copy(series = canonicalSeries(art.seriesName, n.author), seriesIndex = art.seriesOrder)
                } else if (n.seriesIndex == 0f && art.seriesOrder > 0f && sameSeries(n.series, art.seriesName, n.title)) {
                    n = n.copy(seriesIndex = art.seriesOrder)
                }
            }
        }
        if (n == fresh) return@withContext false
        repo.dao.update(n.copy(metaUpdatedAt = System.currentTimeMillis()))
        true
    }

    /** Наш цикл и цикл Литреса — один и тот же (с поправкой на написание). */
    private fun sameSeries(ours: String, theirs: String, title: String): Boolean {
        val a = CoverFinder.norm(ours)
        val b = CoverFinder.norm(theirs)
        return a == b || a.contains(b) || b.contains(a) || CoverFinder.norm(title).startsWith(a)
    }

    /** Если у автора уже есть такой цикл с другим написанием — берём его, чтобы книги собрались в одну группу. */
    private suspend fun canonicalSeries(name: String, author: String): String {
        val key = CoverFinder.norm(name)
        val surname = CoverFinder.norm(author.substringBefore(',')).split(' ').lastOrNull().orEmpty()
        return repo.dao.allForSyncBlocking()
            .firstOrNull { !it.deleted && it.series.isNotBlank() && CoverFinder.norm(it.series) == key && CoverFinder.norm(it.author).contains(surname) }
            ?.series ?: name
    }

    class Volume(val art: LitresMeta.Art, val ownedId: Long?)

    /** Тома цикла книги с отметкой, какие уже есть в библиотеке. null — цикл на Литресе не найден. */
    suspend fun seriesVolumes(bookId: Long): List<Volume>? = withContext(Dispatchers.IO) {
        val b = repo.dao.get(bookId) ?: return@withContext null
        val artId = LitresMeta.idFromUrl(b.sourceUrl) ?: CoverFinder.litresId(b.title, b.author) ?: return@withContext null
        val art = LitresMeta.art(artId) ?: return@withContext null
        if (art.seriesId == 0L) return@withContext null
        val vols = LitresMeta.seriesArts(art.seriesId)
        val mine = repo.dao.allForSyncBlocking().filter { !it.deleted }
        vols.map { v ->
            val owned = mine.firstOrNull { m ->
                CoverFinder.norm(m.title) == CoverFinder.norm(v.title) ||
                    (m.series.isNotBlank() && sameSeries(m.series, art.seriesName, m.title) && m.seriesIndex == v.seriesOrder && v.seriesOrder > 0f &&
                        CoverFinder.norm(m.author).contains(CoverFinder.norm(b.author.substringBefore(',')).split(' ').last()))
            }
            Volume(v, owned?.id ?: if (v.id == artId) b.id else null)
        }
    }

    /** Добавить недостающие тома в «Хочу прочитать». Возвращает, сколько добавлено. */
    suspend fun addMissingVolumes(bookId: Long, vols: List<Volume>): Int = withContext(Dispatchers.IO) {
        val b = repo.dao.get(bookId) ?: return@withContext 0
        val series = b.series.ifBlank { vols.firstOrNull()?.art?.seriesName.orEmpty() }
        var added = 0
        for (v in vols) {
            if (v.ownedId != null) continue
            val cover = CoverFinder.download(v.art.coverUrl)
            val id = repo.createManual(
                BookInfo(
                    title = v.art.title, author = v.art.authors.ifBlank { b.author },
                    url = v.art.pageUrl, cover = cover, series = series, seriesIndex = v.art.seriesOrder,
                ),
                BookStatus.WANT,
            )
            repo.dao.get(id)?.let { nb -> if (b.genres.isNotBlank()) repo.dao.update(nb.copy(genres = b.genres)) }
            added++
            delay(150)
        }
        added
    }

    /**
     * Запись-цикл → тома: сама запись становится томом, на который указывает её ссылка,
     * остальные тома добавляются в «Хочу прочитать».
     */
    private suspend fun expandPlaceholder(bookId: Long): Int {
        val vols = seriesVolumes(bookId) ?: return 0
        if (vols.size < 2) return 0
        val b = repo.dao.get(bookId) ?: return 0
        val artId = LitresMeta.idFromUrl(b.sourceUrl)
        val self = vols.firstOrNull { it.art.id == artId } ?: vols.first()
        // Запись становится конкретным томом
        repo.dao.update(
            b.copy(
                title = self.art.title, seriesIndex = self.art.seriesOrder,
                series = b.series.ifBlank { self.art.seriesName },
                sourceUrl = self.art.pageUrl,
                notes = b.notes, metaUpdatedAt = System.currentTimeMillis(),
            ),
        )
        val rest = vols.map { if (it === self) Volume(it.art, bookId) else it }
        return addMissingVolumes(bookId, rest)
    }

    /** Пройтись по библиотеке. auto — автоматический запуск после обновления (один раз). */
    fun run(auto: Boolean = false) {
        if (_state.value?.running == true) return
        if (auto && prefs.getBoolean(KEY_AUTO_DONE, false)) return
        repo.appScope.launch {
            val all = repo.dao.allForSyncBlocking().filter { !it.deleted }
            val ids = all.filter { needs(it) || isCyclePlaceholder(it) }.map { it.id }
            var updated = 0
            var added = 0
            _state.value = Progress(0, ids.size, 0, 0, true)
            CrashLog.note("Дополнение книг: ${ids.size}")
            ids.forEachIndexed { i, id ->
                runCatching {
                    if (enrichOne(id)) updated++
                    val b = repo.dao.get(id)
                    if (b != null && isCyclePlaceholder(b)) {
                        added += expandPlaceholder(id)
                        // Метка выполнена — запись больше не «весь цикл»
                        repo.dao.get(id)?.let { nb -> repo.dao.update(nb.copy(notes = nb.notes.replace("Где: ", "Где купить: "))) }
                    }
                }.onFailure { CrashLog.note("Дополнение: ${it.javaClass.simpleName} ${it.message}") }
                _state.value = Progress(i + 1, ids.size, updated, added, true)
                delay(250)
            }
            _state.value = Progress(ids.size, ids.size, updated, added, false)
            prefs.edit().putBoolean(KEY_AUTO_DONE, true).apply()
            CrashLog.note("Дополнение книг: обновлено $updated, добавлено томов $added")
        }
    }

    companion object {
        private const val KEY_AUTO_DONE = "auto_v2_done"
    }
}
