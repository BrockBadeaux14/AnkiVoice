package org.ankivoice.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.ankivoice.provider.CredentialPolicy
import org.ankivoice.provider.ProviderModule
import org.ankivoice.provider.QuotaLedger

/** What the grader is sent, and what it is never sent. AV-006's measured limits. */
internal val DISCLOSURE_SENT = listOf(
    "Your spoken answer, as text",
    "The card's Prompt, ReferenceAnswer, RequiredConcepts and AcceptedAnswers",
)

internal val DISCLOSURE_NEVER_SENT = listOf(
    "Audio of any kind",
    "The card's Extra field",
    "Card or note IDs, deck names, or anything else from your collection",
)

internal val DISCLOSURE_LIMITS = listOf(
    "OpenRouter says prompt and output storage is opt-in, and it keeps request metadata.",
    "Liquid's policy permits training on inputs and outputs and promises no fixed short retention.",
    "Speech recognition is Android's own service, signed out. Its retention was not established.",
)

@Composable
internal fun ProviderSettingsCard(state: ProviderState, controller: ProviderController) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AI grading", style = MaterialTheme.typography.titleLarge)
            Text(
                if (state.gradingConfigured) "AI grading is on. It only ever suggests; you confirm every rating."
                else "AI grading is off. Self-grading works without it.",
                color = if (state.gradingConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text("Route: ${ProviderModule.routeDescription}.", style = MaterialTheme.typography.bodySmall)
            Text(CredentialPolicy.describe(state.keyPresent), style = MaterialTheme.typography.bodyMedium)

            var entered by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = entered,
                onValueChange = { entered = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                label = { Text(if (state.keyPresent) "Replace the OpenRouter key" else "OpenRouter key") },
                supportingText = {
                    Text("Stored on this device only, wrapped by Android's Keystore, excluded from backup, and never shown again.")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        controller.saveKey(entered)
                        entered = ""
                    },
                    enabled = entered.isNotBlank() && !state.busy,
                ) { Text("Save key") }
                if (state.keyPresent) {
                    OutlinedButton(onClick = controller::clearKey, enabled = !state.busy) { Text("Remove key") }
                }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
internal fun DisclosureCard(state: ProviderState, controller: ProviderController) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Before grading is used", style = MaterialTheme.typography.titleLarge)
            Text("Sent to the grader:", style = MaterialTheme.typography.titleMedium)
            DISCLOSURE_SENT.forEach { Text("• $it") }
            Text("Never sent:", style = MaterialTheme.typography.titleMedium)
            DISCLOSURE_NEVER_SENT.forEach { Text("• $it") }
            Text("What is not established:", style = MaterialTheme.typography.titleMedium)
            DISCLOSURE_LIMITS.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Text("Grading stays off until you acknowledge this, and self-grading is always available.")
            Button(onClick = controller::acknowledgeDisclosure, enabled = !state.busy) {
                Text("I understand — turn on AI grading")
            }
        }
    }
}

@Composable
internal fun QuotaCard(state: ProviderState, controller: ProviderController) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Grading allowance", style = MaterialTheme.typography.titleLarge)
            Text("${state.dailyRemaining} of ${state.dailyLimit} requests left today (UTC).")
            Text("${state.sessionRemaining} of ${QuotaLedger.SESSION_LIMIT} left in this session.")
            state.stop?.let { Text(it.reason, color = MaterialTheme.colorScheme.error) }

            var limit by rememberSaveable(state.dailyLimit) { mutableStateOf(state.dailyLimit.toString()) }
            OutlinedTextField(
                value = limit,
                onValueChange = { limit = it.filter(Char::isDigit).take(4) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("Daily limit") },
                supportingText = { Text("0 to ${QuotaLedger.MAX_DAILY_LIMIT} requests per UTC day. The default is ${QuotaLedger.DEFAULT_DAILY_LIMIT}.") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (limit.toIntOrNull() != state.dailyLimit) {
                TextButton(
                    onClick = { controller.setDailyLimit(limit.toIntOrNull() ?: -1) },
                    enabled = limit.isNotBlank() && !state.busy,
                ) { Text("Save limit") }
            }
            Text(
                "Requests are counted before they are sent, so a timed-out request still counts. " +
                    "Deleting the app's data does not give you more free requests, and AnkiVoice never adds funds, " +
                    "raises a cap or switches to a paid model.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = controller::checkRoute, enabled = !state.busy) { Text("Check the free route") }
                OutlinedButton(onClick = controller::sendSmokeRequest, enabled = state.gradingConfigured && !state.busy) {
                    Text("Send one test request")
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun DiagnosticsCard(state: ProviderState, controller: ProviderController) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
            Text("Timings, failure names and request counts only. No secrets, card text, transcripts or audio.", style = MaterialTheme.typography.bodySmall)
            if (state.diagnostics.isEmpty()) {
                Text("Nothing recorded yet.")
            } else {
                state.diagnostics.takeLast(12).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = state.retainContent, onCheckedChange = controller::setRetainContent, enabled = !state.busy)
                Text("Keep transcripts and card text for the pilot report", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = controller::clearDiagnostics, enabled = !state.busy) { Text("Clear diagnostics") }
        }
    }
}
