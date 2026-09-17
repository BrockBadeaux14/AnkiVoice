package org.ankivoice.app

import android.app.Application
import android.content.Context
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.ankivoice.ankidroid.AndroidAccessPlatform
import org.ankivoice.ankidroid.AnkiDroidAccess
import org.ankivoice.ankidroid.AnkiDroidCardProvider
import org.ankivoice.ankidroid.AnkiDroidProvisioning
import org.ankivoice.ankidroid.AnkiDroidReviewTransport
import org.ankivoice.core.contracts.Capabilities
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.ForegroundEventPort
import org.ankivoice.core.contracts.GuardedReviewWriter
import org.ankivoice.core.contracts.ReviewOutcome
import org.ankivoice.core.contracts.ReviewWriter
import org.ankivoice.core.exchange.AutomaticGrading
import org.ankivoice.core.exchange.PrecommitExchange
import org.ankivoice.core.journal.JournaledReviewWriter
import org.ankivoice.core.journal.ReviewJournal
import org.ankivoice.core.journal.SettledTranscripts
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

    /** The study surface's controller: one real session over the real collection and transport. */
    internal lateinit var study: StudyController
        private set

    /** Both controllers see every foreground change; neither may miss one. */
    internal val foregroundEvents: ForegroundEventPort
        get() = ForegroundEventPort { event ->
            controller.onForegroundEvent(event)
            study.onForegroundEvent(event)
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
        // AV-019 wires the same journal into the study session's writer, where it is
        // reached from the session's own worker rather than this one. ReviewJournal
        // serializes its own fold, and AV-045's gate finishes reconciliation before any
        // session can open, so the two workers never race for the same entry.
        val reviewJournal = JournalModule.journal(filesDir)
        val journal = JournalAccess(reviewJournal, journalWorker, mainExecutor)
        // AV-045: one reconciliation gate for the process. The readiness check and the
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
        // AV-047: the cancel window's clock. One daemon timer for the process; it only ever
        // hands the expiry back to the study worker, and never touches the session itself.
        val windowTimer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ankivoice-auto-commit").apply { isDaemon = true }
        }
        // AV-026: the study surface drives a real session on a thread of its own, because
        // AV-025's transport blocks its caller for the whole of playback and capture.
        study = StudyController(
            StudySessionFactory {
                // A fresh GradingProvider per session: #17 holds a refused key or route
                // for the rest of the session and clears it only at the next one.
                val grading = ProviderModule.grading(credentials, ledger, providerSettings, diagnostics)
                studySession(
                    platform, settingsStore, worker, mainExecutor, applicationContext, reviewJournal,
                ) { sessionId, revision -> StudyGrader(grading, sessionId, revision, providerWorker) }
            },
            Executors.newSingleThreadExecutor(),
            mainExecutor,
            providerWorker,
            gate,
            cards,
            timerScheduler(windowTimer),
        ) { sessionId -> reviewJournal.entries().filter { it.sessionId == sessionId } }
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
 * AV-047's cancel window over a real timer.
 *
 * `Future.cancel(false)` is the whole of it: a task that has not started is dropped, and
 * one that has already started is left alone, which is exactly the guarantee
 * [StudyController] is written against — it refuses a released task itself rather than
 * relying on the timer to recall it.
 */
private fun timerScheduler(timer: ScheduledExecutorService): DelayScheduler = DelayScheduler { delayMs, task ->
    val future = timer.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    ScheduledWindow { future.cancel(false) }
}

/**
 * One study session over the real collection, the real AV-025 transport and AV-045's
 * grader: #16's rules on device, then #18's semantic grader on a rule miss.
 *
 * The card provider is wrapped in AV-010's [StudiableCardProvider], so an unstudiable card
 * is announced and skipped in memory rather than halting the session, and five in a row
 * stop it with the recorded summary.
 *
 * AV-019 completes the write path. The real guarded writer is wrapped in AV-018's
 * [JournaledReviewWriter], so the one place a review can be written journals the intent
 * and flushes it **before** the single dispatch and settles the entry from the outcome
 * that writer returned. The guarded writer still performs the pre-commit reads, the single
 * dispatch and the verification, and nothing here performs a write, re-read or
 * verification of its own.
 *
 * [SettledTranscripts] is the composition root's job because AV-012 owns transcript state
 * and AV-013 owns the turn: the journal may not reach into either. It hands back this
 * session's settled answer text **only** when its revision is the one the intent was
 * computed from, and an empty string otherwise, so a superseded revision's text can never
 * be journalled against a newer rating.
 */
private fun studySession(
    platform: AndroidAccessPlatform,
    settings: ShellSettings,
    worker: Executor,
    delivery: Executor,
    context: Context,
    journal: ReviewJournal,
    grader: (sessionId: String, revision: AtomicInteger) -> StudyGrader,
): Result<StudySession> {
    val deckId = settings.selectedDeckId
        ?: return Result.failure(StudySessionUnavailable(Failure(CardProviderFailure.DECK_MISSING, "no deck is selected")))
    val cards = AnkiDroidCardProvider(platform, deckId, worker, delivery)
    val capabilities = cards.capabilities()
    if (capabilities !is Capabilities) {
        return Result.failure(StudySessionUnavailable(capabilities as Failure))
    }
    val provider = StudiableCardProvider(cards, settings.language)
    val speech = SpeechModule.create(context)
    val sessionId = UUID.randomUUID().toString()
    val revision = AtomicInteger()
    val studyGrader = grader(sessionId, revision)
    // Written once, on this thread, before the session can reach the writer at all.
    var opened: ReviewSession? = null
    val session = ReviewSession(
        provider = provider,
        speechOutput = speech,
        speechInput = speech,
        grader = studyGrader,
        writer = studyWriter(
            GuardedReviewWriter(provider, AnkiDroidReviewTransport(platform), capabilities),
            journal,
            sessionId,
        ) { opened },
        capabilities = capabilities,
        language = settings.language,
        sessionId = sessionId,
    )
    opened = session
    return Result.success(
        StudySession(
            session = session,
            grader = studyGrader,
            // AV-047: the option is read once, here, when the session is built. The study
            // screen cannot be reached without leaving the setup screen, so a session never
            // sees the setting change underneath it.
            exchange = PrecommitExchange(session, speech, AutomaticGrading(settings.automaticGrading)),
            revision = revision,
            gradingSource = studyGrader::sourceOf,
            gradingRoute = studyGrader::routeOf,
            speech = speech,
            language = settings.language,
            partial = speech::lastPartial,
            rawConfidence = speech::lastConfidence,
            skips = provider::report,
            release = speech::releaseAll,
        ),
    )
}

/**
 * AV-019: the one writer a study session may use, and the only place a review is written.
 *
 * AV-018's journal wraps AV-024's guarded writer rather than replacing it: the intent is
 * recorded and flushed before the guarded writer's pre-commit reads and its single
 * dispatch, and the entry is settled from the [ReviewOutcome] that writer returned and
 * from nothing else. Nothing here re-reads, re-verifies or retries a write.
 *
 * [session] is read at commit time rather than captured, because the session cannot exist
 * before the writer it is built with. It is set once, on the session's own thread, before
 * that session can reach the writer at all.
 *
 * The transcript rule is the whole reason this lives in the composition root: AV-012 owns
 * transcript state and AV-013 owns the turn, so the journal may not reach into either for
 * the text. It is handed the settled answer **only** when that answer's revision is the
 * one the intent was computed from, and an empty string otherwise, so a superseded
 * revision's text can never be journalled against a newer rating.
 */
internal fun studyWriter(
    guarded: ReviewWriter,
    journal: ReviewJournal,
    sessionId: String,
    session: () -> ReviewSession?,
): ReviewWriter = JournaledReviewWriter(
    guarded,
    journal,
    sessionId,
    SettledTranscripts { intent ->
        val settled = session()?.answer
        if (settled != null && settled.transcriptRevision == intent.transcriptRevision) settled.text else ""
    },
)

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

    // AV-047: off unless this device's learner turned it on. A missing key is off, so a
    // first run, a cleared store and an upgrade from before the option all study manually.
    override var automaticGrading: Boolean
        get() = preferences.getBoolean("automatic_grading", false)
        set(value) { preferences.edit().putBoolean("automatic_grading", value).apply() }
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
