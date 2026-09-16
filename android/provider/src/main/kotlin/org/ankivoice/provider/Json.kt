package org.ankivoice.provider

/**
 * Just enough JSON for the OpenRouter request and reply, so `:provider` needs no JSON
 * dependency and its rules stay unit-testable on the JVM. Objects are maps, arrays
 * lists, numbers doubles; a number that must keep its exact value, such as a price, is
 * read from its text with [JsonText.number].
 *
 * Android's `org.json` is not used: it is a stub in JVM unit tests.
 */
internal object Json {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    fun parse(text: String): Any? = Parser(text).run {
        val value = value()
        skipSpace()
        require(finished) { "trailing content" }
        value
    }

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value)
            is Double -> {
                require(value.isFinite()) { "not a JSON number" }
                if (value == value.toLong().toDouble()) out.append(value.toLong()) else out.append(value)
            }
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) out.append(',')
                    quote(out, key as? String ?: error("object keys must be strings"))
                    out.append(':')
                    append(out, item)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }
            else -> error("unsupported JSON value: ${value.javaClass.name}")
        }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        private var at = 0
        val finished: Boolean get() = at == text.length

        fun value(): Any? {
            skipSpace()
            return when (val c = text[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $at")
            }
        }

        fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipSpace()
            if (text[at] == '}') return result.also { at++ }
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                expect(':')
                result[key] = value()
                skipSpace()
                if (text[at] == ',') at++ else return result.also { expect('}') }
            }
        }

        private fun array(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipSpace()
            if (text[at] == ']') return result.also { at++ }
            while (true) {
                result += value()
                skipSpace()
                if (text[at] == ',') at++ else return result.also { expect(']') }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> when (val escaped = text[at++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('')
                        'u' -> out.append(text.substring(at, at + 4).toInt(16).toChar()).also { at += 4 }
                        else -> out.append(escaped)
                    }
                    else -> out.append(c)
                }
            }
        }

        /** Kept as text too, so a price never loses precision on its way to BigDecimal. */
        private fun number(): JsonText {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
            val literal = text.substring(start, at)
            return JsonText(literal, literal.toDouble())
        }

        private fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, at)) { "expected $word at $at" }
            at += word.length
            return value
        }

        private fun expect(c: Char) {
            require(at < text.length && text[at] == c) { "expected '$c' at $at" }
            at++
        }
    }
}

/** A parsed JSON number, with the source text kept for exact decimal comparisons. */
internal data class JsonText(val text: String, val value: Double) {
    override fun toString(): String = text

    companion object {
        /** The literal text of a JSON number or string, or null for anything else. */
        fun number(value: Any?): String? = when (value) {
            is JsonText -> value.text
            is String -> value
            is Int, is Long, is Double -> value.toString()
            else -> null
        }
    }
}

internal fun Any?.asObject(): Map<*, *>? = this as? Map<*, *>

internal fun Any?.asList(): List<*>? = this as? List<*>

internal fun Any?.asText(): String? = this as? String

internal fun Map<*, *>.child(key: String): Any? = this[key]
