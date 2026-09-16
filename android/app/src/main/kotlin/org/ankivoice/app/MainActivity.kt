package org.ankivoice.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.ankivoice.ankidroid.AndroidAccessPlatform
import org.ankivoice.core.contracts.*

class MainActivity : ComponentActivity() {
    private val root get() = application as ShellApplication
    private val controller get() = root.controller
    private val provider get() = root.provider
    private var screen by mutableStateOf(ShellState())
    private var providerScreen by mutableStateOf(ProviderState())
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        controller.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        screen = controller.state
        controller.observer = { screen = it }
        providerScreen = provider.state
        provider.observer = { providerScreen = it }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF14695F), onPrimary = Color.White,
                background = Color(0xFFF6F8F6), surface = Color(0xFFF6F8F6),
                surfaceContainer = Color(0xFFEAF0EA),
            )) {
                ShellScreen(screen, providerScreen, controller, provider, ::correctAccess, ::openAppSettings)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        root.foregroundEvents.onForegroundEvent(ForegroundEvent.RESUME)
    }

    override fun onPause() {
        if (!isChangingConfigurations) root.foregroundEvents.onForegroundEvent(ForegroundEvent.PAUSE)
        super.onPause()
    }

    override fun onStop() {
        if (!isChangingConfigurations) root.foregroundEvents.onForegroundEvent(ForegroundEvent.STOP)
        super.onStop()
    }

    override fun onDestroy() {
        controller.observer = null
        provider.observer = null
        super.onDestroy()
    }

    private fun openAppSettings() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun correctAccess(mode: FailureMode) {
        when (mode) {
            CardProviderFailure.ACCESS_DENIED -> requestPermission.launch(AndroidAccessPlatform.DATABASE_PERMISSION)
            SpeechInputFailure.PERMISSION_DENIED -> requestPermission.launch(Manifest.permission.RECORD_AUDIO)
            CardProviderFailure.PACKAGE_UNAVAILABLE -> {
                val installed = try { packageManager.getApplicationInfo(AndroidAccessPlatform.PACKAGE, 0); true }
                    catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
                open(if (installed) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${AndroidAccessPlatform.PACKAGE}"))
                    else Intent(Intent.ACTION_VIEW, Uri.parse("https://ankidroid.org/")))
            }
            else -> open(packageManager.getLaunchIntentForPackage(AndroidAccessPlatform.PACKAGE)
                ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${AndroidAccessPlatform.PACKAGE}")))
        }
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { controller.refresh() }
    }
}

@Composable
private fun ShellScreen(
    state: ShellState,
    providerState: ProviderState,
    controller: ShellController,
    provider: ProviderController,
    correctAccess: (FailureMode) -> Unit,
    openAppSettings: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("AnkiVoice", style = MaterialTheme.typography.headlineLarge)
            Text("Set up your study space", style = MaterialTheme.typography.titleMedium)
            Text("Connect AnkiDroid, allow microphone access, then choose a deck.")
            if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())

            val failure = state.accessFailure
            if (failure != null) {
                AccessCard(failure, { correctAccess(failure.mode) }, controller::refresh, openAppSettings)
            } else if (!state.checking) {
                Text("AnkiDroid connected · Microphone allowed", color = MaterialTheme.colorScheme.primary)
            }

            if (failure == null || failure.mode == CardProviderFailure.DECK_MISSING) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Study deck", style = MaterialTheme.typography.titleLarge)
                        Text("Choosing a deck changes AnkiDroid’s current deck. This does not submit a review.")
                        if (state.decks.isEmpty() && !state.checking) Text("No decks available. Add or import a deck in AnkiDroid, then check again.")
                        state.decks.forEach { deck ->
                            val selected = state.selectedDeckId == deck.id
                            OutlinedButton(
                                onClick = { controller.selectDeck(deck.id) },
                                enabled = !state.checking,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (selected) "Selected: ${deck.name}" else deck.name) }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Session", style = MaterialTheme.typography.titleLarge)
                    Text(statusText(if (failure != null && state.status == PreviewStatus.Idle) PreviewStatus.Paused(failure) else state.status), style = MaterialTheme.typography.titleMedium)
                    Text(PREVIEW_DESCRIPTION, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = controller::start,
                            enabled = !state.checking && failure == null && state.selectedDeckId != null,
                        ) { Text(if (state.status is PreviewStatus.Paused) "Resume" else "Start") }
                        OutlinedButton(onClick = controller::stop) { Text("Stop") }
                    }
                }
            }

            ProviderSettingsCard(providerState, provider)
            if (providerState.keyPresent && !providerState.disclosureAcknowledged) {
                DisclosureCard(providerState, provider)
            }
            QuotaCard(providerState, provider)
            DiagnosticsCard(providerState, provider)

            var language by rememberSaveable(state.language) { mutableStateOf(state.language) }
            OutlinedTextField(
                value = language, onValueChange = { language = it }, singleLine = true,
                label = { Text("Session language") }, supportingText = { Text("Language tag, such as en-US. English is the initial study language.") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (language.trim() != state.language) {
                TextButton(onClick = { controller.setLanguage(language) }, enabled = language.isNotBlank()) { Text("Save language") }
            }
            TextButton(onClick = controller::refresh, enabled = !state.checking) { Text("Check access again") }
        }
    }
}

@Composable
private fun AccessCard(failure: Failure, correct: () -> Unit, retry: () -> Unit, settings: () -> Unit) {
    val (title, explanation, action) = when (failure.mode) {
        CardProviderFailure.PACKAGE_UNAVAILABLE -> Triple("Set up AnkiDroid", "Install AnkiDroid, or enable it in Android’s app settings, then return here.", "Install or enable AnkiDroid")
        CardProviderFailure.API_DISABLED -> Triple("Enable AnkiDroid access", "In AnkiDroid, open Settings → Advanced and turn on Enable AnkiDroid API. Return here to check access.", "Open AnkiDroid")
        CardProviderFailure.ACCESS_DENIED -> Triple("Allow AnkiDroid access", "AnkiVoice needs AnkiDroid’s database permission to list your decks. If you denied access, you can allow it below. If Android no longer asks, open app settings and grant it there.", "Allow AnkiDroid access")
        SpeechInputFailure.PERMISSION_DENIED -> Triple("Allow microphone access", "Microphone permission is needed for voice study. Denying it keeps study paused. Allow it below, or grant it in app settings if Android no longer asks.", "Allow microphone")
        CardProviderFailure.DECK_MISSING -> Triple("Choose another deck", "Your saved deck is no longer available. Choose a deck from the list below; the missing deck does not mean your queue is finished.", "Open AnkiDroid")
        else -> Triple("Check AnkiDroid", "AnkiDroid did not return usable deck information. Open it, finish its setup, and check that its API is enabled. Then try again.", "Open AnkiDroid")
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(explanation)
            Text(failure.mode.specName, style = MaterialTheme.typography.labelMedium)
            Button(onClick = correct) { Text(action) }
            if (failure.mode == CardProviderFailure.ACCESS_DENIED || failure.mode == SpeechInputFailure.PERMISSION_DENIED) {
                TextButton(onClick = settings) { Text("Open app settings") }
            }
            TextButton(onClick = retry) { Text("Try again") }
        }
    }
}

private fun statusText(status: PreviewStatus): String = when (status) {
    PreviewStatus.Idle -> "Ready to start"
    PreviewStatus.Starting -> "Checking access and deck…"
    PreviewStatus.CardReady -> "Card ready"
    PreviewStatus.Exhausted -> "Queue exhausted"
    is PreviewStatus.Paused -> status.failure?.let { "Paused · ${it.mode.specName}" } ?: "Paused · Tap Resume to continue"
    PreviewStatus.Stopped -> "Stopped"
    PreviewStatus.Unavailable -> "Study unavailable"
}
