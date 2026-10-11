package com.shoosman.bookapp.ui.player

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shoosman.bookapp.data.BookmarkEntity
import com.shoosman.bookapp.player.PlayerConnection
import com.shoosman.bookapp.player.PlayerUi
import com.shoosman.bookapp.ui.common.fmtTime
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Заголовок окна: название слева (или по центру) и крестик справа. */
@Composable
private fun SheetHeader(title: String, centered: Boolean = false, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.align(if (centered) Alignment.Center else Alignment.CenterStart).padding(end = 48.dp, start = if (centered) 48.dp else 0.dp),
        )
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd)) { Icon(Icons.Filled.Close, "Закрыть") }
    }
}

private fun minutesLabel(ms: Long): String {
    val min = (ms / 60_000.0).roundToInt()
    return if (min >= 1) "$min мин" else "${(ms / 1000).coerceAtLeast(1)} с"
}

// ---------------- оглавление и закладки ----------------

@Composable
fun ChaptersSheet(
    bookTitle: String,
    s: PlayerUi,
    player: PlayerConnection,
    bookmarks: List<BookmarkEntity>,
    onDeleteBookmark: (Long) -> Unit,
    onClose: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val chapters = player.currentChapters
    Column(Modifier.fillMaxWidth().fillMaxSize()) {
        SheetHeader(bookTitle, centered = true, onClose = onClose)
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Оглавление", style = MaterialTheme.typography.titleSmall) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Закладки" + if (bookmarks.isNotEmpty()) " · ${bookmarks.size}" else "", style = MaterialTheme.typography.titleSmall) })
        }
        if (tab == 0) {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (s.chapter - 1).coerceAtLeast(0))
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(chapters) { i, ch ->
                    val current = i == s.chapter
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(if (current) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .clickable { player.goToChapter(i); onClose() }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        Text(ch.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                minutesLabel(ch.durationMs),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (current) {
                                Box(Modifier.padding(horizontal = 10.dp).size(5.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
                                Text("Играет сейчас", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (!current && i + 1 != s.chapter) HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        } else {
            if (bookmarks.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Закладок пока нет.\nНажмите значок закладки в плеере, чтобы запомнить место.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val sorted = bookmarks.filter { it.audioMs >= 0 }.sortedBy { it.audioMs } + bookmarks.filter { it.audioMs < 0 }
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    itemsIndexed(sorted, key = { _, b -> b.id }) { _, bm ->
                        val chIndex = chapters.indexOfLast { it.startMs <= bm.audioMs }
                        val ch = chapters.getOrNull(chIndex)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = bm.audioMs >= 0) { player.seekGlobal(bm.audioMs); onClose() }
                                .padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(bm.snippet, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (bm.audioMs >= 0) listOfNotNull(ch?.title, ch?.let { fmtTime(bm.audioMs - it.startMs) } ?: fmtTime(bm.audioMs)).joinToString(" · ")
                                    else "Закладка в тексте",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            IconButton(onClick = { onDeleteBookmark(bm.id) }) {
                                Icon(Icons.Outlined.Delete, "Удалить закладку", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

// ---------------- таймер сна: барабан ----------------

private val sleepOptions = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)

@Composable
fun SleepSheet(s: PlayerUi, player: PlayerConnection, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("player", Context.MODE_PRIVATE) }
    var endOfChapter by remember { mutableStateOf(s.sleepEndOfChapter) }
    val startIndex = sleepOptions.indexOf(prefs.getInt("sleep_last", 30)).takeIf { it >= 0 } ?: 4
    var picked by remember { mutableIntStateOf(sleepOptions[startIndex]) }
    val active = s.sleepLeftMs > 0 || s.sleepEndOfChapter

    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        SheetHeader("Таймер сна", onClose = onClose)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        if (active) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (s.sleepEndOfChapter) "Сейчас: до конца главы" else "Сейчас: остановлю через ${fmtTime(s.sleepLeftMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (s.sleepLeftMs > 0) TextButton(onClick = { player.extendSleep(5) }) { Text("+5 мин") }
                TextButton(onClick = { player.cancelSleep(); onClose() }) { Text("Выключить", color = MaterialTheme.colorScheme.error) }
            }
        }
        Box(Modifier.alpha(if (endOfChapter) 0.35f else 1f)) {
            WheelPicker(
                items = sleepOptions.map { "$it мин" },
                initialIndex = startIndex,
                enabled = !endOfChapter,
                onSelected = { picked = sleepOptions[it] },
            )
        }
        Row(
            Modifier.fillMaxWidth().clickable { endOfChapter = !endOfChapter }.padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("До конца главы", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = endOfChapter, onCheckedChange = { endOfChapter = it })
        }
        Button(
            onClick = {
                if (endOfChapter) {
                    player.setSleepEndOfChapter()
                } else {
                    prefs.edit().putInt("sleep_last", picked).apply()
                    player.setSleepMinutes(picked)
                }
                onClose()
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).height(54.dp),
        ) { Text("Запустить", style = MaterialTheme.typography.titleMedium) }
    }
}

/** Барабан выбора: бесконечная прокрутка, выбранное — по центру между линиями. */
@Composable
private fun WheelPicker(items: List<String>, initialIndex: Int, enabled: Boolean, onSelected: (Int) -> Unit) {
    val rowH = 52.dp
    val visible = 5
    val loops = 400
    val start = loops / 2 * items.size + initialIndex
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start - visible / 2)
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    val center by remember {
        derivedStateOf {
            state.firstVisibleItemIndex + visible / 2 + if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { center }.collect { onSelected(((it % items.size) + items.size) % items.size) }
    }
    Box(Modifier.fillMaxWidth().height(rowH * visible), contentAlignment = Alignment.Center) {
        LazyColumn(
            state = state,
            flingBehavior = fling,
            userScrollEnabled = enabled,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(count = items.size * loops) { i ->
                val dist = abs(i - center)
                Box(Modifier.height(rowH).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        items[i % items.size],
                        fontSize = if (dist == 0) 28.sp else 24.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = when (dist) { 0 -> 1f; 1 -> 0.75f; else -> 0.3f }),
                    )
                }
            }
        }
        // Линии вокруг выбранного значения
        Column(Modifier.width(200.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface, thickness = 1.dp)
            Spacer(Modifier.height(rowH - 2.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface, thickness = 1.dp)
        }
    }
}

// ---------------- скорость: шкала ----------------

private const val SPEED_MIN = 0.5f
private const val SPEED_STEP = 0.05f
private const val SPEED_TICKS = 51 // 0.5 … 3.0
private val speedPresets = listOf(0.8f, 1f, 1.2f, 1.5f, 1.7f, 2f, 2.5f, 3f)

@Composable
fun SpeedSheet(s: PlayerUi, player: PlayerConnection, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun indexOf(v: Float) = ((v - SPEED_MIN) / SPEED_STEP).roundToInt().coerceIn(0, SPEED_TICKS - 1)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = indexOf(s.speed))
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val tickW = 18.dp
    val tickPx = with(LocalDensity.current) { tickW.toPx() }
    val idx by remember {
        derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > tickPx / 2) 1 else 0 }
    }
    val value = SPEED_MIN + idx.coerceIn(0, SPEED_TICKS - 1) * SPEED_STEP
    // Применяем, когда шкала остановилась
    LaunchedEffect(Unit) {
        snapshotFlow { state.isScrollInProgress to idx }.collect { (moving, i) ->
            if (!moving) player.setSpeed(SPEED_MIN + i.coerceIn(0, SPEED_TICKS - 1) * SPEED_STEP)
        }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        SheetHeader("Скорость", onClose = onClose)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "${fmtSpeed(value)}x",
            fontSize = 34.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            textAlign = TextAlign.Center,
        )
        BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
            val side = (maxWidth - tickW) / 2
            LazyRow(
                state = state,
                flingBehavior = fling,
                contentPadding = PaddingValues(horizontal = side),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(count = SPEED_TICKS) { i ->
                    val v = SPEED_MIN + i * SPEED_STEP
                    val major = abs(v * 2 - (v * 2).roundToInt()) < 0.01f // 0.5, 1.0, 1.5 …
                    Box(Modifier.width(tickW).fillMaxSize().clickable { scope.launch { state.animateScrollToItem(i) } }, contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .width(if (major) 2.dp else 1.5.dp)
                                .height(if (major) 34.dp else 22.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (major) 0.9f else 0.45f)),
                        )
                    }
                }
            }
            // Неподвижная метка по центру
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(3.dp)
                    .height(52.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            items(count = speedPresets.size) { i ->
                val p = speedPresets[i]
                val sel = abs(value - p) < 0.01f
                Surface(
                    onClick = { scope.launch { state.animateScrollToItem(indexOf(p)) } },
                    shape = RoundedCornerShape(12.dp),
                    color = if (sel) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                    contentColor = if (sel) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                    border = if (sel) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(80.dp, 48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("${fmtSpeed(p)}x", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}
