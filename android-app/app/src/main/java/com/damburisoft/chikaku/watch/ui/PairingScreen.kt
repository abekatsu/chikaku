package com.damburisoft.chikaku.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damburisoft.chikaku.watch.R
import com.damburisoft.chikaku.watch.data.Settings

/**
 * 招待コードで family に紐付ける（CLAUDE.md §8「親デバイスとfamilyの紐付け方法」）。
 * サーバーURLは通常隠しておき、「詳細設定」を開いたときだけ触れるようにする。
 */
@Composable
fun PairingScreen(
    settings: Settings,
    pairing: PairingState,
    onSubmit: (inviteCode: String, deviceName: String, serverBaseUrl: String) -> Unit,
) {
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf("") }
    var serverUrl by rememberSaveable(settings.serverBaseUrl) { mutableStateOf(settings.serverBaseUrl) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    val inProgress = pairing is PairingState.InProgress

    ScreenScaffold(title = stringResource(R.string.pairing_title)) {
        Text(
            text = stringResource(R.string.pairing_body),
            style = MaterialTheme.typography.bodyLarge,
        )

        OutlinedTextField(
            value = inviteCode,
            onValueChange = { inviteCode = it },
            label = { Text(stringResource(R.string.pairing_code_label)) },
            singleLine = true,
            enabled = !inProgress,
            // 招待コードは英数字。誤読しやすいので大きめの等幅で表示する。
            textStyle = TextStyle(fontSize = 26.sp, fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text(stringResource(R.string.pairing_name_label)) },
            singleLine = true,
            enabled = !inProgress,
            modifier = Modifier.fillMaxWidth(),
        )

        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(stringResource(R.string.pairing_advanced))
        }
        if (showAdvanced) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text(stringResource(R.string.pairing_server_url_label)) },
                singleLine = true,
                enabled = !inProgress,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (pairing is PairingState.Failed) {
            Text(
                text = pairing.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            text = if (inProgress) {
                stringResource(R.string.pairing_in_progress)
            } else {
                stringResource(R.string.pairing_submit)
            },
            onClick = { onSubmit(inviteCode, deviceName, serverUrl) },
            enabled = !inProgress,
        )
    }
}
