#!/usr/bin/env python3
"""Scripted AV-007 sessions. Runs the whole state machine with no device.

Each scenario drives the fakes through the contracts and records what happened.
Together they cover every enumerated failure mode, the five review states and
explicit confirmation, no skip, and correction before commit only.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from pathlib import Path
import sys
from typing import Callable

# Support the documented direct-file command as well as module execution.
if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools.av007_contracts import (
    ALL_FAILURE_MODES, Capabilities, CardProviderFailure, Failure, GradeLabel,
    GraderFailure, GradingResult, GuardedReviewWriter, Halt, Interruption,
    QueueExhausted, ReviewIntent, ReviewSession, ReviewWriterFailure,
    SpeechInputFailure, SpeechOutputFailure, transcript,
    CaptureEvent, Confidence, ConfirmationSource, GradingReply, RatingConfirmation,
    TranscriptKind,
)
from tools.av007_fakes import (
    FakeCardProvider, FakeClock, FakeCollection, FakeGrader, FakeReviewTransport, FakeReviewWriter,
    FakeSpeechInput, FakeSpeechOutput, WriteAnomaly, demo_collection,
)

CORRECT = GradingResult(GradeLabel.CORRECT, "the learner stated the required concept")
PARTIAL = GradingResult(GradeLabel.PARTIAL, "one required concept is missing")
ANSWER = "Five blocks."


@dataclass
class Harness:
    collection: FakeCollection
    provider: FakeCardProvider
    transport: FakeReviewTransport
    speech_output: FakeSpeechOutput
    speech_input: FakeSpeechInput
    grader: FakeGrader
    session: ReviewSession
    clock: FakeClock


@dataclass
class Run:
    name: str
    purpose: str
    harness: Harness
    notes: list[str] = field(default_factory=list)

    @property
    def session(self) -> ReviewSession:
        return self.harness.session

    def note(self, text: str) -> None:
        self.notes.append(text)


def build(collection: FakeCollection | None = None,
          capabilities: Capabilities | None = None,
          transcripts=(ANSWER,), grades=(CORRECT,)) -> Harness:
    collection = collection or demo_collection()
    capabilities = capabilities or Capabilities(
        max_review_time_ms=collection.max_review_time_ms)
    provider = FakeCardProvider(collection, capabilities)
    transport = FakeReviewTransport(collection)
    writer = FakeReviewWriter(provider, transport, capabilities)
    speech_output, speech_input = FakeSpeechOutput(), FakeSpeechInput(transcripts)
    grader = FakeGrader(grades)
    clock = FakeClock()
    session = ReviewSession(provider, speech_output, speech_input, grader, writer,
                            capabilities, clock=clock)
    session.start()
    return Harness(collection, provider, transport, speech_output, speech_input,
                   grader, session, clock)


def ask_listen_grade(harness: Harness, elapsed_ms: int = 12_345):
    """Drive one turn up to the correction window. Returns the first halt instead."""
    session = harness.session
    for step in (session.offer_card, session.ask):
        outcome = step()
        if isinstance(outcome, (Halt, QueueExhausted)):
            return outcome
    harness.clock.advance(elapsed_ms)
    spoken = session.listen()
    if isinstance(spoken, Halt):
        return spoken
    if spoken is None:
        return None
    return session.grade(spoken)


def confirmation(session: ReviewSession,
                 source: ConfirmationSource = ConfirmationSource.TOUCH) -> RatingConfirmation:
    """Script an explicit learner event, separate from proposal and submission."""
    intent = session.intent
    return RatingConfirmation(intent.token, intent.card.identity, intent.rating,
                              intent.transcript_revision, source,
                              confidence=Confidence.SUFFICIENT)


def confirm_and_commit(session: ReviewSession,
                       source: ConfirmationSource = ConfirmationSource.TOUCH):
    if not session.confirm(confirmation(session, source)):
        raise AssertionError("The scripted learner confirmation was rejected")
    return session.commit()


# --------------------------------------------------------------------------- #
# Named scenarios
# --------------------------------------------------------------------------- #

def confirmed_commit() -> Run:
    harness = build()
    run = Run("confirmed-commit", "A full turn that verifies and advances.", harness)
    result = ask_listen_grade(harness)
    session = harness.session
    session.propose(result.proposed_rating(session.card.permitted_ratings))
    outcome = confirm_and_commit(session)
    run.note(f"review state {outcome.state.value}: {outcome.reason}")
    run.note(f"announcement: {session.announce_result(outcome).text}")
    session.advance()
    run.note(f"one review recorded: {harness.collection.reviews}")
    return run


def precommit_correction() -> Run:
    harness = build(grades=(PARTIAL,))
    run = Run("precommit-correction",
              "The learner changes the rating twice before it is submitted.", harness)
    result = ask_listen_grade(harness)
    session = harness.session
    run.note(f"grader proposed {result.proposed_rating(session.card.permitted_ratings)} "
             f"for a {result.label.value} answer: a self-grade is required")
    session.propose(1)
    session.correct(2)
    session.correct(3)
    run.note(f"pending rating {session.intent.rating} after corrections "
             f"{list(session.intent.corrections)}; nothing written yet: "
             f"{harness.transport.calls == []}")
    outcome = confirm_and_commit(session)
    run.note(f"committed rating {harness.transport.calls[0][1]} once; "
             f"{outcome.state.value}")
    return run


def stale_identity_rejection() -> Run:
    harness = build()
    run = Run("stale-identity-rejection",
              "A snapshot is not a reservation: the card moved before commit.", harness)
    result = ask_listen_grade(harness)
    session = harness.session
    session.propose(result.proposed_rating(session.card.permitted_ratings))
    card_id = session.card.identity.card_id
    harness.collection.native_answer(card_id)
    harness.collection.rebuild_queue([1789414083109, card_id])
    run.note("AnkiDroid answered the same card natively and rebuilt the queue")
    outcome = confirm_and_commit(session)
    run.note(f"{outcome.state.value}: {outcome.failure.mode.value}")
    run.note(f"no write was handed over: {harness.transport.calls == []}")
    run.note("a race still remains between this check and the write")
    return run


def capped_review_time() -> Run:
    harness = build()
    run = Run("capped-review-time",
              "maxTaken truncates the stored value; that is not a failure.", harness)
    result = ask_listen_grade(harness, elapsed_ms=98_765)
    session = harness.session
    session.propose(result.proposed_rating(session.card.permitted_ratings))
    outcome = confirm_and_commit(session)
    stored = harness.collection.reviews[-1]["time_taken_ms"]
    run.note(f"submitted {outcome.submitted_time_ms} ms, expected stored "
             f"{outcome.expected_stored_time_ms} ms, stored {stored} ms")
    run.note(f"capped: {outcome.time_was_capped}; read-back expected: "
             f"{outcome.stored_time_is_expected(stored)}; "
             f"review state {outcome.state.value}")
    return run


def ambiguous_acknowledgement() -> Run:
    harness = build()
    harness.transport.anomalies.append(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
    run = Run("ambiguous-acknowledgement",
              "update_count 1 with no saved review, exactly as AV-004 observed.",
              harness)
    result = ask_listen_grade(harness)
    session = harness.session
    session.propose(result.proposed_rating(session.card.permitted_ratings))
    outcome = confirm_and_commit(session)
    run.note(f"acknowledgement {outcome.acknowledgement} -> {outcome.state.value}: "
             f"{outcome.reason}")
    run.note(f"session {session.state.value}, reconciliation required: "
             f"{session.halt.reconciliation_required}")
    for label, call in (("replay", lambda: session.writer.commit(session.intent)),
                        ("advance", session.advance),
                        ("announce success", lambda: session.announce_result(outcome)),
                        ("resume", session.resume)):
        try:
            call()
            run.note(f"PROBLEM: {label} was permitted")
        except ValueError as error:
            run.note(f"{label} refused: {error}")
    session.reconcile(learner_confirmed_saved=False)
    return run


def rating_out_of_range() -> Run:
    harness = build()
    run = Run("rating-out-of-range",
              "Five is not offered; it is rejected, never converted to Again.", harness)
    ask_listen_grade(harness)
    session = harness.session
    rejection = session.propose(5)
    run.note(f"session refused: {rejection}")
    run.note(f"state is still {session.state.value}; the window stayed open")
    intent = ReviewIntent(session.card, 5, 12_345)
    outcome = session.writer.commit(intent)
    run.note(f"writer refused the same rating directly: {outcome.state.value}, "
             f"{outcome.failure.mode.value}, write attempted "
             f"{outcome.write_attempted}")
    session.propose(4)
    committed = confirm_and_commit(session)
    run.note(f"the learner then chose 4: {committed.state.value}, "
             f"stored rating {harness.collection.reviews[-1]['rating']}")
    return run


def queue_exhausted_versus_null_cursor() -> Run:
    harness = build()
    harness.collection.rebuild_queue([])
    run = Run("queue-exhausted-versus-null-cursor",
              "An empty queue ends the session; a null cursor pauses it.", harness)
    run.note(f"empty queue -> {type(harness.session.offer_card()).__name__}, "
             f"session {harness.session.state.value}")
    other = build()
    other.provider.next_card_script.append(
        Failure(CardProviderFailure.API_DISABLED, "AnkiDroid API switched off"))
    halt = other.session.offer_card()
    run.note(f"null cursor -> session {other.session.state.value}, "
             f"reason {halt.reason}, resumable {halt.resumable}")
    run.note("a missing deck is a third case and stops rather than pauses")
    return run


def skip_request() -> Run:
    harness = build()
    run = Run("skip-request", "A skip halts without any write of any kind.", harness)
    result = ask_listen_grade(harness)
    session = harness.session
    session.propose(result.proposed_rating(session.card.permitted_ratings))
    halt = session.request_skip()
    run.note(f"session {session.state.value}, reason {halt.reason}")
    run.note(f"no write: transport calls {harness.transport.calls}, "
             f"reviews {harness.collection.reviews}")
    run.note(f"card state untouched: "
             f"{harness.collection.scheduled(1789414083106).state.reps} reps")
    return run


def native_undo_handoff() -> Run:
    harness = build()
    run = Run("native-undo-handoff",
              "After commit there is no in-app correction, only native Undo.", harness)
    result = ask_listen_grade(harness)
    session = harness.session
    session.propose(result.proposed_rating(session.card.permitted_ratings))
    outcome = confirm_and_commit(session)
    run.note(f"committed: {outcome.state.value}")
    try:
        session.correct(1)
        run.note("PROBLEM: correction after commit was permitted")
    except ValueError as error:
        run.note(f"correction refused: {error}")
    halt = session.request_correction_after_commit()
    run.note(f"session {session.state.value}: {halt.detail}")
    run.note(f"still exactly one write: {len(harness.transport.calls)}")
    return run


def unconfirmed_write_variants() -> Run:
    harness = build()
    run = Run("unconfirmed-write-variants",
              "Every inconsistent post-state resolves to outcome-unknown.", harness)
    cases = [
        ("explicit zero, card unchanged", WriteAnomaly.REJECT_WITH_ZERO, None),
        ("null response", WriteAnomaly.NULL_RESPONSE, None),
        ("error response", WriteAnomaly.ERROR_RESPONSE, None),
        ("reps advanced twice", WriteAnomaly.DOUBLE_APPLY, None),
        ("card came back suspended", WriteAnomaly.SUSPENDED_INSTEAD, None),
        ("zero but the card changed", WriteAnomaly.ZERO_BUT_APPLIED, None),
        ("post-state unavailable", None,
         Failure(CardProviderFailure.ACCESS_DENIED, "permission revoked mid-turn")),
        ("verification unsupported", None, None),
    ]
    for label, anomaly, post_failure in cases:
        capabilities = Capabilities(
            supports_post_write_verification=label != "verification unsupported")
        case = build(capabilities=capabilities)
        if anomaly is not None:
            case.transport.anomalies.append(anomaly)
        result = ask_listen_grade(case)
        case.session.propose(result.proposed_rating(case.session.card.permitted_ratings))
        if post_failure is not None:
            case.provider.read_card_script.extend([None, post_failure])
        outcome = confirm_and_commit(case.session)
        run.note(f"{label}: {outcome.state.value} "
                 f"(session {case.session.state.value}, reconcile "
                 f"{case.session.halt.reconciliation_required})")
        run.harness = case  # the transcript shows the last case
    return run


def single_active_reviewer() -> Run:
    harness = build()
    run = Run("single-active-reviewer",
              "App switching and sync stop the session instead of reconciling.",
              harness)
    result = ask_listen_grade(harness)
    session = harness.session
    session.propose(result.proposed_rating(session.card.permitted_ratings))
    halt = session.interrupt(Interruption.APP_SWITCH)
    run.note(f"pending review: session {session.state.value}, reason {halt.reason}, "
             f"reconcile {halt.reconciliation_required}, "
             f"writes {len(harness.transport.calls)}")
    other = build()
    other.transport.anomalies.append(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
    second = ask_listen_grade(other)
    other.session.propose(second.proposed_rating(other.session.card.permitted_ratings))
    confirm_and_commit(other.session)
    stop = other.session.interrupt(Interruption.SYNC)
    run.note(f"after an unconfirmed write: session {other.session.state.value}, "
             f"reason {stop.reason}, reconcile {stop.reconciliation_required}")
    return run


def grader_unavailable() -> Run:
    harness = build(grades=(Failure(GraderFailure.QUOTA_EXHAUSTED, "free tier spent"),))
    run = Run("grader-unavailable",
              "An exhausted grader quota never becomes a rating.", harness)
    halt = ask_listen_grade(harness)
    run.note(f"session {harness.session.state.value}, reason {halt.reason}")
    run.note(f"no rating proposed and no write: {harness.transport.calls == []}")
    harness.session.self_grade(2)
    outcome = confirm_and_commit(harness.session, ConfirmationSource.SPOKEN)
    run.note(f"explicit spoken self-grade and confirmation: {outcome.state.value}")
    return run


def microphone_denied() -> Run:
    harness = build(transcripts=(
        Failure(SpeechInputFailure.PERMISSION_DENIED, "RECORD_AUDIO refused"),))
    run = Run("microphone-denied",
              "A denied microphone is a transport fault, not a wrong answer.", harness)
    halt = ask_listen_grade(harness)
    run.note(f"session {harness.session.state.value}, reason {halt.reason}, "
             f"resumable {halt.resumable}")
    run.note(f"no rating and no write: {harness.transport.calls == []}")
    harness.session.resume()
    run.note(f"after the learner grants permission: {harness.session.state.value}")
    return run


def question_answer_separation() -> Run:
    harness = build()
    run = Run("question-answer-separation",
              "The question side never carries the reference answer or Extra.",
              harness)
    result = ask_listen_grade(harness)
    session = harness.session
    card = session.card
    question = harness.speech_output.attempted[0]
    run.note(f"question audio ({question.purpose.value}): {question.text}")
    run.note(f"contains the reference answer: "
             f"{card.fields.reference_answer.lower() in question.text.lower()}; "
             f"contains Extra: {card.fields.extra.lower() in question.text.lower()}")
    context = harness.grader.seen[0]
    run.note(f"grading criteria: {sorted(vars(context))}")
    run.note(f"Extra reached the grader: "
             f"{card.fields.extra in str(vars(context).values())}")
    session.reveal()
    session.reveal(include_extra=True)
    session.propose(result.proposed_rating(card.permitted_ratings))
    confirm_and_commit(session)
    return run


def corrected_transcript_and_stale_grade() -> Run:
    harness = build(transcripts=("Six blocks.",))
    run = Run("corrected-transcript-and-stale-grade",
              "An edit invalidates the prior suggestion, pending rating and grade callback.", harness)
    ask_listen_grade(harness)
    session = harness.session
    old_request = session.begin_grade()
    session.propose(1)
    old_confirmation = confirmation(session)
    session.correct_transcript(ANSWER)
    session.accept_grade(GradingReply(old_request, CORRECT))
    session.confirm(old_confirmation)
    run.note(f"old suggestion cleared: {session.suggestion is None}; no writes: {not harness.transport.calls}")
    harness.grader.script.append(CORRECT)
    session.grade()
    session.propose(3)
    confirm_and_commit(session)
    return run


def absent_confirmation() -> Run:
    harness = build()
    run = Run("absent-confirmation", "A suggestion and elapsed silence cannot submit.", harness)
    ask_listen_grade(harness)
    session = harness.session
    session.propose(3)
    harness.clock.advance(60_000)
    try:
        session.commit()
        run.note("PROBLEM: absent confirmation submitted a review")
    except ValueError as error:
        run.note(f"submission refused: {error}")
    run.note(f"still pending: {session.intent.state.value}; writes {len(harness.transport.calls)}")
    return run


def spoken_confirmation() -> Run:
    harness = build()
    run = Run("spoken-confirmation", "A final, sufficiently confident spoken command confirms the current rating.", harness)
    ask_listen_grade(harness)
    harness.session.propose(3)
    confirm_and_commit(harness.session, ConfirmationSource.SPOKEN)
    return run


def early_closure_and_touch_fallback() -> Run:
    harness = build()
    run = Run("early-closure-and-touch-fallback",
              "Partial speech then early closure requires explicit retry or learner correction.", harness)
    session = harness.session
    session.offer_card()
    session.ask()
    token = session.capture_token
    session.accept_capture(CaptureEvent(token, "Fi", TranscriptKind.PARTIAL))
    session.accept_capture(CaptureEvent(token, failure=Failure(
        SpeechInputFailure.EARLY_CLOSURE, "recognizer ended without a usable final")))
    session.accept_capture(CaptureEvent(token, ANSWER, confidence=Confidence.SUFFICIENT))
    run.note(f"no grade or write from partial/error/stale final: {not harness.grader.seen and not harness.transport.calls}")
    session.correct_transcript(ANSWER)
    session.self_grade(3)
    confirm_and_commit(session)
    return run


def playback_and_capture_interruption() -> Run:
    harness = build()
    run = Run("playback-and-capture-interruption",
              "Interruptions cancel operations and late completions cannot advance.", harness)
    session = harness.session
    session.offer_card()
    harness.speech_output.on_speak = lambda token: session.interrupt(Interruption.EXTERNAL_AUDIO)
    session.ask()
    run.note(f"playback cancelled: {bool(harness.speech_output.cancelled)}; state {session.state.value}")
    other = build()
    other.session.offer_card()
    other.session.ask()
    token = other.session.capture_token
    other.session.interrupt(Interruption.LOCK)
    other.session.accept_capture(CaptureEvent(token, ANSWER, confidence=Confidence.SUFFICIENT))
    run.note(f"capture cancelled: {token in other.speech_input.cancelled}; state {other.session.state.value}; writes {len(other.transport.calls)}")
    return run


NAMED_SCENARIOS: tuple[Callable[[], Run], ...] = (
    confirmed_commit,
    precommit_correction,
    stale_identity_rejection,
    capped_review_time,
    ambiguous_acknowledgement,
    rating_out_of_range,
    queue_exhausted_versus_null_cursor,
    skip_request,
    native_undo_handoff,
    unconfirmed_write_variants,
    single_active_reviewer,
    grader_unavailable,
    microphone_denied,
    question_answer_separation,
    corrected_transcript_and_stale_grade,
    absent_confirmation,
    spoken_confirmation,
    early_closure_and_touch_fallback,
    playback_and_capture_interruption,
)


# --------------------------------------------------------------------------- #
# Failure sweep: one scripted session per enumerated failure mode
# --------------------------------------------------------------------------- #

def _provider_failure(mode: CardProviderFailure) -> Run:
    harness = build()
    failure = Failure(mode, f"scripted {mode.value}")
    run = Run(f"failure/{mode.contract}.{mode.value}", "Provider fault.", harness)
    if mode is CardProviderFailure.CARD_NOT_FOUND:
        result = ask_listen_grade(harness)
        harness.session.propose(
            result.proposed_rating(harness.session.card.permitted_ratings))
        harness.provider.read_card_script.append(failure)
        outcome = confirm_and_commit(harness.session)
        run.note(f"{outcome.state.value}: {outcome.failure.mode.value}, "
                 f"cause {outcome.failure.cause.mode.value}, "
                 f"write attempted {outcome.write_attempted}")
        return run
    harness.provider.next_card_script.append(failure)
    halt = harness.session.offer_card()
    run.note(f"session {harness.session.state.value}, reason {halt.reason}, "
             f"resumable {halt.resumable}, writes {len(harness.transport.calls)}")
    return run


def _speech_output_failure(mode: SpeechOutputFailure) -> Run:
    harness = build()
    harness.speech_output.script.append(Failure(mode, f"scripted {mode.value}"))
    run = Run(f"failure/{mode.contract}.{mode.value}", "Speech output fault.", harness)
    halt = ask_listen_grade(harness)
    run.note(f"session {harness.session.state.value}, reason {halt.reason}, "
             f"writes {len(harness.transport.calls)}")
    return run


def _speech_input_failure(mode: SpeechInputFailure) -> Run:
    harness = build(transcripts=(Failure(mode, f"scripted {mode.value}"),))
    run = Run(f"failure/{mode.contract}.{mode.value}",
              "Speech input fault, never an incorrect answer.", harness)
    halt = ask_listen_grade(harness)
    run.note(f"session {harness.session.state.value}, reason {halt.reason}, "
             f"writes {len(harness.transport.calls)}")
    return run


def _grader_failure(mode: GraderFailure) -> Run:
    harness = build(grades=(Failure(mode, f"scripted {mode.value}"),))
    run = Run(f"failure/{mode.contract}.{mode.value}",
              "Grader fault, never a rating.", harness)
    halt = ask_listen_grade(harness)
    run.note(f"session {harness.session.state.value}, reason {halt.reason}, "
             f"writes {len(harness.transport.calls)}")
    return run


def _writer_failure(mode: ReviewWriterFailure) -> Run:
    harness = build()
    run = Run(f"failure/{mode.contract}.{mode.value}", "Write refused or rejected.",
              harness)
    result = ask_listen_grade(harness)
    session = harness.session
    rating = result.proposed_rating(session.card.permitted_ratings)
    if mode is ReviewWriterFailure.RATING_REJECTED:
        # Constructed directly, the way AV-004's raw-answer path bypassed the
        # session guard to test a negative case the UI would never produce.
        intent = ReviewIntent(session.card, 5, 12_345)
    elif mode is ReviewWriterFailure.INVALID_REVIEW_TIME:
        intent = ReviewIntent(session.card, rating, -1)
    elif mode is ReviewWriterFailure.CONFIRMATION_REQUIRED:
        intent = ReviewIntent(session.card, rating, 12_345)
    else:
        session.propose(rating)
        intent = session.intent
        if mode is ReviewWriterFailure.STALE_IDENTITY:
            harness.collection.native_answer(session.card.identity.card_id)
        elif mode is ReviewWriterFailure.PRECOMMIT_READ_FAILED:
            harness.provider.read_card_script.append(
                Failure(CardProviderFailure.PACKAGE_UNAVAILABLE, "AnkiDroid disabled"))
        elif mode is ReviewWriterFailure.WRITE_REJECTED:
            harness.transport.anomalies.append(WriteAnomaly.REJECT_WITH_ZERO)
    outcome = confirm_and_commit(session) if intent is session.intent \
        else session.writer.commit(intent)
    run.note(f"{outcome.state.value}: {outcome.failure.mode.value} "
             f"({outcome.reason})")
    run.note(f"write attempted {outcome.write_attempted}, writes by this caller "
             f"{len(harness.transport.calls)}")
    return run


FAILURE_DRIVERS = {
    CardProviderFailure: _provider_failure,
    SpeechOutputFailure: _speech_output_failure,
    SpeechInputFailure: _speech_input_failure,
    GraderFailure: _grader_failure,
    ReviewWriterFailure: _writer_failure,
}


def failure_sweep() -> list[Run]:
    return [FAILURE_DRIVERS[type(mode)](mode) for mode in ALL_FAILURE_MODES]


def all_runs() -> list[Run]:
    return [scenario() for scenario in NAMED_SCENARIOS] + failure_sweep()


# --------------------------------------------------------------------------- #
# Report
# --------------------------------------------------------------------------- #

def render(runs: list[Run]) -> str:
    lines = ["# AV-007 scripted session transcripts", "",
             "Generated by `python tools/av007_scenarios.py`. No emulator, network,",
             "provider or clock is involved; every run is deterministic.", ""]
    for run in runs:
        lines += [f"## {run.name}", "", run.purpose, "", "```"]
        lines.append(transcript(run.session) or "  (no events)")
        lines.append("```")
        if run.notes:
            lines += [""] + [f"- {note}" for note in run.notes]
        lines.append("")
    covered = {run.name.split("/", 1)[1] for run in runs if run.name.startswith("failure/")}
    lines += ["## Coverage", "",
              f"- Scenarios: {len(runs)} ({len(NAMED_SCENARIOS)} named, "
              f"{len(ALL_FAILURE_MODES)} failure modes).",
              f"- Failure modes exercised: {len(covered)} of {len(ALL_FAILURE_MODES)}.",
              ""]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path,
                        help="Write the transcript report here instead of stdout")
    args = parser.parse_args()
    runs = all_runs()
    report = render(runs)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report)
        print(f"Wrote {len(runs)} scenarios to {args.output}")
    else:
        print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
