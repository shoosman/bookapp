package com.shoosman.bookapp.ui.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.shoosman.bookapp.data.AppSettings
import com.shoosman.bookapp.text.BookContent
import kotlinx.coroutines.flow.SharedFlow

/** Режим прокрутки: весь текст одной лентой. */
@Composable
fun ScrollBody(
    c: BookContent,
    settings: AppSettings,
    initialChar: Int,
    jumps: SharedFlow<Int>,
    audioPara: Int,
    follow: Boolean,
    isPlaying: Boolean,
    onCharChange: (Int) -> Unit,
    onTap: () -> Unit,
    onLongPress: (ReaderTextHit) -> Unit,
    onUserScroll: () -> Unit,
) {
    val listState = rememberLazyListState()
    val bars = WindowInsets.systemBars.asPaddingValues()
    var restored by remember { mutableStateOf(false) }
    val charChange by rememberUpdatedState(onCharChange)

    LaunchedEffect(Unit) {
        listState.scrollToItem(c.paragraphAt(initialChar))
        restored = true
    }
    LaunchedEffect(Unit) {
        jumps.collect { listState.scrollToItem(c.paragraphAt(it)) }
    }
    LaunchedEffect(restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.canScrollForward }.collect { (idx, canScroll) ->
            charChange(if (!canScroll) c.totalChars else c.paragraphs.getOrNull(idx)?.start ?: 0)
        }
    }

    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) onUserScroll() }

    LaunchedEffect(audioPara, follow, isPlaying, restored) {
        if (!restored || !follow || !isPlaying || audioPara < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (audioPara < first || audioPara >= last) listState.animateScrollToItem(audioPara)
    }

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
            ScrollParagraph(i, p.start, p.text, p.kind, settings, i == audioPara, onTap, onLongPress)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScrollParagraph(
    index: Int,
    startChar: Int,
    text: String,
    kind: com.shoosman.bookapp.text.ParaKind,
    s: AppSettings,
    highlighted: Boolean,
    onTap: () -> Unit,
    onLongPress: (ReaderTextHit) -> Unit,
) {
    val look = paraLook(kind, s, MaterialTheme.colorScheme.onBackground)
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    var textLayout by remember(index, text) { mutableStateOf<TextLayoutResult?>(null) }

    Box(Modifier.fillMaxWidth().padding(top = look.top, bottom = look.bottom, start = look.start)) {
        Text(
            text,
            style = look.style,
            onTextLayout = { textLayout = it },
            modifier = Modifier.fillMaxWidth()
                .then(if (highlighted) Modifier.background(highlightColor, RoundedCornerShape(4.dp)) else Modifier)
                .pointerInput(index, text) {
                    detectTapGestures(
                        onTap = { tap() },
                        onLongPress = { offset ->
                            // Первое касание может случиться до onTextLayout.
                            // В этом случае открываем меню на начале абзаца,
                            // а не теряем долгое нажатие целиком.
                            val layout = textLayout
                            val lineStart = if (layout != null) {
                                val line = layout.getLineForVerticalPosition(offset.y)
                                layout.getLineStart(line).coerceIn(0, text.length)
                            } else 0
                            longPress(ReaderTextHit(index, startChar + lineStart))
                        },
                    )
                },
        )
    }
}
