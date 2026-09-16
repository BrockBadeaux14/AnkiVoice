package org.ankivoice.app

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.ReviewOutcome
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.fakes.FakeCardProvider
import org.ankivoice.core.fakes.demoCollection
import org.ankivoice.core.journal.JournalPhase
import org.ankivoice.core.journal.JournalRequest
import org.ankivoice.core.journal.JournalResolution
import org.ankivoice.core.journal.ReviewJournal
import org.ankivoice.provider.Diagnostics
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * AV-018's `:app` half: the durable file, its backup exclusion, and the rule that the
 * learner's transcript never reaches AV-020's diagnostics.
 */
class SessionJournalTest {
    private val collection = demoCollection()
    private val provider = FakeCardProvider(collection)
    private val card = provider.nextCard() as ScheduledCard
    private val token = OperationToken("av018-app", 1, 1)

    private fun tempFile(): File =
        File(Files.createTempDirectory("av018").toFile(), JournalModule.FILE)

    private fun journal(file: File) = ReviewJournal(FileJournalStore(file))

    private fun record(target: ReviewJournal, transcript: String, session: String = "av018-app") =
        target.record(JournalRequest(session, token, card.identity, 3, 4_200, 2, transcript, card.state))

    // -- the durable file ----------------------------------------------------- //

    @Test fun `an appended entry is on disk before the call returns`() {
        val file = tempFile()
        record(journal(file), "five blocks")
        assertTrue(file.isFile)
        val line = file.readLines().single()
        assertTrue(line.contains("\"transcript\":\"five blocks\""))
        // A new journal over the same path is what the next process sees.
        assertEquals(JournalPhase.DISPATCHING, journal(file).unsettled().single().phase)
    }

    @Test fun `the file is created under the app's own files directory`() {
        val filesDir = Files.createTempDirectory("av018-files").toFile()
        record(JournalModule.journal(filesDir), "five blocks")
        assertEquals(listOf(JournalModule.FILE), filesDir.list()?.toList())
    }

    @Test fun `a truncated final line does not cost the entries before it`() {
        val file = tempFile()
        val target = journal(file)
        val first = record(target, "five blocks")
        target.settle(first.entryId, ReviewOutcome(ReviewState.CONFIRMED, "consistent one-review transition"))
        record(target, "green blue red")
        val lines = file.readLines()
        file.writeText(lines.dropLast(1).joinToString("\n") + "\n" + lines.last().take(20))
        val reopened = journal(file).entries()
        assertEquals(listOf(JournalPhase.SETTLED, JournalPhase.UNREADABLE), reopened.map { it.phase })
    }

    @Test fun `pruning replaces the file through a temporary and leaves nothing behind`() {
        val file = tempFile()
        val target = ReviewJournal(
            FileJournalStore(file),
            retention = org.ankivoice.core.journal.JournalRetention(maxSettledEntries = 1),
        )
        repeat(3) {
            val entry = record(target, "answer $it", session = "older")
            target.settle(entry.entryId, ReviewOutcome(ReviewState.CONFIRMED, "consistent one-review transition"))
        }
        assertEquals(2, target.prune("current"))
        assertEquals(listOf(JournalModule.FILE), file.parentFile.list()?.toList())
        assertEquals(1, journal(file).entries().size)
    }

    // -- backup and device transfer ------------------------------------------- //

    @Test fun `the journal file is excluded from backup and device transfer by name`() {
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = rules.substringAfter("<$section>").substringBefore("</$section>")
            assertTrue(
                body.contains("path=\"${JournalModule.FILE}\""),
                "the journal is not excluded from $section",
            )
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    // -- AV-020's diagnostics stay content-free -------------------------------- //

    @Test fun `no journal surface puts transcript text into a diagnostics bundle`() {
        val secret = "the learner's own spoken words"
        val file = tempFile()
        val target = journal(file)
        val entry = record(target, secret)
        target.settle(entry.entryId, ReviewOutcome(ReviewState.OUTCOME_UNKNOWN, "Unverified write"))

        val diagnostics = Diagnostics()
        // Everything this card is allowed to hand the recorder, recorded.
        val reloaded = journal(file).entries().single()
        diagnostics.record("journalEntry", detail = reloaded.summary())
        diagnostics.record("journalPhase", detail = reloaded.phase.specName)
        diagnostics.record("journalPruned", detail = "0")
        val reconciliation = org.ankivoice.core.journal.Reconciliation(
            reloaded, JournalResolution.OUTCOME_UNKNOWN, "unverified",
        )
        diagnostics.record("journalReconciled", detail = reconciliation.resolution.specName)

        val bundle = diagnostics.entries().joinToString("\n") { it.toString() } +
            diagnostics.counts().toString() + diagnostics.retainedContent().toString()
        assertFalse(bundle.contains(secret), "transcript text reached the diagnostics bundle")
        assertTrue(bundle.contains("card ${card.identity.cardId}"), "the content-free summary is still useful")
        assertTrue(diagnostics.retainedContent().isEmpty(), "content retention is off by default")
        // The transcript is on disk, and only there.
        assertTrue(file.readText().contains(secret))
        assertEquals(secret, reloaded.transcript)
    }

    // -- off the main thread, and before the first card ------------------------ //

    @Test fun `journal work runs on the worker and only results cross to delivery`() {
        val file = tempFile()
        record(journal(file), "five blocks")
        val worker = RecordingExecutor()
        val delivery = RecordingExecutor()
        val access = JournalAccess(journal(file), worker, delivery)
        var report: JournalReport? = null
        access.reconcile("current", { provider }) { report = it }
        assertEquals(1, worker.runs, "reconciliation ran on the journal worker")
        assertEquals(1, delivery.runs, "only the report crossed back")
        assertEquals(JournalResolution.FAILED, report?.reconciliations?.single()?.resolution)
        assertFalse(report!!.blocking, "a provable non-write does not stop the session")
    }

    @Test fun `an unknown outcome blocks until it is acknowledged`() {
        val file = tempFile()
        record(journal(file), "five blocks")
        collection.applyReview(card.identity.cardId, 3, 4_200)
        val access = JournalAccess(journal(file), Executor { it.run() }, Executor { it.run() })
        var report: JournalReport? = null
        access.reconcile("current", { provider }) { report = it }
        val first = checkNotNull(report)
        assertTrue(first.blocking)
        assertEquals(1, first.notices.size)
        assertTrue(first.notices.single().contains("cannot prove it saved rating 3"))
        assertEquals(1, collection.reviews.size, "reconciliation adds no second review")

        access.acknowledge(first.outstanding.single().entryId) { report = it }
        assertFalse(checkNotNull(report).blocking)
        // And it stays acknowledged for the next process.
        assertTrue(journal(file).outstandingNotices().isEmpty())
    }

    @Test fun `reconciliation with no card provider resolves nothing and keeps the entry`() {
        val file = tempFile()
        record(journal(file), "five blocks")
        val access = JournalAccess(journal(file), Executor { it.run() }, Executor { it.run() })
        var report: JournalReport? = null
        access.reconcile("current", { null }) { report = it }
        assertTrue(checkNotNull(report).reconciliations.isEmpty())
        assertEquals(1, journal(file).unsettled().size, "the entry waits for a start that can read it")
    }

    private class RecordingExecutor : Executor {
        var runs = 0
            private set

        override fun execute(command: Runnable) {
            runs += 1
            command.run()
        }
    }
}
