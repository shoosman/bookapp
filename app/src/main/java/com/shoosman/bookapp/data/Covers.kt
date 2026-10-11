package com.shoosman.bookapp.data

import com.shoosman.bookapp.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Поиск недостающих обложек: для одной книги или для всей библиотеки в фоне. */
class Covers(private val repo: BookRepository) {

    class Progress(val done: Int, val total: Int, val found: Int, val running: Boolean)

    private val _state = MutableStateFlow<Progress?>(null)
    val state: StateFlow<Progress?> = _state

    /** true — обложка нашлась и сохранена. */
    suspend fun findOne(bookId: Long): Boolean = withContext(Dispatchers.IO) {
        val b = repo.dao.get(bookId) ?: return@withContext false
        val found = runCatching { CoverFinder.find(b.title, b.author) }
            .onFailure { CrashLog.note("Обложка: ошибка поиска ${it.javaClass.simpleName}") }
            .getOrNull() ?: return@withContext false
        val dir = File(b.dir).apply { mkdirs() }
        val file = repo.coverFile(dir)
        file.writeBytes(found.cover)
        val fresh = repo.dao.get(bookId) ?: return@withContext false
        repo.dao.update(
            fresh.copy(
                coverPath = file.absolutePath,
                sourceUrl = fresh.sourceUrl.ifBlank { found.pageUrl },
                metaUpdatedAt = System.currentTimeMillis(),
            ),
        )
        true
    }

    fun missingCount(books: List<BookEntity>) = books.count { it.coverPath == null }

    /** Пройтись по всем книгам без обложки. Повторный вызов во время работы ничего не делает. */
    fun findMissing() {
        if (_state.value?.running == true) return
        repo.appScope.launch {
            val ids = repo.dao.allForSyncBlocking().filter { !it.deleted && it.coverPath == null }.map { it.id }
            var found = 0
            _state.value = Progress(0, ids.size, 0, true)
            CrashLog.note("Обложки: ищу для ${ids.size} книг")
            ids.forEachIndexed { i, id ->
                if (findOne(id)) found++
                _state.value = Progress(i + 1, ids.size, found, true)
                delay(250) // не нагружаем сайты
            }
            _state.value = Progress(ids.size, ids.size, found, false)
            CrashLog.note("Обложки: найдено $found из ${ids.size}")
        }
    }
}
