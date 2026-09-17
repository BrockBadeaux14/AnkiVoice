package org.ankivoice.app

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import org.ankivoice.core.contracts.CardProvider
import org.ankivoice.core.journal.JournalEntry
import org.ankivoice.core.journal.JournalReconciler
import org.ankivoice.core.journal.JournalResolution
import org.ankivoice.core.journal.JournalStore
import org.ankivoice.core.journal.Reconciliation
import org.ankivoice.core.journal.ReviewJournal

/**
 * AV-018's durable store: the `:app` half of AV-022's *"`:core` port with `:app` storage"*.
 *
 * Append-only and flushed through to the filesystem before returning, in the manner of
 * AV-020's quota ledger, because an entry that is not on the platter when the process
 * dies is an entry that never existed. Pruning is the only whole-file operation and it
 * goes through a temporary file, so an interrupted prune leaves the previous journal
 * intact rather than a half-written one.
 *
 * The file lives in app-private storage, separate from Anki's collection, and is excluded
 * from backup and device transfer by
 * [data_extraction_rules.xml][org.ankivoice.app.JournalModule.FILE] on top of
 * `allowBackup="false"`. `JournalBackupRulesTest` checks that coverage rather than
 * assuming the file inherits it.
 */
internal class FileJournalStore(private val file: File) : JournalStore {
    override fun readLines(): List<String> =
        if (file.isFile) file.readLines() else emptyList()

    override fun append(line: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { output ->
            output.write((line + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
            // The entry must outlive process death, not merely a return from write().
            output.fd.sync()
        }
    }

    override fun rewrite(lines: List<String>) {
        file.parentFile?.mkdirs()
        val staged = File(file.parentFile, "${file.name}.pruning")
        FileOutputStream(staged).use { output ->
            output.write(lines.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        check(staged.renameTo(file)) { "The journal could not be replaced after pruning" }
    }
}

/** Where the journal lives, for the composition root and for the backup-rules check. */
internal object JournalModule {
    /** App-private, and excluded from backup and device transfer with AV-020's two files. */
    const val FILE: String = "av018-session-journal.jsonl"

    fun journal(filesDir: File): ReviewJournal = ReviewJournal(FileJournalStore(File(filesDir, FILE)))
}

/** What startup reconciliation produced, ready for a surface to show. */
internal data class JournalReport(
    val reconciliations: List<Reconciliation> = emptyList(),
    val outstanding: List<JournalEntry> = emptyList(),
    val pruned: Int = 0,
) {
    /** True while an unknown outcome has not been shown to the learner. No card may be offered. */
    val blocking: Boolean get() = outstanding.isNotEmpty()

    /** Only unknown outcomes are announced; a provable non-write needs no warning banner. */
    val notices: List<String>
        get() = reconciliations.filter { it.resolution == JournalResolution.OUTCOME_UNKNOWN }.map { it.notice }
}

/**
 * Journal work, off the main thread.
 *
 * Every call runs on [worker] and only immutable results cross back to [delivery], the
 * same split AV-023's `AnkiDroidAccess` uses for the collection. Nothing here reads or
 * writes the journal on the main thread, and nothing here writes a review.
 */
internal class JournalAccess(
    private val journal: ReviewJournal,
    private val worker: Executor,
    private val delivery: Executor,
) {
    /**
     * Reconcile whatever process loss left behind, then prune, then report.
     *
     * Runs once per process start and **before the first card is offered**. With no card
     * provider — no deck chosen, or AnkiDroid unavailable — the journalled card cannot be
     * re-read, so nothing is resolved and the unsettled entries simply wait for a start
     * that can read them.
     */
    fun reconcile(sessionId: String, cards: () -> CardProvider?, callback: (JournalReport) -> Unit) {
        worker.execute {
            val report = try {
                val provider = cards()
                val results = if (provider == null) emptyList() else JournalReconciler(journal, provider).reconcile()
                val pruned = journal.prune(sessionId)
                JournalReport(results, journal.outstandingNotices(), pruned)
            } catch (e: RuntimeException) {
                // A journal that cannot be read is not a reason to lose a turn's evidence,
                // and it is never a reason to claim a write. Report nothing resolved.
                JournalReport(emptyList(), emptyList(), 0).also { reportFailure(e) }
            }
            delivery.execute { callback(report) }
        }
    }

    /** The learner has seen the notice for [entryId]. Never inferred from a restart. */
    fun acknowledge(entryId: Long, callback: (JournalReport) -> Unit) {
        worker.execute {
            journal.acknowledge(entryId)
            val report = JournalReport(emptyList(), journal.outstandingNotices(), 0)
            delivery.execute { callback(report) }
        }
    }

    /** Content-free by construction: a failure name, never a transcript. */
    var onFailure: ((String) -> Unit)? = null

    private fun reportFailure(e: RuntimeException) {
        onFailure?.invoke(e.javaClass.simpleName)
    }
}

/**
 * AV-045: the one AV-018 gate every surface that offers a card goes through.
 *
 * Reconciliation runs once per process, whichever surface asks first, and every later
 * [open] answers from what it found. An unacknowledged unknown outcome keeps [open]
 * blocking for **every** surface until the learner acknowledges it on any of them, so the
 * study session cannot go around a notice the readiness preview is still showing.
 *
 * Called and answered on [JournalAccess]'s delivery thread — the main thread in the app.
 */
internal class ReconciliationGate(
    private val journal: JournalAccess,
    private val sessionId: String,
) {
    /** Null until reconciliation has run in this process. */
    private var report: JournalReport? = null

    /** Callers that asked while the one reconciliation pass was still running. */
    private var waiting: MutableList<(JournalReport) -> Unit>? = null

    private val listeners = mutableListOf<(JournalReport) -> Unit>()

    val reconciled: Boolean get() = report != null

    /** Told whenever an acknowledgement changes what is outstanding, on whichever surface. */
    fun listen(listener: (JournalReport) -> Unit) {
        listeners += listener
    }

    /**
     * Reconcile if this process has not, then answer. [JournalReport.blocking] means no
     * card may be offered yet. [cards] is read only by the first, reconciling call.
     */
    fun open(cards: () -> CardProvider?, callback: (JournalReport) -> Unit) {
        report?.let { return callback(it) }
        waiting?.let {
            it += callback
            return
        }
        waiting = mutableListOf(callback)
        journal.reconcile(sessionId, cards) { reconciled ->
            report = reconciled
            val callers = waiting.orEmpty()
            waiting = null
            callers.forEach { it(reconciled) }
        }
    }

    /** The learner has seen the notice for [entryId]. Never inferred from a restart. */
    fun acknowledge(entryId: Long, callback: (JournalReport) -> Unit = {}) {
        journal.acknowledge(entryId) { acknowledged ->
            val remaining = acknowledged.outstanding
            val current = report ?: JournalReport()
            val next = current.copy(
                // Once nothing is outstanding there is nothing left to announce.
                reconciliations = if (remaining.isEmpty()) emptyList() else current.reconciliations,
                outstanding = remaining,
            )
            if (report != null) report = next
            callback(next)
            listeners.forEach { it(next) }
        }
    }
}
