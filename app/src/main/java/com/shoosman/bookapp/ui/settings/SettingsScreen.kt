package com.shoosman.bookapp.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shoosman.bookapp.App
import com.shoosman.bookapp.data.AppSettings
import com.shoosman.bookapp.ui.common.SectionTitle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    val store = App.instance.settings
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            SectionTitle("Текст")
            TextSettingsPanel(settings)

            SectionTitle("Плеер")
            Text("Перемотка назад", style = MaterialTheme.typography.bodyMedium)
            ChipRow(listOf(5, 10, 15, 30), settings.skipBackSec, { "$it с" }) { v ->
                scope.launch { store.update { it.copy(skipBackSec = v) } }
            }
            Spacer(Modifier.height(8.dp))
            Text("Перемотка вперёд", style = MaterialTheme.typography.bodyMedium)
            ChipRow(listOf(10, 15, 30, 60), settings.skipForwardSec, { "$it с" }) { v ->
                scope.launch { store.update { it.copy(skipForwardSec = v) } }
            }

            SectionTitle("О приложении")
            Text(
                "Чтец $version\nЧиталка и аудиокниги в одном месте с переключением между текстом и звуком.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** Настройки оформления текста: используются и в читалке, и в настройках. */
@Composable
fun TextSettingsPanel(settings: AppSettings, modifier: Modifier = Modifier) {
    val store = App.instance.settings
    val scope = rememberCoroutineScope()
    fun save(t: (AppSettings) -> AppSettings) {
        scope.launch { store.update(t) }
    }

    var fontSize by remember { mutableFloatStateOf(settings.fontSize) }
    var lineHeight by remember { mutableFloatStateOf(settings.lineHeight) }
    LaunchedEffect(settings.fontSize) { fontSize = settings.fontSize }
    LaunchedEffect(settings.lineHeight) { lineHeight = settings.lineHeight }

    Column(modifier) {
        Text("Тема", style = MaterialTheme.typography.bodyMedium)
        val themes = listOf("system" to "Как в системе", "light" to "Светлая", "sepia" to "Сепия", "dark" to "Тёмная")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            themes.forEach { (key, label) ->
                FilterChip(
                    selected = settings.theme == key,
                    onClick = { save { it.copy(theme = key) } },
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Размер шрифта: ${fontSize.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = fontSize,
            onValueChange = { fontSize = it.roundToInt().toFloat() },
            onValueChangeFinished = { val v = fontSize; save { it.copy(fontSize = v) } },
            valueRange = 13f..34f,
        )
        Text("Межстрочный интервал: ${"%.1f".format(lineHeight)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = lineHeight,
            onValueChange = { lineHeight = (it * 10).roundToInt() / 10f },
            onValueChangeFinished = { val v = lineHeight; save { it.copy(lineHeight = v) } },
            valueRange = 1.1f..2.2f,
        )

        Text(
            "Пример текста: — Ну что ж, — сказал он, — начнём с самого начала.",
            fontFamily = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * lineHeight).sp,
            textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        SwitchRow("Шрифт с засечками", settings.serif) { v -> save { it.copy(serif = v) } }
        SwitchRow("Выравнивание по ширине", settings.justify) { v -> save { it.copy(justify = v) } }
        SwitchRow("Не гасить экран при чтении", settings.keepScreenOn) { v -> save { it.copy(keepScreenOn = v) } }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChipRow(values: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { v ->
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label(v)) })
        }
    }
}
