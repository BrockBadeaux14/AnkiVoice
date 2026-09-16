package org.ankivoice.ankidroid

import android.Manifest
import android.content.ContentValues
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Looper

/**
 * Pinned AnkiDroid 2.24.1's public package, permission and resolver route: deck reads and
 * the verified `selected_deck` update for AV-023, and AV-039's provisioning calls. Every
 * column name below is `FlashCardsContract` 2.24.1's, and the deck and model columns are
 * the ones AV-004 measured on the pinned emulator.
 */
class AndroidAccessPlatform(private val context: Context) : ProvisioningPlatform {
    companion object {
        const val PACKAGE = "com.ichi2.anki"
        const val DATABASE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        private const val AUTHORITY = "com.ichi2.anki.flashcards"
        private const val BASE = "content://$AUTHORITY"
        private val DECKS = Uri.parse("$BASE/decks")
        private val SELECTED_DECK = Uri.parse("$BASE/selected_deck")
        private val MODELS = Uri.parse("$BASE/models")
        private val NOTES = Uri.parse("$BASE/notes")
        private val PROJECTION = arrayOf("deck_id", "deck_name")
        private val MODEL_PROJECTION = arrayOf("_id", "name", "field_names")
        private val TEMPLATE_PROJECTION = arrayOf("card_template_name", "question_format", "answer_format")
        private val NOTE_CARD_PROJECTION = arrayOf("ord", "deck_id")
        private val NOTE_PROJECTION = arrayOf("flds")

        /** AnkiDroid joins field names and note fields with 0x1f. */
        private fun split(joined: String?): List<String> =
            joined.orEmpty().split(VoiceQaNoteType.FIELD_SEPARATOR)
    }

    override fun packageAvailable(): Boolean {
        requireWorker()
        return try {
            context.packageManager.getApplicationInfo(
                PACKAGE, PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
            ).enabled
        } catch (_: PackageManager.NameNotFoundException) { false }
    }

    override fun apiEnabled(): Boolean? {
        requireWorker()
        // In 2.24.1 the API switch enables/disables CardContentProvider itself.
        // Include disabled components so an absent/unknown provider stays unknown.
        val manager = context.packageManager
        val provider = manager.getPackageInfo(
            PACKAGE, PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS).toLong(),
            ),
        ).providers?.firstOrNull { AUTHORITY in it.authority.orEmpty().split(';') } ?: return null
        // ProviderInfo.enabled is the manifest default, not the user's component
        // override. resolveContentProvider may omit a disabled provider entirely.
        return when (manager.getComponentEnabledSetting(ComponentName(provider.packageName, provider.name))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> provider.enabled
        }
    }

    override fun databasePermissionGranted(): Boolean = granted(DATABASE_PERMISSION)
    override fun microphonePermissionGranted(): Boolean = granted(Manifest.permission.RECORD_AUDIO)

    private fun granted(permission: String): Boolean {
        requireWorker()
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun queryDecks(): List<Deck>? = query(DECKS)
    override fun querySelectedDeck(): List<Deck>? = query(SELECTED_DECK)

    private fun query(uri: Uri): List<Deck>? {
        requireWorker()
        return context.contentResolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow("deck_id")
            val name = cursor.getColumnIndexOrThrow("deck_name")
            buildList {
                while (cursor.moveToNext()) add(Deck(cursor.getLong(id), cursor.getString(name)))
            }
        }
    }

    override fun updateSelectedDeck(deckId: Long): Int {
        requireWorker()
        return context.contentResolver.update(
            SELECTED_DECK, ContentValues().apply { put("deck_id", deckId) }, null, null,
        )
    }

    override fun queryNoteTypes(): List<InstalledNoteType>? = read(MODELS, MODEL_PROJECTION) { cursor ->
        InstalledNoteType(cursor.getLong(0), cursor.getString(1), split(cursor.getString(2)))
    }

    override fun queryTemplates(modelId: Long): List<InstalledTemplate>? =
        read(Uri.parse("$BASE/models/$modelId/templates"), TEMPLATE_PROJECTION) { cursor ->
            InstalledTemplate(cursor.getString(0), cursor.getString(1), cursor.getString(2))
        }

    override fun insertNoteType(name: String, fieldNames: String, css: String, deckId: Long): Long? {
        requireWorker()
        // NUM_CARDS = 1 adds the single placeholder template that updateTemplate then names
        // and formats. Inserting a second template needs an accepted full sync, so AV-039
        // never asks for one. DECK_ID makes the demo deck this note type's default deck.
        val values = ContentValues().apply {
            put("name", name)
            put("field_names", fieldNames)
            put("num_cards", 1)
            put("css", css)
            put("deck_id", deckId)
            put("sort_field_index", 0)
            put("type", 0)
        }
        return context.contentResolver.insert(MODELS, values)?.lastPathSegment?.toLongOrNull()
    }

    override fun updateTemplate(modelId: Long, ordinal: Int, template: VoiceQaTemplate): Int {
        requireWorker()
        // 2.24.1 rejects model_id and ord in the values; they are read from the URI.
        val values = ContentValues().apply {
            put("card_template_name", template.name)
            put("question_format", template.front)
            put("answer_format", template.back)
        }
        return context.contentResolver.update(
            Uri.parse("$BASE/models/$modelId/templates/$ordinal"), values, null, null,
        )
    }

    override fun insertDeck(name: String): Long? {
        requireWorker()
        return try {
            context.contentResolver.insert(
                DECKS, ContentValues().apply { put("deck_name", name) },
            )?.lastPathSegment?.toLongOrNull()
        } catch (_: IllegalArgumentException) {
            null // 2.24.1 rejects a duplicate deck name this way.
        }
    }

    override fun insertNote(modelId: Long, fields: String, tags: String): Long? {
        requireWorker()
        val values = ContentValues().apply {
            put("mid", modelId)
            put("flds", fields)
            put("tags", tags)
        }
        return context.contentResolver.insert(NOTES, values)?.lastPathSegment?.toLongOrNull()
    }

    override fun queryNoteFields(noteId: Long): List<String>? {
        // A valid cursor with no row means the note is gone, which is not a null cursor.
        val rows = read(Uri.withAppendedPath(NOTES, noteId.toString()), NOTE_PROJECTION) {
            split(it.getString(0))
        } ?: return null
        return rows.firstOrNull() ?: emptyList()
    }

    override fun queryNoteCards(noteId: Long): List<InstalledCard>? =
        read(Uri.parse("$BASE/notes/$noteId/cards"), NOTE_CARD_PROJECTION) { cursor ->
            InstalledCard(cursor.getInt(0), cursor.getLong(1))
        }

    override fun moveCard(noteId: Long, ordinal: Int, deckId: Long): Int {
        requireWorker()
        return context.contentResolver.update(
            Uri.parse("$BASE/notes/$noteId/cards/$ordinal"),
            ContentValues().apply { put("deck_id", deckId) }, null, null,
        )
    }

    private fun <T> read(uri: Uri, projection: Array<String>, row: (Cursor) -> T): List<T>? {
        requireWorker()
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(row(cursor)) }
        }
    }

    private fun requireWorker() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "AnkiDroid access must run off the main thread" }
    }
}
