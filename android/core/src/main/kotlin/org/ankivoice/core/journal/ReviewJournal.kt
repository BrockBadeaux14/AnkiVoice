package org.ankivoice.core.journal

import org.ankivoice.core.contracts.CardIdentity
import org.ankivoice.core.contracts.CardState
import org.ankivoice.core.contracts.ConfirmationSource
import org.ankivoice.core.contracts.MonotonicClock
import org.ankivoice.core.contracts.ReviewOutcome
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.SystemMonotonicClock
import org.ankivoice.core.contracts.VOICEQA_MODEL

/**
 * AV-018's session journal: one durable record per review intent, written before the
 * write is dispatched and settled only from the writer's own evidence.
 *
 * Storage is append-only, in the manner of AV-020's quota ledger, because an append that
 * has been flushed is the only thing a killed process leaves behind reliably. An entry is
 * the fold of the records written for it: one `dispatch`, then at most one `settle`, at
 * most one `reconcile` and at most one `acknowledge`. A second settle for an entry that
 * already has one is ignored rather than applied, so a delayed or duplicated callback can
 * never overwrite the evidence the writer actually returned.
 *
 * Nothing here reads the collection, decides a rating, retries a write or upgrades an
 * unknown outcome. [JournalReconciler] owns the one policy that may resolve an unsettled
 * entry, and even it may only ever resolve to `failed` or `outcome-unknown`.
 *
 * **Threading.** This class is not a concurrency mechanism. `:app` confines it to the I/O
 * executor, which is also where its store's flushes belong; the main thread never touches
 * it. Methods are synchronized so a single misuse fails predictably rather than tearing
 * the fold.
 */
class ReviewJournal(
    private val store: JournalStore,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val wallClock: () -> Long = System::currentTimeMillis,
    val retention: JournalRetention = JournalRetention.SELECTED,
) {
    private val lines = ArrayList<String>()

    init {
        lines.addAll(store.readLines())
    }

    /** Every entry the journal holds, oldest first, including unreadable lines. */
    @Synchronized
    fun entries(): List<JournalEntry> = fold(lines)

    /** Entries persisted before dispatch that no settle or reconcile record resolved. */
    @Synchronized
    fun unsettled(): List<JournalEntry> = entries().filter { it.unsettled }

    /**
     * Unknown outcomes the learner has not acknowledged, from this run or any earlier one.
     * A restart does not erase the obligation to show one.
     */
    @Synchronized
    fun outstandingNotices(): List<JournalEntry> = entries().filter { it.noticeOutstanding }

    /**
     * Persist one intent and flush it, **before** the writer is called.
     *
     * The returned entry is already durable: a crash on the next instruction leaves a
     * readable unsettled entry, which is exactly the state [JournalReconciler] resolves.
     */
    @Synchronized
    fun record(request: JournalRequest): JournalEntry {
        val id = (fold(lines).maxOfOrNull { it.entryId } ?: 0L).coerceAtLeast(0L) + 1
        val capped = request.transcript.length > retention.maxTranscriptChars
        val fields = LinkedHashMap<String, Any?>()
        fields["record"] = RecordKind.DISPATCH
        fields["entry"] = id
        fields["session"] = request.sessionId
        fields["turn"] = request.token?.turn ?: 0
        fields["attempt"] = request.token?.sequence ?: 0
        fields["tokenSession"] = request.token?.sessionId ?: request.sessionId
        putIdentity(fields, request.identity)
        fields["rating"] = request.rating
        fields["elapsedMs"] = request.elapsedMs
        fields["revision"] = request.transcriptRevision
        fields["transcript"] = if (capped) request.transcript.take(retention.maxTranscriptChars) else request.transcript
        fields["transcriptTruncated"] = capped
        // AV-047: what authorized the write, recorded before it is handed over, so an
        // automatic commit is readable back as one even from a process that then died.
        fields["confirmation"] = request.confirmationSource?.specName
        putState(fields, "pre", request.preState)
        fields["monotonicMs"] = clock.nowMs()
        fields["wallMs"] = wallClock()
        appendRecord(fields)
        return entries().first { it.entryId == id }
    }

    /**
     * Record what the writer returned, and only that.
     *
     * No second read, no re-derivation of confirmation and no upgrade of an unknown
     * outcome. An unknown [entryId], an already settled entry or an unreadable one is
     * ignored: a stale callback may not invent or overwrite evidence.
     */
    @Synchronized
    fun settle(entryId: Long, outcome: ReviewOutcome): JournalEntry? {
        val entry = entries().firstOrNull { it.entryId == entryId } ?: return null
        if (entry.phase != JournalPhase.DISPATCHING) return entry
        val fields = LinkedHashMap<String, Any?>()
        fields["record"] = RecordKind.SETTLE
        fields["entry"] = entryId
        fields["outcome"] = outcome.state.specName
        fields["reason"] = outcome.reason
        fields["ack"] = outcome.acknowledgement
        fields["writeAttempted"] = outcome.writeAttempted
        outcome.postState?.let { putState(fields, "post", it) }
        fields["monotonicMs"] = clock.nowMs()
        fields["wallMs"] = wallClock()
        appendRecord(fields)
        return entries().first { it.entryId == entryId }
    }

    /**
     * Record a startup reconciliation. Called only by [JournalReconciler], which owns the
     * policy; this method stores the decision and nothing more.
     */
    @Synchronized
    internal fun resolve(reconciliation: Reconciliation): JournalEntry? {
        val entryId = reconciliation.entry.entryId
        val entry = entries().firstOrNull { it.entryId == entryId } ?: return null
        if (!entry.unsettled) return entry
        val fields = LinkedHashMap<String, Any?>()
        fields["record"] = RecordKind.RECONCILE
        fields["entry"] = entryId
        fields["resolution"] = reconciliation.resolution.specName
        fields["reason"] = reconciliation.reason
        reconciliation.observedState?.let { putState(fields, "observed", it) }
        fields["monotonicMs"] = clock.nowMs()
        fields["wallMs"] = wallClock()
        appendRecord(fields)
        return entries().first { it.entryId == entryId }
    }

    /** The learner has been shown an unknown outcome. Never inferred from a restart. */
    @Synchronized
    fun acknowledge(entryId: Long): JournalEntry? {
        val entry = entries().firstOrNull { it.entryId == entryId } ?: return null
        if (entry.acknowledgedByLearner) return entry
        appendRecord(
            linkedMapOf(
                "record" to RecordKind.ACKNOWLEDGE,
                "entry" to entryId,
                "wallMs" to wallClock(),
            ),
        )
        return entries().first { it.entryId == entryId }
    }

    /**
     * Apply the retention bound, oldest first, and report how many entries were dropped.
     *
     * Kept regardless of age or count: everything from [currentSessionId], every
     * unsettled entry, every unreadable line and every unknown outcome the learner has
     * not acknowledged. Pruning one of those would throw away the evidence the journal
     * exists for, so the bound applies only to resolved history.
     */
    @Synchronized
    fun prune(currentSessionId: String? = null): Int {
        val all = entries()
        val now = wallClock()
        val prunable = all.filter {
            it.phase != JournalPhase.UNREADABLE && !it.unsettled && !it.noticeOutstanding &&
                it.sessionId != currentSessionId
        }
        val recentEnough = prunable.filter { now - it.wallClockMs <= retention.maxAgeMs }
        val keep = recentEnough.takeLast(retention.maxSettledEntries).map { it.entryId }.toSet()
        val dropped = prunable.filterNot { it.entryId in keep }.map { it.entryId }.toSet()
        if (dropped.isEmpty()) return 0
        val remaining = lines.filter { line ->
            val fields = JournalLine.read(line) ?: return@filter true
            (fields["entry"] as? Long) !in dropped
        }
        store.rewrite(remaining)
        lines.clear()
        lines.addAll(remaining)
        return dropped.size
    }

    private fun appendRecord(fields: Map<String, Any?>) {
        val line = JournalLine.write(fields)
        store.append(line)
        lines.add(line)
    }

    private fun putIdentity(fields: MutableMap<String, Any?>, identity: CardIdentity) {
        fields["cardId"] = identity.cardId
        fields["noteId"] = identity.noteId
        fields["deckId"] = identity.deckId
        fields["ordinal"] = identity.ordinal
        fields["model"] = identity.model
    }

    private fun putState(fields: MutableMap<String, Any?>, prefix: String, state: CardState) {
        fields["${prefix}Reps"] = state.reps
        fields["${prefix}Type"] = state.cardType
        fields["${prefix}Queue"] = state.queue
        fields["${prefix}Due"] = state.due
        fields["${prefix}Interval"] = state.intervalDays
        fields["${prefix}LastReview"] = state.lastReviewTimeSecs
    }

    private fun fold(source: List<String>): List<JournalEntry> {
        val byId = LinkedHashMap<Long, JournalEntry>()
        var unreadable = 0L
        for (line in source) {
            if (line.isBlank()) continue
            val fields = JournalLine.read(line)
            val kind = fields?.get("record") as? String
            val entryId = fields?.get("entry") as? Long
            if (fields == null || kind == null || entryId == null) {
                // Never discarded and never assumed settled: an unreadable line becomes an
                // entry of its own, which reconciliation can only resolve to outcome-unknown.
                unreadable -= 1
                byId[unreadable] = unreadableEntry(unreadable)
                continue
            }
            when (kind) {
                RecordKind.DISPATCH -> dispatched(entryId, fields)?.let { byId[entryId] = it }
                RecordKind.SETTLE -> byId[entryId]?.let { byId[entryId] = settled(it, fields) }
                RecordKind.RECONCILE -> byId[entryId]?.let { byId[entryId] = reconciled(it, fields) }
                RecordKind.ACKNOWLEDGE -> byId[entryId]?.let { byId[entryId] = it.copy(acknowledgedByLearner = true) }
                else -> {
                    unreadable -= 1
                    byId[unreadable] = unreadableEntry(unreadable)
                }
            }
        }
        return byId.values.toList()
    }

    private fun unreadableEntry(id: Long): JournalEntry = JournalEntry(
        entryId = id,
        sessionId = "",
        turn = 0,
        attempt = 0,
        identity = CardIdentity(0, 0, 0, 0, VOICEQA_MODEL),
        rating = 0,
        elapsedMs = 0,
        transcriptRevision = 0,
        transcript = "",
        transcriptTruncated = false,
        confirmationSource = null,
        preState = CardState(reps = 0, cardType = 0, queue = 0, due = 0, intervalDays = 0),
        monotonicMs = 0,
        wallClockMs = 0,
        phase = JournalPhase.UNREADABLE,
    )

    private fun dispatched(entryId: Long, fields: Map<String, Any?>): JournalEntry? {
        val identity = readIdentity(fields) ?: return null
        val preState = readState(fields, "pre") ?: return null
        return JournalEntry(
            entryId = entryId,
            sessionId = fields["session"] as? String ?: return null,
            turn = (fields["turn"] as? Long)?.toInt() ?: 0,
            attempt = (fields["attempt"] as? Long)?.toInt() ?: 0,
            identity = identity,
            rating = (fields["rating"] as? Long)?.toInt() ?: return null,
            elapsedMs = fields["elapsedMs"] as? Long ?: return null,
            transcriptRevision = (fields["revision"] as? Long)?.toInt() ?: 0,
            transcript = fields["transcript"] as? String ?: "",
            transcriptTruncated = fields["transcriptTruncated"] as? Boolean ?: false,
            // A line written before AV-047 has no such field, and says so by staying null
            // rather than being read as a learner confirmation it never recorded.
            confirmationSource = (fields["confirmation"] as? String)
                ?.let { name -> ConfirmationSource.entries.firstOrNull { it.specName == name } },
            preState = preState,
            monotonicMs = fields["monotonicMs"] as? Long ?: 0,
            wallClockMs = fields["wallMs"] as? Long ?: 0,
            phase = JournalPhase.DISPATCHING,
        )
    }

    private fun settled(entry: JournalEntry, fields: Map<String, Any?>): JournalEntry {
        // First settle wins. A duplicate or delayed callback changes nothing.
        if (entry.phase != JournalPhase.DISPATCHING) return entry
        val state = ReviewState.entries.firstOrNull { it.specName == fields["outcome"] } ?: return entry
        return entry.copy(
            phase = JournalPhase.SETTLED,
            outcomeState = state,
            outcomeReason = fields["reason"] as? String ?: "",
            acknowledgement = (fields["ack"] as? Long)?.toInt(),
            postState = readState(fields, "post"),
            settledMonotonicMs = fields["monotonicMs"] as? Long,
            settledWallClockMs = fields["wallMs"] as? Long,
        )
    }

    private fun reconciled(entry: JournalEntry, fields: Map<String, Any?>): JournalEntry {
        if (!entry.unsettled) return entry
        val resolution = JournalResolution.entries.firstOrNull { it.specName == fields["resolution"] } ?: return entry
        return entry.copy(
            phase = JournalPhase.RECONCILED,
            resolution = resolution,
            resolutionReason = fields["reason"] as? String ?: "",
            observedState = readState(fields, "observed"),
            settledMonotonicMs = fields["monotonicMs"] as? Long,
            settledWallClockMs = fields["wallMs"] as? Long,
        )
    }

    private fun readIdentity(fields: Map<String, Any?>): CardIdentity? = CardIdentity(
        cardId = fields["cardId"] as? Long ?: return null,
        noteId = fields["noteId"] as? Long ?: return null,
        deckId = fields["deckId"] as? Long ?: return null,
        ordinal = (fields["ordinal"] as? Long)?.toInt() ?: return null,
        model = fields["model"] as? String ?: return null,
    )

    private fun readState(fields: Map<String, Any?>, prefix: String): CardState? = CardState(
        reps = (fields["${prefix}Reps"] as? Long)?.toInt() ?: return null,
        cardType = (fields["${prefix}Type"] as? Long)?.toInt() ?: return null,
        queue = (fields["${prefix}Queue"] as? Long)?.toInt() ?: return null,
        due = fields["${prefix}Due"] as? Long ?: return null,
        intervalDays = (fields["${prefix}Interval"] as? Long)?.toInt() ?: return null,
        lastReviewTimeSecs = fields["${prefix}LastReview"] as? Long,
    )
}
