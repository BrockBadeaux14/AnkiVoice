package org.ankivoice.app

import android.app.Application
import android.content.Context
import java.util.concurrent.Executors
import org.ankivoice.ankidroid.AndroidAccessPlatform
import org.ankivoice.ankidroid.AnkiDroidAccess
import org.ankivoice.core.contracts.ForegroundEventPort
import org.ankivoice.provider.Diagnostics
import org.ankivoice.provider.ProviderModule
import org.ankivoice.provider.QuotaLedger

/** Process-owned composition root; retains the shell through Activity recreation. */
class ShellApplication : Application() {
    internal lateinit var controller: ShellController
        private set
    internal lateinit var provider: ProviderController
        private set
    internal val foregroundEvents: ForegroundEventPort get() = controller

    override fun onCreate() {
        super.onCreate()
        controller = ShellController(
            AnkiDroidAccess(AndroidAccessPlatform(this), Executors.newSingleThreadExecutor(), mainExecutor),
            PrivateShellSettings(this),
            ::previewCardProvider,
        )
        // AV-020: :provider owns the only network route. Its work never runs on the main thread.
        val settings = PrivateProviderSettings(this)
        val diagnostics = Diagnostics()
        val credentials = ProviderModule.credentialStore(this)
        val ledger = ProviderModule.ledger(this)
        provider = ProviderController(
            credentials,
            settings,
            ledger,
            diagnostics,
            ProviderModule.grading(credentials, ledger, settings, diagnostics),
            Executors.newSingleThreadExecutor(),
            mainExecutor,
        )
        provider.refresh()
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

/**
 * AV-020's non-secret provider settings, in AV-023's private store. The credential itself
 * lives in the Keystore-wrapped store, never here.
 */
private class PrivateProviderSettings(context: Context) : AppProviderSettings {
    private val preferences = context.getSharedPreferences("shell", Context.MODE_PRIVATE)
    override var dailyLimit: Int
        get() = QuotaLedger.validDailyLimit(preferences.getInt("daily_limit", QuotaLedger.DEFAULT_DAILY_LIMIT))
        set(value) {
            if (QuotaLedger.isValidDailyLimit(value)) preferences.edit().putInt("daily_limit", value).apply()
        }
    override var disclosureAcknowledged: Boolean
        get() = preferences.getBoolean("disclosure_acknowledged", false)
        set(value) { preferences.edit().putBoolean("disclosure_acknowledged", value).apply() }
    override var retainContent: Boolean
        get() = preferences.getBoolean("retain_content", false)
        set(value) { preferences.edit().putBoolean("retain_content", value).apply() }
}
