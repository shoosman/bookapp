package com.shoosman.bookapp.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shoosman.bookapp.App

/** Окна обновления: «есть новая версия», ход скачивания, ошибки. */
@Composable
fun UpdateHost() {
    val updater = App.instance.updater
    val state by updater.state.collectAsStateWithLifecycle()

    // Вернулись из настроек «Установка неизвестных приложений» — продолжаем установку.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updater.resumeInstall() }

    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("Доступна версия ${s.release.version}") },
            text = {
                Column {
                    Text("У вас ${updater.currentVersion}.", style = MaterialTheme.typography.bodyMedium)
                    if (s.release.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Что нового:", style = MaterialTheme.typography.titleSmall)
                        Text(
                            s.release.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { updater.download(s.release) }) { Text("Обновить") } },
            dismissButton = { TextButton(onClick = { updater.skip(s.release) }) { Text("Позже") } },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Скачиваю ${s.release.version}…") },
            text = {
                Column {
                    val f = s.fraction
                    if (f != null) {
                        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                        Text("${(f * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        )

        is UpdateState.Failed -> if (s.message == "NEED_PERMISSION") {
            AlertDialog(
                onDismissRequest = { updater.dismiss() },
                title = { Text("Нужно разрешение") },
                text = {
                    Text("Обновление скачано. Чтобы установить его, разрешите Чтецу устанавливать приложения — Android спросит это один раз. Потом вернитесь сюда.")
                },
                confirmButton = { TextButton(onClick = { updater.openInstallPermissionSettings() }) { Text("Открыть настройки") } },
                dismissButton = { TextButton(onClick = { updater.dismiss() }) { Text("Отмена") } },
            )
        } else {
            AlertDialog(
                onDismissRequest = { updater.dismiss() },
                title = { Text("Обновление") },
                text = { Text(s.message) },
                confirmButton = { TextButton(onClick = { updater.dismiss() }) { Text("Понятно") } },
            )
        }

        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("Обновлений нет") },
            text = { Text("У вас последняя версия — ${updater.currentVersion}.") },
            confirmButton = { TextButton(onClick = { updater.dismiss() }) { Text("Отлично") } },
        )

        else -> Unit
    }
}
