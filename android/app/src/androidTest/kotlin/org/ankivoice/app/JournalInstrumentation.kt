package org.ankivoice.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import java.io.File
import java.util.concurrent.Executors
import org.ankivoice.ankidroid.AndroidAccessPlatform
import org.ankivoice.ankidroid.AnkiDroidCardProvider
import org.ankivoice.ankidroid.AnkiDroidReviewTransport
import org.ankivoice.core.contracts.*
import org.ankivoice.core.journal.JournalEntry
import org.ankivoice.core.journal.JournalReconciler
import org.ankivoice.core.journal.JournaledReviewWriter
import org.ankivoice.core.journal.Reconciliation
import org.ankivoice.core.journal.ReviewJournal
import org.json.JSONArray
import org.json.JSONObject

/**
 * AV-018's live check: strand a journalled review by killing the process mid-submission,
 * then reconcile it in a new process against the real collection.
 *
 * Explicit and synthetic-only, like AV-024's entry point. It refuses a deck whose name
 * does not start with `AV002`, and nothing in the app's UI reaches it.
 *
 * The two strand modes stop at the two windows the card is built around. `before` writes
 * the journal entry and then blocks **inside the transport, before the single dispatch**;
 * `after` lets the dispatch happen and blocks **before the settle**. In both, the host
 * force-stops the process while it is blocked, which is the process loss this card has to
 * survive. Neither mode ever returns normally.
 */
class JournalInstrumentation : Instrumentation() {
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
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
            check(args.getString("confirm") == "AV018_SYNTHETIC_ONLY")
            val platform = AndroidAccessPlatform(targetContext)
            val deckId = args.getString("deck")!!.toLong()
            val deck = if (platform.databasePermissionGranted() && platform.apiEnabled() != false)
                platform.queryDecks()?.singleOrNull { it.id == deckId } else null
            check(deck != null && deck.name.startsWith("AV002")) { "A disposable AV002 deck is required" }
            val journalFile = File(targetContext.filesDir, JournalModule.FILE)
            output.put("journalPath", journalFile.absolutePath)
            when (val mode = args.getString("mode") ?: "inspect") {
                "clear" -> {
                    // Setup between cases. Deleting a file is not a claim about a review.
                    output.put("deleted", journalFile.delete())
                }
                "inspect" -> output.put("entries", entries(ReviewJournal(FileJournalStore(journalFile))))
                "reconcile" -> {
                    val journal = ReviewJournal(FileJournalStore(journalFile))
                    output.put("entriesBefore", entries(journal))
                    val provider = AnkiDroidCardProvider(platform, deckId, worker, targetContext.mainExecutor)
                    val results = JournalReconciler(journal, provider).reconcile()
                    output.put("reconciliations", JSONArray(results.map(::describe)))
                    output.put("entriesAfter", entries(journal))
                    output.put("outstanding", journal.outstandingNotices().size)
                    output.put("blocked", JournalReconciler(journal, provider).blocked())
                }
                "strand" -> strand(platform, deckId, journalFile, worker, output)
                else -> error("unknown mode $mode")
            }
            output.put("passed", true)
        } catch (e: Throwable) {
            output.put("passed", false).put("error", e.toString())
        } finally {
            worker.shutdown()
        }
        File(targetContext.filesDir, "av018-result.json").writeText(output.toString(2))
        finish(
            if (output.optBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("result", output.toString()) },
        )
    }

    /** Journal one real intent and block where the host can kill the process. */
    private fun strand(
        platform: AndroidAccessPlatform,
        deckId: Long,
        journalFile: File,
        worker: java.util.concurrent.ExecutorService,
        output: JSONObject,
    ) {
        val afterDispatch = args.getString("window") == "after"
        val provider = AnkiDroidCardProvider(platform, deckId, worker, targetContext.mainExecutor)
        val capabilities = provider.capabilities() as Capabilities
        val card = provider.nextCard() as ScheduledCard
        val expected = args.getString("card")!!.toLong()
        check(card.identity.cardId == expected) { "Wrong fixture scheduled: ${card.identity.cardId}" }
        output.put("card", card.identity.cardId).put("preState", state(card.state))
        val rating = args.getString("rating")!!.toInt()
        val token = OperationToken("av018-live", 1, 1)
        val intent = ReviewIntent(card, rating, 4_200, token, transcriptRevision = 2)
        check(intent.confirm(RatingConfirmation(token, card.identity, rating, 2, ConfirmationSource.TOUCH)))
        val real = AnkiDroidReviewTransport(platform)
        val transport = object : ReviewTransport {
            override fun answerCard(identity: CardIdentity, rating: Int, elapsedMs: Long): RawAcknowledgement {
                if (!afterDispatch) block("before-dispatch")
                val acknowledgement = real.answerCard(identity, rating, elapsedMs)
                block("after-dispatch")
                return acknowledgement
            }
        }
        val journal = ReviewJournal(FileJournalStore(journalFile))
        val writer = JournaledReviewWriter(
            GuardedReviewWriter(provider, transport, capabilities),
            journal,
            "av018-live",
        ) { "five blocks" }
        writer.commit(intent)
        error("The host did not force-stop the process; the strand window was missed")
    }

    /** Announce the window, then wait to be killed. Returning from here is a failure. */
    private fun block(window: String): Nothing {
        File(targetContext.filesDir, "av018-ready").writeText(window)
        Thread.sleep(180_000)
        error("No force-stop arrived during $window")
    }

    private fun entries(journal: ReviewJournal): JSONArray =
        JSONArray(journal.entries().map(::describe))

    private fun describe(entry: JournalEntry): JSONObject = JSONObject()
        .put("entryId", entry.entryId)
        .put("session", entry.sessionId)
        .put("cardId", entry.identity.cardId)
        .put("noteId", entry.identity.noteId)
        .put("deckId", entry.identity.deckId)
        .put("rating", entry.rating)
        .put("elapsedMs", entry.elapsedMs)
        .put("transcriptRevision", entry.transcriptRevision)
        .put("transcript", entry.transcript)
        .put("preState", state(entry.preState))
        .put("phase", entry.phase.specName)
        .put("outcome", entry.outcomeState?.specName)
        .put("resolution", entry.resolution?.specName)
        .put("resolutionReason", entry.resolutionReason)
        .put("observedState", state(entry.observedState))
        .put("acknowledgedByLearner", entry.acknowledgedByLearner)

    private fun describe(result: Reconciliation): JSONObject = JSONObject()
        .put("entryId", result.entry.entryId)
        .put("cardId", result.entry.identity.cardId)
        .put("rating", result.entry.rating)
        .put("resolution", result.resolution.specName)
        .put("reason", result.reason)
        .put("failure", result.failure?.toString())
        .put("observedState", state(result.observedState))
        .put("notice", result.notice)

    private fun state(value: CardState?): Any = value?.let {
        JSONObject().put("reps", it.reps).put("type", it.cardType).put("queue", it.queue)
            .put("due", it.due).put("interval", it.intervalDays).put("lastReviewTimeSecs", it.lastReviewTimeSecs)
    } ?: JSONObject.NULL
}
