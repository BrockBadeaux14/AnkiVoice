package org.ankivoice.app

import java.math.BigDecimal
import java.util.concurrent.Executor
import org.ankivoice.provider.CredentialStore
import org.ankivoice.provider.Diagnostics
import org.ankivoice.provider.GradingProvider
import org.ankivoice.provider.GradingRoute
import org.ankivoice.provider.LedgerStop
import org.ankivoice.provider.ProviderOutcome
import org.ankivoice.provider.ProviderSettings
import org.ankivoice.provider.QuotaLedger
import org.ankivoice.provider.RouteStart
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
    /** AV-043: the paid route's day — the owner's cap, what is spent so far, and any paid-only stop. */
    val dailyCapUsd: BigDecimal = QuotaLedger.DEFAULT_DAILY_CAP_USD,
    val spentTodayUsd: BigDecimal = BigDecimal.ZERO,
    val paidStop: LedgerStop? = null,
    val retainContent: Boolean = false,
    val diagnostics: List<String> = emptyList(),
    val busy: Boolean = false,
    /** One line of feedback for the last action. It never contains the key. */
    val message: String? = null,
) {
    /** Grading needs a key and an acknowledged disclosure; self-grading never does. */
    val gradingConfigured: Boolean get() = keyPresent && disclosureAcknowledged

    /** A cap of zero turns the paid route off. */
    val paidEnabled: Boolean get() = dailyCapUsd.signum() > 0
}

/**
 * AV-020's settings surface: runtime credential entry, the retention disclosure, the
 * configurable daily limit and the content-free diagnostics. AV-043 adds the paid route's
 * daily cap, today's spend and the paid route's own stop reason.
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
        val allowance = ledger.allowance(SETTINGS_SESSION, settings.dailyLimit, GradingRoute.FREE)
        val budget = ledger.budget(settings.dailyCapUsd)
        publish(
            ProviderState(
                keyPresent = credentials.present(),
                disclosureAcknowledged = settings.disclosureAcknowledged,
                dailyLimit = settings.dailyLimit,
                sessionRemaining = allowance.sessionRemaining,
                dailyRemaining = allowance.dailyRemaining,
                stop = allowance.stop,
                dailyCapUsd = settings.dailyCapUsd,
                spentTodayUsd = budget.spentTodayUsd,
                // A stop that applies to every route is already shown beside the request counters.
                paidStop = budget.stop?.takeIf { it != allowance.stop },
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

    /** AV-043: the paid route's daily cap in USD. Zero turns the paid route off. */
    fun setDailyCap(entered: String) {
        val cap = QuotaLedger.parseDailyCap(entered)
        if (cap == null) {
            refresh("Choose a daily paid budget from \$0 to \$${QuotaLedger.formatUsd(QuotaLedger.MAX_DAILY_CAP_USD)}, in dollars and cents.")
            return
        }
        settings.dailyCapUsd = cap
        diagnostics.record("dailyCapSet", detail = "usd=${QuotaLedger.formatUsd(cap)}")
        refresh(
            if (cap.signum() == 0) "Paid route off: the daily budget is \$0. The free route and self-grading stay available."
            else "Daily paid budget set to \$${QuotaLedger.formatUsd(cap)}. It is a ceiling, not a target; the free route is always tried first.",
        )
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

    /** The pre-session price checks for both routes. They are not grading requests and reserve nothing. */
    fun checkRoute() = background {
        when (val start = grading.startSession(SETTINGS_SESSION)) {
            is SessionStart.Ready -> {
                val budget = grading.budget()
                describe(start.routes) + " ${start.allowance.dailyRemaining} requests left today; " +
                    "\$${QuotaLedger.formatUsd(budget.spentTodayUsd, 4)} of \$${QuotaLedger.formatUsd(budget.capUsd)} spent."
            }
            is SessionStart.Unavailable ->
                if (start.routes.isEmpty()) listOf(start.cause.reason, start.detail).filter { it.isNotEmpty() }.joinToString(" ")
                else describe(start.routes)
        }
    }

    /**
     * One live request with a fixed sample over [route], for the recorded smoke runs. It
     * carries no card, transcript or collection data, and it consumes the ledger — and,
     * on the paid route, the budget — like any request.
     */
    fun sendSmokeRequest(route: GradingRoute = GradingRoute.FREE) = background {
        when (val start = grading.startSession(SETTINGS_SESSION)) {
            is SessionStart.Unavailable -> listOf(start.cause.reason, start.detail).filter { it.isNotEmpty() }.joinToString(" ")
            is SessionStart.Ready -> {
                val before = grading.budget().spentTodayUsd
                when (val outcome = grading.request(SETTINGS_SESSION, SMOKE_SYSTEM, SMOKE_USER, route = route)) {
                    is ProviderOutcome.Content -> {
                        val cost = when (outcome.route) {
                            GradingRoute.FREE -> "at a verified zero cost"
                            GradingRoute.PAID -> "for \$${QuotaLedger.formatUsd(grading.budget().spentTodayUsd - before, 4)}"
                        }
                        "The ${outcome.route.specName} route answered $cost. ${outcome.text.take(80)}"
                    }
                    is ProviderOutcome.Failed -> "No grade from the ${route.specName} route: ${outcome.failure}. Self-grading stays available."
                }
            }
        }
    }

    private fun describe(routes: List<RouteStart>): String = routes.joinToString(" ") { route ->
        val name = when (route.route) {
            GradingRoute.FREE -> "Free route"
            GradingRoute.PAID -> "Paid route"
        }
        if (route.ready) "$name verified: ${route.endpointName}."
        else listOf("$name off:", route.cause?.reason.orEmpty(), route.detail).filter { it.isNotEmpty() }.joinToString(" ")
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
