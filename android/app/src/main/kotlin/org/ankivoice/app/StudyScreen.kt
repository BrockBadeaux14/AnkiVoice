package org.ankivoice.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.ankivoice.core.commands.VoiceCommand
import org.ankivoice.core.exchange.RatingSource
import org.ankivoice.core.exchange.ratingName

/** How often the screen asks the transport what it has heard so far. */
private const val HEARING_POLL_MS = 250L

/** How often AV-047's cancel window is redrawn while it counts down. */
private const val AUTO_COMMIT_TICK_MS = 100L

/**
 * The study screen: one card at a time, reached after deck selection, provisioning and
 * AV-018's gate.
 *
 * Everything on it is read from one [StudyState] snapshot the controller published from
 * the session, and every control here is enabled only while [StudyState.controls] lists
 * it. Nothing on this screen writes except Confirm, and Confirm reaches the writer only
 * through AV-019's exchange for a confirmation AV-013 accepted for this attempt and
 * revision. Every voice command has a touch control; no control is voice-only.
 *
 * AV-047: when the learner has turned **Automatic grading** on in the setup screen, a
 * rating the grader proposed is also saved when its cancel window runs out. The screen
 * says so for the whole session, counts the window down where the rating is shown, and
 * offers **Keep it manual** as the one control that stops it. It still writes nothing
 * itself: the window is timed by the controller and committed through the same exchange.
 */
@Composable
internal fun StudyScreen(state: StudyState, study: StudyController, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Setup") }
                Spacer(Modifier.weight(1f))
                Text("Study", style = MaterialTheme.typography.headlineMedium)
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            JournalNotices(state, study)
            Text(state.status, style = MaterialTheme.typography.titleMedium)

            if (state.running) {
                AutomaticGradingBanner(state)
                CardPanel(state)
                AnswerPanel(state, study)
                RatingPanel(state, study)
                OutcomePanel(state, study)
            }
            HaltPanel(state, study, onBack)
            if (state.running) CommandPanel(state, study)

            state.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            state.failure?.let {
                Text(it.mode.specName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** AV-018: an unattributable review from an earlier run blocks the start until it is acknowledged. */
@Composable
private fun JournalNotices(state: StudyState, study: StudyController) {
    if (state.journalNotices.isEmpty() && state.journalOutstanding.isEmpty()) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("An earlier review is unresolved", style = MaterialTheme.typography.titleMedium)
            state.journalNotices.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                "AnkiVoice stopped before it could confirm what it wrote. It will not send this " +
                    "review again. Check the card in AnkiDroid, then acknowledge it here.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.journalOutstanding.forEach { entryId ->
                Button(onClick = { study.acknowledgeJournalNotice(entryId) }) { Text("I have checked AnkiDroid") }
            }
        }
    }
}

/** The card's Prompt, and what AV-010 skipped on the way to it. Never the answer or the Extra. */
@Composable
private fun CardPanel(state: StudyState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Question", style = MaterialTheme.typography.labelLarge)
            Text(
                state.prompt ?: if (state.sessionState == "exhausted") "No card is due." else "No card is open.",
                style = MaterialTheme.typography.headlineSmall,
            )
            state.cardId?.let { Text("Card $it · answer version ${state.transcriptRevision}", style = MaterialTheme.typography.labelSmall) }
            if (state.skipped.isNotEmpty()) {
                Text("Skipped without a write", style = MaterialTheme.typography.labelMedium)
                state.skipped.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            state.skipSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** The learner's own words, the answer controls, and the transcript editor. */
@Composable
private fun AnswerPanel(state: StudyState, study: StudyController) {
    val controls = state.controls
    val idle = !state.busy
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your answer", style = MaterialTheme.typography.labelLarge)
            if (state.answering || state.listeningForCommand) {
                // What the recognizer makes of it while it is still making it up. It is
                // progress, not a result: AV-012 settles on the final alone, and the line is
                // labelled so a partial is never read as what the attempt recorded.
                var hearing by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(state.answering, state.listeningForCommand) {
                    while (true) {
                        hearing = study.hearing()
                        delay(HEARING_POLL_MS)
                    }
                }
                Text(
                    hearing?.let { "Hearing: $it" } ?: "Hearing: (nothing yet)",
                    color = MaterialTheme.colorScheme.primary,
                )
                if (state.answering) {
                    Text(
                        "Command words spoken now are part of your answer, not commands.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val transcript = state.transcript
            when {
                transcript != null -> {
                    Text("“$transcript”", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Answer version ${state.transcriptRevision} · " +
                            when (state.transcriptKind) {
                                "user-corrected" -> "typed by you"
                                else -> if (state.transcriptNeedsReview) "heard, but not vouched for" else "heard"
                            },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                state.heard != null -> Text("Heard: “${state.heard}”", style = MaterialTheme.typography.bodyLarge)
                state.attempt > 0 && !state.answering -> Text("No transcript for this attempt.", style = MaterialTheme.typography.bodyMedium)
            }

            var editing by rememberSaveable { mutableStateOf(false) }
            if (StudyControl.EDIT_TRANSCRIPT !in controls) editing = false
            if (editing) {
                var draft by rememberSaveable(state.transcriptRevision) { mutableStateOf(state.transcript.orEmpty()) }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Your answer, as text") },
                    supportingText = { Text("Saving a new version retires any suggestion or pending rating for the old one.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            study.editTranscript(draft)
                            editing = false
                        },
                        enabled = idle && draft.isNotBlank(),
                    ) { Text("Use this transcript") }
                    TextButton(onClick = { editing = false }) { Text("Keep it") }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = study::ask, enabled = idle && StudyControl.PLAY_PROMPT in controls) { Text("Play prompt") }
                Button(onClick = study::startAnswer, enabled = idle && StudyControl.START_ANSWER in controls) { Text("Start answer") }
                // Done and Cancel end a capture, so they stay live while one is in flight —
                // the session thread is busy inside exactly that capture.
                Button(onClick = study::finishAnswer, enabled = StudyControl.DONE in controls) { Text("Done") }
                OutlinedButton(onClick = study::cancelAnswer, enabled = StudyControl.CANCEL_ANSWER in controls) { Text("Cancel") }
                OutlinedButton(onClick = study::retry, enabled = idle && StudyControl.TRY_AGAIN in controls) { Text("Try again") }
                OutlinedButton(
                    onClick = { editing = !editing },
                    enabled = idle && StudyControl.EDIT_TRANSCRIPT in controls,
                ) { Text(if (state.transcriptNeedsReview) "Check the transcript" else "Edit transcript") }
            }
        }
    }
}

/**
 * AV-019's Announced position and the grading status behind it: what is pending, where it
 * came from, and which answer version it was computed from. Nothing here is written, and an
 * abstention is never shown as a rating.
 */
@Composable
private fun RatingPanel(state: StudyState, study: StudyController) {
    val controls = state.controls
    val idle = !state.busy
    val grading = state.grading
    val announcement = state.announcement
    if (grading == null && announcement == null && !state.gradingInFlight && StudyControl.RATE !in controls) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Rating", style = MaterialTheme.typography.labelLarge)
            if (state.gradingInFlight) Text("Checking your answer…", style = MaterialTheme.typography.bodyMedium)
            grading?.let {
                Text(it.status, style = MaterialTheme.typography.bodyMedium)
                Text("Graded answer version ${it.revision}", style = MaterialTheme.typography.labelSmall)
            }
            if (announcement != null) {
                Text(
                    state.pendingRating?.let { "${ratingName(it)} is waiting" } ?: "No rating was suggested",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(announcement, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Source: ${sourceLabel(state.ratingSource)} · answer version ${state.announcedRevision ?: 0}",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (state.confirmed) Text("Confirmed for this answer.", style = MaterialTheme.typography.labelMedium)
                AutomaticGradingPanel(state, study)
            }
            if (StudyControl.RATE in controls) {
                Text(
                    if (state.pendingRating != null) "Choose a different rating" else "Rate it yourself",
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.ratings.forEach { rating ->
                        OutlinedButton(
                            onClick = { study.rate(rating) },
                            enabled = idle && rating != state.pendingRating,
                        ) { Text(ratingName(rating)) }
                    }
                }
            }
            if (StudyControl.CONFIRM in controls || StudyControl.CHANGE in controls) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { study.run(VoiceCommand.CONFIRM) },
                        enabled = idle && StudyControl.CONFIRM in controls,
                    ) { Text("Confirm ${state.pendingRating?.let(::ratingName) ?: ""}".trim()) }
                    OutlinedButton(
                        onClick = { study.run(VoiceCommand.CHANGE) },
                        enabled = idle && StudyControl.CHANGE in controls,
                    ) { Text("Change") }
                }
                Text(
                    if (state.automaticGrading) {
                        "Automatic grading is on, so a rating the grader proposes is saved on its own. " +
                            "Confirm saves it now; everything else here leaves your collection alone."
                    } else {
                        "Confirm is the one control that writes a review. Everything else leaves your collection alone."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * AV-047: the option's state, for as long as a session runs.
 *
 * It is on the screen whether or not a rating is waiting, because the learner needs to know
 * before they answer that this session can save a rating without them.
 */
@Composable
private fun AutomaticGradingBanner(state: StudyState) {
    if (!state.automaticGrading) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Automatic grading is on", style = MaterialTheme.typography.titleSmall)
            Text(
                "A rating the grader proposes is saved without your confirmation, after a few " +
                    "seconds you can use to stop it. A rating you name yourself, and a turn the " +
                    "grader could not grade, still wait for you. Turn it off in Setup.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * AV-047: the armed commit, where the rating it will save is shown.
 *
 * While a window is open it counts it down and offers the one control that stops it. The
 * countdown is the surface's own display of a window the **controller** is timing; it never
 * drives the write, so a countdown a recomposition restarts cannot lengthen or shorten what
 * actually happens.
 */
@Composable
private fun AutomaticGradingPanel(state: StudyState, study: StudyController) {
    if (!state.automaticGrading) return
    val window = state.autoCommitWindowMs
    if (window == null) {
        Text(
            if (state.autoCommitCancelled) {
                "You stopped automatic grading for this card. Nothing was written; confirm the " +
                    "rating yourself when you are ready."
            } else {
                "This rating is yours to confirm: automatic grading saves only what the grader proposed."
            },
            style = MaterialTheme.typography.labelMedium,
        )
        return
    }
    // Restarted whenever a new window opens, which is what the key on the revision and the
    // rating gives: a correction or an edit opens its own window, never a resumed one.
    var remaining by remember(state.announcedRevision, state.pendingRating, window) {
        mutableStateOf(window)
    }
    LaunchedEffect(state.announcedRevision, state.pendingRating, window) {
        while (remaining > 0) {
            delay(AUTO_COMMIT_TICK_MS)
            remaining = (remaining - AUTO_COMMIT_TICK_MS).coerceAtLeast(0)
        }
    }
    Text(
        if (remaining > 0) {
            "Saving automatically in ${(remaining + 999) / 1000}s. Stop it to keep this card manual."
        } else {
            "Saving automatically now…"
        },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    LinearProgressIndicator(
        progress = { (remaining.toFloat() / window.toFloat()).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = study::cancelAutomaticCommit,
        enabled = !state.busy && StudyControl.CANCEL_AUTOMATIC in state.controls,
    ) { Text("Keep it manual") }
    Text(
        "Once a review is saved, only AnkiDroid's own Undo can take it back.",
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun sourceLabel(source: String?): String = when (source) {
    RatingSource.RULE.specName -> "an exact rule match"
    RatingSource.AI.specName -> "the AI grader's suggestion"
    RatingSource.LEARNER.specName -> "you named it"
    RatingSource.NONE.specName -> "nobody proposed one"
    else -> "unknown"
}

/**
 * What the writer returned, and only that.
 *
 * A confirmed review is the only one announced as saved and the only one that offers Next
 * card; a failed write says plainly that nothing was saved; and an unknown outcome offers
 * the learner-reported reconcile and never a retry, because AnkiVoice cannot find out on
 * its own and will not send the review again.
 */
@Composable
private fun OutcomePanel(state: StudyState, study: StudyController) {
    val outcome = state.outcomeState ?: return
    val controls = state.controls
    val idle = !state.busy
    val resolved = state.committed
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (resolved) MaterialTheme.colorScheme.surfaceContainer
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when {
                    resolved -> "Review saved"
                    state.reconcileRequired -> "The review could not be confirmed"
                    else -> "Nothing was saved"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(outcome, style = MaterialTheme.typography.labelMedium)
            state.outcomeReason?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            when {
                resolved -> {
                    Text(
                        "AnkiVoice cannot take a review back. Correcting this one is AnkiDroid's own " +
                            "Undo, and AnkiDroid may no longer offer it after other activity there, or " +
                            "after either app's process is closed or replaced.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = study::nextCard, enabled = idle && StudyControl.NEXT_CARD in controls) { Text("Next card") }
                        OutlinedButton(onClick = study::handOffToUndo, enabled = idle && StudyControl.UNDO_HANDOFF in controls) {
                            Text("Wrong rating? Undo in AnkiDroid")
                        }
                    }
                }
                state.reconcileRequired -> {
                    Text(
                        "AnkiVoice stopped before it could confirm what it wrote, and it will not " +
                            "send this review again. Open AnkiDroid, look at this card, then tell " +
                            "AnkiVoice what you saw.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { study.reportReconciled(true) }, enabled = idle && StudyControl.REPORT_RECONCILED in controls) {
                            Text("I checked AnkiDroid: saved")
                        }
                        OutlinedButton(onClick = { study.reportReconciled(false) }, enabled = idle && StudyControl.REPORT_RECONCILED in controls) {
                            Text("I checked AnkiDroid: not saved")
                        }
                    }
                }
                else -> Text(
                    "The card is exactly as it was: nothing was written, buried, suspended or reordered.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Every paused or halted state, in the learner's words, with only the controls that apply.
 * None of them writes: Try again and the transcript editor live in the answer panel, the
 * self-grade in the rating panel, and the reconcile report in the outcome panel.
 */
@Composable
private fun HaltPanel(state: StudyState, study: StudyController, onBack: () -> Unit) {
    val halt = state.halt
    val closed = state.closed
    if (halt == null && closed == null && state.running) return
    if (halt == null && closed == null && state.controls.isEmpty()) return
    val controls = state.controls
    val idle = !state.busy
    val severe = halt != null && !halt.resumable && halt.kind != "exhausted" && halt.kind != "stopped"
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (severe) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when {
                    halt?.kind == "outcome-unknown" -> "The review could not be confirmed"
                    halt?.kind == "interrupted" || closed == "interrupted" -> "Study was interrupted"
                    halt?.kind == "exhausted" -> "Queue finished"
                    halt?.kind == "paused" -> "Paused"
                    closed != null || halt != null -> "Study stopped"
                    state.journalOutstanding.isNotEmpty() -> "Study is blocked"
                    else -> "Study is unavailable"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            halt?.let {
                Text(it.explanation, style = MaterialTheme.typography.bodyMedium)
                Text(it.reason, style = MaterialTheme.typography.labelSmall)
            }
            if (halt == null && !state.running) Text(state.status, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (StudyControl.RESUME in controls) {
                    Button(onClick = { study.run(VoiceCommand.RESUME) }, enabled = idle) { Text("Resume") }
                }
                if (StudyControl.RELOAD in controls) {
                    Button(onClick = study::reload, enabled = idle) {
                        Text(if (closed == "interrupted" || halt?.kind == "interrupted") "Reload" else "Start again")
                    }
                }
                if (StudyControl.FINISH in controls && halt != null) {
                    OutlinedButton(onClick = study::stop, enabled = idle) { Text("Finish") }
                }
                if (!state.running) TextButton(onClick = onBack) { Text("Back to setup") }
            }
        }
    }
}

/** AV-014's routine commands, each with a touch control, and the learner-opened command capture. */
@Composable
private fun CommandPanel(state: StudyState, study: StudyController) {
    val controls = state.controls
    val idle = !state.busy
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Controls", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { study.run(VoiceCommand.REPEAT) }, enabled = idle && StudyControl.REPEAT in controls) { Text("Repeat") }
                OutlinedButton(onClick = { study.run(VoiceCommand.REVEAL) }, enabled = idle && StudyControl.REVEAL in controls) { Text("Show answer") }
                OutlinedButton(onClick = { study.run(VoiceCommand.PAUSE) }, enabled = idle && StudyControl.PAUSE in controls) { Text("Pause") }
                OutlinedButton(onClick = { study.run(VoiceCommand.SKIP) }, enabled = idle && StudyControl.SKIP in controls) { Text("Skip") }
                if (state.halt == null) {
                    OutlinedButton(onClick = study::stop, enabled = idle && StudyControl.FINISH in controls) { Text("Finish") }
                }
            }
            Text(
                if (state.spokenAvailable.isEmpty()) {
                    "No command can be spoken right now. Every command has a button."
                } else {
                    "Say one of: ${state.spokenAvailable.joinToString(", ") { it.specName.replace('-', ' ') }}."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = study::listenForCommand,
                enabled = idle && StudyControl.SPEAK_COMMAND in controls,
            ) { Text("Speak a command") }
        }
    }
}
