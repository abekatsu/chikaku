package com.damburisoft.chikaku.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.damburisoft.chikaku.watch.R

/**
 * プロミネントディスクロージャー（CLAUDE.md §2.3 / §5）。
 * 権限ダイアログを出す前に、何のために背景で位置情報を使うのかを本人に説明し、
 * 同意を得る。ここを通らないと見守りは始められない。
 */
@Composable
fun DisclosureScreen(onAgree: () -> Unit) {
    var declined by remember { mutableStateOf(false) }

    ScreenScaffold(title = stringResource(R.string.disclosure_title)) {
        Text(
            text = stringResource(R.string.disclosure_body),
            style = MaterialTheme.typography.bodyLarge,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BulletPoint(stringResource(R.string.disclosure_point_who))
                BulletPoint(stringResource(R.string.disclosure_point_when))
                BulletPoint(stringResource(R.string.disclosure_point_stop))
            }
        }

        if (declined) {
            Text(
                text = stringResource(R.string.disclosure_declined_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        PrimaryButton(text = stringResource(R.string.disclosure_agree), onClick = onAgree)
        SecondaryButton(
            text = stringResource(R.string.disclosure_decline),
            onClick = { declined = true },
        )
    }
}

@Composable
private fun BulletPoint(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(text = "・", style = MaterialTheme.typography.bodyLarge)
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}
