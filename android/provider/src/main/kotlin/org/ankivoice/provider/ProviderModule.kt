package org.ankivoice.provider

import android.content.Context
import java.io.File

/**
 * Provider access, assembled for `:app`'s composition root.
 *
 * AV-022 gives this module the app's only network route (#17), and it is the only module
 * declaring `android.permission.INTERNET`. It holds the Keystore credential store, the
 * free-route guard, the durable quota ledger and the content-free diagnostics. #18's
 * [SemanticGrader] adds the grading instruction, the reply validation and the label
 * policy on top of [GradingProvider.request].
 */
object ProviderModule {
    /** App-private, and excluded from backup and device transfer with the credential. */
    const val LEDGER_FILE: String = "av020-quota-ledger.jsonl"

    fun credentialStore(context: Context): CredentialStore = KeystoreCredentialStore(context)

    fun ledger(context: Context): QuotaLedger = QuotaLedger(File(context.applicationContext.filesDir, LEDGER_FILE))

    /** The live route. The HTTPS transport is the only connection this app opens. */
    fun grading(
        credentials: CredentialStore,
        ledger: QuotaLedger,
        settings: ProviderSettings,
        diagnostics: Diagnostics,
    ): GradingProvider = GradingProvider(credentials, ledger, settings, HttpsUrlTransport(), diagnostics)

    /** What the pinned free route is, for the settings and disclosure screens. */
    val routeDescription: String get() = "${FreeRoute.MODEL} through ${FreeRoute.PROVIDER}, free endpoints only"
}
