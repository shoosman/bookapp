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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
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
import com.shoosman.bookapp.data.BookEntity
import com.shoosman.bookapp.data.BookStatus
import com.shoosman.bookapp.data.hasAudio
import com.shoosman.bookapp.data.hasText
import com.shoosman.bookapp.data.progress
import com.shoosman.bookapp.ui.common.Cover
import com.shoosman.bookapp.ui.home.Importer
import com.shoosman.bookapp.ui.home.SHELF_ALL

private val sorts = listOf("Недавно открытые", "Название", "Автор", "Дата добавления", "Оценка")

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

            val q = query.trim().lowercase()
            val shown = all
                .filter { shelf == SHELF_ALL || it.status == shelf }
                .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.author.lowercase().contains(q) }
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
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(shown, key = { it.id }) { book -> BookRow(book) { onOpenBook(book.id) } }
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: BookEntity, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(book.title, book.author, book.coverPath, Modifier.size(56.dp, 80.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotBlank()) {
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
