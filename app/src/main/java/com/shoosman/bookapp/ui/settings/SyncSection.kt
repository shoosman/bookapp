package com.shoosman.bookapp.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shoosman.bookapp.App
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Облако: вход в GitHub по коду, статус и ручная синхронизация. */
@Composable
fun SyncSection() {
    val sync = App.instance.sync
    val s by sync.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val code = s.userCode
        when {
            code != null -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Откройте GitHub и введите код:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(code, fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(code))
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.verificationUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Скопировать код и открыть GitHub") }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Жду подтверждения…", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { sync.cancelLogin() }) { Text("Отмена") }
                }
            }

            !s.connected -> {
                Text(
                    "Библиотека — полки, оценки, заметки, прогресс, закладки, обложки и файлы книг — будет храниться в вашем приватном репозитории ${s.repo}. " +
                        "Так она не потеряется и будет доступна на других устройствах. Аудио остаётся на телефоне.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { sync.startLogin() }) { Text("Подключить GitHub") }
            }

            else -> {
                Text("Подключено: @${s.login} → ${s.repo}", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.running) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Синхронизирую…", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            if (s.lastSync > 0) "Последняя синхронизация: " + SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(s.lastSync))
                            else "Ещё не синхронизировалось",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !s.running, onClick = { scope.launch { sync.syncNow() } }) { Text("Синхронизировать сейчас") }
                    TextButton(onClick = { sync.logout() }) { Text("Отключить") }
                }
                Text(
                    "Синхронизация идёт сама: при открытии и закрытии приложения и через пару минут после изменений.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
