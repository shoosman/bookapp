package com.shoosman.bookapp.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shoosman.bookapp.App
import com.shoosman.bookapp.data.AppSettings
import com.shoosman.bookapp.data.hasText
import com.shoosman.bookapp.sync.SyncMapper
import com.shoosman.bookapp.text.BookContent
import com.shoosman.bookapp.ui.common.Cover
import com.shoosman.bookapp.ui.common.fmtDuration
import com.shoosman.bookapp.ui.common.fmtTime
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

fun fmtSpeed(v: Float): String = "%.2f".format(v).trimEnd('0').trimEnd(',', '.')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    bookId: Long,
    startMs: Long,
    settings: AppSettings,
    onBack: () -> Unit,
    onReadAt: (Int) -> Unit,
) {
    val app = App.instance
    val player = app.player
    val repo = app.repo
    val book by repo.dao.observe(bookId).collectAsStateWithLifecycle(initialValue = null)
    val tracks by repo.dao.observeTracks(bookId).collectAsStateWithLifecycle(initialValue = emptyList())
    val anchors by repo.dao.observeAnchors(bookId).collectAsStateWithLifecycle(initialValue = emptyList())
    val s by player.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var content by remember { mutableStateOf<BookContent?>(null) }
    LaunchedEffect(bookId) {
        val fresh = player.ui.value.bookId != bookId
        player.open(bookId, if (startMs >= 0) startMs else null, play = fresh || startMs >= 0)
        val b = repo.book(bookId)
        if (b != null && b.hasText) content = runCatching { repo.content(b) }.getOrNull()
    }
    var marks by remember { mutableStateOf<List<Long>>(emptyList()) }
    LaunchedEffect(tracks) {
        val b = repo.book(bookId) ?: return@LaunchedEffect
        marks = repo.embeddedMarks(b, tracks).map { it.startMs }
    }
    val c = content
    val mapper = remember(c, tracks, anchors, marks) {
        if (c != null && tracks.isNotEmpty()) SyncMapper.build(c, tracks, anchors, marks) else null
    }
    val active = s.bookId == bookId
    var sheet by remember { mutableStateOf<String?>(null) }

    fun currentChar(): Int = mapper?.charAt(if (active) s.globalMs else (book?.audioGlobalMs ?: 0L)) ?: -1

    fun readHere() {
        val pos = currentChar()
        if (pos >= 0) onReadAt(pos)
    }

    fun addBookmark() {
        if (!active) return
        val ms = s.globalMs
        val pos = currentChar()
        val snippet = c?.let { if (pos >= 0) it.paragraphs.getOrNull(it.paragraphAt(pos))?.text else null }
            ?: "${s.chapterTitle} · ${fmtTime(s.chapterPosMs)}"
        scope.launch {
            repo.addBookmark(bookId, pos, ms, snippet)
            snackbar.showSnackbar("Закладка добавлена")
        }
    }

    val b = book
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (b != null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Верх: свернуть
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    RoundButton(Icons.Filled.KeyboardArrowDown, "Свернуть", onBack)
                }

                // Обложка занимает всё свободное место
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Cover(
                        b.title, b.author, b.coverPath,
                        Modifier.fillMaxHeight().aspectRatio(0.72f, matchHeightConstraintsFirst = true),
                    )
                }

                // Сколько осталось в главе, с учётом скорости
                val leftInChapter = ((s.chapterDurMs - s.chapterPosMs) / s.speed.coerceAtLeast(0.1f)).toLong()
                Text(
                    "Ещё ${fmtDuration(leftInChapter.coerceAtLeast(60_000))}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Переключатель «слушать | читать»
                if (b.hasText) {
                    Spacer(Modifier.height(14.dp))
                    ModeSwitch(onRead = { readHere() }, readEnabled = mapper != null)
                }

                // Глава и книга
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { sheet = "chapters" }) {
                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, "Главы")
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { sheet = "chapters" }
                            .padding(horizontal = 4.dp),
                    ) {
                        Text(
                            s.chapterTitle.ifBlank { b.title },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            b.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { addBookmark() }) {
                        Icon(Icons.Outlined.BookmarkBorder, "Закладка")
                    }
                }

                // Таймлайн главы
                var drag by remember { mutableStateOf<Float?>(null) }
                val dur = s.chapterDurMs.coerceAtLeast(1L)
                val shownPos = drag?.toLong() ?: s.chapterPosMs
                Slider(
                    value = (drag ?: s.chapterPosMs.toFloat()).coerceIn(0f, dur.toFloat()),
                    valueRange = 0f..dur.toFloat(),
                    onValueChange = { drag = it },
                    onValueChangeFinished = {
                        drag?.let { player.seekInChapter(it.toLong()) }
                        drag = null
                    },
                    colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(fmtTime(shownPos), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text("- " + fmtTime((s.chapterDurMs - shownPos).coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium)
                }

                // Управление
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { player.previousChapter() }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Filled.SkipPrevious, "Предыдущая глава", Modifier.size(36.dp))
                    }
                    SeekButton(settings.skipBackSec, forward = false) { player.skip(-settings.skipBackSec * 1000L) }
                    Surface(
                        onClick = { player.toggle() },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(84.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (s.isPlaying) "Пауза" else "Играть",
                                Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.background,
                            )
                        }
                    }
                    SeekButton(settings.skipForwardSec, forward = true) { player.skip(settings.skipForwardSec * 1000L) }
                    IconButton(
                        onClick = { player.nextChapter() },
                        enabled = s.chapter < s.chapterCount - 1,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(Icons.Filled.SkipNext, "Следующая глава", Modifier.size(36.dp))
                    }
                }

                // Низ: таймер сна и скорость
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    val sleepOn = s.sleepLeftMs > 0 || s.sleepEndOfChapter
                    Row(
                        Modifier
                            .clickable { sheet = "sleep" }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Timer, "Таймер сна",
                            tint = if (sleepOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (sleepOn) {
                            Text(
                                if (s.sleepEndOfChapter) " конец главы" else " " + fmtTime(s.sleepLeftMs),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        "${fmtSpeed(s.speed)}x",
                        fontSize = 22.sp,
                        color = if (abs(s.speed - 1f) > 0.01f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { sheet = "speed" }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
    }

    when (sheet) {
        "chapters" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("Главы", style = MaterialTheme.typography.titleMedium)
                if (s.totalMs > 0) {
                    val left = ((s.totalMs - s.globalMs) / s.speed.coerceAtLeast(0.1f)).toLong()
                    Text(
                        "До конца книги ${fmtDuration(left)} · прослушано ${s.globalMs * 100 / s.totalMs}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val list = player.currentChapters
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (s.chapter - 3).coerceAtLeast(0))
            LazyColumn(state = listState) {
                itemsIndexed(list) { i, ch ->
                    val current = i == s.chapter
                    ListItem(
                        headlineContent = {
                            Text(
                                ch.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        trailingContent = { Text(fmtTime(ch.durationMs), style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.clickable {
                            player.goToChapter(i)
                            sheet = null
                        },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        "speed" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                var v by remember { mutableFloatStateOf(s.speed) }
                Text("Скорость: ${fmtSpeed(v)}x", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = v,
                    onValueChange = { v = (it * 20).roundToInt() / 20f },
                    onValueChangeFinished = { player.setSpeed(v) },
                    valueRange = 0.5f..3f,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.8f, 1f, 1.2f, 1.4f, 1.5f, 1.75f, 2f, 2.5f).forEach { preset ->
                        FilterChip(
                            selected = abs(v - preset) < 0.01f,
                            onClick = { v = preset; player.setSpeed(preset) },
                            label = { Text("${fmtSpeed(preset)}x") },
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }

        "sleep" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Таймер сна", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                listOf(5, 10, 15, 20, 30, 45, 60, 90).forEach { min ->
                    ListItem(
                        headlineContent = { Text("$min мин") },
                        modifier = Modifier.clickable { player.setSleepMinutes(min); sheet = null },
                    )
                }
                ListItem(
                    headlineContent = { Text("До конца главы") },
                    modifier = Modifier.clickable { player.setSleepEndOfChapter(); sheet = null },
                )
                if (s.sleepLeftMs > 0 || s.sleepEndOfChapter) {
                    ListItem(
                        headlineContent = { Text("Выключить таймер", color = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable { player.cancelSleep(); sheet = null },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description) }
    }
}

/** Круглая стрелка с числом секунд внутри; для «вперёд» стрелка зеркальная. */
@Composable
private fun SeekButton(seconds: Int, forward: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Replay,
            if (forward) "Вперёд на $seconds с" else "Назад на $seconds с",
            Modifier
                .size(44.dp)
                .graphicsLayer { if (forward) scaleX = -1f },
        )
        Text(
            "$seconds",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** Переключатель «слушать | читать» как пилюля. */
@Composable
private fun ModeSwitch(onRead: () -> Unit, readEnabled: Boolean) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(44.dp, 36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Headphones, "Слушать", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.background)
                }
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(44.dp, 36.dp)
                    .clickable(enabled = readEnabled, onClick = onRead),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Notes, "Читать с этого места", Modifier.size(22.dp),
                    tint = if (readEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
