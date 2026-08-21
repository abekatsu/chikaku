package com.damburisoft.chikaku.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.damburisoft.chikaku.watch.R

/**
 * 権限の状態。Android 10 未満・13 未満では該当しないものが常に true になる。
 */
data class PermissionStatus(
    val foregroundLocation: Boolean,
    val backgroundLocation: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
) {
    /** 見守りを始めるのに最低限必要なもの。 */
    val canStartTracking: Boolean get() = foregroundLocation && backgroundLocation
}

/**
 * 権限を一度にまとめて要求すると高齢者には何を聞かれているのか分からないため、
 * 1つずつ「何のための許可か」を添えて順番に進める。
 * 特に ACCESS_BACKGROUND_LOCATION は通常権限と同時に要求できない（CLAUDE.md §2.3）。
 */
@Composable
fun PermissionScreen(
    status: PermissionStatus,
    deniedPermanently: Boolean,
    onRequestForegroundLocation: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
) {
    ScreenScaffold(title = stringResource(R.string.permission_title)) {
        PermissionStep(
            title = stringResource(R.string.permission_step_foreground_title),
            body = stringResource(R.string.permission_step_foreground_body),
            granted = status.foregroundLocation,
            onRequest = onRequestForegroundLocation,
        )
        PermissionStep(
            title = stringResource(R.string.permission_step_background_title),
            body = stringResource(R.string.permission_step_background_body),
            granted = status.backgroundLocation,
            // 前段の許可がないと背景位置情報は要求すらできない。
            enabled = status.foregroundLocation,
            actionLabel = stringResource(R.string.permission_open_settings),
            onRequest = onRequestBackgroundLocation,
        )
        PermissionStep(
            title = stringResource(R.string.permission_step_notification_title),
            body = stringResource(R.string.permission_step_notification_body),
            granted = status.notifications,
            onRequest = onRequestNotifications,
        )
        PermissionStep(
            title = stringResource(R.string.permission_step_battery_title),
            body = stringResource(R.string.permission_step_battery_body),
            granted = status.batteryUnrestricted,
            onRequest = onRequestBatteryExemption,
        )

        if (deniedPermanently) {
            Text(
                text = stringResource(R.string.permission_rationale_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            SecondaryButton(
                text = stringResource(R.string.permission_open_settings),
                onClick = onOpenSettings,
            )
        }

        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            text = stringResource(R.string.permission_start),
            onClick = onStart,
            enabled = status.canStartTracking,
        )
    }
}

@Composable
private fun PermissionStep(
    title: String,
    body: String,
    granted: Boolean,
    onRequest: () -> Unit,
    enabled: Boolean = true,
    actionLabel: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (granted) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            if (granted) {
                Text(
                    text = "✓ " + stringResource(R.string.permission_granted),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                SecondaryButton(
                    text = actionLabel ?: stringResource(R.string.permission_grant),
                    onClick = onRequest,
                    enabled = enabled,
                )
            }
        }
    }
}
