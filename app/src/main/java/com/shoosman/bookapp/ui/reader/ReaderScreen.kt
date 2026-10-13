package com.shoosman.bookapp.ui.reader

import com.shoosman.bookapp.ErrorText
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.shoosman.bookapp.ui.common.fmtTime
import com.shoosman.bookapp.ui.settings.TextSettingsPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    bookId: Long,
    startPos: Int,
    settings: AppSettings,
    onBack: () -> Unit,
    /** Переключиться на аудио с этой позиции (мс от начала книги). */
    onOpenPlayer: (Long) -> Unit,
) {
    val repo = App.instance.repo
    val book by repo.dao.observe(bookId).collectAsStateWithLifecycle(initialValue = null)
    val tracks by repo.dao.observeTracks(bookId).collectAsStateWithLifecycle(initialValue = emptyList())
    val anchors by repo.dao.observeAnchors(bookId).collectAsStateWithLifecycle(initialValue = emptyList())
    val bookmarks by repo.dao.observeBookmarks(bookId).collectAsStateWithLifecycle(initialValue = emptyList())

    var content by remember { mutableStateOf<BookContent?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Переключились на текст — останавливаем аудио этой книги. Только при входе,
    // а не при повороте экрана, чтобы не прерывать чтение под аудио.
    var pausedOnOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(bookId) {
        if (!pausedOnOpen) {
            pausedOnOpen = true
            val player = App.instance.player
            if (player.ui.value.bookId == bookId && player.ui.value.isPlaying) {
                CrashLog.note("Читалка: открыт текст — аудио на паузу")
                player.pause()
            }
        }
    }

    LaunchedEffect(bookId) {
        val b = repo.book(bookId) ?: return@LaunchedEffect
        CrashLog.note("Читалка: открыта книга $bookId (${b.textFormat})")
        try {
            content = repo.content(b) ?: run { loadError = "У этой книги нет текста"; null }
        } catch (e: Exception) {
            loadError = "Не удалось открыть текст. " + ErrorText.ru(e)
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
    /** Переключиться на аудио с этой позиции (мс от начала книги). */
    onOpenPlayer: (Long) -> Unit,
) {
    val app = App.instance
    val repo = app.repo
    val player = app.player
    val bookId = book.id
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val ps by player.ui.collectAsStateWithLifecycle()

    var marks by remember { mutableStateOf<List<Long>>(emptyList()) }
    LaunchedEffect(tracks) { marks = repo.embeddedMarks(book, tracks).map { it.startMs } }
    val mapper = remember(c, tracks, anchors, marks) {
        if (tracks.isNotEmpty()) SyncMapper.build(c, tracks, anchors, marks) else null
    }
    val hasAudio = tracks.isNotEmpty()
    val audioActive = ps.bookId == bookId

    var showUi by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var follow by rememberSaveable { mutableStateOf(true) }

    // Текущее место чтения — общее для обоих режимов.
    var curChar by rememberSaveable { mutableIntStateOf(if (startPos >= 0) startPos else book.readPos) }
    val jumps = remember { MutableSharedFlow<Int>(extraBufferCapacity = 4) }
    fun jumpTo(char: Int) {
        follow = false
        jumps.tryEmit(char.coerceIn(0, c.totalChars))
    }

    LaunchedEffect(Unit) {
        snapshotFlow { curChar }.collectLatest {
            delay(600)
            repo.saveReadPos(bookId, it)
        }
    }

    val curPara = c.paragraphAt(curChar)
    val curChapter = c.chapterAt(curChar)
    val chapterTitle = c.chapters.getOrNull(curChapter)?.title.orEmpty()

    // Где сейчас звучит аудио.
    // Текст и аудио — разные режимы: в читалке аудио не играет и не подсвечивается.
    val audioChar = -1
    val audioPara = -1

    // Экран не гаснет, пока открыта книга.
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Яркость: своя для читалки, меняется вертикальным свайпом в режиме книги.
    val activity = remember(ctx) { ctx.findActivity() }
    var brightness by remember { mutableFloatStateOf(settings.brightness) }
    var brightnessHint by remember { mutableStateOf(false) }
    LaunchedEffect(settings.brightness) { brightness = settings.brightness }
    LaunchedEffect(brightness) {
        activity?.window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (brightness < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }
    LaunchedEffect(brightnessHint) {
        if (brightnessHint) {
            delay(1200)
            brightnessHint = false
        }
    }
    fun systemBrightness(): Float = runCatching {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrDefault(0.5f).coerceIn(0.02f, 1f)

    /** Кнопка «слушать»: если страница та же, с которой пришли из аудио, — продолжаем ровно с того места. */
    fun backToAudio() {
        val exact = com.shoosman.bookapp.player.ListenHandoff.resumeMs(bookId, curChar)
        if (exact != null) {
            CrashLog.note("Читалка: возврат к аудио на то же место (${fmtTime(exact)})")
            repo.appScope.launch { repo.saveReadPos(bookId, curChar) }
            onOpenPlayer(exact)
        } else {
            listenFrom(curPara)
        }
    }

    fun listenFrom(i: Int) {
        val m = mapper ?: return
        val ms = m.msAt(c.paragraphs[i].start)
        CrashLog.note("Читалка: переключение на аудио с абзаца $i (${fmtTime(ms)})")
        repo.appScope.launch { repo.saveReadPos(bookId, c.paragraphs[i].start) }
        onOpenPlayer(ms)
    }

    fun syncHere(i: Int) {
        if (!audioActive) {
            scope.launch { snackbar.showSnackbar("Сначала послушайте аудио, остановите его и вернитесь к тексту") }
            return
        }
        val ms = ps.globalMs
        CrashLog.note("Читалка: привязка абзаца $i к ${fmtTime(ms)}")
        scope.launch {
            repo.addAnchor(bookId, ms, c.paragraphs[i].start)
            player.refreshChapters(bookId)
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

    fun userMoved() {
        if (audioActive && ps.isPlaying) follow = false
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        key(settings.pageMode) {
            if (settings.pageMode) {
                PagedBody(
                    c = c,
                    settings = settings,
                    initialChar = curChar,
                    jumps = jumps,
                    audioChar = audioChar,
                    audioPara = audioPara,
                    follow = follow,
                    isPlaying = audioActive && ps.isPlaying,
                    chapterTitle = chapterTitle,
                    onCharChange = { curChar = it },
                    onTapCenter = { showUi = !showUi },
                    onLongPress = { selected = it },
                    onUserTurn = { userMoved() },
                    onBrightnessDelta = { d ->
                        val base = if (brightness < 0) systemBrightness() else brightness
                        brightness = (base + d * 1.3f).coerceIn(0.02f, 1f)
                        brightnessHint = true
                    },
                    onBrightnessEnd = {
                        val v = brightness
                        scope.launch { app.settings.update { it.copy(brightness = v) } }
                    },
                )
            } else {
                ScrollBody(
                    c = c,
                    settings = settings,
                    initialChar = curChar,
                    jumps = jumps,
                    audioPara = audioPara,
                    follow = follow,
                    isPlaying = audioActive && ps.isPlaying,
                    onCharChange = { curChar = it },
                    onTap = { showUi = !showUi },
                    onLongPress = { selected = it },
                    onUserScroll = { userMoved() },
                )
            }
        }

        // Подсказка яркости
        AnimatedVisibility(
            visible = brightnessHint,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WbSunny, null)
                    Spacer(Modifier.width(10.dp))
                    Text("${(brightness.coerceAtLeast(0f) * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
                }
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
                            chapterTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { sheet = "toc" }) { Icon(Icons.AutoMirrored.Filled.List, "Оглавление") }
                    IconButton(onClick = { addBookmark(curPara) }) { Icon(Icons.Filled.BookmarkAdd, "Добавить закладку") }
                    IconButton(onClick = { sheet = "bookmarks" }) { Icon(Icons.Filled.Bookmarks, "Закладки") }
                    IconButton(onClick = { sheet = "text" }) { Icon(Icons.Filled.FormatSize, "Оформление") }
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
                    val progress = curChar.toFloat() / c.totalChars.coerceAtLeast(1)
                    val shown = sliderValue ?: progress
                    Row {
                        Text("${(shown * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Глава ${curChapter + 1} из ${c.chapters.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = shown.coerceIn(0f, 1f),
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            val target = ((sliderValue ?: progress) * c.totalChars).roundToInt()
                            sliderValue = null
                            jumpTo(target)
                        },
                    )
                    if (hasAudio) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            ReadListenSwitch(enabled = mapper != null) { backToAudio() }
                        }
                    }
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
                    headlineContent = { Text("Аудио остановилось здесь") },
                    supportingContent = { Text("Привязать место, где вы остановили аудио, к этому абзацу — синхронизация станет точнее") },
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
                                jumpTo(ch.start)
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
                            jumpTo(bm.charPos)
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

/** Переключатель «читать | слушать»: текущий режим — текст, нажатие на 🎧 открывает аудио. */
@Composable
private fun ReadListenSwitch(enabled: Boolean, onListen: () -> Unit) {
    Surface(
        shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(52.dp, 38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        androidx.compose.material.icons.Icons.AutoMirrored.Filled.Notes, "Читать",
                        Modifier.size(22.dp), tint = MaterialTheme.colorScheme.background,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(52.dp, 38.dp)
                    .clickable(enabled = enabled, onClick = onListen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Headphones, "Слушать с этого места", Modifier.size(22.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
