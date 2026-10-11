package com.shoosman.bookapp.ui.book

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shoosman.bookapp.App
import com.shoosman.bookapp.data.AppSettings
import com.shoosman.bookapp.data.BookEntity
import com.shoosman.bookapp.data.BookStatus
import com.shoosman.bookapp.data.audioProgress
import com.shoosman.bookapp.data.hasAudio
import com.shoosman.bookapp.data.hasText
import com.shoosman.bookapp.data.textProgress
import com.shoosman.bookapp.ui.common.Cover
import com.shoosman.bookapp.ui.common.MiniPlayer
import com.shoosman.bookapp.ui.common.SectionTitle
import com.shoosman.bookapp.ui.common.fmtDuration
import com.shoosman.bookapp.ui.common.fmtTime
import com.shoosman.bookapp.ui.common.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    bookId: Long,
    settings: AppSettings,
    onBack: () -> Unit,
    /** Открыть читалку; -1 — с сохранённого места. */
    onRead: (Int) -> Unit,
    /** Открыть плеер; -1 — с сохранённого места. */
    onListen: (Long) -> Unit,
) {
    val app = App.instance
    val repo = app.repo
    val book by repo.dao.observe(bookId).collectAsStateWithLifecycle(initialValue = null)
    val tracks by repo.dao.observeTracks(bookId).collectAsStateWithLifecycle(initialValue = emptyList())
    val bookmarks by repo.dao.observeBookmarks(bookId).collectAsStateWithLifecycle(initialValue = emptyList())
    val anchors by repo.dao.observeAnchors(bookId).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var annotationExpanded by remember { mutableStateOf(false) }

    fun runTask(block: suspend () -> Unit) {
        busy = true
        repo.appScope.launch {
            val r = runCatching { block() }
            withContext(Dispatchers.Main) {
                busy = false
                r.onFailure { error = it.message ?: "Ошибка" }
            }
        }
    }

    val pickText = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runTask { repo.attachText(bookId, uri) }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) runTask { repo.attachAudio(bookId, uris) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runTask { repo.attachAudioTree(bookId, uri) }
    }

    val b = book
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, "Изменить") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Удалить") }
                },
            )
        },
        bottomBar = { MiniPlayer(onOpen = { onListen(-1L) }, skipBackSec = settings.skipBackSec) },
    ) { padding ->
        if (b == null) return@Scaffold
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
        ) {
            item { Header(b, tracks.size) }

            item { Column {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (b.hasText) {
                        Button(onClick = { onRead(-1) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (b.readPos > 0) "Читать · ${(b.textProgress * 100).toInt()}%" else "Читать")
                        }
                    }
                    if (b.hasAudio) {
                        val listenLabel = if (b.audioGlobalMs > 0) "Слушать · ${(b.audioProgress * 100).toInt()}%" else "Слушать"
                        if (b.hasText) {
                            FilledTonalButton(onClick = { onListen(-1L) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Headphones, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(listenLabel)
                            }
                        } else {
                            Button(onClick = { onListen(-1L) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Headphones, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(listenLabel)
                            }
                        }
                    }
                }
            } }

            item { Column {
                SectionTitle("Полка")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BookStatus.ordered.forEach { s ->
                        FilterChip(
                            selected = b.status == s,
                            onClick = { scope.launch { repo.setStatus(bookId, s) } },
                            label = { Text(BookStatus.label(s)) },
                        )
                    }
                }
                SectionTitle("Моя оценка")
                Row {
                    for (i in 1..5) {
                        IconButton(onClick = { scope.launch { repo.setRating(bookId, if (b.rating == i) 0 else i) } }) {
                            Icon(
                                if (i <= b.rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = "$i",
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            } }

            if (b.annotation.isNotBlank()) {
                item { Column {
                    SectionTitle("Аннотация")
                    Text(
                        b.annotation,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (annotationExpanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { annotationExpanded = !annotationExpanded },
                    )
                } }
            }

            item { Column {
                SectionTitle("Файлы книги")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Текст", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (b.hasText) "${b.textFormat} · ${b.chapterCount} ${plural(b.chapterCount, "глава", "главы", "глав")}"
                            else "Не добавлен",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { pickText.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 8.dp)) {
                            Text(if (b.hasText) "Заменить текст" else "Добавить текст")
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("Аудио", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (b.hasAudio) "${tracks.size} ${plural(tracks.size, "файл", "файла", "файлов")} · ${fmtDuration(b.audioTotalMs)}"
                            else "Не добавлено",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pickAudio.launch(arrayOf("audio/*")) }) { Text("Файлы") }
                            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Папка") }
                        }

                        if (b.hasText && b.hasAudio) {
                            Spacer(Modifier.height(16.dp))
                            Text("Синхронизация", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (anchors.isEmpty()) {
                                    "Автоматически по главам и длительности. Если текст и аудио расходятся, в читалке нажмите на абзац и выберите «Сейчас звучит здесь»."
                                } else {
                                    "Ручных точек привязки: ${anchors.size}. Между ними позиция рассчитывается автоматически."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (anchors.isNotEmpty()) {
                                TextButton(onClick = { scope.launch { repo.clearAnchors(bookId) } }) { Text("Сбросить привязки") }
                            }
                        }
                    }
                }
            } }

            if (bookmarks.isNotEmpty()) {
                item { SectionTitle("Закладки") }
                items(bookmarks, key = { it.id }) { bm ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (bm.charPos >= 0 && b.hasText) onRead(bm.charPos) else if (bm.audioMs >= 0) onListen(bm.audioMs)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(bm.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            val date = SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(bm.createdAt))
                            val audio = if (bm.audioMs >= 0) " · 🎧 ${fmtTime(bm.audioMs)}" else ""
                            Text("$date$audio", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { scope.launch { repo.deleteBookmark(bm.id) } }) {
                            Icon(Icons.Filled.Delete, "Удалить закладку", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && b != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить книгу?") },
            text = { Text("«${b.title}» исчезнет из библиотеки вместе с закладками. Исходные файлы на телефоне останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (app.player.ui.value.bookId == bookId) app.player.pause()
                    repo.appScope.launch { repo.delete(bookId) }
                    onBack()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }

    if (editing && b != null) {
        var title by remember { mutableStateOf(b.title) }
        var author by remember { mutableStateOf(b.author) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Название и автор") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название") })
                    OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("Автор") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    scope.launch { repo.rename(bookId, title, author) }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Отмена") } },
        )
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
private fun Header(b: BookEntity, trackCount: Int) {
    Row(Modifier.padding(top = 8.dp)) {
        Cover(b.title, b.author, b.coverPath, Modifier.size(110.dp, 160.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, style = MaterialTheme.typography.headlineSmall)
            if (b.author.isNotBlank()) {
                Text(
                    b.author,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            if (b.hasText) {
                Text("📖 ${b.textFormat}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (b.hasAudio) {
                Text(
                    "🎧 ${fmtDuration(b.audioTotalMs)} · $trackCount ${plural(trackCount, "файл", "файла", "файлов")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
