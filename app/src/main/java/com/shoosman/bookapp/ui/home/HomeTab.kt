package com.shoosman.bookapp.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import com.shoosman.bookapp.data.SeriesGuess
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

/** Размер обложек на полках главной (настройка «Обложки на главной»). */
val LocalTileScale = androidx.compose.runtime.compositionLocalOf { 1f }

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
                ShelfRow(shelf, books, onOpenBook, onShelf)
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
private fun ShelfRow(shelf: Shelf, allBooks: List<BookEntity>, onOpenBook: (Long) -> Unit, onShelf: (Int) -> Unit) {
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
        var openSeries by remember { mutableStateOf<List<BookEntity>?>(null) }
        val tiles = remember(shelf.books, allBooks) { tilesOf(shelf.books, allBooks) }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(tiles, key = { it.first }) { (_, group) ->
                if (group.size == 1) BookTile(group[0]) { onOpenBook(group[0].id) }
                else SeriesTile(group) { openSeries = group }
            }
        }
        openSeries?.let { g -> SeriesBooksSheet(g, onDismiss = { openSeries = null }) { id -> openSeries = null; onOpenBook(id) } }
    }
}

/** Все тома цикла, независимо от полки и наличия скачанного файла. */
private fun tilesOf(shelfBooks: List<BookEntity>, allBooks: List<BookEntity>): List<Pair<String, List<BookEntity>>> {
    fun authorKey(book: BookEntity): String = book.author.substringBefore(',').trim()
        .lowercase().replace('ё', 'е').split(' ').lastOrNull().orEmpty()

    // Строим индекс один раз вместо поиска по всем книгам для каждого тома.
    val knownSeries = HashMap<String, String>()
    for (book in allBooks) {
        if (book.series.isNotBlank()) {
            knownSeries[SeriesGuess.key(book.series) + "|" + authorKey(book)] = book.series
        }
    }
    fun seriesOf(book: BookEntity): String {
        if (book.series.isNotBlank()) return book.series
        SeriesGuess.guess(book.title)?.let { return it.series }
        val titleKey = SeriesGuess.key(book.title)
        val author = authorKey(book)
        return knownSeries["$titleKey|$author"]
            ?: if (author.isBlank()) knownSeries.entries.firstOrNull {
                it.key.startsWith("$titleKey|")
            }?.value.orEmpty() else ""
    }
    val keys = HashMap<Long, String>()
    val seriesNames = HashMap<Long, String>()
    val groups = HashMap<String, MutableList<BookEntity>>()
    for (book in allBooks) {
        val name = seriesOf(book)
        if (name.isBlank()) continue
        val key = SeriesGuess.key(name) + "|" + authorKey(book)
        keys[book.id] = key
        seriesNames[book.id] = name
        groups.getOrPut(key) { ArrayList() }.add(book)
    }
    val placed = HashSet<String>()
    val out = ArrayList<Pair<String, List<BookEntity>>>()
    for (book in shelfBooks) {
        val key = keys[book.id]
        val group = if (key != null) groups[key].orEmpty() else emptyList()
        if (group.size < 2) {
            out.add("b${book.id}" to listOf(book))
            continue
        }
        if (key != null && placed.add(key)) {
            val series = seriesNames[book.id].orEmpty()
            val sorted = group.sortedWith(compareBy<BookEntity>(
                {
                    when {
                        it.seriesIndex > 0f -> it.seriesIndex
                        SeriesGuess.key(it.title) == SeriesGuess.key(series) -> 1f
                        else -> Float.MAX_VALUE
                    }
                },
                { it.title.lowercase() },
            ))
            out.add("s$key" to sorted)
        }
    }
    return out
}

@Composable
private fun SeriesTile(books: List<BookEntity>, onClick: () -> Unit) {
    val first = books.first()
    val k = LocalTileScale.current
    Column(Modifier.width(104.dp * k).clickable(onClick = onClick)) {
        Box(Modifier.size(104.dp * k, 152.dp * k)) {
            books.getOrNull(1)?.let { Cover(it.title, it.author, it.coverPath, Modifier.size(92.dp * k, 140.dp * k).offset(12.dp * k, 0.dp)) }
            Cover(first.title, first.author, first.coverPath, Modifier.offset(0.dp, 6.dp).shadow(3.dp).size(94.dp * k, 146.dp * k))
            Text(
                "${books.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        Text(
            first.series,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "Цикл · ${books.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeriesBooksSheet(books: List<BookEntity>, onDismiss: () -> Unit, onOpen: (Long) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(books.firstOrNull { it.series.isNotBlank() }?.series ?: books.first().title, style = MaterialTheme.typography.titleLarge)
            Text(books.first().author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val seen = HashSet<String>()
            books.forEach { b ->
                val duplicate = !seen.add(SeriesGuess.key(b.title) + "|" + b.seriesIndex)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(b.id) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (b.seriesIndex > 0f) SeriesGuess.format(b.seriesIndex) else "·",
                        modifier = Modifier.width(30.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Cover(b.title, b.author, b.coverPath, Modifier.size(40.dp, 58.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(BookStatus.label(b.status) + if (duplicate) " · Повтор в библиотеке" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookTile(book: BookEntity, onClick: () -> Unit) {
    val k = LocalTileScale.current
    Column(Modifier.width(104.dp * k).clickable(onClick = onClick)) {
        Cover(book.title, book.author, book.coverPath, Modifier.size(104.dp * k, 152.dp * k))
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
