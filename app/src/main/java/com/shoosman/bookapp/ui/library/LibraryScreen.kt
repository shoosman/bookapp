package com.shoosman.bookapp.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import com.shoosman.bookapp.data.BookEntity
import com.shoosman.bookapp.data.BookStatus
import com.shoosman.bookapp.data.SeriesGuess
import com.shoosman.bookapp.data.genreList
import com.shoosman.bookapp.data.hasAudio
import com.shoosman.bookapp.data.hasText
import com.shoosman.bookapp.data.progress
import com.shoosman.bookapp.ui.common.Cover
import com.shoosman.bookapp.ui.home.Importer
import com.shoosman.bookapp.ui.home.SHELF_ALL

private val sorts = listOf("Недавно открытые", "Название", "Автор", "Дата добавления", "Оценка")

/** Строка списка: отдельная книга или цикл из нескольких книг. */
private sealed interface Entry {
    val key: String
}

private class Single(val book: BookEntity) : Entry {
    override val key = "b${book.id}"
}

private class SeriesGroup(override val key: String, val title: String, val author: String, val books: List<BookEntity>) : Entry

private fun surnameKey(author: String): String =
    author.substringBefore(',').substringBefore(" и др").trim().lowercase().replace('ё', 'е').split(' ', '.').lastOrNull { it.isNotBlank() }.orEmpty()

/** Книги одного цикла (и одного автора) собираются в группу на месте первой из них. */
private fun groupSeries(list: List<BookEntity>): List<Entry> {
    val byKey = list.filter { it.series.isNotBlank() }
        .groupBy { SeriesGuess.key(it.series) + "|" + surnameKey(it.author) }
    val placed = HashSet<String>()
    val out = ArrayList<Entry>()
    for (b in list) {
        if (b.series.isBlank()) { out.add(Single(b)); continue }
        val k = SeriesGuess.key(b.series) + "|" + surnameKey(b.author)
        val members = byKey[k].orEmpty()
        if (members.size < 2) { out.add(Single(b)); continue }
        if (!placed.add(k)) continue
        val sorted = members.sortedWith(compareBy<BookEntity>({ if (it.seriesIndex > 0f) it.seriesIndex else Float.MAX_VALUE }, { it.title.lowercase() }))
        out.add(SeriesGroup("s$k", b.series, b.author, sorted))
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTab(
    books: List<BookEntity>?,
    shelf: Int,
    onShelfChange: (Int) -> Unit,
    importer: Importer,
    onOpenBook: (Long) -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    var grouped by rememberSaveable { mutableStateOf(true) }
    var genre by rememberSaveable { mutableStateOf("") }
    var genreMenu by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Название или автор") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Библиотека")
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Filled.Close, "Закрыть поиск") }
                    } else {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Поиск") }
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Сортировка") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            sorts.forEachIndexed { i, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = { if (i == sort) Icon(Icons.Filled.Check, null) },
                                    onClick = { sort = i; sortMenu = false },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Циклы вместе") },
                                leadingIcon = { Checkbox(checked = grouped, onCheckedChange = null) },
                                onClick = { grouped = !grouped; sortMenu = false },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importer.open() },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(if (importer.busy) "Добавляю…" else "Добавить") },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val all = books ?: return@Scaffold
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (importer.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (listOf(SHELF_ALL) + BookStatus.ordered).forEach { s ->
                    val count = if (s == SHELF_ALL) all.size else all.count { it.status == s }
                    FilterChip(
                        selected = shelf == s,
                        onClick = { onShelfChange(s) },
                        label = { Text((if (s == SHELF_ALL) "Все" else BookStatus.label(s)) + " · $count") },
                    )
                }
            }

            // Жанры своей библиотеки: самые частые сверху
            val genres = all.flatMap { it.genreList }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key to it.value }
            if (genres.isNotEmpty()) {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    FilterChip(
                        selected = genre.isNotEmpty(),
                        onClick = { genreMenu = true },
                        label = { Text(if (genre.isEmpty()) "Жанр: все" else "Жанр: $genre") },
                        trailingIcon = {
                            if (genre.isNotEmpty()) Icon(Icons.Filled.Close, "Сбросить жанр", Modifier.size(16.dp).clickable { genre = "" })
                        },
                    )
                    DropdownMenu(expanded = genreMenu, onDismissRequest = { genreMenu = false }) {
                        DropdownMenuItem(text = { Text("Все жанры") }, onClick = { genre = ""; genreMenu = false })
                        genres.forEach { (g, n) ->
                            DropdownMenuItem(
                                text = { Text("$g · $n") },
                                leadingIcon = { if (g == genre) Icon(Icons.Filled.Check, null) },
                                onClick = { genre = g; genreMenu = false },
                            )
                        }
                    }
                }
            }

            val q = query.trim().lowercase()
            val shown = all
                .filter { shelf == SHELF_ALL || it.status == shelf }
                .filter { genre.isEmpty() || genre in it.genreList }
                .filter {
                    q.isEmpty() || it.title.lowercase().contains(q) || it.author.lowercase().contains(q) ||
                        it.series.lowercase().contains(q) || it.genres.lowercase().contains(q)
                }
                .let { list ->
                    when (sort) {
                        1 -> list.sortedBy { it.title.lowercase() }
                        2 -> list.sortedBy { it.author.lowercase() }
                        3 -> list.sortedByDescending { it.addedAt }
                        4 -> list.sortedByDescending { it.rating }
                        else -> list.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) }
                    }
                }

            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            all.isEmpty() -> "Здесь пока пусто.\nНажмите «Добавить» и выберите файл книги или аудиокниги."
                            q.isNotEmpty() -> "Ничего не найдено"
                            else -> "На этой полке пока нет книг"
                        },
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                val entries = if (grouped) groupSeries(shown) else shown.map { Single(it) }
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    for (e in entries) {
                        when (e) {
                            is Single -> item(key = e.key) { BookRow(e.book, showSeries = true) { onOpenBook(e.book.id) } }
                            is SeriesGroup -> {
                                val open = q.isNotEmpty() || e.key in expanded
                                item(key = e.key) {
                                    SeriesRow(e, open) {
                                        expanded = if (e.key in expanded) expanded - e.key else expanded + e.key
                                    }
                                }
                                if (open) {
                                    items(e.books, key = { "${e.key}/${it.id}" }) { book ->
                                        BookRow(book, showSeries = false, nested = true) { onOpenBook(book.id) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesRow(group: SeriesGroup, open: Boolean, onToggle: () -> Unit) {
    val finished = group.books.count { it.status == BookStatus.FINISHED }
    val reading = group.books.any { it.status == BookStatus.READING }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Стопка обложек: первая книга цикла сверху
        Box(Modifier.size(64.dp, 84.dp)) {
            group.books.getOrNull(2)?.let { Cover(it.title, it.author, it.coverPath, Modifier.size(50.dp, 72.dp).offset(14.dp, 0.dp)) }
            group.books.getOrNull(1)?.let { Cover(it.title, it.author, it.coverPath, Modifier.size(52.dp, 76.dp).offset(7.dp, 4.dp)) }
            val first = group.books.first()
            Cover(first.title, first.author, first.coverPath, Modifier.offset(0.dp, 4.dp).shadow(3.dp).size(56.dp, 80.dp))
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(group.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (group.author.isNotBlank()) {
                Text(
                    group.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val n = group.books.size
            Text(
                "Цикл · $n ${plural(n, "книга", "книги", "книг")}" +
                    (if (finished in 1 until n) " · прочитано $finished" else if (finished == n) " · всё прочитано" else "") +
                    (if (reading) " · читаю" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Icon(
            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            if (open) "Свернуть" else "Развернуть",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

@Composable
private fun BookRow(book: BookEntity, showSeries: Boolean, nested: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (nested) 12.dp else 16.dp, end = 16.dp, top = if (nested) 6.dp else 10.dp, bottom = if (nested) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nested) {
            Text(
                if (book.seriesIndex > 0f) SeriesGuess.format(book.seriesIndex) else "·",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(28.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Cover(book.title, book.author, book.coverPath, if (nested) Modifier.size(44.dp, 64.dp) else Modifier.size(56.dp, 80.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                style = if (nested) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showSeries && book.series.isNotBlank()) {
                Text(
                    book.series + if (book.seriesIndex > 0f) " · ${SeriesGuess.format(book.seriesIndex)}" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (book.author.isNotBlank() && !nested) {
                Text(
                    book.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val tint = MaterialTheme.colorScheme.onSurfaceVariant
                if (book.hasText) Icon(Icons.AutoMirrored.Filled.MenuBook, "Есть текст", Modifier.size(16.dp), tint = tint)
                if (book.hasAudio) Icon(Icons.Filled.Headphones, "Есть аудио", Modifier.size(16.dp), tint = tint)
                if (book.rating > 0) {
                    Icon(Icons.Filled.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text("${book.rating}", style = MaterialTheme.typography.labelMedium, color = tint)
                }
                if (book.progress > 0f) {
                    LinearProgressIndicator(progress = { book.progress }, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    Text("${(book.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = tint)
                }
            }
        }
    }
}
