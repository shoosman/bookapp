package com.shoosman.bookapp.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shoosman.bookapp.App
import com.shoosman.bookapp.CrashLog
import com.shoosman.bookapp.data.ImportProgress
import com.shoosman.bookapp.data.ImportResult
import com.shoosman.bookapp.data.OnProgress
import com.shoosman.bookapp.ui.common.ErrorDialog
import com.shoosman.bookapp.ui.common.NoticeDialog
import com.shoosman.bookapp.ui.common.ProgressDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Состояние добавления книг — общее для всех вкладок. */
class Importer {
    var sheet by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<Pair<String, String?>?>(null)
    var progress by mutableStateOf<ImportProgress?>(null)
    var notice by mutableStateOf<String?>(null)
    /** Окно «по ссылке или вручную»; null — закрыто, иначе текст для поля ссылки. */
    var manual by mutableStateOf<String?>(null)

    fun open() {
        if (!busy) sheet = true
    }
}

/** Выбор файлов, окно «Добавить книгу» и ошибки импорта. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImporterHost(importer: Importer, onOpenBook: (Long) -> Unit) {
    val repo = App.instance.repo

    fun startImport(block: suspend (OnProgress) -> ImportResult) {
        importer.busy = true
        importer.progress = ImportProgress("Подождите…", null)
        CrashLog.note("Добавление книги")
        repo.appScope.launch {
            val result = runCatching { block { importer.progress = it } }
            withContext(Dispatchers.Main) {
                importer.busy = false
                importer.progress = null
                result.onSuccess {
                    importer.notice = it.notice
                    onOpenBook(it.bookId)
                }.onFailure {
                    CrashLog.note("Ошибка добавления: ${it.javaClass.simpleName}")
                    importer.error = (it.message ?: "Не удалось добавить книгу") to CrashLog.describe(it, "Ошибка при добавлении книги")
                }
            }
        }
    }

    val pickText = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport { p -> repo.createFromText(uri, p) }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) startImport { p -> repo.createFromAudio(uris, p) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) startImport { p -> repo.createFromAudioTree(uri, p) }
    }

    if (importer.sheet) {
        ModalBottomSheet(onDismissRequest = { importer.sheet = false }) {
            Text(
                "Добавить книгу",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text("Текст книги") },
                supportingContent = { Text("FB2, EPUB, TXT, DOCX, HTML, ZIP") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                modifier = Modifier.clickable { importer.sheet = false; pickText.launch(arrayOf("*/*")) },
            )
            ListItem(
                headlineContent = { Text("По ссылке или вручную") },
                supportingContent = { Text("Без файлов — в список «хочу прочитать» или «прочитано»") },
                leadingContent = { Icon(Icons.Filled.Link, null) },
                modifier = Modifier.clickable { importer.sheet = false; importer.manual = "" },
            )
            ListItem(
                headlineContent = { Text("Аудиокнига — файлы") },
                supportingContent = { Text("Выберите один или несколько файлов") },
                leadingContent = { Icon(Icons.Filled.AudioFile, null) },
                modifier = Modifier.clickable { importer.sheet = false; pickAudio.launch(arrayOf("audio/*")) },
            )
            ListItem(
                headlineContent = { Text("Аудиокнига — папка") },
                supportingContent = { Text("Все аудиофайлы из папки по порядку") },
                leadingContent = { Icon(Icons.Filled.Folder, null) },
                modifier = Modifier.clickable { importer.sheet = false; pickFolder.launch(null) },
            )
            Text(
                "Файлы переносятся в приложение: после добавления оригинал удаляется с телефона. Текст и аудио одной книги объединяются на её странице.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    importer.manual?.let { text ->
        AddBookDialog(
            initialText = text.ifBlank { null },
            onDismiss = { importer.manual = null },
            onSaved = { id ->
                importer.manual = null
                onOpenBook(id)
            },
        )
    }

    importer.progress?.let { ProgressDialog(it) }
    importer.notice?.let { NoticeDialog(it) { importer.notice = null } }

    importer.error?.let { (msg, details) ->
        ErrorDialog(msg, details, onDismiss = { importer.error = null })
    }
}
