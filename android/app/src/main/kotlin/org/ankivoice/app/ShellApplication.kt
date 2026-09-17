package org.ankivoice.app

import android.app.Application
import android.content.Context
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.ankivoice.ankidroid.AndroidAccessPlatform
import org.ankivoice.ankidroid.AnkiDroidCardProvider
import org.ankivoice.ankidroid.AnkiDroidAccess
import org.ankivoice.ankidroid.AnkiDroidProvisioning
import org.ankivoice.ankidroid.AnkiDroidReviewTransport
import org.ankivoice.core.commands.CommandRouter
import org.ankivoice.core.contracts.Capabilities
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.ForegroundEventPort
import org.ankivoice.core.contracts.Grader
import org.ankivoice.core.contracts.GuardedReviewWriter
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.provider.Diagnostics
import org.ankivoice.provider.ProviderModule
import org.ankivoice.provider.QuotaLedger
import org.ankivoice.speech.AndroidSpeechPlatform
import org.ankivoice.speech.SpeechModule
import org.ankivoice.speech.SpeechReadiness

/** Process-owned composition root; retains the shell through Activity recreation. */
class ShellApplication : Application() {
    internal lateinit var controller: ShellController
        private set
    internal lateinit var provider: ProviderController
        private set

    /** AV-014's debug-grade command surface. #27 replaces it with the real study screen. */
    internal lateinit var commands: CommandController
        private set

    /** Both controllers see every foreground change; neither may miss one. */
    internal val foregroundEvents: ForegroundEventPort
        get() = ForegroundEventPort { event ->
            controller.onForegroundEvent(event)
            commands.onForegroundEvent(event)
        }

    override fun onCreate() {
        super.onCreate()
        // One serial worker for every AnkiDroid call, so a deck read and a provisioning
        // write can never run against the collection at the same time.
        val platform = AndroidAccessPlatform(this)
        val worker = Executors.newSingleThreadExecutor()
        // AV-025: the speech half of the support matrix. Resolving the engine can block, so
        // it runs on a worker of its own rather than delaying every AnkiDroid call.
        val speechReadiness = SpeechReadiness(AndroidSpeechPlatform(this))
        val speechWorker = Executors.newSingleThreadExecutor()
        val settingsStore = PrivateShellSettings(this)
        // AV-018: the journal's own I/O worker. Journal reads, appends and pruning never
        // run on the main thread, and never share the collection's serial worker either,
        // so a flush cannot delay a deck read.
        val journalWorker = Executors.newSingleThreadExecutor()
        val journal = JournalAccess(JournalModule.journal(filesDir), journalWorker, mainExecutor)
        // AV-045: one reconciliation gate for the process. The readiness preview and the
        // study session both go through it, so neither can offer a card around the other.
        val gate = ReconciliationGate(journal, UUID.randomUUID().toString())
        val cards = { settingsStore.selectedDeckId?.let { AnkiDroidCardProvider(platform, it, worker, mainExecutor) } }
        // AV-020: :provider owns the only network route. Its work never runs on the main
        // thread, and the study session's grading shares this one worker with settings.
        val providerSettings = PrivateProviderSettings(this)
        val diagnostics = Diagnostics()
        val credentials = ProviderModule.credentialStore(this)
        val ledger = ProviderModule.ledger(this)
        val providerWorker = Executors.newSingleThreadExecutor()
        controller = ShellController(
            SpeechAwareAccess(
                AnkiDroidAccess(platform, worker, mainExecutor),
                speechWorker,
                mainExecutor,
            ) { speechReadiness.check(settingsStore.language) },
            settingsStore,
            AnkiDroidProvisioning(platform, worker, mainExecutor),
            worker, mainExecutor,
            gate,
        ) { deckId -> AnkiDroidCardProvider(platform, deckId, worker, mainExecutor) }
        // AV-014: the command surface drives a real session on a thread of its own, because
        // AV-025's transport blocks its caller for the whole of playback and capture.
        commands = CommandController(
            CommandSessionFactory {
                // A fresh GradingProvider per session: #17 holds a refused key or route
                // for the rest of the session and clears it only at the next one.
                val grading = ProviderModule.grading(credentials, ledger, providerSettings, diagnostics)
                commandSession(platform, settingsStore, worker, mainExecutor, applicationContext) { sessionId, revision ->
                    StudyGrader(grading, sessionId, revision, providerWorker)
                }
            },
            Executors.newSingleThreadExecutor(),
            mainExecutor,
            providerWorker,
            gate,
            cards,
        )
        provider = ProviderController(
            credentials,
            providerSettings,
            ledger,
            diagnostics,
            ProviderModule.grading(credentials, ledger, providerSettings, diagnostics),
            providerWorker,
            mainExecutor,
        )
        provider.refresh()
    }
}

/**
 * One study session over the real collection, the real AV-025 transport and AV-045's
 * grader: #16's rules on device, then #18's semantic grader on a rule miss.
 *
 * The **real** guarded writer is wired in deliberately. No command path reaches it, and
 * that is the property the pinned-AVD check is meant to demonstrate — a writer that could
 * not write would prove nothing. #21 wraps it in the journal when it adds the commit step.
 */
private fun commandSession(
    platform: AndroidAccessPlatform,
    settings: ShellSettings,
    worker: Executor,
    delivery: Executor,
    context: Context,
    grader: (sessionId: String, revision: AtomicInteger) -> Grader,
): Result<CommandSession> {
    val deckId = settings.selectedDeckId
        ?: return Result.failure(CommandSessionUnavailable(Failure(CardProviderFailure.DECK_MISSING, "no deck is selected")))
    val provider = AnkiDroidCardProvider(platform, deckId, worker, delivery)
    val capabilities = provider.capabilities()
    if (capabilities !is Capabilities) {
        return Result.failure(CommandSessionUnavailable(capabilities as Failure))
    }
    val speech = SpeechModule.create(context)
    val sessionId = UUID.randomUUID().toString()
    val revision = AtomicInteger()
    val studyGrader = grader(sessionId, revision)
    val session = ReviewSession(
        provider = provider,
        speechOutput = speech,
        speechInput = speech,
        grader = studyGrader,
        writer = GuardedReviewWriter(provider, AnkiDroidReviewTransport(platform), capabilities),
        capabilities = capabilities,
        language = settings.language,
        sessionId = sessionId,
    )
    return Result.success(
        CommandSession(
            session = session,
            router = CommandRouter(session, speech),
            grader = studyGrader,
            revision = revision,
            speech = speech,
            language = settings.language,
            partial = speech::lastPartial,
            release = speech::releaseAll,
        ),
    )
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
    // AV-043: the paid route's daily cap, kept as its exact decimal text; an unreadable
    // value falls back to the default rather than to an unbounded budget.
    override var dailyCapUsd: BigDecimal
        get() = preferences.getString("daily_cap_usd", null)?.let(QuotaLedger::parseDailyCap) ?: QuotaLedger.DEFAULT_DAILY_CAP_USD
        set(value) {
            if (QuotaLedger.isValidDailyCap(value)) preferences.edit().putString("daily_cap_usd", value.toPlainString()).apply()
        }
    override var retainContent: Boolean
        get() = preferences.getBoolean("retain_content", false)
        set(value) { preferences.edit().putBoolean("retain_content", value).apply() }
}
