package com.shoosman.bookapp.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.shoosman.bookapp.App
import java.io.File

/** Обложка книги или цветная заглушка с названием. */
@Composable
fun Cover(title: String, author: String, coverPath: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    if (coverPath != null) {
        AsyncImage(
            model = File(coverPath),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
    } else {
        val colors = listOf(0xFF2E6B5E, 0xFF8A5A2B, 0xFF5B4B8A, 0xFF8A3B4B, 0xFF3B5E8A, 0xFF6B7A2E)
        val bg = Color(colors[Math.floorMod(title.hashCode(), colors.size)])
        Box(modifier.clip(shape).background(bg).padding(6.dp), contentAlignment = Alignment.Center) {
            Text(
                title,
                color = Color.White,
                fontFamily = FontFamily.Serif,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Мини-плеер внизу экрана, если что-то загружено в плеер. */
@Composable
fun MiniPlayer(onOpen: (Long) -> Unit, skipBackSec: Int, insetBottom: Boolean = true) {
    val player = App.instance.player
    val s by player.ui.collectAsStateWithLifecycle()
    if (s.bookId < 0) return
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(if (insetBottom) Modifier.navigationBarsPadding() else Modifier) {
            LinearProgressIndicator(
                progress = { if (s.durMs > 0) (s.posMs.toFloat() / s.durMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(s.bookId) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(s.title, s.author, s.coverPath, Modifier.size(40.dp, 56.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        s.trackTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { player.skip(-skipBackSec * 1000L) }) {
                    Icon(Icons.Filled.Replay, contentDescription = "Назад на $skipBackSec с")
                }
                IconButton(onClick = { player.toggle() }) {
                    Icon(
                        if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (s.isPlaying) "Пауза" else "Играть",
                    )
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/** 1:02:03 или 4:05 */
fun fmtTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** «8 ч 30 мин» */
fun fmtDuration(ms: Long): String {
    val totalMin = (ms / 60_000).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "$h ч $m мин"
        h > 0 -> "$h ч"
        else -> "$m мин"
    }
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val n100 = n % 100
    val n10 = n % 10
    return when {
        n100 in 11..14 -> many
        n10 == 1 -> one
        n10 in 2..4 -> few
        else -> many
    }
}
