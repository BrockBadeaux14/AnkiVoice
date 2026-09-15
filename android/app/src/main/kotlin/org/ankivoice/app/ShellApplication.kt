package org.ankivoice.app

import android.app.Application
import android.content.Context
import java.util.concurrent.Executors
import org.ankivoice.ankidroid.AndroidAccessPlatform
import org.ankivoice.ankidroid.AnkiDroidAccess
import org.ankivoice.ankidroid.AnkiDroidProvisioning
import org.ankivoice.core.contracts.ForegroundEventPort

/** Process-owned composition root; retains the shell through Activity recreation. */
class ShellApplication : Application() {
    internal lateinit var controller: ShellController
        private set
    internal val foregroundEvents: ForegroundEventPort get() = controller

    override fun onCreate() {
        super.onCreate()
        // One serial worker for every AnkiDroid call, so a deck read and a provisioning
        // write can never run against the collection at the same time.
        val platform = AndroidAccessPlatform(this)
        val worker = Executors.newSingleThreadExecutor()
        controller = ShellController(
            AnkiDroidAccess(platform, worker, mainExecutor),
            PrivateShellSettings(this),
            AnkiDroidProvisioning(platform, worker, mainExecutor),
            ::previewCardProvider,
        )
    }
}

private class PrivateShellSettings(context: Context) : ShellSettings {
    private val preferences = context.getSharedPreferences("shell", Context.MODE_PRIVATE)
    override var selectedDeckId: Long?
        get() = if (preferences.contains("selected_deck")) preferences.getLong("selected_deck", 0) else null
        set(value) {
            preferences.edit().apply {
                if (value == null) remove("selected_deck") else putLong("selected_deck", value)
            }.apply()
        }
    override var language: String
        get() = preferences.getString("language", "en-US") ?: "en-US"
        set(value) { preferences.edit().putString("language", value).apply() }
}
