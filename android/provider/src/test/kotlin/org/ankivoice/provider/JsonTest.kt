package org.ankivoice.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The request and reply encoding, so a price or a reply body is never misread. */
class JsonTest {
    @Test
    fun `objects, arrays and scalars round trip`() {
        val value = linkedMapOf(
            "model" to "liquid/lfm-2.5-2.6b:free",
            "stream" to false,
            "temperature" to 0,
            "messages" to listOf(linkedMapOf("role" to "user", "content" to "hi")),
            "nothing" to null,
        )
        val text = Json.write(value)
        assertEquals(
            """{"model":"liquid/lfm-2.5-2.6b:free","stream":false,"temperature":0,""" +
                """"messages":[{"role":"user","content":"hi"}],"nothing":null}""",
            text,
        )
        assertEquals("hi", Json.parse(text).asObject()?.child("messages").asList()?.firstOrNull().asObject()?.child("content"))
    }

    @Test
    fun `quotes, newlines and control characters are escaped`() {
        val text = Json.write(mapOf("content" to "say \"five\"\nor\tnot\\ever"))
        assertEquals("""{"content":"say \"five\"\nor\tnot\\ever"}""", text)
        assertEquals("say \"five\"\nor\tnot\\ever", Json.parse(text).asObject()?.child("content"))
    }

    @Test
    fun `a number keeps its exact text for a price comparison`() {
        val cost = Json.parse("""{"cost":0.0000000001}""").asObject()?.child("cost")
        assertEquals("0.0000000001", JsonText.number(cost))
        assertEquals("0", JsonText.number(Json.parse("""{"cost":0}""").asObject()?.child("cost")))
        assertNull(JsonText.number(Json.parse("""{"cost":true}""").asObject()?.child("cost")))
    }

    @Test
    fun `malformed json is rejected rather than half-read`() {
        for (text in listOf("{", """{"a":}""", """{"a":1}trailing""", "")) {
            assertThrows(RuntimeException::class.java, { Json.parse(text) }, text)
        }
    }
}
