package org.ankivoice.ankidroid

import android.Manifest
import android.content.ContentValues
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper

/** Pinned AnkiDroid 2.24.1's public package, permission and deck-only resolver route. */
class AndroidAccessPlatform(private val context: Context) : AccessPlatform {
    companion object {
        const val PACKAGE = "com.ichi2.anki"
        const val DATABASE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        private const val AUTHORITY = "com.ichi2.anki.flashcards"
        private val DECKS = Uri.parse("content://$AUTHORITY/decks")
        private val SELECTED_DECK = Uri.parse("content://$AUTHORITY/selected_deck")
        private val PROJECTION = arrayOf("deck_id", "deck_name")
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

    private fun requireWorker() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "AnkiDroid access must run off the main thread" }
    }
}
