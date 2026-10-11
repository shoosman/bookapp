package com.shoosman.bookapp.ui.common

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Окно с текстом отчёта об ошибке: можно выделить, скопировать или отправить. */
@Composable
fun ReportDialog(title: String, intro: String, report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(intro, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                SelectionContainer(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(report, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(report))
                Toast.makeText(ctx, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
            }) { Text("Скопировать") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Отчёт об ошибке Чтеца")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    ctx.startActivity(Intent.createChooser(send, "Отправить отчёт"))
                }) { Text("Отправить") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
    )
}

/** Окно ошибки с кнопкой «Подробности», которая открывает полный отчёт. */
@Composable
fun ErrorDialog(message: String, details: String?, onDismiss: () -> Unit) {
    var showDetails by remember { mutableStateOf(false) }
    if (showDetails && details != null) {
        ReportDialog("Подробности ошибки", "Скопируйте и пришлите этот текст, чтобы ошибку исправили.", details, onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Не получилось") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
        dismissButton = if (details != null) {
            { TextButton(onClick = { showDetails = true }) { Text("Подробности") } }
        } else {
            null
        },
    )
}
