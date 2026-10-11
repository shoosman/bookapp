package com.shoosman.bookapp.ui.catalog

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.shoosman.bookapp.App
import com.shoosman.bookapp.CrashLog
import com.shoosman.bookapp.data.BookInfo
import com.shoosman.bookapp.data.BookStatus
import com.shoosman.bookapp.data.CoverFinder
import com.shoosman.bookapp.data.Opds
import com.shoosman.bookapp.data.OpdsEntry
import com.shoosman.bookapp.data.OpdsFeed
import com.shoosman.bookapp.data.SeriesGuess
import com.shoosman.bookapp.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

/**
 * Каталог (Флибуста по OPDS): разделы, жанры, новинки, поиск. Только описание книг —
 * дальше «Хочу прочитать» или «Где купить».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(onOpenBrowser: (String) -> Unit, onOpenBook: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) {
        mutableStateListOf(Opds.rootUrl(ctx))
    }
    var feed by remember { mutableStateOf<OpdsFeed?>(null) }
    val extra = remember { mutableStateListOf<OpdsEntry>() }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var byAuthor by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var mirrorDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<OpdsEntry?>(null) }
    var reload by remember { mutableStateOf(0) }

    val current = stack.last()
    LaunchedEffect(current, reload) {
        loading = true
        error = null
        feed = null
        extra.clear()
        runCatching { Opds.load(current) }
            .onSuccess { feed = it; next = it.nextUrl }
            .onFailure {
                CrashLog.note("Каталог: ${it.javaClass.simpleName} ${it.message}")
                error = it.message?.takeIf { m -> m.length < 200 } ?: "Каталог не открылся"
            }
        loading = false
    }

    fun go(url: String) {
        if (url != stack.last()) stack.add(url)
    }

    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (stack.size > 1) IconButton(onClick = { stack.removeAt(stack.lastIndex) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(if (byAuthor) "Автор" else "Название книги") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                if (query.isNotBlank()) go(Opds.searchUrl(ctx, query, byAuthor))
                            }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(feed?.title?.takeIf { stack.size > 1 } ?: "Каталог", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { searching = false }) { Icon(Icons.Filled.Close, "Закрыть поиск") }
                    } else {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Поиск") }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("В начало каталога") },
                                leadingIcon = { Icon(Icons.Filled.Home, null) },
                                onClick = { menu = false; stack.clear(); stack.add(Opds.rootUrl(ctx)) },
                            )
                            DropdownMenuItem(text = { Text("Адрес зеркала…") }, onClick = { menu = false; mirrorDialog = true })
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searching) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !byAuthor, onClick = { byAuthor = false }, label = { Text("Книги") })
                    FilterChip(selected = byAuthor, onClick = { byAuthor = true }, label = { Text("Авторы") })
                }
            }
            val f = feed
            when {
                loading && f == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error!!, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Сейчас: ${Opds.host(ctx)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { reload++ }) { Text("Повторить") }
                        OutlinedButton(onClick = { mirrorDialog = true }) { Text("Другое зеркало") }
                    }
                }
                f != null -> {
                    val all = f.entries + extra
                    if (all.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("Ничего не нашлось", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                            items(all, key = { it.id + "|" + it.navUrl + "|" + it.title }) { e ->
                                if (e.isBook) BookRow(e) { selected = e } else NavRow(e) { e.navUrl?.let { go(it) } }
                                HorizontalDivider()
                            }
                            next?.let { url ->
                                item {
                                    var more by remember { mutableStateOf(false) }
                                    TextButton(
                                        enabled = !more,
                                        onClick = {
                                            more = true
                                            scope.launch {
                                                runCatching { Opds.load(url) }.onSuccess {
                                                    extra.addAll(it.entries)
                                                    next = it.nextUrl
                                                }
                                                more = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    ) { Text(if (more) "Загружаю…" else "Ещё") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { e ->
        BookSheet(
            e,
            onDismiss = { selected = null },
            onRelated = { url -> selected = null; go(url) },
            onOpenBrowser = onOpenBrowser,
            onOpenBook = { id -> selected = null; onOpenBook(id) },
        )
    }

    if (mirrorDialog) {
        var host by remember { mutableStateOf(Opds.host(ctx)) }
        AlertDialog(
            onDismissRequest = { mirrorDialog = false },
            title = { Text("Адрес зеркала") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Если каталог не открывается, укажите другое зеркало Флибусты или адрес любого каталога OPDS.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(value = host, onValueChange = { host = it }, singleLine = true, label = { Text("Адрес") })
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("flibusta.is", "flibusta.site", "flibusta.club").forEach { h ->
                            AssistChip(onClick = { host = h }, label = { Text(h) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Opds.setHost(ctx, host)
                    mirrorDialog = false
                    stack.clear()
                    stack.add(Opds.rootUrl(ctx))
                    reload++
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { mirrorDialog = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun NavRow(e: OpdsEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleMedium)
            if (e.summary.isNotBlank()) {
                Text(
                    e.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CoverThumb(url: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(5.dp))) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().padding(0.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawRect(Color(0xFF8A7F96)) }
            }
        }
    }
}

@Composable
private fun BookRow(e: OpdsEntry, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        CoverThumb(e.coverUrl, Modifier.size(54.dp, 78.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.authors.isNotEmpty()) {
                Text(
                    e.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (e.series.isNotBlank()) {
                Text(
                    e.series + if (e.seriesIndex > 0f) " · ${SeriesGuess.format(e.seriesIndex)}" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (e.genres.isNotEmpty()) {
                Text(
                    e.genres.take(3).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookSheet(
    e: OpdsEntry,
    onDismiss: () -> Unit,
    onRelated: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onOpenBook: (Long) -> Unit,
) {
    val repo = App.instance.repo
    val scope = rememberCoroutineScope()
    val author = e.authors.joinToString(", ")
    val books by repo.dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val existing = books.firstOrNull {
        CoverFinder.norm(it.title) == CoverFinder.norm(e.title) &&
            CoverFinder.norm(it.author).contains(CoverFinder.norm(e.authors.firstOrNull().orEmpty()).split(' ').lastOrNull().orEmpty())
    }
    var adding by remember { mutableStateOf(false) }
    var litres by remember { mutableStateOf<String?>(null) }
    var litresChecked by remember { mutableStateOf(false) }

    LaunchedEffect(e.id) {
        litres = withContext(Dispatchers.IO) { runCatching { CoverFinder.litresPage(e.title, author) }.getOrNull() }
        litresChecked = true
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row {
                CoverThumb(e.coverUrl, Modifier.size(96.dp, 140.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.titleLarge)
                    if (author.isNotBlank()) {
                        Text(author, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (e.series.isNotBlank()) {
                        Text(
                            e.series + if (e.seriesIndex > 0f) " · книга ${SeriesGuess.format(e.seriesIndex)}" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (e.genres.isNotEmpty()) {
                        Text(
                            e.genres.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (existing != null) {
                OutlinedButton(onClick = { onOpenBook(existing.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Уже в библиотеке: ${BookStatus.label(existing.status)}")
                }
            } else {
                Button(
                    enabled = !adding,
                    onClick = {
                        adding = true
                        scope.launch {
                            val cover = withContext(Dispatchers.IO) { e.coverUrl?.let { download(it) } }
                            repo.createManual(
                                BookInfo(
                                    title = e.title, author = author, annotation = e.summary,
                                    cover = cover, series = e.series, seriesIndex = e.seriesIndex,
                                ),
                                BookStatus.WANT,
                            )
                            CrashLog.note("Каталог: добавлена книга в «Хочу прочитать»")
                            adding = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (adding) "Добавляю…" else "Хочу прочитать") }
            }

            Text("Где купить", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            val q = URLEncoder.encode(listOf(e.title, e.authors.firstOrNull().orEmpty()).joinToString(" ").trim(), "UTF-8")
            val qTitle = URLEncoder.encode(e.title, "UTF-8")
            when {
                !litresChecked -> Text("Проверяю Литрес…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                litres != null -> TextButton(onClick = { onOpenBrowser(litres!!) }) { Text("Есть на Литресе — открыть") }
                else -> TextButton(onClick = { onOpenBrowser("https://www.litres.ru/search/?q=$q") }) { Text("Искать на Литресе") }
            }
            TextButton(onClick = { onOpenBrowser("https://books.yandex.ru/search?text=$q") }) { Text("Искать в Яндекс Книгах") }
            TextButton(onClick = { onOpenBrowser("https://author.today/search?category=works&q=$qTitle") }) { Text("Искать на Author.Today") }

            if (e.summary.isNotBlank()) {
                Text("Аннотация", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                Text(e.summary, style = MaterialTheme.typography.bodyMedium)
            }
            if (e.related.isNotEmpty()) {
                Text("Ещё", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                e.related.forEach { (label, url) ->
                    TextButton(onClick = { onRelated(url) }) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

private fun download(url: String): ByteArray? = runCatching {
    Http.client.newCall(Request.Builder().url(url).header("User-Agent", "Chtets/1.0").build()).execute().use { r ->
        if (r.isSuccessful) r.body?.bytes()?.takeIf { it.size > 1000 } else null
    }
}.getOrNull()
