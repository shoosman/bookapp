package com.shoosman.bookapp.ui.home

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shoosman.bookapp.data.BookEntity
import com.shoosman.bookapp.data.BookStatus
import com.shoosman.bookapp.data.audioProgress
import com.shoosman.bookapp.data.hasAudio
import com.shoosman.bookapp.data.hasText
import com.shoosman.bookapp.data.progress
import com.shoosman.bookapp.data.textProgress
import com.shoosman.bookapp.ui.common.Cover

const val SHELF_ALL = -1
const val SHELF_RECENT = -2

private class Shelf(val title: String, val filter: Int, val books: List<BookEntity>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTab(
    books: List<BookEntity>?,
    importer: Importer,
    onOpenBook: (Long) -> Unit,
    onRead: (Long) -> Unit,
    onListen: (Long) -> Unit,
    onShelf: (Int) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Чтец", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold) })
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
        if (books == null) return@Scaffold
        if (books.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Здесь будут ваши книги.\n\nНажмите «Добавить» и выберите файл книги или аудиокниги — текст и аудио одной книги потом можно объединить.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Scaffold
        }

        val reading = books.filter { it.status == BookStatus.READING }
        val current = reading.filter { it.lastOpenedAt > 0 }.maxByOrNull { it.lastOpenedAt } ?: reading.firstOrNull()
        val shelves = listOf(
            Shelf("Читаю", BookStatus.READING, reading.filter { it.id != current?.id }),
            Shelf("Хочу прочитать", BookStatus.WANT, books.filter { it.status == BookStatus.WANT }),
            Shelf(
                "Недавно добавленные", SHELF_RECENT,
                if (books.size > 4) books.sortedByDescending { it.addedAt }.take(12) else emptyList(),
            ),
            Shelf("Прочитано", BookStatus.FINISHED, books.filter { it.status == BookStatus.FINISHED }.sortedByDescending { it.finishedAt }),
        ).filter { it.books.isNotEmpty() }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            if (current != null) {
                item { ContinueCard(current, onOpenBook, onRead, onListen) }
            }
            items(shelves, key = { it.title }) { shelf ->
                ShelfRow(shelf, onOpenBook, onShelf)
            }
        }
    }
}

@Composable
private fun ContinueCard(
    book: BookEntity,
    onOpenBook: (Long) -> Unit,
    onRead: (Long) -> Unit,
    onListen: (Long) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onOpenBook(book.id) },
    ) {
        Row(Modifier.padding(16.dp)) {
            Cover(book.title, book.author, book.coverPath, Modifier.size(92.dp, 134.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Продолжить",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { book.progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (book.hasText) {
                        Button(onClick = { onRead(book.id) }) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("${(book.textProgress * 100).toInt()}%")
                        }
                    }
                    if (book.hasAudio) {
                        FilledTonalButton(onClick = { onListen(book.id) }) {
                            Icon(Icons.Filled.Headphones, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("${(book.audioProgress * 100).toInt()}%")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(shelf: Shelf, onOpenBook: (Long) -> Unit, onShelf: (Int) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onShelf(if (shelf.filter == SHELF_RECENT) SHELF_ALL else shelf.filter) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shelf.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                "${shelf.books.size}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Вся полка", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelf.books, key = { it.id }) { book -> BookTile(book) { onOpenBook(book.id) } }
        }
    }
}

@Composable
private fun BookTile(book: BookEntity, onClick: () -> Unit) {
    Column(Modifier.width(104.dp).clickable(onClick = onClick)) {
        Cover(book.title, book.author, book.coverPath, Modifier.size(104.dp, 152.dp))
        if (book.progress > 0f && book.status != BookStatus.FINISHED) {
            LinearProgressIndicator(
                progress = { book.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
            )
        }
        Text(
            book.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (book.author.isNotBlank()) {
            Text(
                book.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
