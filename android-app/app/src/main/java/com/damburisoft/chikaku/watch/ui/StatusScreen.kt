package com.damburisoft.chikaku.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.damburisoft.chikaku.watch.R
import com.damburisoft.chikaku.watch.data.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")

/**
 * 定常状態の画面。親が見るのはほぼこの画面だけなので、
 * 「ちゃんと動いているか」が一目で分かることだけを目的にしている。
 */
@Composable
fun StatusScreen(
    settings: Settings,
    pendingCount: Int,
    onSendNow: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUnpair: () -> Unit,
) {
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }

    val running = settings.trackingEnabled

    ScreenScaffold(
        title = if (running) {
            stringResource(R.string.status_title)
        } else {
            stringResource(R.string.status_title_stopped)
        },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = if (running) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                CardDefaults.cardColors()
            },
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusRow(
                    label = stringResource(R.string.status_last_sent),
                    value = if (settings.lastSentAt > 0) {
                        timeFormat.format(
                            Instant.ofEpochMilli(settings.lastSentAt).atZone(ZoneId.systemDefault())
                        )
                    } else {
                        stringResource(R.string.status_last_sent_never)
                    },
                )
                if (pendingCount > 0) {
                    StatusRow(
                        label = stringResource(R.string.status_pending),
                        value = stringResource(R.string.status_pending_count, pendingCount),
                    )
                }
                settings.deviceName?.let {
                    StatusRow(label = stringResource(R.string.status_device_name), value = it)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (running) {
            PrimaryButton(text = stringResource(R.string.status_send_now), onClick = onSendNow)
            SecondaryButton(
                text = stringResource(R.string.status_stop),
                onClick = { confirmStop = true },
                destructive = true,
            )
        } else {
            PrimaryButton(text = stringResource(R.string.status_start), onClick = onStart)
        }

        SecondaryButton(
            text = stringResource(R.string.status_unpair),
            onClick = { confirmUnpair = true },
            destructive = true,
        )
    }

    if (confirmStop) {
        ConfirmDialog(
            title = stringResource(R.string.status_stop_confirm_title),
            body = stringResource(R.string.status_stop_confirm_body),
            confirmLabel = stringResource(R.string.status_stop_confirm_ok),
            onConfirm = {
                confirmStop = false
                onStop()
            },
            onDismiss = { confirmStop = false },
        )
    }

    if (confirmUnpair) {
        ConfirmDialog(
            title = stringResource(R.string.status_unpair),
            body = stringResource(R.string.status_unpair_confirm_body),
            confirmLabel = stringResource(R.string.status_unpair),
            onConfirm = {
                confirmUnpair = false
                onUnpair()
            },
            onDismiss = { confirmUnpair = false },
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
