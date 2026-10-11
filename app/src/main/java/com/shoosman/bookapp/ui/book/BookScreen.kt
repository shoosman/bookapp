package com.shoosman.bookapp.ui.book

import com.shoosman.bookapp.ErrorText
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
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
import com.shoosman.bookapp.data.SeriesGuess
import com.shoosman.bookapp.data.genreList
import com.shoosman.bookapp.data.audioProgress
import com.shoosman.bookapp.data.hasAudio
import com.shoosman.bookapp.data.hasText
import com.shoosman.bookapp.data.textProgress
import com.shoosman.bookapp.CrashLog
import com.shoosman.bookapp.ui.common.Cover
import com.shoosman.bookapp.data.ImportProgress
import com.shoosman.bookapp.data.OnProgress
import com.shoosman.bookapp.ui.common.ErrorDialog
import com.shoosman.bookapp.ui.common.NoticeDialog
import com.shoosman.bookapp.ui.common.ProgressDialog
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
    /** Открыть другую книгу (например, том того же цикла). */
    onOpenOther: (Long) -> Unit = {},
) {
    val app = App.instance
    val repo = app.repo
    val book by repo.dao.observe(bookId).collectAsStateWithLifecycle(initialValue = null)
    val tracks by repo.dao.observeTracks(bookId).collectAsStateWithLifecycle(initialValue = emptyList())
    val bookmarks by repo.dao.observeBookmarks(bookId).collectAsStateWithLifecycle(initialValue = emptyList())
    val anchors by repo.dao.observeAnchors(bookId).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var annotationExpanded by remember { mutableStateOf(false) }
    var editingNotes by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var progress by remember { mutableStateOf<ImportProgress?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    /** Долгая операция с файлами: показывает ход, предупреждение и ошибку. */
    fun runTask(audioChanges: Boolean, block: suspend (OnProgress) -> String?) {
        busy = true
        progress = ImportProgress("Подождите…", null)
        if (audioChanges) app.player.forget(bookId)
        repo.appScope.launch {
            val r = runCatching { block { progress = it } }
            withContext(Dispatchers.Main) {
                busy = false
                progress = null
                r.onSuccess { notice = it }
                r.onFailure {
                    CrashLog.note("Ошибка: ${it.javaClass.simpleName}")
                    error = ErrorText.ru(it, "Ошибка") to CrashLog.describe(it, "Ошибка на странице книги")
                }
            }
        }
    }

    // Любые файлы — текст заменит текст книги, аудио — аудио; архивы разбираются сами
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) runTask(true) { p -> repo.importFiles(uris, bookId, p).notice }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runTask(true) { p -> repo.attachAudioTree(bookId, uri, p) }
    }

    val b = book
    var confetti by remember { mutableStateOf(0) }
    com.shoosman.bookapp.ui.common.Confetti(confetti)
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
            if (b.coverPath == null) {
                item {
                    var looking by remember { mutableStateOf(false) }
                    var notFound by remember { mutableStateOf(false) }
                    Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = !looking,
                            onClick = {
                                looking = true
                                notFound = false
                                scope.launch {
                                    notFound = !app.enricher.enrichOne(bookId)
                                    looking = false
                                }
                            },
                        ) { Text(if (looking) "Ищу обложку…" else "Найти обложку") }
                    }
                    val status = remember(looking, notFound) { app.enricher.coverStatus(b.uid) }
                    if (!looking && status != null) {
                        Text(
                            if (status.notFound) status.message else "Не удалось найти обложку. " + status.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.notFound) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    }
                }
            }

            // Аннотация — сразу под обложкой
            if (b.annotation.isNotBlank()) {
                item {
                    Column(Modifier.padding(top = 14.dp).clickable { annotationExpanded = !annotationExpanded }) {
                        Text(
                            b.annotation,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (annotationExpanded) Int.MAX_VALUE else 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!annotationExpanded && b.annotation.length > 300) {
                            Text("Читать полностью", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            item { Column {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                if (!b.hasText && !b.hasAudio) {
                    Text(
                        "У книги нет файлов — она хранится в вашем списке. Прикрепить текст или аудио можно ниже, в «Файлах книги».",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
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

            if (b.series.isNotBlank() || b.genres.isNotBlank()) {
                item {
                    var showSeries by remember { mutableStateOf(false) }
                    Column {
                        if (b.genreList.isNotEmpty()) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                b.genreList.forEach { g ->
                                    androidx.compose.material3.SuggestionChip(onClick = {}, label = { Text(g) })
                                }
                            }
                        }
                        if (b.series.isNotBlank()) {
                            OutlinedButton(onClick = { showSeries = true }, modifier = Modifier.padding(top = 8.dp)) {
                                Text("Книги цикла «${b.series}»", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (showSeries) SeriesSheet(b, onDismiss = { showSeries = false }, onOpen = { id -> showSeries = false; onOpenOther(id) })
                }
            }

            item { Column {
                SectionTitle("Полка")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BookStatus.ordered.forEach { s ->
                        FilterChip(
                            selected = b.status == s,
                            onClick = {
                                if (s == BookStatus.FINISHED && b.status != BookStatus.FINISHED && settings.confetti) confetti++
                                scope.launch { repo.setStatus(bookId, s) }
                            },
                            label = { Text(BookStatus.label(s)) },
                        )
                    }
                }
                SectionTitle("Моя оценка")
                Row(Modifier.padding(start = 0.dp)) {
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

                // Даты чтения
                SectionTitle("Даты")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateChip("Начал", b.startedAt) { picking = "start" }
                    DateChip("Закончил", b.finishedAt) { picking = "finish" }
                }

                // Заметки
                SectionTitle("Заметки")
                Text(
                    b.notes.ifBlank { "Мысли, цитаты, отзыв — нажмите, чтобы написать" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (b.notes.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editingNotes = true }
                        .padding(vertical = 6.dp),
                )

                if (b.sourceUrl.isNotBlank()) {
                    SectionTitle("Страница книги")
                    Text(
                        b.sourceUrl.removePrefix("https://").removePrefix("http://").removePrefix("www.").take(60),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            runCatching {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(b.sourceUrl)))
                            }
                        },
                    )
                }
            } }

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

                        Spacer(Modifier.height(12.dp))
                        Text("Аудио", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (b.hasAudio) "${tracks.size} ${plural(tracks.size, "файл", "файла", "файлов")} · ${fmtDuration(b.audioTotalMs)}"
                            else "Не добавлено",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.Button(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Добавить файлы") }
                            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Папка") }
                        }
                        Text(
                            "Текст, аудио или архив — Чтец сам разберётся. Новый текст заменит старый, новое аудио — старое.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        if (tracks.any { repo.isExternal(it.uri) }) {
                            Text(
                                "Аудио лежит вне приложения: если удалить файлы, оно перестанет играть.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            TextButton(onClick = { runTask(true) { p -> repo.moveAudioIntoApp(bookId, p) } }) {
                                Text("Перенести аудио в приложение")
                            }
                        }

                        if (b.hasText && b.hasAudio) {
                            Spacer(Modifier.height(16.dp))
                            Text("Синхронизация", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (anchors.isEmpty()) {
                                    "Автоматически по главам и длительности. Если текст и аудио расходятся, остановите аудио, перейдите к тексту, задержите палец на нужном абзаце и выберите «Аудио остановилось здесь»."
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
        val hasFiles = b.hasText || b.hasAudio
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${b.title}»?") },
            text = {
                Text(
                    if (hasFiles) {
                        "«Только файлы» — освободить место: текст и аудио удалятся, а книга останется в списке с полкой, оценкой, заметками и закладками.\n\n«Совсем» — книга исчезнет из библиотеки целиком. Отменить это нельзя."
                    } else {
                        "Книга исчезнет из библиотеки вместе с оценкой и заметками. Отменить это нельзя."
                    },
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (hasFiles) {
                        TextButton(onClick = {
                            confirmDelete = false
                            app.player.forget(bookId)
                            repo.appScope.launch { repo.deleteFiles(bookId) }
                        }) { Text("Только файлы") }
                    }
                    TextButton(onClick = {
                        confirmDelete = false
                        app.player.forget(bookId)
                        repo.appScope.launch { repo.delete(bookId) }
                        onBack()
                    }) { Text("Удалить совсем", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
                }
            },
        )
    }

    if (editing && b != null) {
        var title by remember { mutableStateOf(b.title) }
        var author by remember { mutableStateOf(b.author) }
        var url by remember { mutableStateOf(b.sourceUrl) }
        var annotation by remember { mutableStateOf(b.annotation) }
        var series by remember { mutableStateOf(b.series) }
        var seriesNo by remember { mutableStateOf(if (b.seriesIndex > 0f) SeriesGuess.format(b.seriesIndex) else "") }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("О книге") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название") })
                    OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("Автор") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = series, onValueChange = { series = it },
                            label = { Text("Цикл") }, singleLine = true, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = seriesNo, onValueChange = { seriesNo = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                            label = { Text("№") }, singleLine = true, modifier = Modifier.width(72.dp),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                            ),
                        )
                    }
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Ссылка") }, singleLine = true)
                    OutlinedTextField(value = annotation, onValueChange = { annotation = it }, label = { Text("Аннотация") }, maxLines = 6)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    scope.launch {
                        repo.updateInfo(
                            bookId, title, author, url, annotation,
                            series, seriesNo.replace(',', '.').toFloatOrNull() ?: 0f,
                        )
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Отмена") } },
        )
    }

    if (editingNotes && b != null) {
        var text by remember { mutableStateOf(b.notes) }
        AlertDialog(
            onDismissRequest = { editingNotes = false },
            title = { Text("Заметки") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Мысли, цитаты, отзыв…") },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editingNotes = false
                    scope.launch { repo.setNotes(bookId, text) }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editingNotes = false }) { Text("Отмена") } },
        )
    }

    val pickingWhat = picking
    if (pickingWhat != null && b != null) {
        val initial = if (pickingWhat == "start") b.startedAt else b.finishedAt
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.takeIf { it > 0 } ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    val ms = state.selectedDateMillis ?: 0L
                    picking = null
                    scope.launch {
                        if (pickingWhat == "start") repo.setDates(bookId, ms, b.finishedAt) else repo.setDates(bookId, b.startedAt, ms)
                    }
                }) { Text("Готово") }
            },
            dismissButton = {
                TextButton(onClick = {
                    picking = null
                    scope.launch {
                        if (pickingWhat == "start") repo.setDates(bookId, 0, b.finishedAt) else repo.setDates(bookId, b.startedAt, 0)
                    }
                }) { Text("Убрать дату") }
            },
        ) { DatePicker(state = state) }
    }

    progress?.let { ProgressDialog(it) }
    notice?.let { NoticeDialog(it) { notice = null } }

    error?.let { (msg, details) ->
        ErrorDialog(msg, details, onDismiss = { error = null })
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
            if (b.series.isNotBlank()) {
                Text(
                    b.series + if (b.seriesIndex > 0f) " · книга ${SeriesGuess.format(b.seriesIndex)}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
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

@Composable
private fun DateChip(label: String, date: Long, onClick: () -> Unit) {
    androidx.compose.material3.AssistChip(
        onClick = onClick,
        label = {
            Text(
                if (date > 0) "$label: ${SimpleDateFormat("d MMM yyyy", Locale("ru")).format(Date(date))}" else "$label: —",
            )
        },
    )
}

/** Тома цикла: что уже есть в библиотеке и чего не хватает (по данным Литреса). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SeriesSheet(b: BookEntity, onDismiss: () -> Unit, onOpen: (Long) -> Unit) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val books by app.repo.dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val mine = books.filter { it.series == b.series }
        .sortedWith(compareBy({ if (it.seriesIndex > 0f) it.seriesIndex else Float.MAX_VALUE }, { it.title }))
    var vols by remember { mutableStateOf<List<com.shoosman.bookapp.data.Enricher.Volume>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var addedMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(b.id) {
        vols = runCatching { app.enricher.seriesVolumes(b.id) }.getOrNull()
        loaded = true
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(b.series, style = MaterialTheme.typography.titleLarge)
            Text("В библиотеке: ${mine.size}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            mine.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(m.id) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (m.seriesIndex > 0f) SeriesGuess.format(m.seriesIndex) else "·",
                        modifier = Modifier.width(32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(m.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(BookStatus.label(m.status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SectionTitle("На Литресе")
            val v = vols
            when {
                !loaded -> Text("Ищу цикл на Литресе…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                v == null -> Text("Цикл на Литресе не найден.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val missing = v.count { it.ownedId == null }
                    v.forEach { vol ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (vol.art.seriesOrder > 0f) SeriesGuess.format(vol.art.seriesOrder) else "·",
                                modifier = Modifier.width(32.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(vol.art.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (vol.ownedId != null) "есть" else "нет",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (vol.ownedId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (missing > 0) {
                        androidx.compose.material3.Button(
                            enabled = !adding,
                            onClick = {
                                adding = true
                                scope.launch {
                                    val n = app.enricher.addMissingVolumes(b.id, v)
                                    addedMsg = "Добавлено в «Хочу прочитать»: $n"
                                    vols = app.enricher.seriesVolumes(b.id)
                                    adding = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text(if (adding) "Добавляю…" else "Добавить недостающие ($missing) в «Хочу прочитать»") }
                    }
                    addedMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
        }
    }
}
