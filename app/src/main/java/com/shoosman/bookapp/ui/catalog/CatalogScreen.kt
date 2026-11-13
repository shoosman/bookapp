package com.shoosman.bookapp.ui.catalog

import com.shoosman.bookapp.ErrorText
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.snapshotFlow
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
import com.shoosman.bookapp.data.hasText
import com.shoosman.bookapp.data.hasAudio
import com.shoosman.bookapp.data.CoverFinder
import com.shoosman.bookapp.data.Opds
import com.shoosman.bookapp.data.OpdsCatalog
import com.shoosman.bookapp.data.OpdsEntry
import com.shoosman.bookapp.data.OpdsFeed
import com.shoosman.bookapp.data.SeriesGuess
import com.shoosman.bookapp.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

/**
 * Каталог OPDS: разделы, жанры, новинки, поиск и скачивание доступных файлов.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(onOpenBrowser: (String) -> Unit, onOpenBook: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val localBooks by App.instance.repo.dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    fun downloaded(entry: OpdsEntry): Boolean = localBooks.any { book ->
        (book.hasText || book.hasAudio) &&
            CoverFinder.norm(book.title) == CoverFinder.norm(entry.title) &&
            entry.authors.any { author -> CoverFinder.norm(book.author).contains(CoverFinder.norm(author).split(' ').lastOrNull().orEmpty()) }
    }
    var catalogs by remember { mutableStateOf(Opds.catalogs(ctx)) }
    var catalogUrl by rememberSaveable { mutableStateOf(if (Opds.catalogs(ctx).size == 1) Opds.catalogs(ctx)[0].url else "") }
    val catalog = catalogs.firstOrNull { it.url == catalogUrl }
    val stack = rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<String>().apply { if (catalogUrl.isNotEmpty()) add(catalogUrl) } }
    var feed by remember { mutableStateOf<OpdsFeed?>(null) }
    val extra = remember { mutableStateListOf<OpdsEntry>() }
    val listState = rememberLazyListState()
    var loadingMore by remember { mutableStateOf(false) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var byAuthor by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var editDialog by remember { mutableStateOf<OpdsCatalog?>(null) }
    var adding by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<OpdsEntry?>(null) }
    var reload by remember { mutableStateOf(0) }
    var template by remember { mutableStateOf<String?>(null) }
    var globalQuery by rememberSaveable { mutableStateOf("") }
    var globalSearching by remember { mutableStateOf(false) }
    var globalResults by remember { mutableStateOf<List<OpdsEntry>>(emptyList()) }
    var globalErrors by remember { mutableStateOf(0) }

    // Параллельный поиск по всем добавленным OPDS-каталогам.
    LaunchedEffect(globalQuery, catalogs, catalogUrl) {
        val q = globalQuery.trim()
        if (catalogUrl.isNotEmpty() || q.length < 2) {
            globalResults = emptyList()
            globalSearching = false
            globalErrors = 0
            return@LaunchedEffect
        }
        delay(450)
        globalSearching = true
        try {
            val batches = supervisorScope {
                catalogs.map { c ->
                    async {
                        try {
                            val template = Opds.searchTemplate(c)
                            if (template == null) emptyList<OpdsEntry>() to false
                            else Opds.load(Opds.searchUrl(template, q)).entries.filter { it.isBook } to false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            emptyList<OpdsEntry>() to true
                        }
                    }
                }.awaitAll()
            }
            globalErrors = batches.count { it.second }
            val unique = LinkedHashMap<String, OpdsEntry>()
            batches.flatMap { it.first }.forEach { book ->
                val key = CoverFinder.norm(book.title) + "|" +
                    CoverFinder.norm(book.authors.firstOrNull().orEmpty())
                val prev = unique[key]
                if (prev == null) unique[key] = book
                else unique[key] = prev.copy(
                    downloads = (prev.downloads + book.downloads).distinctBy { it.second },
                    related = (prev.related + book.related).distinct(),
                    coverUrl = prev.coverUrl ?: book.coverUrl,
                    summary = prev.summary.ifBlank { book.summary },
                )
            }
            val normalizedQuery = CoverFinder.norm(q)
            globalResults = unique.values.sortedByDescending { book ->
                val title = CoverFinder.norm(book.title)
                val author = CoverFinder.norm(book.authors.joinToString(" "))
                when {
                    title == normalizedQuery -> 100
                    title.startsWith(normalizedQuery) -> 80
                    title.contains(normalizedQuery) -> 60
                    author.contains(normalizedQuery) -> 40
                    else -> 0
                }
            }
        } finally {
            globalSearching = false
        }
    }

    fun openCatalog(c: OpdsCatalog?) {
        catalogUrl = c?.url.orEmpty()
        stack.clear()
        if (c != null) stack.add(c.url)
        searching = false
    }

    val current = stack.lastOrNull()
    LaunchedEffect(current, reload) {
        feed = null
        extra.clear()
        next = null
        error = null
        if (current == null) return@LaunchedEffect
        loading = true
        runCatching { Opds.load(current, forceRefresh = reload > 0) }
            .onSuccess { feed = it; next = it.nextUrl }
            .onFailure {
                CrashLog.note("Каталог: ${it.javaClass.simpleName} ${it.message}")
                error = ErrorText.ru(it, "Каталог не открылся")
            }
        loading = false
    }
    LaunchedEffect(catalogUrl) {
        template = catalog?.let { runCatching { Opds.searchTemplate(it) }.getOrNull() }
    }
    // Подгружаем следующую страницу до достижения конца списка.
    LaunchedEffect(current, next, feed, extra.size) {
        val url = next ?: return@LaunchedEffect
        val count = (feed?.entries?.size ?: 0) + extra.size
        if (count == 0 || loadingMore) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .first { it >= count - 5 && it >= 0 }
        if (loadingMore || next != url) return@LaunchedEffect
        loadingMore = true
        try {
            val page = Opds.load(url)
            extra.addAll(page.entries)
            next = page.nextUrl?.takeUnless { it == url }
        } catch (e: Exception) {
            CrashLog.note("Каталог, следующая страница: ${e.message}")
        } finally {
            loadingMore = false
        }
    }


    fun go(url: String) {
        if (url != stack.lastOrNull()) stack.add(url)
    }

    BackHandler(enabled = stack.isNotEmpty() && (stack.size > 1 || catalogs.size > 1)) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else openCatalog(null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (stack.size > 1 || (stack.isNotEmpty() && catalogs.size > 1)) {
                        IconButton(onClick = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else openCatalog(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                        }
                    }
                },
                title = {
                    if (searching && catalog != null) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(if (byAuthor) "Автор" else "Название или автор") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                val t = template
                                if (query.isNotBlank() && t != null) go(Opds.searchUrl(t, query, byAuthor))
                            }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            when {
                                catalog == null -> "Каталоги"
                                stack.size > 1 -> feed?.title ?: catalog.name
                                else -> catalog.name
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (catalog != null) {
                        if (template != null) {
                            if (searching) IconButton(onClick = { searching = false }) { Icon(Icons.Filled.Close, "Закрыть поиск") }
                            else IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Поиск") }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("В начало каталога") },
                                    leadingIcon = { Icon(Icons.Filled.Home, null) },
                                    onClick = { menu = false; openCatalog(catalog) },
                                )
                                DropdownMenuItem(text = { Text("Изменить адрес…") }, onClick = { menu = false; editDialog = catalog })
                                DropdownMenuItem(text = { Text("Все каталоги") }, onClick = { menu = false; openCatalog(null) })
                            }
                        }
                    } else {
                        IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Добавить каталог") }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (catalog == null) {
                OutlinedTextField(
                    value = globalQuery,
                    onValueChange = { globalQuery = it },
                    label = { Text("Искать во всех каталогах") },
                    placeholder = { Text("Название книги или автор") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
                if (globalQuery.trim().length >= 2) {
                    if (globalSearching) {
                        LinearSearchProgress()
                    }
                    if (globalErrors > 0 && !globalSearching) {
                        Text("Недоступных каталогов: $globalErrors", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    if (!globalSearching && globalResults.isEmpty()) {
                        Text("Совпадений нет", modifier = Modifier.padding(16.dp))
                    }
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(globalResults, key = { it.id + "|" + it.title }) { book ->
                            BookRow(book, downloaded(book)) { selected = book }
                            HorizontalDivider()
                        }
                    }
                    return@Column
                }
                CatalogList(
                    catalogs,
                    onOpen = { openCatalog(it) },
                    onEdit = { editDialog = it },
                    onAdd = { adding = true },
                    onOpenWebsite = onOpenBrowser,
                )
                return@Column
            }
            if (searching && catalog.isFlibustaLike) {
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
                        "Адрес: ${catalog.url}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { reload++ }) { Text("Повторить") }
                        OutlinedButton(onClick = { editDialog = catalog }) { Text("Другой адрес") }
                    }
                }
                f != null -> {
                    val all = f.entries + extra
                    if (all.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("Ничего не нашлось", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
                            items(all, key = { it.id + "|" + it.navUrl + "|" + it.title }) { e ->
                                if (e.isBook) BookRow(e, downloaded(e)) { selected = e } else NavRow(e) { e.navUrl?.let { go(it) } }
                                HorizontalDivider()
                            }
                            next?.let { url ->
                                item {
                                    var more by remember { mutableStateOf(false) }
                                    TextButton(
                                        enabled = !more && !loadingMore,
                                        onClick = {
                                            more = true
                                            scope.launch {
                                                runCatching { Opds.load(url) }.onSuccess {
                                                    extra.addAll(it.entries)
                                                    next = it.nextUrl?.takeUnless { newUrl -> newUrl == url }
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
            allowDownload = true,
            onDismiss = { selected = null },
            onRelated = { url -> selected = null; go(url) },
            onOpenBrowser = onOpenBrowser,
            onOpenBook = { id -> selected = null; onOpenBook(id) },
        )
    }

    if (adding || editDialog != null) {
        val editing = editDialog
        var name by remember { mutableStateOf(editing?.name.orEmpty()) }
        var url by remember { mutableStateOf(editing?.url.orEmpty()) }
        AlertDialog(
            onDismissRequest = { adding = false; editDialog = null },
            title = { Text(if (editing == null) "Новый каталог" else "Каталог") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Адрес каталога OPDS. Для Флибусты достаточно адреса зеркала, например flibusta.site.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Название") })
                    OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, label = { Text("Адрес") })
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val chips = if (editing?.isFlibustaLike == true) {
                            listOf("flibusta.is", "flibusta.site", "flibusta.club").map { OpdsCatalog(editing.name, "https://$it/opds") }
                        } else Opds.presets
                        chips.forEach { c ->
                            AssistChip(onClick = { name = c.name; url = c.url }, label = { Text(c.host.ifBlank { c.name }) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = url.isNotBlank(),
                    onClick = {
                        val c = OpdsCatalog(name.ifBlank { runCatching { java.net.URL(Opds.normalizeUrl(url)).host }.getOrDefault("Каталог") }, Opds.normalizeUrl(url))
                        val list = catalogs.toMutableList()
                        val i = list.indexOfFirst { it.url == editing?.url }
                        if (i >= 0) list[i] = c else list.add(c)
                        Opds.saveCatalogs(ctx, list)
                        catalogs = list
                        adding = false
                        editDialog = null
                        openCatalog(c)
                        reload++
                    },
                ) { Text("Сохранить") }
            },
            dismissButton = {
                Row {
                    if (editing != null && catalogs.size > 1) {
                        TextButton(onClick = {
                            val list = catalogs.filter { it.url != editing.url }
                            Opds.saveCatalogs(ctx, list)
                            catalogs = list
                            editDialog = null
                            openCatalog(null)
                        }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { adding = false; editDialog = null }) { Text("Отмена") }
                }
            },
        )
    }
}

@Composable
private fun LinearSearchProgress() {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CatalogList(
    catalogs: List<OpdsCatalog>,
    onOpen: (OpdsCatalog) -> Unit,
    onEdit: (OpdsCatalog) -> Unit,
    onAdd: () -> Unit,
    onOpenWebsite: (String) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        items(catalogs, key = { it.url }) { c ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(c) }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        c.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onEdit(c) }) { Icon(Icons.Filled.MoreVert, "Изменить") }
            }
            HorizontalDivider()
        }
        item {
            Text("Современная фантастика и фэнтези",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp))
            Text("Эти источники открываются во встроенном браузере; это не OPDS-каталоги. Доступные для загрузки файлы можно импортировать в Чтец.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        items(listOf(
            "Author.Today" to "https://author.today/",
            "ЛитМаркет" to "https://litmarket.ru/",
            "Литнет" to "https://litnet.com/",
            "Литрес" to "https://www.litres.ru/",
        )) { (name, url) ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenWebsite(url) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text("Открыть сайт", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
            HorizontalDivider()
        }
        item {
            TextButton(onClick = onAdd, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Добавить каталог OPDS")
            }
            Text(
                "OPDS — открытый формат книжных каталогов: так устроены Флибуста, Project Gutenberg, домашний сервер Calibre и многие библиотеки.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
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
private fun BookRow(e: OpdsEntry, isDownloaded: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        CoverThumb(e.coverUrl, Modifier.size(54.dp, 78.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (isDownloaded) Text("Скачано", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
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
    allowDownload: Boolean,
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
                            val id = repo.createManual(
                                BookInfo(
                                    title = e.title, author = author, annotation = e.summary,
                                    cover = cover, series = e.series, seriesIndex = e.seriesIndex,
                                ),
                                BookStatus.WANT,
                            )
                            if (e.genres.isNotEmpty()) {
                                repo.dao.get(id)?.let { nb -> repo.dao.update(nb.copy(genres = e.genres.joinToString("; "))) }
                            }
                            // Остальное (обложка, цикл, жанры Литреса) — с Литреса
                            App.instance.enricher.enrichOne(id)
                            CrashLog.note("Каталог: добавлена книга в «Хочу прочитать»")
                            adding = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (adding) "Добавляю…" else "Хочу прочитать") }
            }

            // Можно получить текст и аудио из разных источников и объединить в одну запись.
            val textFile = e.downloads.firstOrNull {
                val mime = it.first.lowercase()
                val url = it.second.substringBefore('?').lowercase()
                mime.contains("fb2") || mime.contains("epub") || mime.startsWith("text/") ||
                    mime.contains("wordprocessingml") || url.endsWith(".fb2") ||
                    url.endsWith(".epub") || url.endsWith(".txt") || url.endsWith(".fb2.zip")
            }
            val audioFile = e.downloads.firstOrNull {
                val mime = it.first.lowercase()
                val url = it.second.substringBefore('?').lowercase()
                mime.startsWith("audio/") || url.endsWith(".mp3") ||
                    url.endsWith(".m4b") || url.endsWith(".m4a") ||
                    url.endsWith(".ogg") || url.endsWith(".opus") ||
                    url.endsWith(".flac")
            }
            val alternative = e.downloads.firstOrNull()
            var dl by remember(e.id, e.title) { mutableStateOf<String?>(null) }
            fun downloadFiles(files: List<Pair<String, String>>) {
                if (dl != null || files.isEmpty()) return
                dl = "Скачиваю…"
                repo.appScope.launch {
                    val outcome = runCatching {
                        var bookId: Long? = existing?.id
                        for ((index, file) in files.withIndex()) {
                            dl = "Скачиваю ${index + 1} из ${files.size}…"
                            val downloaded = com.shoosman.bookapp.data.Downloader.download(
                                App.instance, file.second, mime = file.first,
                            )
                            val result = repo.importDownloaded(
                                downloaded.file, downloaded.name, downloaded.mime, bookId,
                            ) {}
                            bookId = result.bookId
                        }
                    }
                    dl = outcome.fold({ "Добавлено в библиотеку" }, { ErrorText.ru(it, "Не удалось скачать") })
                }
            }
            if (textFile != null && audioFile != null) {
                Button(
                    enabled = dl == null,
                    onClick = { downloadFiles(listOf(textFile, audioFile)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(dl ?: "Скачать текст и аудио") }
            }
            if (textFile != null) {
                OutlinedButton(
                    enabled = dl == null,
                    onClick = { downloadFiles(listOf(textFile)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Скачать текст") }
            }
            if (audioFile != null) {
                OutlinedButton(
                    enabled = dl == null,
                    onClick = { downloadFiles(listOf(audioFile)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Скачать аудио") }
            }
            if (textFile == null && audioFile == null && alternative != null) {
                OutlinedButton(
                    enabled = dl == null,
                    onClick = { downloadFiles(listOf(alternative)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Скачать доступный файл") }
            }
            if (dl != null) {
                Text(dl!!, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
