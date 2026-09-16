package org.ankivoice.ankidroid

import java.util.concurrent.Executor
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.VOICEQA_MODEL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * AV-039 provisioning, driven through the same resolver seam the app uses. Every outcome
 * is reached with an in-memory collection: no emulator, no AnkiDroid and no network.
 */
class ProvisioningTest {
    private val specification = VoiceQaNoteType.load()

    private class Tasks : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.add(command) }
        fun run() { pending.removeFirst().run() }
        fun runAll() { while (pending.isNotEmpty()) run() }
    }

    /** An in-memory stand-in for AnkiDroid 2.24.1's provider, with its observed rules. */
    private class Collection : ProvisioningPlatform {
        var available = true
        var api: Boolean? = true
        var database = true
        var microphone = true
        var models: MutableList<InstalledNoteType>? = mutableListOf()
        var templates: MutableMap<Long, MutableList<InstalledTemplate>> = mutableMapOf()
        var decks: MutableList<Deck>? = mutableListOf(Deck(1, "Default"))
        var notes: MutableMap<Long, List<String>> = mutableMapOf()
        var noteTags: MutableMap<Long, String> = mutableMapOf()
        var cards: MutableMap<Long, MutableList<InstalledCard>> = mutableMapOf()
        var noteTypeDecks: MutableMap<Long, Long> = mutableMapOf()
        var nextId = 100L
        var refuseModelInsert = false
        var refuseNoteInsert = false
        var nullNoteTypes = false
        var nullTemplates = false
        var storedTemplate: ((VoiceQaTemplate) -> VoiceQaTemplate)? = null
        var failAt: String? = null
        var failure: RuntimeException = SecurityException()
        var duringCall: ((String) -> Unit)? = null
        val calls = mutableListOf<String>()

        private fun record(call: String) {
            calls += call
            duringCall?.invoke(call)
            failAt?.let { if (call.startsWith(it)) throw failure }
        }

        override fun packageAvailable() = available
        override fun apiEnabled() = api
        override fun databasePermissionGranted() = database
        override fun microphonePermissionGranted() = microphone
        override fun queryDecks(): List<Deck>? { record("decks"); return decks }
        override fun querySelectedDeck(): List<Deck>? = emptyList()
        override fun updateSelectedDeck(deckId: Long) = 1

        override fun queryNoteTypes(): List<InstalledNoteType>? {
            record("models")
            return if (nullNoteTypes) null else models
        }

        override fun queryTemplates(modelId: Long): List<InstalledTemplate>? {
            record("templates:$modelId")
            return if (nullTemplates) null else templates[modelId]
        }

        override fun insertNoteType(name: String, fieldNames: String, css: String, deckId: Long): Long? {
            record("models:insert:$name:$deckId")
            if (refuseModelInsert) return null
            val id = nextId++
            models?.add(InstalledNoteType(id, name, fieldNames.split(VoiceQaNoteType.FIELD_SEPARATOR)))
            // A models insert creates num_cards placeholder templates.
            templates[id] = mutableListOf(InstalledTemplate("Card 1", "{{Prompt}}", "{{FrontSide}}"))
            noteTypeDecks[id] = deckId
            return id
        }

        override fun updateTemplate(modelId: Long, ordinal: Int, template: VoiceQaTemplate): Int {
            record("templates:update:$modelId:$ordinal")
            val stored = storedTemplate?.invoke(template) ?: template
            templates.getValue(modelId)[ordinal] = InstalledTemplate(stored.name, stored.front, stored.back)
            return 3
        }

        override fun insertDeck(name: String): Long? {
            record("decks:insert:$name")
            if (decks?.any { it.name == name } == true) return null // Duplicate name is rejected.
            val id = nextId++
            decks?.add(Deck(id, name))
            return id
        }

        override fun insertNote(modelId: Long, fields: String, tags: String): Long? {
            record("notes:insert:$modelId")
            if (refuseNoteInsert) return null
            val id = nextId++
            notes[id] = fields.split(VoiceQaNoteType.FIELD_SEPARATOR)
            noteTags[id] = tags
            cards[id] = mutableListOf(InstalledCard(0, noteTypeDecks[modelId] ?: 1))
            return id
        }

        override fun queryNoteFields(noteId: Long): List<String>? {
            record("notes:read:$noteId")
            return notes[noteId] ?: emptyList()
        }

        override fun queryNoteCards(noteId: Long): List<InstalledCard>? {
            record("notes:cards:$noteId")
            return cards[noteId]
        }

        override fun moveCard(noteId: Long, ordinal: Int, deckId: Long): Int {
            record("cards:move:$noteId:$ordinal:$deckId")
            val card = cards.getValue(noteId)
            card[ordinal] = InstalledCard(ordinal, deckId)
            return 1
        }

        /** Everything a provisioning run may not touch, for a before/after comparison. */
        fun snapshot(): String = listOf(
            models?.joinToString(";"), decks?.joinToString(";"), templates.toString(),
            notes.toString(), noteTags.toString(), cards.toString(),
        ).joinToString("|")
    }

    private val collection = Collection()
    private val worker = Tasks()
    private val main = Tasks()
    private val provisioning = AnkiDroidProvisioning(collection, worker, main, specification)

    private fun inspect(): ProvisioningReport {
        var report: ProvisioningReport? = null
        provisioning.inspect { report = it }
        worker.runAll()
        main.runAll()
        return requireNotNull(report)
    }

    private fun provision(accepted: Boolean = true): ProvisioningReport {
        var report: ProvisioningReport? = null
        provisioning.provision(accepted) { report = it }
        worker.runAll()
        main.runAll()
        return requireNotNull(report)
    }

    private fun withMatchingNoteType(id: Long = 55): Long {
        collection.models?.add(InstalledNoteType(id, VOICEQA_MODEL, specification.fieldNames))
        collection.templates[id] = mutableListOf(
            InstalledTemplate(
                specification.template.name, specification.template.front, specification.template.back,
            ),
        )
        collection.noteTypeDecks[id] = 1
        return id
    }

    private fun demoDeckId(): Long? = collection.decks?.firstOrNull { it.name == specification.demoDeckName }?.id

    // Placement and threading.

    @Test fun `provider calls run on the worker and reports wait for main delivery`() {
        var report: ProvisioningReport? = null
        provisioning.inspect { report = it }
        assertTrue(collection.calls.isEmpty())
        worker.runAll()
        assertEquals(listOf("models", "decks"), collection.calls)
        assertNull(report)
        main.runAll()
        assertTrue(requireNotNull(report).workRemains)
    }

    @Test fun `inspection never writes`() {
        val before = collection.snapshot()
        assertTrue(inspect().workRemains)
        assertEquals(before, collection.snapshot())
    }

    // Disclosure.

    @Test fun `declining the full sync disclosure writes nothing`() {
        val before = collection.snapshot()
        val report = provision(accepted = false)
        assertEquals(ProvisioningStatus.Declined, report.status)
        assertEquals(before, collection.snapshot())
        assertTrue(collection.calls.none { it.contains("insert") })
    }

    @Test fun `an already provisioned collection needs no disclosure and writes nothing`() {
        provision()
        val before = collection.snapshot()
        collection.calls.clear()
        val report = provision(accepted = false)
        assertEquals(ProvisioningStatus.Complete, report.status)
        assertEquals(before, collection.snapshot())
        assertTrue(collection.calls.none { it.contains("insert") })
    }

    // Fresh install and its read-back.

    @Test fun `a fresh install writes the note type, deck and sample notes once`() {
        val report = provision()
        assertEquals(ProvisioningStatus.Complete, report.status)
        assertEquals(ProvisioningStep.CREATED, report.noteType)
        assertEquals(ProvisioningStep.CREATED, report.demoDeck)
        assertEquals(ProvisioningStep.CREATED, report.demoNotes)
        assertEquals(specification.demoNotes.size, report.notesAdded)

        val model = requireNotNull(collection.models?.single { it.name == VOICEQA_MODEL })
        assertEquals(specification.fieldNames, model.fieldNames)
        assertEquals(
            listOf(InstalledTemplate(
                specification.template.name, specification.template.front, specification.template.back,
            )),
            collection.templates[model.id],
        )
        assertEquals(demoDeckId(), collection.noteTypeDecks[model.id])
        assertEquals(specification.demoNotes.map { it.fields }, collection.notes.values.toList())
        assertEquals(specification.demoNotes.map { it.tags }, collection.noteTags.values.toList())
        assertTrue(collection.cards.values.flatten().all { it.deckId == demoDeckId() })
    }

    @Test fun `the fixture template is installed unchanged and the front hides the answer`() {
        provision()
        val model = requireNotNull(collection.models?.single { it.name == VOICEQA_MODEL })
        val template = collection.templates.getValue(model.id).single()
        assertEquals(specification.template.front, template.front)
        assertEquals(specification.template.back, template.back)
        for (field in specification.fieldNames.drop(1)) assertFalse(template.front.contains(field), field)
        assertTrue(template.back.contains("ReferenceAnswer"))
    }

    @Test fun `the deck exists before the note type binds it as its default deck`() {
        provision()
        val deckInsert = collection.calls.indexOfFirst { it.startsWith("decks:insert") }
        val modelInsert = collection.calls.indexOfFirst { it.startsWith("models:insert") }
        assertTrue(deckInsert in 0..<modelInsert, collection.calls.toString())
        assertTrue(collection.calls.contains("models:insert:$VOICEQA_MODEL:${demoDeckId()}"))
    }

    @Test fun `a model that reads back with the wrong template is incomplete, not successful`() {
        collection.storedTemplate = { it.copy(back = "{{FrontSide}}") }
        val report = provision()
        val status = assertInstanceOf(ProvisioningStatus.Incomplete::class.java, report.status)
        assertTrue(status.reasons.any { it.contains("back template") }, status.reasons.toString())
        assertEquals(ProvisioningStep.CREATED, report.noteType)
        assertEquals(0, report.notesAdded)
    }

    @Test fun `a model the provider never returns is incomplete`() {
        collection.refuseModelInsert = true
        val report = provision()
        val status = assertInstanceOf(ProvisioningStatus.Incomplete::class.java, report.status)
        assertTrue(status.reasons.single().contains("did not return"), status.reasons.toString())
        assertTrue(status.reasons.single().contains(specification.demoDeckName))
        assertTrue(collection.notes.isEmpty())
    }

    // Reuse and repeatability.

    @Test fun `a matching note type is reused and nothing about it is rewritten`() {
        val id = withMatchingNoteType()
        val report = provision()
        assertEquals(ProvisioningStatus.Complete, report.status)
        assertEquals(ProvisioningStep.PRESENT, report.noteType)
        assertEquals(1, collection.models?.size)
        assertEquals(id, collection.models?.single()?.id)
        assertTrue(collection.calls.none { it.startsWith("models:insert") })
        assertTrue(collection.calls.none { it.startsWith("templates:update") })
        assertEquals(1L, collection.noteTypeDecks[id]) // Its default deck is left alone.
    }

    @Test fun `sample notes added to a reused note type still reach the demo deck`() {
        withMatchingNoteType()
        val report = provision()
        assertEquals(specification.demoNotes.size, report.notesAdded)
        assertTrue(collection.cards.values.flatten().all { it.deckId == demoDeckId() })
        assertEquals(specification.demoNotes.size, collection.calls.count { it.startsWith("cards:move") })
    }

    @Test fun `running setup again duplicates nothing and changes nothing`() {
        assertEquals(ProvisioningStatus.Complete, provision().status)
        val after = collection.snapshot()
        collection.calls.clear()
        val second = provision()
        assertEquals(ProvisioningStatus.Complete, second.status)
        assertEquals(ProvisioningStep.PRESENT, second.noteType)
        assertEquals(ProvisioningStep.PRESENT, second.demoDeck)
        assertEquals(ProvisioningStep.SKIPPED, second.demoNotes)
        assertEquals(0, second.notesAdded)
        assertEquals(after, collection.snapshot())
        assertTrue(collection.calls.none { it.contains("insert") || it.contains("update") })
        assertEquals(ProvisioningStatus.Complete, inspect().status)
    }

    @Test fun `a partially provisioned collection is completed rather than duplicated`() {
        withMatchingNoteType()
        val report = inspect()
        val status = assertInstanceOf(ProvisioningStatus.Incomplete::class.java, report.status)
        assertEquals(1, status.reasons.size)
        assertTrue(status.reasons.single().contains(specification.demoDeckName))
        assertEquals(ProvisioningStep.PRESENT, report.noteType)

        assertEquals(ProvisioningStatus.Complete, provision().status)
        assertEquals(1, collection.models?.size)
        assertEquals(1, collection.decks?.count { it.name == specification.demoDeckName })
    }

    @Test fun `an existing demo deck skips demo content and is never written to`() {
        collection.decks?.add(Deck(9, specification.demoDeckName))
        val report = provision()
        assertEquals(ProvisioningStatus.Complete, report.status)
        assertEquals(ProvisioningStep.CREATED, report.noteType)
        assertEquals(ProvisioningStep.PRESENT, report.demoDeck)
        assertEquals(ProvisioningStep.SKIPPED, report.demoNotes)
        assertEquals(0, report.notesAdded)
        assertTrue(collection.notes.isEmpty())
        assertTrue(collection.calls.none { it.startsWith("decks:insert") })
    }

    @Test fun `a demo deck created between the survey and the write is treated as provisioned`() {
        // AnkiDroid rejects a duplicate deck name. That is the only signal for this race,
        // and it means already provisioned, not broken.
        collection.duringCall = { call ->
            if (call == "decks:insert:${specification.demoDeckName}") {
                collection.decks?.add(Deck(9, specification.demoDeckName))
            }
        }
        val report = provision()
        assertEquals(ProvisioningStatus.Complete, report.status)
        assertEquals(ProvisioningStep.PRESENT, report.demoDeck)
        assertEquals(ProvisioningStep.SKIPPED, report.demoNotes)
        assertEquals(1, collection.decks?.count { it.name == specification.demoDeckName })
        assertTrue(collection.notes.isEmpty())
    }

    // Conflict.

    @Test fun `a VoiceQA note type with different fields stops with a named conflict`() {
        collection.models?.add(InstalledNoteType(55, VOICEQA_MODEL, listOf("Front", "Back")))
        val other = InstalledNoteType(56, "Basic", listOf("Front", "Back"))
        collection.models?.add(other)
        val before = collection.snapshot()

        for (report in listOf(inspect(), provision())) {
            val status = assertInstanceOf(ProvisioningStatus.Conflict::class.java, report.status)
            assertTrue(status.differences.first().contains("Front, Back"), status.differences.toString())
            assertTrue(status.differences.any { it.contains(specification.fieldNames.joinToString(", ")) })
            assertTrue(status.differences.any { it.contains("Nothing was changed") })
            assertEquals(ProvisioningStep.PRESENT, report.noteType)
            assertEquals(ProvisioningStep.NOT_ATTEMPTED, report.demoDeck)
        }
        assertEquals(before, collection.snapshot())
        assertEquals(other, collection.models?.last())
    }

    @Test fun `two note types named VoiceQA stop rather than guessing`() {
        collection.models?.add(InstalledNoteType(55, VOICEQA_MODEL, specification.fieldNames))
        collection.models?.add(InstalledNoteType(56, VOICEQA_MODEL, specification.fieldNames))
        val before = collection.snapshot()
        val status = assertInstanceOf(ProvisioningStatus.Conflict::class.java, provision().status)
        assertTrue(status.differences.single().contains("2 note types"), status.differences.toString())
        assertEquals(before, collection.snapshot())
    }

    // Failing safely.

    @Test fun `missing package, disabled API and denied permission are reported by name`() {
        val cases = mapOf<CardProviderFailure, () -> Unit>(
            CardProviderFailure.PACKAGE_UNAVAILABLE to { collection.available = false },
            CardProviderFailure.API_DISABLED to { collection.api = false },
            CardProviderFailure.ACCESS_DENIED to { collection.database = false },
        )
        val before = collection.snapshot()
        for ((mode, arrange) in cases) {
            collection.available = true
            collection.api = true
            collection.database = true
            arrange()
            collection.calls.clear()
            for (report in listOf(inspect(), provision())) {
                val status = assertInstanceOf(ProvisioningStatus.Failed::class.java, report.status)
                assertEquals(mode, status.failure.mode, mode.specName)
            }
            // The preflight decides before any resolver call, and nothing is written.
            assertTrue(collection.calls.isEmpty(), mode.specName)
            assertEquals(before, collection.snapshot(), mode.specName)
        }
    }

    @Test fun `a denied microphone belongs to speech and does not block provisioning`() {
        collection.microphone = false
        val report = provision()
        assertEquals(ProvisioningStatus.Complete, report.status)
        assertEquals(specification.demoNotes.size, report.notesAdded)
        assertNotEquals(
            SpeechInputFailure.PERMISSION_DENIED,
            (inspect().status as? ProvisioningStatus.Failed)?.failure?.mode,
        )
    }

    @Test fun `permission revoked mid-run is denied and keeps the progress it made`() {
        collection.failAt = "notes:insert"
        collection.failure = SecurityException()
        val report = provision()
        val status = assertInstanceOf(ProvisioningStatus.Failed::class.java, report.status)
        assertEquals(CardProviderFailure.ACCESS_DENIED, status.failure.mode)
        assertEquals(ProvisioningStep.CREATED, report.demoDeck)
        assertEquals(ProvisioningStep.CREATED, report.noteType)
        assertEquals(0, report.notesAdded)
        assertTrue(collection.notes.isEmpty())
    }

    @Test fun `a null cursor is never reported as successful provisioning`() {
        collection.nullNoteTypes = true
        for (api in listOf(true, null)) {
            collection.api = api
            for (report in listOf(inspect(), provision())) {
                val status = assertInstanceOf(ProvisioningStatus.Failed::class.java, report.status)
                assertEquals(CardProviderFailure.NULL_CURSOR, status.failure.mode)
            }
        }
    }

    @Test fun `a null read-back with an unknown cause stays a null cursor`() {
        collection.nullTemplates = true
        val report = provision()
        assertEquals(CardProviderFailure.NULL_CURSOR,
            assertInstanceOf(ProvisioningStatus.Failed::class.java, report.status).failure.mode)
        assertEquals(ProvisioningStep.CREATED, report.noteType)
        assertEquals(0, report.notesAdded)
    }

    @Test fun `a null read-back rechecks a component disabled during the run`() {
        collection.nullTemplates = true
        collection.duringCall = { if (it.startsWith("templates:")) collection.api = false }
        assertEquals(CardProviderFailure.API_DISABLED,
            assertInstanceOf(ProvisioningStatus.Failed::class.java, provision().status).failure.mode)
    }

    @Test fun `an unexpected provider fault stays unknown rather than successful`() {
        collection.failAt = "decks:insert:${specification.demoDeckName}"
        collection.failure = IllegalStateException("provider died")
        val report = provision()
        val status = assertInstanceOf(ProvisioningStatus.Failed::class.java, report.status)
        assertEquals(CardProviderFailure.NULL_CURSOR, status.failure.mode)
        assertTrue(collection.models.isNullOrEmpty())
    }

    @Test fun `a refused note insert reports how many sample notes landed`() {
        collection.refuseNoteInsert = true
        val report = provision()
        val status = assertInstanceOf(ProvisioningStatus.Incomplete::class.java, report.status)
        assertTrue(status.reasons.single().contains("Only 0 of ${specification.demoNotes.size}"))
        assertTrue(status.reasons.single().contains("delete it in AnkiDroid"))
        assertEquals(0, report.notesAdded)

        // The deck now exists, so the next run skips demo content instead of duplicating it.
        collection.refuseNoteInsert = false
        val second = provision()
        assertEquals(ProvisioningStatus.Complete, second.status)
        assertEquals(ProvisioningStep.SKIPPED, second.demoNotes)
        assertTrue(collection.notes.isEmpty())
    }

    @Test fun `a sample note that does not reach the demo deck is not counted`() {
        val provider = object : ProvisioningPlatform by collection {
            override fun moveCard(noteId: Long, ordinal: Int, deckId: Long) = 0
            override fun insertNote(modelId: Long, fields: String, tags: String): Long? {
                val id = collection.insertNote(modelId, fields, tags)
                collection.cards[requireNotNull(id)] = mutableListOf(InstalledCard(0, 1))
                return id
            }
        }
        val provisioning = AnkiDroidProvisioning(provider, worker, main, specification)
        var report: ProvisioningReport? = null
        provisioning.provision(true) { report = it }
        worker.runAll()
        main.runAll()
        val status = assertInstanceOf(
            ProvisioningStatus.Incomplete::class.java, requireNotNull(report).status,
        )
        assertTrue(status.reasons.single().contains("Only 0 of"), status.reasons.toString())
    }
}
