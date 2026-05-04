// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aikeyboard.app.latin.R
import com.aikeyboard.app.ai.storage.SecureStorage

/**
 * Phase 7b first-run consent for Read & Respond. Shown the first time the
 * user taps the IME button while the service is enabled. On Accept,
 * persists `readRespondConsented = true`; on Decline, finishes without
 * persisting (so the next tap re-prompts).
 *
 * The user does NOT auto-resume into the Read & Respond flow on Accept —
 * they tap the keyboard button a second time, which now skips this
 * activity. One-time twoTaps is acceptable for a one-time consent.
 */
class ReadRespondConsentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storage = SecureStorage.getInstance(applicationContext)
        setContent {
            ConsentScreen(
                onAccept = {
                    storage.setReadRespondConsented(true)
                    finish()
                },
                onDecline = { finish() },
            )
        }
    }
}

@Composable
private fun ConsentScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.ai_read_respond_consent_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.ai_read_respond_consent_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ai_read_respond_consent_accept))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ai_read_respond_consent_decline))
            }
        }
    }
}
