package com.shoosman.bookapp.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shoosman.bookapp.App
import com.shoosman.bookapp.data.AppSettings
import com.shoosman.bookapp.data.BookEntity
import com.shoosman.bookapp.data.BookStatus
import com.shoosman.bookapp.data.hasAudio
import com.shoosman.bookapp.data.hasText
import com.shoosman.bookapp.data.progress
import com.shoosman.bookapp.ui.common.Cover
import com.shoosman.bookapp.ui.common.MiniPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ALL = -1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    settings: AppSettings,
    onOpenBook: (Long) -> Unit,
    onOpenPlayer: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    val app = App.instance
    val books by app.repo.dao.observeAll().collectAsStateWithLifecycle(initialValue = null)
    val tabs = BookStatus.ordered + ALL
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runImport(block: suspend () -> Long) {
        busy = true
        app.repo.appScope.launch {
            val result = runCatching { block() }
            withContext(Dispatchers.Main) {
                busy = false
                result.onSuccess { onOpenBook(it) }
                    .onFailure { error = it.message ?: "Не удалось добавить книгу" }
            }
        }
    }

    val pickText = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runImport { app.repo.createFromText(uri) }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) runImport { app.repo.createFromAudio(uris) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runImport { app.repo.createFromAudioTree(uri) }
    }

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
                        Text("Мои книги")
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Закрыть поиск")
                        }
                    } else {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Поиск")
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                        }
                    }
                },
            )
        },
        bottomBar = { MiniPlayer(onOpen = onOpenPlayer, skipBackSec = settings.skipBackSec) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!busy) showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(if (busy) "Добавляю…" else "Добавить") },
            )
        },
    ) { padding ->
        val all = books
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (all == null) return@Column

            val q = query.trim().lowercase()
            val filtered = if (q.isEmpty()) all else all.filter {
                it.title.lowercase().contains(q) || it.author.lowercase().contains(q)
            }

            if (!searching) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                    tabs.forEachIndexed { i, status ->
                        val count = if (status == ALL) all.size else all.count { it.status == status }
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = { Text(if (status == ALL) "Все · $count" else "${BookStatus.label(status)} · $count") },
                        )
                    }
                }
            }

            val status = tabs[tab]
            val shown = if (searching || status == ALL) filtered else filtered.filter { it.status == status }

            if (shown.isEmpty()) {
                EmptyState(
                    when {
                        all.isEmpty() -> "Здесь пока пусто.\nНажмите «Добавить» и выберите файл книги или аудиокниги."
                        searching -> "Ничего не найдено"
                        else -> "На этой полке пока нет книг"
                    },
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(shown, key = { it.id }) { book ->
                        BookRow(book, onClick = { onOpenBook(book.id) })
                    }
                }
            }
        }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }) {
            Text(
                "Добавить книгу",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text("Текст книги") },
                supportingContent = { Text("FB2, EPUB, TXT, DOCX, HTML, ZIP") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                modifier = Modifier.clickable { showAdd = false; pickText.launch(arrayOf("*/*")) },
            )
            ListItem(
                headlineContent = { Text("Аудиокнига — файлы") },
                supportingContent = { Text("Выберите один или несколько файлов") },
                leadingContent = { Icon(Icons.Filled.AudioFile, null) },
                modifier = Modifier.clickable { showAdd = false; pickAudio.launch(arrayOf("audio/*")) },
            )
            ListItem(
                headlineContent = { Text("Аудиокнига — папка") },
                supportingContent = { Text("Все аудиофайлы из папки по порядку") },
                leadingContent = { Icon(Icons.Filled.Folder, null) },
                modifier = Modifier.clickable { showAdd = false; pickFolder.launch(null) },
            )
            Text(
                "Текст и аудио одной книги объединяются на её странице: добавьте одно, а второе прикрепите там.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("Понятно") } },
            title = { Text("Не получилось") },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
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
                    LinearProgressIndicator(
                        progress = { book.progress },
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    Text("${(book.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = tint)
                }
            }
        }
    }
}
