package org.ankivoice.core.fakes

import org.ankivoice.core.journal.JournalStore

/**
 * AV-018's storage port, in memory. The lines outlive the [org.ankivoice.core.journal.ReviewJournal]
 * that wrote them, which is what lets a JVM test simulate process loss: build a journal,
 * write to it, drop it, and build another over the same store.
 *
 * The real store is `:app`'s file, which flushes through to the filesystem. This one
 * cannot lose a line by accident; [truncateLastLine] and [corruptLastLine] are how a test
 * asks for the damage a kill mid-write causes.
 */
class FakeJournalStore(initial: List<String> = emptyList()) : JournalStore {
    val lines: MutableList<String> = initial.toMutableList()

    /** Every append this store has seen, including ones a later rewrite removed. */
    var appends: Int = 0
        private set

    var rewrites: Int = 0
        private set

    override fun readLines(): List<String> = lines.toList()

    override fun append(line: String) {
        appends += 1
        lines.add(line)
    }

    override fun rewrite(lines: List<String>) {
        rewrites += 1
        this.lines.clear()
        this.lines.addAll(lines)
    }

    /** A kill mid-write: the last line reached storage only in part. */
    fun truncateLastLine(keepChars: Int = 12) {
        if (lines.isEmpty()) return
        lines[lines.size - 1] = lines.last().take(keepChars)
    }

    /** A line that is no longer readable at all. */
    fun corruptLastLine(text: String = "{\"record\":\"dispatch\",\"entry\":") {
        if (lines.isEmpty()) return
        lines[lines.size - 1] = text
    }

    /** The bytes a file-backed store would hold for these lines. */
    fun byteSize(): Int = lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
}
