package org.ankivoice.app

import java.util.concurrent.Executor
import org.ankivoice.provider.CredentialStore
import org.ankivoice.provider.Diagnostics
import org.ankivoice.provider.GradingProvider
import org.ankivoice.provider.LedgerStop
import org.ankivoice.provider.ProviderOutcome
import org.ankivoice.provider.ProviderSettings
import org.ankivoice.provider.QuotaLedger
import org.ankivoice.provider.SessionStart

/** The learner's provider settings, held in `:app`'s private preferences. */
internal interface AppProviderSettings : ProviderSettings {
    /** #29's report needs study content; this opt-in is off by default and clearable. */
    var retainContent: Boolean
}

internal data class ProviderState(
    val keyPresent: Boolean = false,
    val disclosureAcknowledged: Boolean = false,
    val dailyLimit: Int = QuotaLedger.DEFAULT_DAILY_LIMIT,
    val sessionRemaining: Int = QuotaLedger.SESSION_LIMIT,
    val dailyRemaining: Int = QuotaLedger.DEFAULT_DAILY_LIMIT,
    val stop: LedgerStop? = null,
    val retainContent: Boolean = false,
    val diagnostics: List<String> = emptyList(),
    val busy: Boolean = false,
    /** One line of feedback for the last action. It never contains the key. */
    val message: String? = null,
) {
    /** Grading needs a key and an acknowledged disclosure; self-grading never does. */
    val gradingConfigured: Boolean get() = keyPresent && disclosureAcknowledged
}

/**
 * AV-020's settings surface: runtime credential entry, the retention disclosure, the
 * configurable daily limit and the content-free diagnostics.
 *
 * Network work runs on [worker] and results come back on [delivery], as in AV-023's deck
 * access. Nothing here submits or implies a rating, and no state ever holds the key: the
 * UI only learns whether one is saved.
 */
internal class ProviderController(
    private val credentials: CredentialStore,
    private val settings: AppProviderSettings,
    private val ledger: QuotaLedger,
    private val diagnostics: Diagnostics,
    private val grading: GradingProvider,
    private val worker: Executor,
    private val delivery: Executor,
) {
    var state = ProviderState()
        private set
    var observer: ((ProviderState) -> Unit)? = null

    init {
        diagnostics.retainContent = settings.retainContent
    }

    fun refresh(message: String? = null, busy: Boolean = false) {
        val allowance = ledger.allowance(SETTINGS_SESSION, settings.dailyLimit)
        publish(
            ProviderState(
                keyPresent = credentials.present(),
                disclosureAcknowledged = settings.disclosureAcknowledged,
                dailyLimit = settings.dailyLimit,
                sessionRemaining = allowance.sessionRemaining,
                dailyRemaining = allowance.dailyRemaining,
                stop = allowance.stop,
                retainContent = settings.retainContent,
                diagnostics = diagnostics.entries().map(Diagnostics.Entry::toString),
                busy = busy,
                message = message,
            ),
        )
    }

    /** Replacing the key always re-arms the disclosure, which #17 requires. */
    fun saveKey(entered: String) {
        if (!credentials.save(entered)) {
            refresh("That is not an OpenRouter key. Nothing was saved.")
            return
        }
        settings.disclosureAcknowledged = false
        diagnostics.record("credentialSaved")
        refresh("Key saved. Read what is sent, then turn grading on.")
    }

    fun clearKey() {
        credentials.clear()
        settings.disclosureAcknowledged = false
        diagnostics.record("credentialCleared")
        refresh("Key removed. AI grading is off; self-grading stays available.")
    }

    fun acknowledgeDisclosure() {
        if (!credentials.present()) {
            refresh("Add a key first. Self-grading works without one.")
            return
        }
        settings.disclosureAcknowledged = true
        diagnostics.record("disclosureAcknowledged")
        refresh("AI grading is on. Every rating still needs your confirmation.")
    }

    fun setDailyLimit(limit: Int) {
        if (!QuotaLedger.isValidDailyLimit(limit)) {
            refresh("Choose a daily limit between 0 and ${QuotaLedger.MAX_DAILY_LIMIT}.")
            return
        }
        settings.dailyLimit = limit
        refresh("Daily limit set to $limit requests.")
    }

    fun setRetainContent(retain: Boolean) {
        settings.retainContent = retain
        diagnostics.retainContent = retain
        if (!retain) diagnostics.clearRetainedContent()
        refresh(
            if (retain) "Transcripts and card text are kept for the pilot report until you clear them."
            else "Kept study content was cleared. Diagnostics stay content-free.",
        )
    }

    fun clearDiagnostics() {
        diagnostics.clear()
        refresh("Diagnostics cleared.")
    }

    /** The pre-session zero-price check. It is not a grading request and reserves nothing. */
    fun checkRoute() = background {
        when (val start = grading.startSession(SETTINGS_SESSION)) {
            is SessionStart.Ready -> "Free route verified: ${start.endpointName}. ${start.allowance.dailyRemaining} requests left today."
            is SessionStart.Unavailable -> listOf(start.cause.reason, start.detail).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }

    /**
     * One live request with a fixed sample, for the recorded smoke run. It carries no
     * card, transcript or collection data, and it consumes the ledger like any request.
     */
    fun sendSmokeRequest() = background {
        when (val start = grading.startSession(SETTINGS_SESSION)) {
            is SessionStart.Unavailable -> listOf(start.cause.reason, start.detail).filter { it.isNotEmpty() }.joinToString(" ")
            is SessionStart.Ready -> when (val outcome = grading.request(SETTINGS_SESSION, SMOKE_SYSTEM, SMOKE_USER)) {
                is ProviderOutcome.Content -> "The route answered at a verified zero cost. ${outcome.text.take(80)}"
                is ProviderOutcome.Failed -> "No grade: ${outcome.failure}. Self-grading stays available."
            }
        }
    }

    private fun background(operation: () -> String) {
        if (state.busy) return
        refresh(busy = true)
        worker.execute {
            val message = try {
                operation()
            } catch (error: RuntimeException) {
                "The check could not run: ${error.javaClass.simpleName}"
            }
            delivery.execute { refresh(message) }
        }
    }

    private fun publish(next: ProviderState) {
        state = next
        observer?.invoke(next)
    }

    private companion object {
        /** Settings checks share one session id, so they cannot spend 30 session slots each. */
        const val SETTINGS_SESSION = "settings"
        const val SMOKE_SYSTEM = "Reply with only the JSON object {\"status\":\"ok\"} and nothing else."
        const val SMOKE_USER = "{\"check\":\"AnkiVoice AV-020 route smoke test\"}"
    }
}
