package org.ankivoice.ankidroid

import java.util.concurrent.Executor
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.Failure

/** A note type as AnkiDroid reports it through `models`. */
data class InstalledNoteType(val id: Long, val name: String, val fieldNames: List<String>)

/** A card template as AnkiDroid reports it through `models/<id>/templates`. */
data class InstalledTemplate(val name: String, val front: String, val back: String)

/** One card of a note, as AnkiDroid reports it through `notes/<id>/cards`. */
data class InstalledCard(val ordinal: Int, val deckId: Long)

/**
 * The provisioning half of the resolver seam. `null` means the provider gave no usable
 * answer; an empty list is a valid, empty result. These calls report what AnkiDroid
 * returned rather than interpreting it, so [AnkiDroidProvisioning] owns every policy
 * decision and a JVM test can drive each outcome.
 */
interface ProvisioningPlatform : AccessPlatform {
    fun queryNoteTypes(): List<InstalledNoteType>?
    fun queryTemplates(modelId: Long): List<InstalledTemplate>?

    /** Inserts a note type with one placeholder template. Null when no model URI came back. */
    fun insertNoteType(name: String, fieldNames: String, css: String, deckId: Long): Long?

    /** Sets the placeholder template's name and formats. Returns the provider's update count. */
    fun updateTemplate(modelId: Long, ordinal: Int, template: VoiceQaTemplate): Int

    /** Null when AnkiDroid rejected the insert, which a duplicate deck name does. */
    fun insertDeck(name: String): Long?

    /** Null when no note URI came back. */
    fun insertNote(modelId: Long, fields: String, tags: String): Long?
    fun queryNoteFields(noteId: Long): List<String>?
    fun queryNoteCards(noteId: Long): List<InstalledCard>?

    /** Moves one card of a note to [deckId]. Returns the provider's update count. */
    fun moveCard(noteId: Long, ordinal: Int, deckId: Long): Int
}

/** What provisioning did to one item. */
enum class ProvisioningStep {
    /** The run stopped before reaching it. */
    NOT_ATTEMPTED,

    /** Already present and matching; reused unchanged. */
    PRESENT,

    /** Written by this run. */
    CREATED,

    /** Deliberately not written, by AV-039's demo-content policy. */
    SKIPPED,
}

/** The outcome of an inspection or a provisioning run. Exhaustively matchable. */
sealed interface ProvisioningStatus {
    /** Nothing is missing. Provisioning wrote what it needed to, or had nothing to do. */
    data object Complete : ProvisioningStatus

    /** The learner did not accept the full-sync disclosure. Nothing was written. */
    data object Declined : ProvisioningStatus

    /** Work remains. From an inspection, [reasons] is what setup would still do. */
    data class Incomplete(val reasons: List<String>) : ProvisioningStatus

    /** A different note type already owns the name `VoiceQA`. Nothing was written. */
    data class Conflict(val differences: List<String>) : ProvisioningStatus

    /** AnkiDroid could not be reached, or did not answer. */
    data class Failed(val failure: Failure) : ProvisioningStatus
}

/**
 * What provisioning found and did. [status] is the verdict; the steps say how far it got,
 * so a partial run is described without a separate outcome for every combination.
 */
data class ProvisioningReport(
    val status: ProvisioningStatus,
    val noteType: ProvisioningStep = ProvisioningStep.NOT_ATTEMPTED,
    val demoDeck: ProvisioningStep = ProvisioningStep.NOT_ATTEMPTED,
    val demoNotes: ProvisioningStep = ProvisioningStep.NOT_ATTEMPTED,
    val notesAdded: Int = 0,
) {
    /**
     * True when work remains. From [Provisioner.inspect] that is exactly the case where
     * running setup would write to the collection, so the learner must be told first.
     */
    val workRemains: Boolean get() = status is ProvisioningStatus.Incomplete
}

interface Provisioner {
    /** Reads the collection and reports what setup would do. Never writes. */
    fun inspect(callback: (ProvisioningReport) -> Unit)

    /**
     * Installs whatever is missing. [fullSyncAccepted] is the learner's answer to the
     * disclosure; without it a run that would write anything writes nothing.
     */
    fun provision(fullSyncAccepted: Boolean, callback: (ProvisioningReport) -> Unit)
}

/**
 * AV-039 provisioning: install the VoiceQA note type when it is missing, reuse it when its
 * ordered fields match, refuse when a different note type owns the name, and add the sample
 * notes to a new `VoiceQA Demo` deck.
 *
 * Every provider call runs on [worker]; only immutable reports cross to [delivery]. The
 * only writes are the ones AV-039 names: a `models` insert with its template update, a
 * `decks` insert, `notes` inserts, and a move of the cards this run just created when they
 * did not land in the demo deck. No existing note type, deck or note is modified, and no
 * review is ever submitted.
 */
class AnkiDroidProvisioning(
    private val platform: ProvisioningPlatform,
    private val worker: Executor,
    private val delivery: Executor,
    private val specification: VoiceQaNoteType = VoiceQaNoteType.load(),
) : Provisioner {
    override fun inspect(callback: (ProvisioningReport) -> Unit) =
        dispatch(callback) { survey().report }

    override fun provision(fullSyncAccepted: Boolean, callback: (ProvisioningReport) -> Unit) =
        dispatch(callback) { install(fullSyncAccepted) }

    /** What the collection holds now, and the report that describes it. */
    private class Survey(
        val report: ProvisioningReport,
        val modelId: Long? = null,
        val demoDeckId: Long? = null,
    )

    private fun survey(): Survey {
        accessFailure()?.let { return Survey(failed(it)) }
        val models = platform.queryNoteTypes() ?: return Survey(unavailable())
        val named = models.filter { it.name == specification.name }
        if (named.size > 1) {
            return Survey(conflict(listOf(
                "${named.size} note types are named ${specification.name}; " +
                    "AnkiVoice cannot tell them apart. Nothing was changed.",
            )))
        }
        val installed = named.singleOrNull()
        if (installed != null && installed.fieldNames != specification.fieldNames) {
            return Survey(conflict(fieldDifferences(installed.fieldNames)).copy(
                noteType = ProvisioningStep.PRESENT,
            ))
        }
        val decks = platform.queryDecks() ?: return Survey(unavailable())
        val demoDeck = decks.firstOrNull { it.name == specification.demoDeckName }
        val reasons = buildList {
            if (installed == null) add("Install the ${specification.name} note type.")
            if (demoDeck == null) {
                add("Add the ${specification.demoDeckName} deck and its " +
                    "${specification.demoNotes.size} sample notes.")
            }
        }
        return Survey(
            report = ProvisioningReport(
                status = if (reasons.isEmpty()) ProvisioningStatus.Complete
                else ProvisioningStatus.Incomplete(reasons),
                noteType = if (installed == null) ProvisioningStep.NOT_ATTEMPTED else ProvisioningStep.PRESENT,
                demoDeck = if (demoDeck == null) ProvisioningStep.NOT_ATTEMPTED else ProvisioningStep.PRESENT,
                demoNotes = if (demoDeck == null) ProvisioningStep.NOT_ATTEMPTED else ProvisioningStep.SKIPPED,
            ),
            modelId = installed?.id,
            demoDeckId = demoDeck?.id,
        )
    }

    private fun install(fullSyncAccepted: Boolean): ProvisioningReport {
        // Re-read rather than trusting an earlier inspection: the collection may have moved.
        val survey = survey()
        if (!survey.report.workRemains) return survey.report
        if (!fullSyncAccepted) return survey.report.copy(status = ProvisioningStatus.Declined)
        // From here on every return keeps the progress made, including on a provider fault.
        var progress = survey.report
        return try {
            val deck = createDemoDeck(survey) ?: return progress.copy(status = failedNow())
            progress = progress.copy(demoDeck = deck.step)
            val modelId = survey.modelId ?: run {
                val created = platform.insertNoteType(
                    specification.name, specification.joinedFieldNames(), specification.css, deck.id,
                ) ?: return progress.copy(status = ProvisioningStatus.Incomplete(listOf(
                    "AnkiDroid did not return the new ${specification.name} note type." +
                        orphanedDeck(deck.step),
                )))
                platform.updateTemplate(created, PLACEHOLDER_TEMPLATE_ORDINAL, specification.template)
                progress = progress.copy(noteType = ProvisioningStep.CREATED)
                readBack(created)?.let { return progress.copy(status = it) }
                created
            }
            if (deck.step != ProvisioningStep.CREATED) {
                // The demo deck was already there, so demo content is skipped, not duplicated.
                progress.copy(status = ProvisioningStatus.Complete, demoNotes = ProvisioningStep.SKIPPED)
            } else {
                addDemoNotes(progress, modelId, deck.id)
            }
        } catch (_: SecurityException) {
            progress.copy(status = ProvisioningStatus.Failed(Failure(CardProviderFailure.ACCESS_DENIED)))
        } catch (_: RuntimeException) {
            progress.copy(status = failedNow())
        }
    }

    private class DemoDeck(val id: Long, val step: ProvisioningStep)

    /** Creates the demo deck, or resolves an existing one. Null when the provider went away. */
    private fun createDemoDeck(survey: Survey): DemoDeck? {
        survey.demoDeckId?.let { return DemoDeck(it, ProvisioningStep.PRESENT) }
        platform.insertDeck(specification.demoDeckName)?.let { return DemoDeck(it, ProvisioningStep.CREATED) }
        // A duplicate name is rejected. That means already provisioned, not broken.
        val decks = platform.queryDecks() ?: return null
        val existing = decks.firstOrNull { it.name == specification.demoDeckName } ?: return null
        return DemoDeck(existing.id, ProvisioningStep.PRESENT)
    }

    /** Null when the model AnkiDroid stored matches the fixture. */
    private fun readBack(modelId: Long): ProvisioningStatus? {
        val models = platform.queryNoteTypes() ?: return failedNow()
        val stored = models.firstOrNull { it.id == modelId } ?: return ProvisioningStatus.Incomplete(listOf(
            "The new note type was not readable through models after it was written.",
        ))
        val templates = platform.queryTemplates(modelId) ?: return failedNow()
        val template = templates.getOrNull(PLACEHOLDER_TEMPLATE_ORDINAL)
        val expected = specification.template
        val differences = buildList {
            if (stored.name != specification.name) {
                add("AnkiDroid stored the note type as ${stored.name}, not ${specification.name}.")
            }
            if (stored.fieldNames != specification.fieldNames) addAll(fieldDifferences(stored.fieldNames))
            if (templates.size != 1) {
                add("The note type has ${templates.size} card templates; AV-039 installs one.")
            }
            if (template == null) {
                add("The ${expected.name} template was not readable after it was written.")
            } else {
                if (template.name != expected.name) {
                    add("The template is named ${template.name}, not ${expected.name}.")
                }
                if (template.front != expected.front) add("The stored front template differs from the fixture.")
                if (template.back != expected.back) add("The stored back template differs from the fixture.")
            }
        }
        if (differences.isEmpty()) return null
        return ProvisioningStatus.Incomplete(
            differences + "Inspect ${specification.name} in AnkiDroid's note type manager.",
        )
    }

    private fun addDemoNotes(progress: ProvisioningReport, modelId: Long, deckId: Long): ProvisioningReport {
        var added = 0
        for (note in specification.demoNotes) {
            val noteId = platform.insertNote(
                modelId, note.fields.joinToString(VoiceQaNoteType.FIELD_SEPARATOR), note.tags,
            ) ?: return incompleteNotes(progress, added)
            // A note lands in its note type's default deck, which is the demo deck only when
            // this run created the note type. Place, then read every card back.
            val cards = platform.queryNoteCards(noteId)
                ?: return progress.copy(status = failedNow(), demoNotes = ProvisioningStep.CREATED, notesAdded = added)
            for (card in cards) {
                if (card.deckId != deckId) platform.moveCard(noteId, card.ordinal, deckId)
            }
            val placed = platform.queryNoteCards(noteId)
                ?: return progress.copy(status = failedNow(), demoNotes = ProvisioningStep.CREATED, notesAdded = added)
            val fields = platform.queryNoteFields(noteId)
                ?: return progress.copy(status = failedNow(), demoNotes = ProvisioningStep.CREATED, notesAdded = added)
            if (placed.isEmpty() || placed.any { it.deckId != deckId } || fields != note.fields) {
                return incompleteNotes(progress, added)
            }
            added++
        }
        return progress.copy(
            status = ProvisioningStatus.Complete,
            demoNotes = ProvisioningStep.CREATED,
            notesAdded = added,
        )
    }

    private fun incompleteNotes(progress: ProvisioningReport, added: Int) = progress.copy(
        status = ProvisioningStatus.Incomplete(listOf(
            "Only $added of ${specification.demoNotes.size} sample notes reached the " +
                "${specification.demoDeckName} deck. Setup skips demo content while that deck " +
                "exists, so delete it in AnkiDroid and run setup again to add them.",
        )),
        demoNotes = ProvisioningStep.CREATED,
        notesAdded = added,
    )

    private fun fieldDifferences(stored: List<String>): List<String> = listOf(
        "${specification.name} already exists with the fields ${stored.joinToString(", ")}.",
        "AnkiVoice needs ${specification.fieldNames.joinToString(", ")}, in that order.",
        "Nothing was changed. Rename or remove that note type in AnkiDroid and run setup again, " +
            "or keep it and leave AnkiVoice unprovisioned.",
    )

    private fun orphanedDeck(step: ProvisioningStep): String =
        if (step != ProvisioningStep.CREATED) "" else
            " The empty ${specification.demoDeckName} deck it created can be deleted in AnkiDroid."

    private fun accessFailure(): Failure? = when {
        !platform.packageAvailable() -> Failure(CardProviderFailure.PACKAGE_UNAVAILABLE)
        platform.apiEnabled() == false -> Failure(CardProviderFailure.API_DISABLED)
        !platform.databasePermissionGranted() -> Failure(CardProviderFailure.ACCESS_DENIED)
        else -> null
    }

    // Re-check after a null or an exception: permissions and component state change mid-run.
    private fun failedNow() = ProvisioningStatus.Failed(
        accessFailure() ?: Failure(CardProviderFailure.NULL_CURSOR),
    )

    private fun failed(failure: Failure) = ProvisioningReport(ProvisioningStatus.Failed(failure))
    private fun conflict(differences: List<String>) = ProvisioningReport(ProvisioningStatus.Conflict(differences))
    private fun unavailable() = ProvisioningReport(failedNow())

    private fun dispatch(callback: (ProvisioningReport) -> Unit, operation: () -> ProvisioningReport) {
        worker.execute {
            val result = try {
                operation()
            } catch (_: SecurityException) {
                failed(Failure(CardProviderFailure.ACCESS_DENIED))
            } catch (_: RuntimeException) {
                // A provider or binder fault cannot masquerade as successful provisioning.
                try {
                    unavailable()
                } catch (_: RuntimeException) {
                    failed(Failure(CardProviderFailure.NULL_CURSOR))
                }
            }
            delivery.execute { callback(result) }
        }
    }

    private companion object {
        /** A `models` insert creates NUM_CARDS placeholder templates; AV-039 asks for one. */
        const val PLACEHOLDER_TEMPLATE_ORDINAL = 0
    }
}
