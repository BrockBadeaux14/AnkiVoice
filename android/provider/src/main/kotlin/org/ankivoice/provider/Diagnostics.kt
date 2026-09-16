package org.ankivoice.provider

/**
 * Content-free by default: timings, failure names, request and reservation counts, and
 * the reported cost, which is expected to be $0.
 *
 * No secret, card field, transcript or audio is recorded unless the learner turns
 * [retainContent] on, which is off by default and clearable. Every detail passes through
 * [CredentialPolicy.redact], so a key quoted by a provider message cannot land in the
 * log. `:provider` has no audio API at all, so raw audio is never written to disk.
 *
 * #27 adds per-turn session timings later through this same recorder; this card defines
 * no session turns.
 */
class Diagnostics(
    private val capacity: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Entry(
        val epochMillis: Long,
        val name: String,
        val durationMs: Long? = null,
        val detail: String = "",
    ) {
        override fun toString(): String = buildString {
            append(name)
            durationMs?.let { append(" · ${it}ms") }
            if (detail.isNotEmpty()) append(" · $detail")
        }
    }

    /** Off by default. #29's report needs an explicit opt-in to keep study content. */
    var retainContent: Boolean = false

    private val entries = ArrayDeque<Entry>()
    private val content = ArrayDeque<Entry>()

    @Synchronized
    fun record(name: String, durationMs: Long? = null, detail: String = "") {
        add(entries, Entry(clock(), name, durationMs, CredentialPolicy.redact(detail)))
    }

    /** Study content for #29's report. Dropped unless [retainContent] is on. */
    @Synchronized
    fun recordContent(label: String, text: String) {
        if (!retainContent) return
        add(content, Entry(clock(), label, null, CredentialPolicy.redact(text)))
    }

    @Synchronized
    fun entries(): List<Entry> = entries.toList()

    @Synchronized
    fun retainedContent(): List<Entry> = content.toList()

    @Synchronized
    fun counts(): Map<String, Int> = entries.groupingBy { it.name }.eachCount().toSortedMap()

    @Synchronized
    fun clear() {
        entries.clear()
        content.clear()
    }

    /** Clears study content only, leaving the content-free record in place. */
    @Synchronized
    fun clearRetainedContent() {
        content.clear()
    }

    private fun add(target: ArrayDeque<Entry>, entry: Entry) {
        target.addLast(entry)
        while (target.size > capacity) target.removeFirst()
    }
}
