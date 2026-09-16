package org.ankivoice.core.journal

/**
 * Durable storage for the journal, with no filesystem type in sight.
 *
 * AV-022 puts the port here and the file in `:app`, so every rule above can be driven by
 * a JVM test against a fake. The contract an implementation must honour:
 *
 * - [append] returns only once the line is durable. A process killed immediately
 *   afterwards must still see it in [readLines]. `:app` flushes through to the
 *   filesystem; the fake simply cannot lose a line.
 * - [readLines] returns every stored line in write order, including one a kill truncated
 *   mid-write. Nothing may drop a line it cannot parse; that judgement belongs to
 *   [ReviewJournal], which turns it into an [JournalPhase.UNREADABLE] entry.
 * - [rewrite] replaces the whole file at once. It runs only during pruning, never during
 *   a turn, and must not leave a partial file behind if it fails.
 */
interface JournalStore {
    fun readLines(): List<String>

    /** Durable on return. A crash one instruction later still leaves the line readable. */
    fun append(line: String)

    fun rewrite(lines: List<String>)
}

/** The four record kinds an entry is folded from. Storage is append-only. */
internal object RecordKind {
    const val DISPATCH = "dispatch"
    const val SETTLE = "settle"
    const val RECONCILE = "reconcile"
    const val ACKNOWLEDGE = "acknowledge"
}

/**
 * One stored line: a flat map of string keys to strings, longs, booleans or null.
 *
 * Flat and small on purpose. The journal has no nested values, the transcript is the only
 * free text it stores, and a codec this size is easier to hold to "an unreadable line is
 * never silently discarded" than a general JSON parser would be. `:provider` keeps its
 * own reader for OpenRouter's replies; `:core` may not depend on it.
 */
internal object JournalLine {
    fun write(fields: Map<String, Any?>): String = buildString {
        append('{')
        var first = true
        for ((key, value) in fields) {
            if (!first) append(',')
            first = false
            quote(key)
            append(':')
            when (value) {
                null -> append("null")
                is String -> quote(value)
                is Boolean -> append(value)
                is Int -> append(value)
                is Long -> append(value)
                else -> error("unsupported journal value: ${value.javaClass.name}")
            }
        }
        append('}')
    }

    /** The parsed fields, or null for a line this codec cannot read back in full. */
    fun read(line: String): Map<String, Any?>? = try {
        parse(line)
    } catch (_: RuntimeException) {
        // A torn final line from a kill mid-write, or anything else unreadable. The
        // caller keeps it; only the caller may decide what an unreadable line means.
        null
    } catch (_: StringIndexOutOfBoundsException) {
        null
    }

    private fun parse(line: String): Map<String, Any?>? {
        val text = line.trim()
        if (text.isEmpty()) return null
        require(text.startsWith("{") && text.endsWith("}")) { "not a journal record" }
        val fields = LinkedHashMap<String, Any?>()
        var at = 1
        fun skipSpace() { while (at < text.length && text[at].isWhitespace()) at++ }
        skipSpace()
        if (text[at] == '}') return fields
        while (true) {
            skipSpace()
            val key = readString(text, at).also { at = it.second }.first
            skipSpace()
            require(text[at] == ':') { "expected ':'" }
            at++
            skipSpace()
            when {
                text[at] == '"' -> readString(text, at).let { fields[key] = it.first; at = it.second }
                text.startsWith("true", at) -> { fields[key] = true; at += 4 }
                text.startsWith("false", at) -> { fields[key] = false; at += 5 }
                text.startsWith("null", at) -> { fields[key] = null; at += 4 }
                else -> {
                    val start = at
                    if (text[at] == '-') at++
                    while (at < text.length && text[at].isDigit()) at++
                    require(at > start) { "expected a value" }
                    fields[key] = text.substring(start, at).toLong()
                }
            }
            skipSpace()
            when (text[at]) {
                ',' -> at++
                '}' -> {
                    require(at == text.length - 1) { "trailing content" }
                    return fields
                }
                else -> error("expected ',' or '}'")
            }
        }
    }

    private fun readString(text: String, from: Int): Pair<String, Int> {
        require(text[from] == '"') { "expected a string" }
        var at = from + 1
        val out = StringBuilder()
        while (true) {
            require(at < text.length) { "unterminated string" }
            when (val c = text[at++]) {
                '"' -> return out.toString() to at
                '\\' -> when (val escaped = text[at++]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        require(at + 4 <= text.length) { "truncated escape" }
                        out.append(text.substring(at, at + 4).toInt(16).toChar())
                        at += 4
                    }
                    '"', '\\', '/' -> out.append(escaped)
                    else -> error("unsupported escape '\\$escaped'")
                }
                else -> out.append(c)
            }
        }
    }

    private fun StringBuilder.quote(text: String) {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                // Anything else below the printable range, including the line separators a
                // line-oriented file must never contain, goes out escaped.
                c < ' ' || c == ' ' || c == ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
