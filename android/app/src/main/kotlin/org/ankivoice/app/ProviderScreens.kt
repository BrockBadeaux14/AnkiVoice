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
import org.ankivoice.provider.GradingRoute
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

/**
 * AV-043: which requests may cost money, the cap, and that a stop never rates a card. The
 * route names come from the pinned constants, so this text cannot describe a route the
 * build does not ship.
 */
internal val DISCLOSURE_COST: List<String>
    get() = listOf(
        "The free route (${ProviderModule.freeRouteDescription}) is always tried first and costs nothing.",
        "If the free route is refused, unavailable, times out or fails, one paid request may be sent to " +
            "${ProviderModule.paidRouteDescription}. It is charged to your OpenRouter credits.",
        "Paid requests stop for the UTC day at the budget you set below: the default is " +
            "\$${QuotaLedger.formatUsd(QuotaLedger.DEFAULT_DAILY_CAP_USD)}, and \$0 turns the paid route off. " +
            "Each paid request holds its maximum cost before it is sent, and the reply's own cost replaces the hold.",
        "A stop — a used-up allowance, a spent budget, or a refused route — never rates a card. You grade yourself instead.",
    )

internal val DISCLOSURE_LIMITS = listOf(
    "OpenRouter says prompt and output storage is opt-in, and it keeps request metadata.",
    "Liquid's policy permits training on inputs and outputs and promises no fixed short retention.",
    "The paid route's provider, named above, has its own data policy; its retention was not established by this project.",
    "Speech recognition is Android's own service, signed out. Its retention was not established.",
)

/**
 * AV-018 (#20) widened what this app stores on the device, so the disclosure moves with
 * it. The app must never describe a narrower footprint than it has.
 */
internal val DISCLOSURE_STORED_ON_DEVICE = listOf(
    "Your answer, as text, in a session journal: what you said, which card it was for, the " +
        "rating and what happened to it. It is kept so an interrupted review can be " +
        "explained rather than guessed at.",
    "The journal keeps the current session plus the newest 50 finished reviews, or 7 days, " +
        "whichever is smaller. Older entries are deleted automatically.",
    "It stays in this app's private storage, separate from your Anki collection, and is " +
        "excluded from cloud backup and device-to-device transfer. Clearing this app's data " +
        "deletes it.",
    "Card text — prompts, reference answers and Extra — and audio are never written to " +
        "disk. Diagnostics stay free of your answers unless you turn content on below.",
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

/**
 * What this app keeps on the device, shown whether or not AI grading is configured.
 *
 * The AV-020 disclosure below is gated on a saved key, but AV-018's journal records
 * transcripts on every study session, so the storage statement cannot be gated with it.
 */
@Composable
internal fun OnDeviceStorageCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Kept on this device", style = MaterialTheme.typography.titleLarge)
            DISCLOSURE_STORED_ON_DEVICE.forEach { Text("• $it") }
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
            Text("What may cost money:", style = MaterialTheme.typography.titleMedium)
            DISCLOSURE_COST.forEach { Text("• $it") }
            Text("Kept on this device:", style = MaterialTheme.typography.titleMedium)
            DISCLOSURE_STORED_ON_DEVICE.forEach { Text("• $it") }
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
                supportingText = { Text("0 to ${QuotaLedger.MAX_DAILY_LIMIT} requests per UTC day, free and paid together. The default is ${QuotaLedger.DEFAULT_DAILY_LIMIT}.") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (limit.toIntOrNull() != state.dailyLimit) {
                TextButton(
                    onClick = { controller.setDailyLimit(limit.toIntOrNull() ?: -1) },
                    enabled = limit.isNotBlank() && !state.busy,
                ) { Text("Save limit") }
            }

            // AV-043: today's paid spend beside the request counters, and the cap that bounds it.
            Text(
                "Paid fallback: \$${QuotaLedger.formatUsd(state.spentTodayUsd, 4)} of " +
                    "\$${QuotaLedger.formatUsd(state.dailyCapUsd)} spent today (UTC)." +
                    if (state.paidEnabled) "" else " The paid route is off.",
            )
            state.paidStop?.let { Text(it.reason, color = MaterialTheme.colorScheme.error) }
            var cap by rememberSaveable(state.dailyCapUsd.toPlainString()) { mutableStateOf(QuotaLedger.formatUsd(state.dailyCapUsd)) }
            OutlinedTextField(
                value = cap,
                onValueChange = { cap = it.filter { c -> c.isDigit() || c == '.' }.take(7) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                label = { Text("Daily paid budget (USD)") },
                supportingText = {
                    Text(
                        "\$0 turns the paid route off. Up to \$${QuotaLedger.formatUsd(QuotaLedger.MAX_DAILY_CAP_USD)} per UTC day; " +
                            "the default is \$${QuotaLedger.formatUsd(QuotaLedger.DEFAULT_DAILY_CAP_USD)}. The free route is always tried first.",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (QuotaLedger.parseDailyCap(cap)?.compareTo(state.dailyCapUsd) != 0) {
                TextButton(
                    onClick = { controller.setDailyCap(cap) },
                    enabled = cap.isNotBlank() && !state.busy,
                ) { Text("Save budget") }
            }
            Text(
                "Requests are counted before they are sent, so a timed-out request still counts, and a paid request " +
                    "holds its maximum cost until the reply reports the real one. Deleting the app's data does not give " +
                    "you more requests or budget. AnkiVoice never adds funds, and never raises this limit or budget on " +
                    "its own: the paid route is used only within the budget you set here.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = controller::checkRoute, enabled = !state.busy) { Text("Check the routes") }
                OutlinedButton(onClick = { controller.sendSmokeRequest(GradingRoute.FREE) }, enabled = state.gradingConfigured && !state.busy) {
                    Text("Send one free test request")
                }
            }
            OutlinedButton(
                onClick = { controller.sendSmokeRequest(GradingRoute.PAID) },
                enabled = state.gradingConfigured && state.paidEnabled && !state.busy,
            ) { Text("Send one paid test request") }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun DiagnosticsCard(state: ProviderState, controller: ProviderController) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
            Text("Timings, failure names, request counts and reported costs only. No secrets, card text, transcripts or audio.", style = MaterialTheme.typography.bodySmall)
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
