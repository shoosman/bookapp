package com.shoosman.bookapp.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.shoosman.bookapp.CrashLog
import com.shoosman.bookapp.ui.common.ReportDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableStateOf
import com.shoosman.bookapp.data.AppSettings
import com.shoosman.bookapp.ui.common.SectionTitle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, onBack: (() -> Unit)?) {
    val store = App.instance.settings
    val scope = rememberCoroutineScope()
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }
    val titles = mapOf(
        "appearance" to "Внешний вид",
        "reader" to "Читалка",
        "player" to "Плеер",
        "library" to "Обложки, жанры, циклы",
        "about" to "О приложении",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.let { titles[it] } ?: "Настройки") },
                navigationIcon = {
                    when {
                        page != null -> IconButton(onClick = { page = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                        onBack != null -> IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                    }
                },
            )
        },
        contentWindowInsets = if (onBack == null) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            when (page) {
                null -> {
                    SectionTitle("Тема")
                    ThemeChips(settings) { key -> scope.launch { store.update { it.copy(theme = key) } } }

                    SectionTitle("Облако")
                    SyncSection()

                    SectionTitle("Обновления")
                    Text("Установлена версия ${App.instance.updater.currentVersion}", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { App.instance.updater.check(manual = true) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Проверить обновления")
                    }

                    Spacer(Modifier.height(20.dp))
                    androidx.compose.material3.HorizontalDivider()
                    MenuRow("Внешний вид", "Цвет, иконка, шрифт, скругления, размер, фон плеера") { page = "appearance" }
                    MenuRow("Читалка", "Шрифт и интервал текста, страницы или прокрутка, яркость") { page = "reader" }
                    MenuRow("Плеер", "Перемотка: ${settings.skipBackSec} с назад, ${settings.skipForwardSec} с вперёд") { page = "player" }
                    MenuRow("Обложки, жанры, циклы", "Дополнить книги с Литреса, Author.Today и Фантлаба") { page = "library" }
                    MenuRow("О приложении", "Версия, отчёт об ошибке") { page = "about" }
                }

                "appearance" -> AppearanceSettings(settings)

                "reader" -> {
                    Spacer(Modifier.height(8.dp))
                    TextSettingsPanel(settings, showTheme = false)
                }

                "player" -> {
                    SectionTitle("Перемотка назад")
                    ChipRow(listOf(5, 10, 15, 30), settings.skipBackSec, { "$it с" }) { v ->
                        scope.launch { store.update { it.copy(skipBackSec = v) } }
                    }
                    SectionTitle("Перемотка вперёд")
                    ChipRow(listOf(10, 15, 30, 60), settings.skipForwardSec, { "$it с" }) { v ->
                        scope.launch { store.update { it.copy(skipForwardSec = v) } }
                    }
                    Text(
                        "Те же шаги — у кнопок в шторке, на экране блокировки, в машине и на наушниках.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                "library" -> {
                    Spacer(Modifier.height(8.dp))
                    CoversSection()
                }

                "about" -> {
                    val ctx = LocalContext.current
                    val version = remember {
                        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Чтец $version\nЧиталка и аудиокниги в одном месте с переключением между текстом и звуком.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    var report by remember { mutableStateOf<String?>(null) }
                    var journal by remember { mutableStateOf<String?>(null) }
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { journal = CrashLog.journal() }) { Text("Журнал действий") }
                        OutlinedButton(onClick = { report = CrashLog.last() ?: "Сбоев и ошибок пока не было 🎉" }) { Text("Последняя ошибка") }
                    }
                    Text(
                        "Если что-то молча не работает — повторите действие и пришлите «Журнал действий».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    report?.let {
                        ReportDialog("Последняя ошибка", "Скопируйте и пришлите этот текст, если что-то работает не так.", it) { report = null }
                    }
                    journal?.let {
                        ReportDialog("Журнал действий", "Последние 40 действий в приложении. Скопируйте и пришлите.", it) { journal = null }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    androidx.compose.material3.HorizontalDivider()
}

/** Выбор темы — на главной странице настроек и в читалке. */
@Composable
fun ThemeChips(settings: AppSettings, onSelect: (String) -> Unit) {
    val themes = listOf("system" to "Как в системе", "light" to "Светлая", "sepia" to "Сепия", "dark" to "Тёмная", "black" to "Чёрная")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        themes.forEach { (key, label) ->
            FilterChip(selected = settings.theme == key, onClick = { onSelect(key) }, label = { Text(label) })
        }
    }
}

/** Настройки оформления текста: используются и в читалке, и в настройках. */
@Composable
fun TextSettingsPanel(settings: AppSettings, modifier: Modifier = Modifier, showTheme: Boolean = true) {
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
        if (showTheme) {
            Text("Тема", style = MaterialTheme.typography.bodyMedium)
            ThemeChips(settings) { key -> save { it.copy(theme = key) } }
        }
        Spacer(Modifier.height(12.dp))
        Text("Чтение", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = settings.pageMode, onClick = { save { it.copy(pageMode = true) } }, label = { Text("Страницы") })
            FilterChip(selected = !settings.pageMode, onClick = { save { it.copy(pageMode = false) } }, label = { Text("Прокрутка") })
        }
        if (settings.pageMode) {
            Text(
                "Края экрана листают, свайп вверх-вниз меняет яркость",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (settings.brightness < 0) "Яркость: как в системе" else "Яркость: ${(settings.brightness * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (settings.brightness >= 0) {
                androidx.compose.material3.TextButton(onClick = { save { it.copy(brightness = -1f) } }) { Text("Сбросить") }
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

@androidx.compose.runtime.Composable
private fun CoversSection() {
    val app = App.instance
    val books by app.repo.dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val state by app.enricher.state.collectAsStateWithLifecycle()
    val missingCovers = books.count { it.coverPath == null }
    val missingGenres = books.count { it.genres.isBlank() }
    val s = state
    if (s != null && s.running) {
        Text(
            "Дополняю книги: ${s.done} из ${s.total} · обновлено ${s.updated}" + if (s.added > 0) " · добавлено томов ${s.added}" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        androidx.compose.material3.LinearProgressIndicator(
            progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    } else {
        Text(
            "Без обложки: $missingCovers, без жанров: $missingGenres. Чтец найдёт на Литресе обложки, жанры, циклы с номерами томов и аннотации, обложки — ещё и на Author.Today.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s != null) {
            Text(
                "В прошлый раз: обновлено ${s.updated} из ${s.total}" + if (s.added > 0) ", добавлено томов ${s.added}." else ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { app.enricher.run() }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Дополнить книги")
            }
            if (missingCovers > 0) {
                var show by remember { mutableStateOf(false) }
                androidx.compose.material3.TextButton(onClick = { show = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Почему нет обложек")
                }
                if (show) MissingCoversDialog(books.filter { it.coverPath == null }) { show = false }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun MissingCoversDialog(books: List<com.shoosman.bookapp.data.BookEntity>, onDismiss: () -> Unit) {
    val enricher = App.instance.enricher
    val rows = books.map { it to enricher.coverStatus(it.uid) }
    val notFound = rows.count { it.second?.notFound == true }
    val failed = rows.count { it.second != null && it.second?.notFound == false }
    val never = rows.count { it.second == null }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Книги без обложки: ${books.size}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Не нашлись нигде: $notFound · ошибка при поиске: $failed · ещё не искали: $never",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (failed > 0) {
                    Text(
                        "Ошибки обычно временные (связь, сайт недоступен) — нажмите «Дополнить книги» ещё раз.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                rows.sortedBy { (_, s) -> if (s == null) 2 else if (s.notFound) 1 else 0 }.forEach { (b, s) ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(b.title + if (b.author.isNotBlank()) " — ${b.author}" else "", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when {
                                s == null -> "Ещё не искали"
                                s.notFound -> "Не нашлась"
                                else -> "Ошибка: " + s.message
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (s != null && !s.notFound) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
