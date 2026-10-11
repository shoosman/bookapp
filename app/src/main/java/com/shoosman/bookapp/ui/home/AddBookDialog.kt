package com.shoosman.bookapp.ui.home

import com.shoosman.bookapp.ErrorText
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.InputChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.shoosman.bookapp.App
import com.shoosman.bookapp.CrashLog
import com.shoosman.bookapp.data.BookInfo
import com.shoosman.bookapp.data.BookStatus
import com.shoosman.bookapp.data.Downloader
import com.shoosman.bookapp.data.LinkImport
import kotlinx.coroutines.launch
import org.jsoup.UnsupportedMimeTypeException

/** Файлы, прикреплённые в окне «Новая книга». */
class AttachedFiles(val files: List<Uri>, val audioTree: Uri?) {
    val isEmpty get() = files.isEmpty() && audioTree == null
}

/**
 * Новая книга: по ссылке (данные подтягиваются со страницы) или вручную, с файлами или без.
 * Прямая ссылка на файл скачивается, страницу магазина можно открыть во встроенном браузере.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBookDialog(
    initialText: String?,
    onDismiss: () -> Unit,
    onSaved: (Long) -> Unit,
    onSaveWithFiles: (BookInfo, Int, AttachedFiles) -> Unit,
    onDownload: (url: String, status: Int) -> Unit,
    onOpenBrowser: (url: String) -> Unit,
) {
    val repo = App.instance.repo
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var link by remember { mutableStateOf(initialText?.let { LinkImport.extractUrl(it) } ?: "") }
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var annotation by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<ByteArray?>(null) }
    var status by remember { mutableIntStateOf(BookStatus.WANT) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var fileLink by remember { mutableStateOf(false) }

    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var audioTree by remember { mutableStateOf<Uri?>(null) }
    // Любые файлы — текст, аудио, архив: что есть что, Чтец разберёт сам
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) picked = (picked + it).distinct()
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) audioTree = it
    }
    val files = AttachedFiles(picked, audioTree)

    fun load() {
        if (link.isBlank() || loading) return
        if (Downloader.looksLikeFile(link)) {
            fileLink = true
            return
        }
        loading = true
        error = null
        CrashLog.note("Добавление по ссылке: $link")
        scope.launch {
            runCatching { LinkImport.fetch(link) }
                .onSuccess { info ->
                    title = info.title
                    author = info.author
                    annotation = info.annotation
                    cover = info.cover ?: cover
                    link = info.url.ifBlank { link }
                }
                .onFailure {
                    CrashLog.note("Ссылка не разобралась: ${it.javaClass.simpleName} ${it.message}")
                    if (it is UnsupportedMimeTypeException) {
                        fileLink = true
                        loading = false
                        return@launch
                    }
                    error = ErrorText.ru(it, "Не удалось открыть страницу. Проверьте ссылку или заполните поля вручную.")
                }
            loading = false
        }
    }

    // Открыли из «Поделиться» или в буфере уже есть ссылка — сразу подставляем.
    LaunchedEffect(Unit) {
        if (link.isBlank()) {
            clipboard.getText()?.text?.let { LinkImport.extractUrl(it) }?.let { link = it }
        } else {
            load()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Новая книга") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Закрыть") } },
                    actions = {
                        TextButton(
                            enabled = (title.isNotBlank() || !files.isEmpty) && !saving,
                            onClick = {
                                saving = true
                                if (!files.isEmpty) {
                                    onSaveWithFiles(
                                        BookInfo(title = title, author = author, annotation = annotation, url = link, cover = cover),
                                        status,
                                        files,
                                    )
                                    return@TextButton
                                }
                                scope.launch {
                                    val id = repo.createManual(
                                        BookInfo(title = title, author = author, annotation = annotation, url = link, cover = cover),
                                        status,
                                    )
                                    CrashLog.note("Добавлена книга без файлов: $id")
                                    onSaved(id)
                                }
                            },
                        ) { Text("Сохранить") }
                    },
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Вставьте ссылку на книгу — Литрес, Author.Today, Яндекс Книги, LiveLib, Фантлаб, Лабиринт или другой сайт — и данные подтянутся сами. Или заполните поля вручную.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it; fileLink = false },
                    label = { Text("Ссылка") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { load() }),
                    trailingIcon = {
                        when {
                            loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            link.isBlank() -> IconButton(onClick = {
                                clipboard.getText()?.text?.let { link = LinkImport.extractUrl(it) ?: it }
                            }) { Icon(Icons.Filled.ContentPaste, "Вставить") }
                            else -> IconButton(onClick = { load() }) { Icon(Icons.Filled.Download, "Загрузить данные") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (fileLink) {
                    Text(
                        "Это ссылка на файл. Его можно скачать и сразу добавить книгой.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { onDownload(link.trim(), status) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Скачать и добавить")
                    }
                } else if (link.isNotBlank()) {
                    TextButton(onClick = { onOpenBrowser(link.trim()) }) {
                        Icon(Icons.Filled.Public, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Открыть во встроенном браузере, чтобы скачать")
                    }
                }

                Row(verticalAlignment = Alignment.Top) {
                    val c = cover
                    if (c != null) {
                        AsyncImage(
                            model = c,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(84.dp, 122.dp).clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = title, onValueChange = { title = it },
                            label = { Text("Название") }, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = author, onValueChange = { author = it },
                            label = { Text("Автор") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                OutlinedTextField(
                    value = annotation, onValueChange = { annotation = it },
                    label = { Text("Аннотация") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth(),
                )

                Text("Полка", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(BookStatus.WANT, BookStatus.READING, BookStatus.FINISHED, BookStatus.DROPPED).forEach { s ->
                        FilterChip(selected = status == s, onClick = { status = s }, label = { Text(BookStatus.label(s)) })
                    }
                }
                Text("Файлы", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { pickFiles.launch(arrayOf("*/*")) },
                        label = { Text("Файлы") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp)) },
                    )
                    AssistChip(
                        onClick = { pickFolder.launch(null) },
                        label = { Text("Папка") },
                        leadingIcon = { Icon(Icons.Filled.Folder, null, Modifier.size(18.dp)) },
                    )
                }
                picked.forEach { u ->
                    FileChip(repo.displayName(u)) { picked = picked - u }
                }
                audioTree?.let { t ->
                    FileChip("Папка: " + (DocumentFile.fromTreeUri(ctx, t)?.name ?: "аудио")) { audioTree = null }
                }
                Text(
                    if (files.isEmpty) "Необязательно: файлы можно прикрепить и позже на странице книги."
                    else "Файлы перенесутся в приложение, оригиналы удалятся с телефона.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FileChip(text: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { Icon(Icons.Filled.Close, "Убрать", Modifier.size(18.dp)) },
        modifier = Modifier.fillMaxWidth(),
    )
}
