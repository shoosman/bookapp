package com.shoosman.bookapp.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shoosman.bookapp.App
import com.shoosman.bookapp.CrashLog
import com.shoosman.bookapp.data.AnchorEntity
import com.shoosman.bookapp.data.AppSettings
import com.shoosman.bookapp.data.BookEntity
import com.shoosman.bookapp.data.BookmarkEntity
import com.shoosman.bookapp.data.TrackEntity
import com.shoosman.bookapp.sync.SyncMapper
import com.shoosman.bookapp.text.BookContent
import com.shoosman.bookapp.text.ParaKind
import com.shoosman.bookapp.text.Paragraph
import com.shoosman.bookapp.ui.common.fmtTime
import com.shoosman.bookapp.ui.settings.TextSettingsPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    bookId: Long,
    startPos: Int,
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val repo = App.instance.repo
    val book by repo.dao.observe(bookId).collectAsStateWithLifecycle(initialValue = null)
    val tracks by repo.dao.observeTracks(bookId).collectAsStateWithLifecycle(initialValue = emptyList())
    val anchors by repo.dao.observeAnchors(bookId).collectAsStateWithLifecycle(initialValue = emptyList())
    val bookmarks by repo.dao.observeBookmarks(bookId).collectAsStateWithLifecycle(initialValue = emptyList())

    var content by remember { mutableStateOf<BookContent?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bookId) {
        val b = repo.book(bookId) ?: return@LaunchedEffect
        CrashLog.note("Читалка: открыта книга $bookId (${b.textFormat})")
        try {
            content = repo.content(b) ?: run { loadError = "У этой книги нет текста"; null }
        } catch (e: Exception) {
            loadError = "Не удалось открыть текст: ${e.message}"
        }
        repo.markOpened(bookId)
    }

    val c = content
    val b = book
    if (c == null || b == null) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            val err = loadError
            if (err != null) Text(err, modifier = Modifier.padding(32.dp)) else CircularProgressIndicator()
        }
        return
    }
    ReaderContent(b, c, tracks, anchors, bookmarks, startPos, settings, onBack, onOpenPlayer)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    book: BookEntity,
    c: BookContent,
    tracks: List<TrackEntity>,
    anchors: List<AnchorEntity>,
    bookmarks: List<BookmarkEntity>,
    startPos: Int,
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val app = App.instance
    val repo = app.repo
    val player = app.player
    val bookId = book.id
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val ps by player.ui.collectAsStateWithLifecycle()

    val mapper = remember(c, tracks, anchors) {
        if (tracks.isNotEmpty()) SyncMapper.build(c, tracks, anchors) else null
    }
    val hasAudio = tracks.isNotEmpty()
    val audioActive = ps.bookId == bookId

    var showUi by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var restored by remember { mutableStateOf(false) }

    // Экран не гаснет, пока открыта книга.
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Восстанавливаем место чтения.
    LaunchedEffect(Unit) {
        val pos = if (startPos >= 0) startPos else book.readPos
        listState.scrollToItem(c.paragraphAt(pos))
        restored = true
    }

    // Сохраняем место чтения.
    LaunchedEffect(restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.canScrollForward }.collectLatest { (idx, canScroll) ->
            delay(600)
            val pos = if (!canScroll) c.totalChars else c.paragraphs.getOrNull(idx)?.start ?: 0
            repo.saveReadPos(bookId, pos)
        }
    }

    val curPara by remember { derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, (c.paragraphs.size - 1).coerceAtLeast(0)) } }
    val curChapter = c.chapterAt(c.paragraphs.getOrNull(curPara)?.start ?: 0)

    // Где сейчас звучит аудио.
    val audioPara = if (audioActive && mapper != null) c.paragraphAt(mapper.charAt(ps.globalMs)) else -1

    // Ручная прокрутка во время прослушивания выключает «следовать за аудио».
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        if (dragged && audioActive && ps.isPlaying) follow = false
    }

    LaunchedEffect(audioPara, follow, ps.isPlaying, restored) {
        if (!restored || !follow || !audioActive || !ps.isPlaying || audioPara < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (audioPara < first || audioPara >= last) listState.animateScrollToItem(audioPara)
    }

    fun listenFrom(i: Int) {
        val m = mapper ?: return
        val ms = m.msAt(c.paragraphs[i].start)
        CrashLog.note("Читалка: слушать с абзаца $i (${fmtTime(ms)})")
        follow = true
        scope.launch { player.open(bookId, ms, play = true) }
    }

    fun syncHere(i: Int) {
        if (!audioActive) {
            scope.launch { snackbar.showSnackbar("Сначала включите аудио, а потом отметьте, где оно звучит") }
            return
        }
        val ms = ps.globalMs
        CrashLog.note("Читалка: привязка абзаца $i к ${fmtTime(ms)}")
        scope.launch {
            repo.addAnchor(bookId, ms, c.paragraphs[i].start)
            follow = true
            snackbar.showSnackbar("Готово: ${fmtTime(ms)} в аудио = этот абзац")
        }
    }

    fun addBookmark(i: Int) {
        val p = c.paragraphs.getOrNull(i) ?: return
        val ms = mapper?.msAt(p.start) ?: -1L
        scope.launch {
            repo.addBookmark(bookId, p.start, ms, p.text)
            snackbar.showSnackbar("Закладка добавлена")
        }
    }

    val bars = WindowInsets.systemBars.asPaddingValues()
    val bg = MaterialTheme.colorScheme.background

    Box(Modifier.fillMaxSize().background(bg)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 22.dp,
                end = 22.dp,
                top = bars.calculateTopPadding() + 24.dp,
                bottom = bars.calculateBottomPadding() + 96.dp,
            ),
        ) {
            itemsIndexed(c.paragraphs) { i, p ->
                ParagraphView(
                    p = p,
                    s = settings,
                    highlighted = i == audioPara,
                    onTap = { showUi = !showUi },
                    onLongPress = { selected = i },
                )
            }
        }

        // Верхняя панель
        AnimatedVisibility(
            visible = showUi,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            c.chapters.getOrNull(curChapter)?.title.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { sheet = "toc" }) { Icon(Icons.AutoMirrored.Filled.List, "Оглавление") }
                    IconButton(onClick = { addBookmark(curPara) }) { Icon(Icons.Filled.BookmarkAdd, "Добавить закладку") }
                    IconButton(onClick = { sheet = "bookmarks" }) { Icon(Icons.Filled.Bookmarks, "Закладки") }
                    IconButton(onClick = { sheet = "text" }) { Icon(Icons.Filled.FormatSize, "Оформление текста") }
                }
            }
        }

        // Нижняя панель
        AnimatedVisibility(
            visible = showUi,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    var sliderValue by remember { mutableStateOf<Float?>(null) }
                    val last = (c.paragraphs.size - 1).coerceAtLeast(1)
                    val progress = curPara.toFloat() / last
                    val shownProgress = sliderValue ?: progress
                    Row {
                        Text("${(shownProgress * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Глава ${curChapter + 1} из ${c.chapters.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = shownProgress,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            val target = ((sliderValue ?: progress) * last).roundToInt()
                            sliderValue = null
                            follow = false
                            scope.launch { listState.scrollToItem(target) }
                        },
                    )
                    if (hasAudio) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledIconButton(onClick = { if (audioActive) player.toggle() else listenFrom(curPara) }) {
                                Icon(
                                    if (audioActive && ps.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    if (audioActive && ps.isPlaying) "Пауза" else "Слушать",
                                )
                            }
                            TextButton(onClick = { listenFrom(curPara) }) {
                                Icon(Icons.Filled.Headphones, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Слушать отсюда")
                            }
                            Spacer(Modifier.weight(1f))
                            IconToggleButton(
                                checked = follow,
                                onCheckedChange = {
                                    follow = it
                                    if (it && audioPara >= 0) scope.launch { listState.animateScrollToItem(audioPara) }
                                },
                            ) {
                                Icon(
                                    if (follow) Icons.Filled.MyLocation else Icons.Filled.LocationSearching,
                                    "Следовать за аудио",
                                )
                            }
                            IconButton(onClick = onOpenPlayer) { Icon(Icons.Filled.GraphicEq, "Открыть плеер") }
                        }
                    }
                }
            }
        }

        // Компактные кнопки, когда панели скрыты, а аудио играет.
        if (!showUi && audioActive) {
            Column(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (!follow && ps.isPlaying && audioPara >= 0) {
                    SmallFloatingActionButton(onClick = {
                        follow = true
                        scope.launch { listState.animateScrollToItem(audioPara) }
                    }) { Icon(Icons.Filled.MyLocation, "К месту аудио") }
                }
                SmallFloatingActionButton(onClick = { player.toggle() }) {
                    Icon(if (ps.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Пауза/играть")
                }
            }
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (showUi) 160.dp else 16.dp),
        )
    }

    // ---------- Действия с абзацем ----------
    selected?.let { i ->
        val p = c.paragraphs[i]
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Text(
                p.text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (hasAudio) {
                ListItem(
                    headlineContent = { Text("Слушать с этого места") },
                    leadingContent = { Icon(Icons.Filled.Headphones, null) },
                    modifier = Modifier.clickable { selected = null; listenFrom(i) },
                )
                ListItem(
                    headlineContent = { Text("Сейчас звучит здесь") },
                    supportingContent = { Text("Привязать текущее место аудио к этому абзацу, чтобы синхронизация была точнее") },
                    leadingContent = { Icon(Icons.Filled.Link, null) },
                    modifier = Modifier.clickable { selected = null; syncHere(i) },
                )
            }
            ListItem(
                headlineContent = { Text("Добавить закладку") },
                leadingContent = { Icon(Icons.Filled.BookmarkAdd, null) },
                modifier = Modifier.clickable { selected = null; addBookmark(i) },
            )
            ListItem(
                headlineContent = { Text("Копировать текст") },
                leadingContent = { Icon(Icons.Filled.ContentCopy, null) },
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(p.text))
                    selected = null
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---------- Нижние листы ----------
    when (sheet) {
        "toc" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Text("Оглавление", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val tocState = rememberLazyListState(initialFirstVisibleItemIndex = (curChapter - 3).coerceAtLeast(0))
            LazyColumn(state = tocState) {
                itemsIndexed(c.chapters) { i, ch ->
                    val isCurrent = i == curChapter
                    Text(
                        ch.title,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                sheet = null
                                follow = false
                                scope.launch { listState.scrollToItem(ch.paragraph) }
                            }
                            .padding(start = (24 + ch.depth * 16).dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        "bookmarks" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Text("Закладки", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val list = bookmarks.filter { it.charPos >= 0 }
            if (list.isEmpty()) {
                Text(
                    "Закладок пока нет. Нажмите на значок закладки сверху или удерживайте палец на абзаце.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn {
                itemsIndexed(list) { _, bm ->
                    ListItem(
                        headlineContent = { Text(bm.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable {
                            sheet = null
                            follow = false
                            scope.launch { listState.scrollToItem(c.paragraphAt(bm.charPos)) }
                        },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        "text" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            TextSettingsPanel(settings, Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParagraphView(
    p: Paragraph,
    s: AppSettings,
    highlighted: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val size = s.fontSize
    val base = TextStyle(
        color = MaterialTheme.colorScheme.onBackground,
        fontFamily = if (s.serif) FontFamily.Serif else FontFamily.SansSerif,
        fontSize = size.sp,
        lineHeight = (size * s.lineHeight).sp,
        hyphens = Hyphens.Auto,
        lineBreak = LineBreak.Paragraph,
    )
    val style: TextStyle
    val pad: Modifier
    when (p.kind) {
        ParaKind.TITLE -> {
            style = base.copy(
                fontSize = (size * 1.25f).sp,
                lineHeight = (size * 1.25f * 1.3f).sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                hyphens = Hyphens.None,
            )
            pad = Modifier.padding(top = 28.dp, bottom = 14.dp)
        }
        ParaKind.SUBTITLE -> {
            style = base.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            pad = Modifier.padding(vertical = 10.dp)
        }
        ParaKind.EPIGRAPH -> {
            style = base.copy(fontStyle = FontStyle.Italic, fontSize = (size * 0.92f).sp)
            pad = Modifier.padding(start = 48.dp, bottom = 4.dp)
        }
        ParaKind.POEM -> {
            style = base.copy(fontStyle = FontStyle.Italic)
            pad = Modifier.padding(start = 32.dp)
        }
        ParaKind.AUTHOR -> {
            style = base.copy(fontStyle = FontStyle.Italic, textAlign = TextAlign.End)
            pad = Modifier.padding(bottom = 10.dp)
        }
        ParaKind.TEXT -> {
            style = base.copy(
                textAlign = if (s.justify) TextAlign.Justify else TextAlign.Start,
                textIndent = TextIndent(firstLine = (size * 1.5f).sp),
            )
            pad = Modifier.padding(bottom = (size * 0.35f).dp)
        }
    }
    val highlight = if (highlighted) {
        Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), RoundedCornerShape(4.dp))
    } else {
        Modifier
    }
    Text(
        p.text,
        style = style,
        modifier = Modifier
            .fillMaxWidth()
            .then(highlight)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .then(pad),
    )
}
