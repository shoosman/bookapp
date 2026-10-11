package com.shoosman.bookapp.ui.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shoosman.bookapp.data.AppSettings
import com.shoosman.bookapp.text.BookContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private class PagePos(val section: Int, val page: Int, val pages: List<Page>) {
    val key: Long get() = section.toLong() * 1_000_000 + page
}

/**
 * Режим книги: страницы листаются касанием левого/правого края или свайпом,
 * вертикальный свайп меняет яркость, касание в центре показывает панели.
 */
@Composable
fun PagedBody(
    c: BookContent,
    settings: AppSettings,
    initialChar: Int,
    jumps: SharedFlow<Int>,
    audioChar: Int,
    audioPara: Int,
    follow: Boolean,
    isPlaying: Boolean,
    chapterTitle: String,
    onCharChange: (Int) -> Unit,
    onTapCenter: () -> Unit,
    onLongPress: (Int) -> Unit,
    onUserTurn: () -> Unit,
    onBrightnessDelta: (Float) -> Unit,
    onBrightnessEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val bars = WindowInsets.systemBars.asPaddingValues()
    val scope = rememberCoroutineScope()

    val tapCenter by rememberUpdatedState(onTapCenter)
    val longPress by rememberUpdatedState(onLongPress)
    val userTurn by rememberUpdatedState(onUserTurn)
    val brightnessDelta by rememberUpdatedState(onBrightnessDelta)
    val brightnessEnd by rememberUpdatedState(onBrightnessEnd)

    // Последнее известное место — чтобы не потерять его при смене шрифта или поворота экрана.
    var anchorChar by remember { mutableIntStateOf(initialChar) }
    val hitAreas = remember { HashMap<Int, Rect>() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInRoot() },
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(start = 22.dp, end = 22.dp, top = bars.calculateTopPadding() + 20.dp, bottom = bars.calculateBottomPadding() + 34.dp),
        ) {
            val widthPx = constraints.maxWidth
            val heightPx = constraints.maxHeight
            val paginator = remember(c, settings.fontSize, settings.lineHeight, settings.serif, settings.justify, widthPx, heightPx, density) {
                Paginator(c, settings, widthPx, heightPx, density, TextMeasurer(resolver, density, direction, 0))
            }
            var pos by remember(paginator) { mutableStateOf<PagePos?>(null) }

            suspend fun show(section: Int, page: Int) {
                val pages = withContext(Dispatchers.Default) { paginator.pages(section) }
                val index = if (page < 0) pages.lastIndex else page.coerceIn(0, pages.lastIndex)
                pos = PagePos(section, index, pages)
            }

            suspend fun showChar(char: Int) {
                val (section, page) = withContext(Dispatchers.Default) { paginator.locate(char) }
                show(section, page)
            }

            fun next() {
                val p = pos ?: return
                scope.launch {
                    when {
                        p.page < p.pages.lastIndex -> pos = PagePos(p.section, p.page + 1, p.pages)
                        p.section < paginator.sections.lastIndex -> show(p.section + 1, 0)
                    }
                }
            }

            fun prev() {
                val p = pos ?: return
                scope.launch {
                    when {
                        p.page > 0 -> pos = PagePos(p.section, p.page - 1, p.pages)
                        p.section > 0 -> show(p.section - 1, -1)
                    }
                }
            }

            LaunchedEffect(paginator) { showChar(anchorChar) }
            LaunchedEffect(paginator) { jumps.collect { showChar(it) } }

            // Сообщаем наружу текущее место.
            LaunchedEffect(pos) {
                val p = pos ?: return@LaunchedEffect
                val page = p.pages[p.page]
                anchorChar = paginator.startChar(page)
                val atEnd = p.section == paginator.sections.lastIndex && p.page == p.pages.lastIndex
                onCharChange(if (atEnd) c.totalChars else anchorChar)
                hitAreas.clear()
                // Заранее размечаем соседние разделы, чтобы листание было мгновенным.
                withContext(Dispatchers.Default) {
                    if (p.page >= p.pages.size - 2 && p.section < paginator.sections.lastIndex) paginator.pages(p.section + 1)
                    if (p.page <= 1 && p.section > 0) paginator.pages(p.section - 1)
                }
            }

            // Следуем за аудио: если звучащее место ушло со страницы — перелистываем.
            LaunchedEffect(audioChar, follow, isPlaying, pos == null) {
                val p = pos ?: return@LaunchedEffect
                if (!follow || !isPlaying || audioChar < 0) return@LaunchedEffect
                val page = p.pages[p.page]
                if (audioChar < paginator.startChar(page) || audioChar >= paginator.endChar(page)) showChar(audioChar)
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(paginator) {
                        detectTapGestures(
                            onTap = { o ->
                                val x = o.x / size.width
                                when {
                                    x < 0.3f -> { userTurn(); prev() }
                                    x > 0.7f -> { userTurn(); next() }
                                    else -> tapCenter()
                                }
                            },
                            onLongPress = { o ->
                                val point = o + rootOrigin
                                hitAreas.entries.firstOrNull { it.value.contains(point) }?.let { longPress(it.key) }
                            },
                        )
                    }
                    .pointerInput(paginator) {
                        var dx = 0f
                        var dy = 0f
                        var axis = 0 // 0 — ещё не ясно, 1 — горизонталь, 2 — вертикаль
                        detectDragGestures(
                            onDragStart = { dx = 0f; dy = 0f; axis = 0 },
                            onDragEnd = {
                                if (axis == 1) {
                                    if (dx < -60f) { userTurn(); next() } else if (dx > 60f) { userTurn(); prev() }
                                } else if (axis == 2) {
                                    brightnessEnd()
                                }
                            },
                            onDragCancel = { if (axis == 2) brightnessEnd() },
                            onDrag = { change, amount ->
                                change.consume()
                                dx += amount.x
                                dy += amount.y
                                if (axis == 0 && (abs(dx) > 24f || abs(dy) > 24f)) axis = if (abs(dx) > abs(dy)) 1 else 2
                                if (axis == 2) brightnessDelta(-amount.y / size.height)
                            },
                        )
                    },
            ) {
                val current = pos
                if (current == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    AnimatedContent(
                        targetState = current,
                        contentKey = { it.key },
                        transitionSpec = {
                            val forward = targetState.key > initialState.key
                            slideInHorizontally { w -> if (forward) w else -w } togetherWith
                                slideOutHorizontally { w -> if (forward) -w else w }
                        },
                        label = "page",
                    ) { p ->
                        PageContent(c, p.pages[p.page], settings, audioPara) { para, rect -> hitAreas[para] = rect }
                    }
                }
            }
        }

        // Подвал страницы: глава и номер страницы.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 22.dp, end = 22.dp, bottom = bars.calculateBottomPadding() + 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                chapterTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "${(anchorChar * 100L / c.totalChars.coerceAtLeast(1)).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PageContent(
    c: BookContent,
    page: Page,
    s: AppSettings,
    audioPara: Int,
    register: (Int, Rect) -> Unit,
) {
    val color = MaterialTheme.colorScheme.onBackground
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
    Column(Modifier.fillMaxSize()) {
        for (slice in page.slices) {
            val p = c.paragraphs[slice.para]
            val look = paraLook(p.kind, s, color, continuation = slice.from > 0)
            val endsParagraph = slice.to >= p.text.length
            var text = p.text.substring(slice.from, slice.to)
            if (slice.hyphen) text += "-"
            Text(
                text,
                style = look.style,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = look.top, bottom = if (endsParagraph) look.bottom else 0.dp, start = look.start)
                    .then(
                        if (slice.para == audioPara) Modifier.background(highlightColor, RoundedCornerShape(4.dp)) else Modifier,
                    )
                    .onGloballyPositioned { register(slice.para, it.boundsInRoot()) },
            )
        }
    }
}
