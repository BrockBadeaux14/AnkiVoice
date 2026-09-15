package org.ankivoice.ankidroid

import java.io.File
import org.ankivoice.core.contracts.VOICEQA_MODEL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The loader half of AV-039's drift guard. `tools/av039_note_type.py` derives the resource
 * from `fixtures/voiceqa/note-type.json` and `tests/test_av039_note_type.py` fails when the
 * checked-in copy is stale; this fails when the Kotlin loader disagrees with the resource,
 * or when the resource is not packaged where the app will look for it.
 */
class VoiceQaNoteTypeTest {
    private val specification = VoiceQaNoteType.load()

    /** The fixture itself, read as text so no JSON parser is needed to compare values. */
    private val fixture: String = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures/voiceqa/note-type.json") }
        .first { it.isFile }
        .readText()

    private fun quoted(value: String) = '"' + value
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"'

    @Test fun `the resource is on the runtime classpath, not only the source tree`() {
        assertNotNull(VoiceQaNoteType::class.java.getResourceAsStream("/av039/voiceqa-note-type.properties"))
    }

    @Test fun `the note type is the one AV-007 identifies a VoiceQA card by`() {
        assertEquals(VOICEQA_MODEL, specification.name)
        assertEquals("VoiceQA", specification.name)
    }

    @Test fun `the six fields keep the fixture's names and order`() {
        assertEquals(
            listOf("Prompt", "ReferenceAnswer", "RequiredConcepts", "AcceptedAnswers", "Language", "Extra"),
            specification.fieldNames,
        )
        assertEquals(specification.fieldNames.joinToString(""), specification.joinedFieldNames())
        for (field in specification.fieldNames) assertTrue(fixture.contains(quoted(field)), field)
    }

    @Test fun `the template and CSS are the fixture's, character for character`() {
        assertEquals("Voice recall", specification.template.name)
        for (value in listOf(specification.template.front, specification.template.back, specification.css)) {
            assertTrue(fixture.contains(quoted(value)), value.take(60))
        }
    }

    @Test fun `the front shows the prompt and no grading criteria`() {
        val front = specification.template.front
        assertTrue(front.contains("{{Prompt}}"))
        for (field in specification.fieldNames.drop(1)) assertFalse(front.contains(field), field)
    }

    @Test fun `the four sample notes supply one value per field and stay traceable`() {
        assertEquals(4, specification.demoNotes.size)
        assertEquals("VoiceQA Demo", specification.demoDeckName)
        for (note in specification.demoNotes) {
            assertEquals(specification.fieldNames.size, note.fields.size)
            assertTrue(note.fields[0].isNotBlank()) // Prompt is required.
            assertTrue(note.fields[1].isNotBlank()) // ReferenceAnswer is required.
            for (value in note.fields) assertTrue(fixture.contains(quoted(value)), value.take(60))
            val tags = note.tags.split(" ")
            assertEquals("ankivoice-demo", tags.first())
            assertTrue(fixture.contains(quoted(tags.last())), note.tags)
        }
    }

    @Test fun `a specification that breaks AV-007's identity rules is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            specification.copy(name = "VoiceQA (copy)")
        }
        assertThrows(IllegalArgumentException::class.java) {
            specification.copy(fieldNames = specification.fieldNames.reversed())
        }
        assertThrows(IllegalArgumentException::class.java) {
            specification.copy(demoNotes = listOf(VoiceQaDemoNote(listOf("only one"), "t")))
        }
    }
}
