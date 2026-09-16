package org.ankivoice.core.session

import org.ankivoice.core.contracts.Capabilities
import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.ConfirmationSource
import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.QueueExhausted
import org.ankivoice.core.contracts.RatingConfirmation
import org.ankivoice.core.contracts.ReviewOutcome
import org.ankivoice.core.fakes.FakeCardProvider
import org.ankivoice.core.fakes.FakeClock
import org.ankivoice.core.fakes.FakeCollection
import org.ankivoice.core.fakes.FakeGrader
import org.ankivoice.core.fakes.FakeReviewTransport
import org.ankivoice.core.fakes.FakeReviewWriter
import org.ankivoice.core.fakes.FakeSpeechInput
import org.ankivoice.core.fakes.FakeSpeechOutput
import org.ankivoice.core.fakes.demoCollection

/**
 * The AV-013 conformance harness: the Kotlin counterpart of `build`, `ask_listen_grade`,
 * `confirmation` and `confirm_and_commit` in `tools/av007_scenarios.py`.
 *
 * Every scenario runs against the AV-041 fakes with no emulator, no network and no real
 * clock, so the whole suite is deterministic.
 */

/** Names one ported scenario, so ScenarioDriftTest can check the suite against the manifest. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Scenario(val name: String)

val CORRECT = GradingResult(GradeLabel.CORRECT, "the learner stated the required concept")
val PARTIAL = GradingResult(GradeLabel.PARTIAL, "one required concept is missing")
const val ANSWER = "Five blocks."

class Harness(
    val collection: FakeCollection,
    val provider: FakeCardProvider,
    val transport: FakeReviewTransport,
    val speechOutput: FakeSpeechOutput,
    val speechInput: FakeSpeechInput,
    val grader: FakeGrader,
    val session: ReviewSession,
    val clock: FakeClock,
) {
    /** True only when this caller wrote nothing at all through the transport. */
    val wroteNothing: Boolean get() = transport.calls.isEmpty()
}

fun build(
    collection: FakeCollection = demoCollection(),
    capabilities: Capabilities? = null,
    transcripts: List<FakeSpeechInput.Step> = listOf(FakeSpeechInput.Say(ANSWER)),
    grades: List<FakeGrader.Step> = listOf(FakeGrader.Answer(CORRECT)),
    // Supplied only where a guard test needs a fake that re-enters the session.
    speechOutput: FakeSpeechOutput = FakeSpeechOutput(),
    speechInput: FakeSpeechInput = FakeSpeechInput(*transcripts.toTypedArray()),
    grader: FakeGrader = FakeGrader(*grades.toTypedArray()),
    owner: Thread = Thread.currentThread(),
): Harness {
    val resolved = capabilities ?: Capabilities(maxReviewTimeMs = collection.maxReviewTimeMs)
    val provider = FakeCardProvider(collection, resolved)
    val transport = FakeReviewTransport(collection)
    val writer = FakeReviewWriter(provider, transport, resolved)
    val clock = FakeClock()
    val session = ReviewSession(
        provider = provider,
        speechOutput = speechOutput,
        speechInput = speechInput,
        grader = grader,
        writer = writer,
        capabilities = resolved,
        clock = clock,
        sessionId = "scenario",
        owner = owner,
    )
    session.start()
    return Harness(collection, provider, transport, speechOutput, speechInput, grader, session, clock)
}

/** How one scripted turn ended before the correction window opened. */
sealed interface TurnOutcome {
    data class Graded(val result: GradingResult) : TurnOutcome

    data class Halted(val halt: Halt) : TurnOutcome

    data object Exhausted : TurnOutcome

    /** A stale or partial callback: the turn neither graded nor halted. */
    data object Ignored : TurnOutcome

    val haltOrNull: Halt? get() = (this as? Halted)?.halt

    val gradeOrNull: GradingResult? get() = (this as? Graded)?.result
}

/** Drive one turn up to the correction window, or report the first halt instead. */
fun askListenGrade(harness: Harness, elapsedMs: Long = 12_345): TurnOutcome {
    val session = harness.session
    when (val offered = session.offerCard()) {
        is SessionResult.Halted -> return TurnOutcome.Halted(offered.halt)
        is SessionResult.Ignored -> return TurnOutcome.Ignored
        is SessionResult.Produced -> if (offered.value is QueueExhausted) return TurnOutcome.Exhausted
    }
    when (val asked = session.ask()) {
        is SessionResult.Halted -> return TurnOutcome.Halted(asked.halt)
        is SessionResult.Ignored -> return TurnOutcome.Ignored
        is SessionResult.Produced -> Unit
    }
    harness.clock.advance(elapsedMs)
    when (val spoken = session.listen()) {
        is SessionResult.Halted -> return TurnOutcome.Halted(spoken.halt)
        is SessionResult.Ignored -> return TurnOutcome.Ignored
        is SessionResult.Produced -> Unit
    }
    return when (val graded = session.grade()) {
        is SessionResult.Halted -> TurnOutcome.Halted(graded.halt)
        is SessionResult.Ignored -> TurnOutcome.Ignored
        is SessionResult.Produced -> TurnOutcome.Graded(graded.value)
    }
}

/** The rating the advisory suggestion proposes, or null when the learner must supply one. */
fun TurnOutcome.proposedRating(session: ReviewSession): Int? =
    gradeOrNull?.proposedRating(checkNotNull(session.card).permittedRatings)

/** Script an explicit learner event, separate from proposal and submission. */
fun confirmation(
    session: ReviewSession,
    source: ConfirmationSource = ConfirmationSource.TOUCH,
): RatingConfirmation {
    val intent = checkNotNull(session.intent) { "There is no pending review to confirm" }
    return RatingConfirmation(
        token = checkNotNull(intent.token),
        identity = intent.cardSnapshot.identity,
        rating = intent.rating,
        transcriptRevision = intent.transcriptRevision,
        source = source,
        confidence = Confidence.SUFFICIENT,
    )
}

fun confirmAndCommit(
    session: ReviewSession,
    source: ConfirmationSource = ConfirmationSource.TOUCH,
): ReviewOutcome {
    check(session.confirm(confirmation(session, source))) { "The scripted learner confirmation was rejected" }
    return session.commit()
}

/** Propose whatever the advisory suggestion proposed, which a PARTIAL label never does. */
fun proposeSuggested(harness: Harness, outcome: TurnOutcome): Int {
    val session = harness.session
    val rating = checkNotNull(outcome.proposedRating(session)) { "The suggestion proposed no rating" }
    check(session.propose(rating) is ProposalOutcome.Proposed)
    return rating
}
