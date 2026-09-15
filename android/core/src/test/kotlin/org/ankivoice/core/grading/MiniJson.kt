package org.ankivoice.core.grading

/**
 * Just enough JSON to read the repository fixtures in a test, so :core needs no JSON
 * dependency. Objects become maps, arrays lists, numbers doubles.
 */
internal class MiniJson private constructor(private val text: String) {
    private var at = 0

    companion object {
        fun parse(text: String): Any? = MiniJson(text).run {
            val value = value()
            skipSpace()
            check(at == text.length) { "trailing content at $at" }
            value
        }
    }

    private fun value(): Any? {
        skipSpace()
        return when (val c = peek()) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $at")
        }
    }

    private fun obj(): Map<String, Any?> {
        expect('{')
        val result = LinkedHashMap<String, Any?>()
        skipSpace()
        if (peek() == '}') return result.also { at++ }
        while (true) {
            skipSpace()
            val key = string()
            skipSpace()
            expect(':')
            result[key] = value()
            skipSpace()
            if (peek() == ',') at++ else return result.also { expect('}') }
        }
    }

    private fun array(): List<Any?> {
        expect('[')
        val result = ArrayList<Any?>()
        skipSpace()
        if (peek() == ']') return result.also { at++ }
        while (true) {
            result += value()
            skipSpace()
            if (peek() == ',') at++ else return result.also { expect(']') }
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

    private fun number(): Double {
        val start = at
        while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
        return text.substring(start, at).toDouble()
    }

    private fun literal(word: String, value: Any?): Any? {
        check(text.startsWith(word, at)) { "expected $word at $at" }
        at += word.length
        return value
    }

    private fun peek(): Char = text[at]

    private fun expect(c: Char) {
        check(peek() == c) { "expected '$c' at $at, found '${peek()}'" }
        at++
    }

    private fun skipSpace() {
        while (at < text.length && text[at].isWhitespace()) at++
    }
}
