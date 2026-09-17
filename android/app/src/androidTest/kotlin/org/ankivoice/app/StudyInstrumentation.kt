package org.ankivoice.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.ankivoice.ankidroid.AndroidAccessPlatform
import org.ankivoice.ankidroid.AnkiDroidCardProvider
import org.ankivoice.core.contracts.CardState
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.eligibility.CardOffer
import org.ankivoice.core.eligibility.offerNextCard
import org.ankivoice.core.journal.JournalEntry
import org.ankivoice.core.journal.ReviewJournal
import org.json.JSONArray
import org.json.JSONObject

/**
 * AV-026's live check on the pinned AVD, on a disposable AV-002 collection: one turn of
 * the **real study screen**, driven by the operator, observed and exported from here.
 *
 * Unlike the earlier harnesses this one composes nothing of its own. It launches the app's
 * own `MainActivity`, watches the shipped `StudyController`'s published snapshots while the
 * operator follows the runbook's routine for the named turn on the real screen, and when
 * the session closes it exports the controller's per-session evidence — events, journal
 * entries, and per turn the grading path, recognition outcomes, retries, edits,
 * confirmation source, corrections and touch actions — beside the card's stored state
 * before and after. So what is verified is the composed app: the same activity, controller,
 * session, grader, transport and journaled writer the learner uses.
 *
 * Every turn needs the operator to speak the answer; nothing here synthesizes a transcript
 * or taps a control on their behalf. The two AI turns need the owner's OpenRouter key
 * entered in the app, and spend the owner's credit within AV-043's cap.
 *
 * | Turn | The operator does, on the study screen | What must hold |
 * | --- | --- | --- |
 * | `rule-match` | answers with the reference answer, confirms | grading path `rule`; one review |
 * | `ai-labelled` | answers in other words, confirms the AI suggestion | path `ai-free` or `ai-paid`; one review |
 * | `abstain-self-grade` | answers so the grader abstains, names a rating, confirms | path `abstain`, a self-grade; one review |
 * | `corrected-confirmed` | answers, changes the rating, confirms the corrected one | a correction; the corrected rating written |
 * | `transcript-edit` | answers, edits the transcript, confirms the regraded rating | an edit; a grade at the new version; one review |
 * | `skip` | answers, skips, finishes | `skip_requested`; nothing written |
 * | `pause-resume` | pauses, resumes, finishes | a pause and a resume; nothing written |
 * | `interruption-reload` | presses Home mid-turn, returns, reloads, finishes | an `app_switch` halt; a fresh session; nothing written |
 * | `undo-handoff` | answers, confirms, hands off to AnkiDroid's Undo | one write, then the session closed for Undo |
 * | `route-refused-self-grade` | with the daily limit at 0, answers in other words, names a rating, confirms | path `unavailable`, a self-grade; one review |
 *
 * Reproduce with docs/testing/av026/runbook.md.
 */
class StudyInstrumentation : Instrumentation() {
    private lateinit var args: Bundle

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        args = arguments
        start()
    }

    override fun onStart() {
        val output = JSONObject()
        val worker = Executors.newSingleThreadExecutor()
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) {
                "The live check runs on the pinned emulator only"
            }
            check(args.getString("confirm") == "AV026_LIVE_STUDY") { "Explicit confirmation required" }
            val platform = AndroidAccessPlatform(targetContext)
            val deckId = checkNotNull(args.getString("deck")) { "A disposable AV-002 deck is required" }.toLong()
            val deck = if (platform.databasePermissionGranted() && platform.apiEnabled() != false) {
                platform.queryDecks()?.singleOrNull { it.id == deckId }
            } else {
                null
            }
            check(deck != null && deck.name.startsWith("AV002")) { "Refusing a deck that is not disposable" }
            output.put("deck", deckId)

            val preferences = targetContext.getSharedPreferences("shell", Context.MODE_PRIVATE)
            if (args.getString("mode") == "prepare") {
                // Setup between turns: the app studies the deck it has selected, and this is
                // the app's own setting. Selecting a deck is not a claim about a review.
                preferences.edit().putLong("selected_deck", deckId).commit()
                output.put("mode", "prepare").put("selectedDeck", deckId).put("passed", true)
                return report(output)
            }
            check(preferences.getLong("selected_deck", -1) == deckId) {
                "The app has a different deck selected; run mode=prepare or choose the AV002 deck in setup"
            }
            val turn = checkNotNull(args.getString("turn")) { "-e turn <name> is required" }
            check(turn in TURNS) { "Unknown turn $turn; choose from $TURNS" }
            output.put("turn", turn)

            val journalFile = File(targetContext.filesDir, JournalModule.FILE)
            output.put("journalBefore", entries(ReviewJournal(FileJournalStore(journalFile))))

            // The card the deck offers first, read the way the study screen will read it,
            // for the before/after comparison in the manner of AV-024.
            val provider = AnkiDroidCardProvider(platform, deckId, worker, targetContext.mainExecutor)
            val first = (offerNextCard(provider, "en-US") as? CardOffer.Ready)?.card
            output.put("cardId", first?.identity?.cardId ?: JSONObject.NULL)
            output.put("before", first?.let { describe(it.state) } ?: JSONObject.NULL)

            val app = targetContext.applicationContext as ShellApplication
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            phase("Study screen: follow the runbook's routine for '$turn', then Finish")

            val watched = watch(app, turn, output)
            output.put("snapshots", watched)

            output.put("evidence", describe(export(app)))
            output.put("journalAfter", entries(ReviewJournal(FileJournalStore(journalFile))))
            val after = first?.let { provider.readCard(it.identity.cardId) as? ScheduledCard }
            output.put("after", after?.let { describe(it.state) } ?: JSONObject.NULL)
            output.put("stateUnchanged", first != null && after?.state == first.state)
            output.put("passed", judge(turn, output))
            return report(output)
        } catch (e: Throwable) {
            output.put("error", e.toString()).put("passed", false)
            return report(output)
        } finally {
            worker.shutdownNow()
        }
    }

    /**
     * Watch the controller's snapshots until the operator closes the session, or the turn
     * times out. Every change is kept; the first interruption's evidence is exported the
     * moment it appears, because the reload that follows opens a fresh session and the
     * controller then reports that one.
     */
    private fun watch(app: ShellApplication, turn: String, output: JSONObject): JSONArray {
        val snapshots = JSONArray()
        var last: String? = null
        var sawRunning = false
        var interruptions = 0
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = AtomicReference<StudyState>()
            runOnMainSync { state.set(app.study.state) }
            val current = state.get()
            val line = describe(current)
            if (line.toString() != last) {
                last = line.toString()
                snapshots.put(line.put("elapsedMs", SystemClock.elapsedRealtime() - startedAt))
                phase(current.status)
            }
            if (current.running) sawRunning = true
            if (sawRunning && !current.running && !current.busy) {
                when (current.closed) {
                    "interrupted" -> if (interruptions == 0) {
                        interruptions += 1
                        output.put("interruptedEvidence", describe(export(app)))
                        phase("Interrupted; reload when you are back")
                        // Wait for the reload rather than counting the same closed state again.
                        while (SystemClock.elapsedRealtime() < deadline) {
                            runOnMainSync { state.set(app.study.state) }
                            if (state.get().running || state.get().busy || state.get().closed != "interrupted") break
                            SystemClock.sleep(POLL_MS)
                        }
                        continue
                    }
                    null, "reloading" -> Unit
                    else -> {
                        output.put("ended", current.closed)
                        return snapshots
                    }
                }
            }
            SystemClock.sleep(POLL_MS)
        }
        output.put("ended", "timeout")
        output.put("timedOut", true)
        return snapshots
    }

    private fun export(app: ShellApplication): StudyEvidence? {
        val latch = CountDownLatch(1)
        val exported = AtomicReference<StudyEvidence?>()
        app.study.evidence {
            exported.set(it)
            latch.countDown()
        }
        check(latch.await(30, TimeUnit.SECONDS)) { "The evidence export did not return" }
        return exported.get()
    }

    private fun phase(text: String) = sendStatus(1, Bundle().apply { putString("livePhase", text) })

    // -- what each turn has to show ------------------------------------------------ //

    /**
     * Judge the turn from the exported evidence alone. The driver re-derives the writes
     * from the collection's own revlog either side, so this is the app's account and that
     * is the collection's; the retained record keeps both.
     */
    private fun judge(turn: String, output: JSONObject): Boolean {
        if (output.has("error") || output.optBoolean("timedOut")) return false
        val evidence = output.optJSONObject("evidence") ?: return false
        val turns = evidence.optJSONArray("turns") ?: return false
        if (turns.length() == 0) return false
        val first = turns.getJSONObject(0)
        val outcomes = evidence.optJSONArray("outcomes") ?: JSONArray()
        val confirmed = (0 until outcomes.length()).count { outcomes.getJSONObject(it).optString("state") == ReviewState.CONFIRMED.specName }
        val journal = evidence.optJSONArray("journal") ?: JSONArray()
        val journalConfirmed = (0 until journal.length()).count { journal.getJSONObject(it).optString("outcomeState") == ReviewState.CONFIRMED.specName }
        val wroteOne = confirmed == 1 && journalConfirmed == 1 && first.optString("outcome") == ReviewState.CONFIRMED.specName &&
            first.optString("confirmationSource") in setOf("spoken", "touch") && !output.optBoolean("stateUnchanged")
        val wroteNothing = outcomes.length() == 0 && journal.length() == 0 && output.optBoolean("stateUnchanged")
        val touches = strings(first.optJSONArray("touchActions"))
        val halts = strings(first.optJSONArray("halts"))
        val path = first.optString("gradingPath")
        return when (turn) {
            "rule-match" -> wroteOne && path == GradingRecord.RULE
            "ai-labelled" -> wroteOne && path in setOf(GradingRecord.AI_FREE, GradingRecord.AI_PAID)
            "abstain-self-grade" -> wroteOne && path == GradingRecord.ABSTAIN && !first.isNull("selfGrade")
            "corrected-confirmed" -> wroteOne && (first.optJSONArray("ratingCorrections")?.length() ?: 0) > 0 &&
                first.optInt("rating") == first.getJSONArray("ratingCorrections").let { it.getJSONObject(it.length() - 1).getInt("to") }
            "transcript-edit" -> wroteOne && first.optInt("transcriptEdits") >= 1 &&
                (first.optJSONArray("gradings") ?: JSONArray()).let { gradings ->
                    (0 until gradings.length()).any { gradings.getJSONObject(it).optInt("revision") == first.optInt("transcriptRevision") }
                }
            "skip" -> wroteNothing && "skip" in touches && "skip_requested" in halts
            "pause-resume" -> wroteNothing && "pause" in touches && "resume" in touches && "learner_paused" in halts
            "interruption-reload" -> wroteNothing && output.optJSONObject("interruptedEvidence")?.let { interrupted ->
                interrupted.optString("closed") == "interrupted" &&
                    (interrupted.optJSONArray("turns") ?: JSONArray()).let { its ->
                        (0 until its.length()).any { "app_switch" in strings(its.getJSONObject(it).optJSONArray("halts")) }
                    } &&
                    (interrupted.optJSONArray("outcomes")?.length() ?: 0) == 0
            } == true && evidence.optString("sessionId") != output.optJSONObject("interruptedEvidence")?.optString("sessionId")
            "undo-handoff" -> confirmed == 1 && journalConfirmed == 1 && first.optString("outcome") == ReviewState.CONFIRMED.specName &&
                evidence.optString("closed") == "undo-handoff"
            "route-refused-self-grade" -> wroteOne && path == GradingRecord.UNAVAILABLE && !first.isNull("selfGrade")
            else -> false
        }
    }

    private fun strings(array: JSONArray?): List<String> =
        array?.let { (0 until it.length()).map { i -> it.getString(i) } } ?: emptyList()

    // -- evidence ---------------------------------------------------------------- //

    private fun describe(state: StudyState): JSONObject = JSONObject()
        .put("status", state.status)
        .put("running", state.running)
        .put("busy", state.busy)
        .put("sessionState", state.sessionState ?: JSONObject.NULL)
        .put("answerPhase", state.answerPhase ?: JSONObject.NULL)
        .put("controls", JSONArray(state.controls.map { it.name }))
        .put("cardId", state.cardId ?: JSONObject.NULL)
        .put("transcript", state.transcript ?: JSONObject.NULL)
        .put("transcriptRevision", state.transcriptRevision)
        .put("transcriptKind", state.transcriptKind ?: JSONObject.NULL)
        .put("gradingPath", state.grading?.path ?: JSONObject.NULL)
        .put("gradingInFlight", state.gradingInFlight)
        .put("pendingRating", state.pendingRating ?: JSONObject.NULL)
        .put("ratingSource", state.ratingSource ?: JSONObject.NULL)
        .put("announcedRevision", state.announcedRevision ?: JSONObject.NULL)
        .put("confirmed", state.confirmed)
        .put("outcomeState", state.outcomeState ?: JSONObject.NULL)
        .put("haltKind", state.halt?.kind ?: JSONObject.NULL)
        .put("haltReason", state.halt?.reason ?: JSONObject.NULL)
        .put("closed", state.closed ?: JSONObject.NULL)
        .put("skipped", state.skipped.size)
        .put("journalOutstanding", state.journalOutstanding.size)
        .put("notice", state.notice ?: JSONObject.NULL)

    private fun describe(evidence: StudyEvidence?): Any {
        val e = evidence ?: return JSONObject.NULL
        return JSONObject()
            .put("sessionId", e.sessionId)
            .put("closed", e.closed ?: JSONObject.NULL)
            .put("sessionActions", JSONArray(e.sessionActions))
            .put("events", JSONArray(e.events.map { JSONObject().put("step", it.step).put("detail", it.detail) }))
            .put(
                "outcomes",
                JSONArray(
                    e.outcomes.map {
                        JSONObject().put("state", it.state.specName).put("reason", it.reason)
                            .put("acknowledgement", it.acknowledgement ?: JSONObject.NULL)
                            .put("writeAttempted", it.writeAttempted)
                    },
                ),
            )
            .put("journal", JSONArray(e.journal.map(::describe)))
            .put("turns", JSONArray(e.turns.map(::describe)))
    }

    private fun describe(turn: TurnEvidence): JSONObject = JSONObject()
        .put("turn", turn.turn)
        .put("cardId", turn.cardId ?: JSONObject.NULL)
        .put("transcriptRevision", turn.transcriptRevision)
        .put(
            "recognition",
            JSONArray(
                turn.recognition.map {
                    JSONObject().put("attempt", it.attempt).put("revision", it.revision).put("status", it.status)
                        .put("text", it.text).put("confidence", it.confidence)
                        .put("failure", it.failure ?: JSONObject.NULL)
                        .put("rawScore", it.rawScore ?: JSONObject.NULL)
                },
            ),
        )
        .put("retries", turn.retries)
        .put("transcriptEdits", turn.transcriptEdits)
        .put(
            "gradings",
            JSONArray(
                turn.gradings.map {
                    JSONObject().put("revision", it.revision).put("source", it.source)
                        .put("route", it.route ?: JSONObject.NULL).put("label", it.label ?: JSONObject.NULL)
                        .put("proposedRating", it.proposedRating ?: JSONObject.NULL)
                        .put("failure", it.failure ?: JSONObject.NULL).put("path", it.path)
                },
            ),
        )
        .put("gradingPath", turn.gradingPath ?: JSONObject.NULL)
        .put("selfGrade", turn.selfGrade ?: JSONObject.NULL)
        .put("ratingCorrections", JSONArray(turn.ratingCorrections.map { JSONObject().put("from", it.from).put("to", it.to) }))
        .put("confirmationSource", turn.confirmationSource ?: JSONObject.NULL)
        .put("outcome", turn.outcome ?: JSONObject.NULL)
        .put("rating", turn.rating ?: JSONObject.NULL)
        .put("touchActions", JSONArray(turn.touchActions))
        .put("spokenCommands", JSONArray(turn.spokenCommands))
        .put("halts", JSONArray(turn.halts))

    private fun describe(entry: JournalEntry): JSONObject = JSONObject()
        .put("entryId", entry.entryId)
        .put("sessionId", entry.sessionId)
        .put("cardId", entry.identity.cardId)
        .put("rating", entry.rating)
        .put("transcriptRevision", entry.transcriptRevision)
        .put("transcript", entry.transcript)
        .put("phase", entry.phase.specName)
        .put("outcomeState", entry.outcomeState?.specName ?: JSONObject.NULL)
        .put("outcomeReason", entry.outcomeReason)
        .put("acknowledgement", entry.acknowledgement ?: JSONObject.NULL)
        .put("noticeOutstanding", entry.noticeOutstanding)

    private fun describe(state: CardState): JSONObject = JSONObject()
        .put("reps", state.reps)
        .put("cardType", state.cardType)
        .put("queue", state.queue)
        .put("due", state.due)
        .put("intervalDays", state.intervalDays)
        .put("lastReviewTimeSecs", state.lastReviewTimeSecs)

    private fun entries(journal: ReviewJournal): JSONArray = JSONArray(journal.entries().map(::describe))

    /** Every attempt is written out, whether or not it came back the way it should. */
    private fun report(output: JSONObject) {
        output.put("completed", !output.has("error"))
        File(targetContext.filesDir, "av026-result.json").writeText(output.toString(2))
        finish(
            if (output.optBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("av026", output.toString()) },
        )
    }

    private companion object {
        val TURNS = listOf(
            "rule-match", "ai-labelled", "abstain-self-grade", "corrected-confirmed", "transcript-edit",
            "skip", "pause-resume", "interruption-reload", "undo-handoff", "route-refused-self-grade",
        )
        const val POLL_MS = 250L

        /** Long enough for a slow turn with retries; a timeout is recorded, never passed. */
        const val TIMEOUT_MS = 20 * 60 * 1_000L
    }
}
