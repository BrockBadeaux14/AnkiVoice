package org.ankivoice.provider

import android.content.Context
import java.io.File

/**
 * Provider access, assembled for `:app`'s composition root.
 *
 * AV-022 gives this module the app's only network route (#17), and it is the only module
 * declaring `android.permission.INTERNET`. It holds the Keystore credential store, the
 * route guards, the durable quota ledger and budget, and the content-free diagnostics.
 * #18's [SemanticGrader] adds the grading instruction, the reply validation and the label
 * policy on top of [GradingProvider.request]; AV-043 adds the paid fallback behind the
 * same seam.
 */
object ProviderModule {
    /** App-private, and excluded from backup and device transfer with the credential. */
    const val LEDGER_FILE: String = "av020-quota-ledger.jsonl"

    fun credentialStore(context: Context): CredentialStore = KeystoreCredentialStore(context)

    fun ledger(context: Context): QuotaLedger = QuotaLedger(File(context.applicationContext.filesDir, LEDGER_FILE))

    /** The live routes. The HTTPS transport is the only connection this app opens. */
    fun grading(
        credentials: CredentialStore,
        ledger: QuotaLedger,
        settings: ProviderSettings,
        diagnostics: Diagnostics,
    ): GradingProvider = GradingProvider(credentials, ledger, settings, HttpsUrlTransport(), diagnostics)

    /** The pinned free endpoint, for the settings and disclosure screens. */
    val freeRouteDescription: String get() = "${FreeRoute.MODEL} through ${FreeRoute.PROVIDER}, at a verified \$0"

    /** The pinned paid endpoint and its listed prices, for the settings and disclosure screens. */
    val paidRouteDescription: String
        get() {
            val pin = PaidRoute.PINNED
            return "${pin.model} through ${pin.provider}, listed at \$${pin.promptUsdPerMillion.toPlainString()} per million prompt tokens " +
                "and \$${pin.completionUsdPerMillion.toPlainString()} per million completion tokens; at most " +
                "\$${QuotaLedger.formatUsd(pin.ceilingUsd, 4)} is held per request"
        }

    /** What the shipped route is: free first, then paid within the daily cap. */
    val routeDescription: String
        get() = "$freeRouteDescription first; if that route is refused, unavailable, times out or fails, " +
            "$paidRouteDescription, within today's paid budget"
}
