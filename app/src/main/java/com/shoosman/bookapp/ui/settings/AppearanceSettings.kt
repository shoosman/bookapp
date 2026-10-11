package com.shoosman.bookapp.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shoosman.bookapp.App
import com.shoosman.bookapp.R
import com.shoosman.bookapp.data.AppSettings
import com.shoosman.bookapp.ui.common.SectionTitle
import com.shoosman.bookapp.ui.theme.accents
import com.shoosman.bookapp.ui.theme.fontOf
import com.shoosman.bookapp.ui.theme.isDarkTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Варианты иконки приложения: ярлыки .Icon_<key> в манифесте. */
object AppIcons {
    class Variant(val key: String, val title: String, val bg: Int, val fg: Int)

    val all = listOf(
        Variant("headphones_book", "Книга в наушниках", R.drawable.ic_icon_headphones_book_bg, R.drawable.ic_icon_headphones_book_fg),
        Variant("bookmark_wave", "Закладка-звук", R.drawable.ic_icon_bookmark_wave_bg, R.drawable.ic_icon_bookmark_wave_fg),
        Variant("letter_ch", "Буква «Ч»", R.drawable.ic_icon_letter_ch_bg, R.drawable.ic_icon_letter_ch_fg),
        Variant("cat_reader", "Котик-чтец", R.drawable.ic_icon_cat_reader_bg, R.drawable.ic_icon_cat_reader_fg),
        Variant("stack_pink", "Стопка книг", R.drawable.ic_icon_stack_pink_bg, R.drawable.ic_icon_stack_pink_fg),
    )

    /** Включить один ярлык, выключить остальные. Лаунчер обновит значок через несколько секунд. */
    fun apply(ctx: Context, key: String) {
        val pm = ctx.packageManager
        for (v in all) {
            val state = if (v.key == key) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            runCatching {
                pm.setComponentEnabledSetting(ComponentName(ctx.packageName, "com.shoosman.bookapp.Icon_${v.key}"), state, PackageManager.DONT_KILL_APP)
            }
        }
    }
}

@Composable
private fun IconPreview(v: AppIcons.Variant, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.28f).dp))) {
        // Адаптивная иконка 108×108, видимая часть — середина 72×72: увеличиваем, чтобы края ушли за рамку
        Image(painterResource(v.bg), null, Modifier.fillMaxSize())
        Image(painterResource(v.fg), null, Modifier.requiredSize((size * 1.5f).dp).align(Alignment.Center))
    }
}

@Composable
fun AppearanceSettings(settings: AppSettings) {
    val ctx = LocalContext.current
    val store = App.instance.settings
    val scope = rememberCoroutineScope()
    fun save(t: (AppSettings) -> AppSettings) {
        scope.launch { store.update(t) }
    }

    // Превью: так выглядят кнопки и полосы в выбранном цвете
    Spacer(Modifier.height(8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text("Так будет выглядеть", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Искажающие реальность", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
            Text("Михаил Атаманов · книга 3", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(progress = { 0.62f }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}) { Text("Читать") }
                FilterChip(selected = true, onClick = {}, label = { Text("Читаю") })
                FilterChip(selected = false, onClick = {}, label = { Text("Прочитано") })
            }
        }
    }

    SectionTitle("Цвет")
    val dark = isDarkTheme(settings.theme, androidx.compose.foundation.isSystemInDarkTheme())
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        accents.forEach { a ->
            val c = if (dark) a.dark else a.light
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { save { it.copy(accent = a.key) } }) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(if (settings.accent == a.key) 3.dp else 0.dp, MaterialTheme.colorScheme.onBackground, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (settings.accent == a.key) Icon(Icons.Filled.Check, null, tint = if (dark) a.darkOn else Color.White)
                }
                Text(a.title, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
            }
        }
    }

    SectionTitle("Иконка приложения")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AppIcons.all.forEach { v ->
            val selected = settings.appIcon == v.key
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(76.dp).clickable {
                    if (!selected) {
                        save { it.copy(appIcon = v.key) }
                        AppIcons.apply(ctx, v.key)
                    }
                },
            ) {
                Box(
                    Modifier
                        .border(if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.dp, Color.Transparent), RoundedCornerShape(22.dp))
                        .padding(4.dp),
                ) { IconPreview(v, 60) }
                Text(v.title, style = MaterialTheme.typography.labelSmall, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    Text(
        "Значок на рабочем столе сменится через несколько секунд. Если лаунчер убрал его с экрана — добавьте заново из списка приложений.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )

    SectionTitle("Шрифт интерфейса")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("system" to "Обычный", "serif" to "С засечками", "mono" to "Машинка", "cursive" to "Рукописный").forEach { (key, label) ->
            FilterChip(
                selected = settings.uiFont == key,
                onClick = { save { it.copy(uiFont = key) } },
                label = { Text(label, fontFamily = fontOf(key)) },
            )
        }
    }

    SectionTitle("Скругления")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("sharp" to 3, "normal" to 12, "round" to 24).forEach { (key, r) ->
            val selected = settings.corners == key
            Box(
                Modifier
                    .size(72.dp, 48.dp)
                    .clip(RoundedCornerShape(r.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { save { it.copy(corners = key) } },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (key) { "sharp" -> "Острые"; "normal" -> "Обычные"; else -> "Круглые" },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    SectionTitle("Размер интерфейса")
    var scale by remember { mutableFloatStateOf(settings.uiScale) }
    LaunchedEffect(settings.uiScale) { scale = settings.uiScale }
    Text("${(scale * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = scale,
        onValueChange = { scale = (it * 20).roundToInt() / 20f },
        onValueChangeFinished = { val v = scale; save { it.copy(uiScale = v) } },
        valueRange = 0.85f..1.25f,
        steps = 7,
    )

    SectionTitle("Обложки на главной")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("small" to "Мелкие", "normal" to "Обычные", "large" to "Крупные").forEach { (key, label) ->
            FilterChip(selected = settings.tileSize == key, onClick = { save { it.copy(tileSize = key) } }, label = { Text(label) })
        }
    }

    SectionTitle("Фон плеера")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = settings.playerBg == "cover", onClick = { save { it.copy(playerBg = "cover") } }, label = { Text("Цвета обложки") })
        FilterChip(selected = settings.playerBg == "plain", onClick = { save { it.copy(playerBg = "plain") } }, label = { Text("Однотонный") })
    }

    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().clickable { save { it.copy(confetti = !settings.confetti) } }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Конфетти за прочитанную книгу", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text("Маленький праздник, когда отмечаете «Прочитано»", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = settings.confetti, onCheckedChange = { v -> save { it.copy(confetti = v) } })
    }
}
