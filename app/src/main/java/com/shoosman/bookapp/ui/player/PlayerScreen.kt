package com.shoosman.bookapp.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val c = content
    val mapper = remember(c, tracks, anchors) {
        if (c != null && tracks.isNotEmpty()) SyncMapper.build(c, tracks, anchors) else null
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
            ?: "${s.trackTitle} · ${fmtTime(s.posMs)}"
        scope.launch {
            repo.addBookmark(bookId, pos, ms, snippet)
            snackbar.showSnackbar("Закладка добавлена")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val b = book ?: return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Cover(b.title, b.author, b.coverPath, Modifier.fillMaxWidth(0.6f).widthIn(max = 280.dp).aspectRatio(0.68f))
            Spacer(Modifier.height(20.dp))
            Text(b.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (b.author.isNotBlank()) {
                Text(b.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            val chapterTitle = if (c != null && active) {
                val pos = currentChar()
                c.chapters.getOrNull(c.chapterAt(pos))?.title
            } else null
            Text(
                chapterTitle ?: s.trackTitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (s.trackCount > 1) {
                Text(
                    "Файл ${s.track + 1} из ${s.trackCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            var drag by remember { mutableStateOf<Float?>(null) }
            val dur = s.durMs.coerceAtLeast(1L)
            val shownPos = drag?.toLong() ?: s.posMs
            Slider(
                value = (drag ?: s.posMs.toFloat()).coerceIn(0f, dur.toFloat()),
                valueRange = 0f..dur.toFloat(),
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let { player.seekInTrack(it.toLong()) }
                    drag = null
                },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtTime(shownPos), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text("−" + fmtTime((s.durMs - shownPos).coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium)
            }
            if (s.totalMs > 0) {
                val left = ((s.totalMs - s.globalMs) / s.speed.coerceAtLeast(0.1f)).toLong()
                Text(
                    "До конца книги ${fmtDuration(left)} · прослушано ${(s.globalMs * 100 / s.totalMs)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { player.previousTrack() }) { Icon(Icons.Filled.SkipPrevious, "Предыдущий файл") }
                SkipButton(Icons.Filled.FastRewind, "−${settings.skipBackSec}") { player.skip(-settings.skipBackSec * 1000L) }
                FilledIconButton(onClick = { player.toggle() }, modifier = Modifier.size(76.dp)) {
                    Icon(
                        if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (s.isPlaying) "Пауза" else "Играть",
                        Modifier.size(42.dp),
                    )
                }
                SkipButton(Icons.Filled.FastForward, "+${settings.skipForwardSec}") { player.skip(settings.skipForwardSec * 1000L) }
                IconButton(onClick = { player.nextTrack() }) { Icon(Icons.Filled.SkipNext, "Следующий файл") }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { sheet = "speed" }) {
                    Icon(Icons.Filled.Speed, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${fmtSpeed(s.speed)}×")
                }
                TextButton(onClick = { sheet = "sleep" }) {
                    Icon(Icons.Filled.Bedtime, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            s.sleepLeftMs > 0 -> fmtTime(s.sleepLeftMs)
                            s.sleepEndOfTrack -> "Конец файла"
                            else -> "Таймер"
                        },
                    )
                }
                TextButton(onClick = { addBookmark() }) {
                    Icon(Icons.Filled.BookmarkAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Закладка")
                }
            }
            if (b.hasText) {
                OutlinedButton(
                    onClick = { readHere() },
                    enabled = mapper != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Читать с этого места")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (sheet) {
        "speed" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                var v by remember { mutableFloatStateOf(s.speed) }
                Text("Скорость: ${fmtSpeed(v)}×", style = MaterialTheme.typography.titleMedium)
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
                            label = { Text("${fmtSpeed(preset)}×") },
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
                    headlineContent = { Text("До конца текущего файла") },
                    modifier = Modifier.clickable { player.setSleepEndOfTrack(); sheet = null },
                )
                if (s.sleepLeftMs > 0 || s.sleepEndOfTrack) {
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
private fun SkipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
