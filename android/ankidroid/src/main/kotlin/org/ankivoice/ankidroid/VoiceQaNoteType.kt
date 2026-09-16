package org.ankivoice.ankidroid

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import org.ankivoice.core.contracts.VOICEQA_MODEL

/** The one template AV-039 installs. One card per note; the front reveals no answer. */
data class VoiceQaTemplate(val name: String, val front: String, val back: String)

/** A sample note: field values in the note type's field order, plus its tags. */
data class VoiceQaDemoNote(val fields: List<String>, val tags: String)

/**
 * The installable part of `fixtures/voiceqa/note-type.json`.
 *
 * `tools/av039_note_type.py` derives the resource this is loaded from, and
 * `tests/test_av039_note_type.py` fails when that copy is stale. Nothing here talks to
 * AnkiDroid: this is the expected shape that [AnkiDroidProvisioning] installs and then
 * reads back.
 */
data class VoiceQaNoteType(
    val name: String,
    val fieldNames: List<String>,
    val css: String,
    val template: VoiceQaTemplate,
    val demoDeckName: String,
    val demoNotes: List<VoiceQaDemoNote>,
) {
    init {
        require(name == VOICEQA_MODEL) { "AV-007 identifies a VoiceQA card by the model name $VOICEQA_MODEL" }
        require(fieldNames.take(2) == listOf("Prompt", "ReferenceAnswer")) {
            "Prompt and ReferenceAnswer are required and lead the field order"
        }
        require(demoNotes.all { it.fields.size == fieldNames.size }) {
            "Every sample note supplies one value per field"
        }
    }

    /** AnkiDroid joins and splits note fields and field names with 0x1f. */
    fun joinedFieldNames(): String = fieldNames.joinToString(FIELD_SEPARATOR)

    companion object {
        const val FIELD_SEPARATOR: String = ""
        private const val RESOURCE = "/av039/voiceqa-note-type.properties"

        /** The checked-in specification. Read once per call; the result is immutable. */
        fun load(): VoiceQaNoteType {
            val stream = checkNotNull(VoiceQaNoteType::class.java.getResourceAsStream(RESOURCE)) {
                "missing $RESOURCE"
            }
            val values = Properties()
            stream.use { InputStreamReader(it, StandardCharsets.UTF_8).use(values::load) }
            fun read(key: String): String = checkNotNull(values.getProperty(key)) { "missing $key" }
            fun count(key: String): Int = read(key).toInt()
            return VoiceQaNoteType(
                name = read("notetype.name"),
                fieldNames = (0 until count("notetype.fieldCount")).map { read("notetype.field.$it") },
                css = read("notetype.css"),
                template = VoiceQaTemplate(
                    read("template.name"), read("template.front"), read("template.back"),
                ),
                demoDeckName = read("demo.deck"),
                demoNotes = (0 until count("demo.count")).map { note ->
                    VoiceQaDemoNote(
                        fields = (0 until count("notetype.fieldCount")).map { read("demo.note.$note.field.$it") },
                        tags = read("demo.note.$note.tags"),
                    )
                },
            )
        }
    }
}
