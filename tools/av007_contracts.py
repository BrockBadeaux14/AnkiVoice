"""AV-007 session contracts. Specification only: no transport, providers or UI.

Anki keeps collection and scheduler ownership. These types encode the limits
AV-004 measured against released AnkiDroid 2.24.1: no revlog endpoint, no
transaction or idempotency key, no atomic compare-and-write, and an observed
``update_count`` of 1 without a saved review. Every operation here is safe under
those limits. The prose specification is docs/contracts/av007-session-contracts.md.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import time
from typing import Protocol, Union
from uuid import uuid4

VOICEQA_MODEL = "VoiceQA"

# --------------------------------------------------------------------------- #
# Identity, content and stored state
# --------------------------------------------------------------------------- #

@dataclass(frozen=True)
class CardIdentity:
    """The tuple AV-004 proved readable through the released ContentProvider."""

    card_id: int
    note_id: int
    deck_id: int
    ordinal: int
    model: str


@dataclass(frozen=True)
class CardState:
    """Scheduling fields readable without root or filesystem access.

    ``due`` is scheduler-relative and its unit depends on the queue: Unix seconds
    for intraday learning/relearning, collection-relative days for review or
    day learning, a queue position while new. Those units cannot be interchanged, so
    nothing here does arithmetic on ``due``; it is only compared for equality.
    ``last_review_time_secs`` is None on a card that has never been answered.
    """

    reps: int
    card_type: int
    queue: int
    due: int
    interval_days: int
    last_review_time_secs: int | None = None


@dataclass(frozen=True)
class VoiceQAFields:
    """The six VoiceQA fields. Content is study data, never instructions."""

    prompt: str
    reference_answer: str
    required_concepts: tuple[str, ...] = ()
    accepted_answers: tuple[str, ...] = ()
    language: str = ""
    extra: str = ""


@dataclass(frozen=True)
class ScheduledCard:
    """A snapshot of an offered card. Never a reservation on that card.

    ``permitted_ratings`` is what the provider offered for *this* card at
    ``observed_at_ms``. It is read per card and never assumed constant.
    """

    identity: CardIdentity
    state: CardState
    fields: VoiceQAFields
    permitted_ratings: tuple[int, ...]
    observed_at_ms: int = 0


class QueueExhausted:
    """A valid, empty queue. Distinct from a null cursor and a missing deck."""

    def __repr__(self) -> str:  # pragma: no cover - debug aid
        return "QueueExhausted()"


@dataclass(frozen=True)
class Capabilities:
    """Flags that let a session adapt without branching on a device or version.

    Defaults are the AV-004 measurements for AnkiDroid 2.24.1 on API 36. The
    false flags are properties of the reviewed contract, not of one device.
    """

    permitted_ratings: tuple[int, ...] = (1, 2, 3, 4)
    max_review_time_ms: int = 60_000
    supports_post_write_verification: bool = True
    supports_skip: bool = False
    supports_programmatic_undo: bool = False
    supports_revlog_query: bool = False
    supports_transactions: bool = False
    supports_idempotency_key: bool = False
    supports_atomic_compare_and_write: bool = False

    def __post_init__(self) -> None:
        if type(self.max_review_time_ms) is not int or self.max_review_time_ms < 0:
            raise ValueError("The selected deck must expose a nonnegative millisecond cap")


# --------------------------------------------------------------------------- #
# Enumerated failure modes
# --------------------------------------------------------------------------- #

class ContractFailure(Enum):
    """A transport or provider fault. Never evidence about the learner's answer."""

    @property
    def contract(self) -> str:
        return type(self).__name__.removesuffix("Failure")


class CardProviderFailure(ContractFailure):
    ACCESS_DENIED = "access_denied"              # permission never granted or revoked
    API_DISABLED = "api_disabled"                # null cursor: AnkiDroid API switched off
    PACKAGE_UNAVAILABLE = "package_unavailable"  # null cursor: AnkiDroid disabled or absent
    DECK_MISSING = "deck_missing"                # not exhaustion; the deck itself is gone
    CARD_NOT_FOUND = "card_not_found"            # the offered card no longer exists
    COLLECTION_CHANGED = "collection_changed"    # replaced, restored or synced underneath
    UNSUPPORTED_NOTE_TYPE = "unsupported_note_type"
    MALFORMED_CARD = "malformed_card"            # required VoiceQA fields missing
    NULL_CURSOR = "null_cursor"                 # cause not established


class SpeechOutputFailure(ContractFailure):
    ENGINE_UNAVAILABLE = "engine_unavailable"
    LANGUAGE_UNSUPPORTED = "language_unsupported"
    AUDIO_FOCUS_LOST = "audio_focus_lost"
    PLAYBACK_INTERRUPTED = "playback_interrupted"


class SpeechInputFailure(ContractFailure):
    PERMISSION_DENIED = "permission_denied"
    RECOGNIZER_UNAVAILABLE = "recognizer_unavailable"
    RECOGNIZER_ERROR = "recognizer_error"
    NO_SPEECH_DETECTED = "no_speech_detected"    # silence, not a wrong answer
    LISTEN_TIMEOUT = "listen_timeout"
    NETWORK_UNAVAILABLE = "network_unavailable"
    QUOTA_EXHAUSTED = "quota_exhausted"
    NO_MATCH = "no_match"                       # overloaded Android ERROR_NO_MATCH
    EARLY_CLOSURE = "early_closure"             # capture ended without usable final
    LOW_CONFIDENCE = "low_confidence"           # low or absent confidence


class GraderFailure(ContractFailure):
    QUOTA_EXHAUSTED = "quota_exhausted"
    PROVIDER_ERROR = "provider_error"
    GRADER_TIMEOUT = "grader_timeout"
    UNPARSABLE_RESPONSE = "unparsable_response"
    OUTPUT_TRUNCATED = "output_truncated"


class ReviewWriterFailure(ContractFailure):
    RATING_REJECTED = "rating_rejected"          # outside the ratings offered for this card
    INVALID_REVIEW_TIME = "invalid_review_time"  # negative elapsed time
    STALE_IDENTITY = "stale_identity"            # identity or stored state moved
    PRECOMMIT_READ_FAILED = "precommit_read_failed"
    WRITE_REJECTED = "write_rejected"            # explicit zero with an unchanged card
    CONFIRMATION_REQUIRED = "confirmation_required"


FailureMode = Union[
    CardProviderFailure,
    SpeechOutputFailure,
    SpeechInputFailure,
    GraderFailure,
    ReviewWriterFailure,
]

ALL_FAILURE_MODES: tuple[FailureMode, ...] = tuple(
    mode for group in (CardProviderFailure, SpeechOutputFailure, SpeechInputFailure,
                       GraderFailure, ReviewWriterFailure) for mode in group
)


@dataclass(frozen=True)
class Failure:
    """A failed operation. A Failure is never convertible into a rating."""

    mode: FailureMode
    detail: str = ""
    cause: "Failure | None" = None

    @property
    def contract(self) -> str:
        return self.mode.contract

    def __str__(self) -> str:
        text = f"{self.contract}.{self.mode.value}"
        return f"{text}: {self.detail}" if self.detail else text


# --------------------------------------------------------------------------- #
# Question / answer separation
# --------------------------------------------------------------------------- #

class UtterancePurpose(Enum):
    QUESTION = "question"          # Prompt only
    REVEAL = "reveal"              # ReferenceAnswer, after accepted/corrected answer
    ELABORATION = "elaboration"    # Extra, after answer, optional
    ANNOUNCEMENT = "announcement"  # session and rating talkback


@dataclass(frozen=True)
class Utterance:
    purpose: UtterancePurpose
    text: str
    language: str


@dataclass(frozen=True)
class GradingContext:
    """Everything the grader may see. Structurally excludes Extra.

    ``learner_answer`` is the transcript. The other fields are the answer key,
    never words the learner said.
    """

    prompt: str
    reference_answer: str
    required_concepts: tuple[str, ...]
    accepted_answers: tuple[str, ...]
    learner_answer: str
    language: str


def card_language(card: ScheduledCard, session_language: str) -> str:
    return card.fields.language or session_language


def question_utterance(card: ScheduledCard, session_language: str) -> Utterance:
    """Question audio is Prompt and nothing else."""
    return Utterance(UtterancePurpose.QUESTION, card.fields.prompt,
                     card_language(card, session_language))


def reveal_utterance(card: ScheduledCard, session_language: str) -> Utterance:
    return Utterance(UtterancePurpose.REVEAL, card.fields.reference_answer,
                     card_language(card, session_language))


def elaboration_utterance(card: ScheduledCard, session_language: str) -> Utterance | None:
    if not card.fields.extra:
        return None
    return Utterance(UtterancePurpose.ELABORATION, card.fields.extra,
                     card_language(card, session_language))


def grading_context(card: ScheduledCard, transcript: str,
                    session_language: str) -> GradingContext:
    """Grading criteria are Prompt, ReferenceAnswer, RequiredConcepts, AcceptedAnswers."""
    return GradingContext(
        prompt=card.fields.prompt,
        reference_answer=card.fields.reference_answer,
        required_concepts=card.fields.required_concepts,
        accepted_answers=card.fields.accepted_answers,
        learner_answer=transcript,
        language=card_language(card, session_language),
    )


# --------------------------------------------------------------------------- #
# Grading
# --------------------------------------------------------------------------- #

class GradeLabel(Enum):
    CORRECT = "correct"
    PARTIAL = "partial"
    INCORRECT = "incorrect"
    UNCERTAIN = "uncertain"


# AV-006 approved advisory suggestions only. The schema's historical
# initial_auto_ratings names do not authorize unattended writes; #19 evaluates
# suggestion quality and does not waive explicit confirmation.
AUTOMATIC_PROPOSALS: dict[GradeLabel, int | None] = {
    GradeLabel.CORRECT: 3,     # Good
    GradeLabel.INCORRECT: 1,   # Again
    GradeLabel.PARTIAL: None,  # ask for a spoken self-grade
    GradeLabel.UNCERTAIN: None,
}


@dataclass(frozen=True)
class GradingResult:
    label: GradeLabel
    reason: str

    def proposed_rating(self, permitted: tuple[int, ...]) -> int | None:
        """None means the learner must supply the rating; it never means Again."""
        rating = AUTOMATIC_PROPOSALS[self.label]
        return rating if rating in permitted else None


# --------------------------------------------------------------------------- #
# Turn-scoped operations and learner events
# --------------------------------------------------------------------------- #

@dataclass(frozen=True)
class OperationToken:
    session_id: str
    turn: int
    sequence: int


class TranscriptKind(Enum):
    PARTIAL = "partial"
    FINAL = "final"
    CORRECTED = "learner-corrected"


class Confidence(Enum):
    # Policy classification, not an invented numeric recognizer threshold.
    SUFFICIENT = "sufficient"
    LOW = "low"
    ABSENT = "absent"


@dataclass(frozen=True)
class CaptureEvent:
    token: OperationToken
    text: str = ""
    kind: TranscriptKind = TranscriptKind.FINAL
    confidence: Confidence = Confidence.ABSENT
    failure: Failure | None = None


@dataclass(frozen=True)
class PlaybackResult:
    token: OperationToken
    failure: Failure | None = None


@dataclass(frozen=True)
class GradingRequest:
    token: OperationToken
    transcript_revision: int
    context: GradingContext


@dataclass(frozen=True)
class GradingReply:
    request: GradingRequest
    result: GradingResult | Failure


class ConfirmationSource(Enum):
    SPOKEN = "spoken"
    TOUCH = "touch"


@dataclass(frozen=True)
class RatingConfirmation:
    token: OperationToken
    identity: CardIdentity
    rating: int
    transcript_revision: int
    source: ConfirmationSource
    final: bool = True
    confidence: Confidence = Confidence.ABSENT


# --------------------------------------------------------------------------- #
# Review lifecycle
# --------------------------------------------------------------------------- #

class ReviewState(Enum):
    PENDING = "pending"                  # proposed, correctable, nothing written
    SUBMITTING = "submitting"            # exactly one write handed over
    CONFIRMED = "confirmed"              # consistent one-review transition read back
    FAILED = "failed"                    # provably no write landed
    OUTCOME_UNKNOWN = "outcome-unknown"  # a write may or may not have landed


TERMINAL_REVIEW_STATES = frozenset(
    {ReviewState.CONFIRMED, ReviewState.FAILED, ReviewState.OUTCOME_UNKNOWN})


@dataclass
class ReviewIntent:
    """A rating the learner may still change. Correction is open until commit."""

    card: ScheduledCard
    rating: int
    elapsed_ms: int
    state: ReviewState = ReviewState.PENDING
    corrections: tuple[int, ...] = ()
    token: OperationToken | None = None
    transcript_revision: int = 0
    confirmation: RatingConfirmation | None = None
    interrupted: bool = False

    def correct(self, rating: int) -> None:
        if self.state is not ReviewState.PENDING:
            raise ValueError("Correction is only permitted before commit")
        self.corrections += (self.rating,)
        self.rating = rating
        self.confirmation = None

    def has_confirmation(self) -> bool:
        event = self.confirmation
        return (event is not None and self.token is not None
                and event.token == self.token and event.identity == self.card.identity
                and event.rating == self.rating
                and event.transcript_revision == self.transcript_revision
                and event.final
                and (event.source is ConfirmationSource.TOUCH
                     or (event.source is ConfirmationSource.SPOKEN
                         and event.confidence is Confidence.SUFFICIENT)))


@dataclass(frozen=True)
class RawAcknowledgement:
    """One provider answer. ``update_count`` of 1 is not proof of a saved review."""

    update_count: int | None = None
    failure: Failure | None = None


@dataclass(frozen=True)
class ReviewOutcome:
    state: ReviewState
    reason: str
    failure: Failure | None = None
    acknowledgement: int | None = None
    pre_state: CardState | None = None
    post_state: CardState | None = None
    submitted_time_ms: int | None = None
    expected_stored_time_ms: int | None = None
    write_attempted: bool = False

    @property
    def time_was_capped(self) -> bool:
        """An expected transformation by the deck's maxTaken, not a unit error."""
        return (self.submitted_time_ms is not None
                and self.expected_stored_time_ms is not None
                and self.expected_stored_time_ms < self.submitted_time_ms)

    def stored_time_is_expected(self, stored_ms: int) -> bool:
        """For offline evidence only. The app cannot read the revlog back."""
        return stored_ms == self.expected_stored_time_ms


# --------------------------------------------------------------------------- #
# Contracts
# --------------------------------------------------------------------------- #

class CardProvider(Protocol):
    """Reads the scheduled VoiceQA queue. Performs no writes."""

    def capabilities(self) -> Capabilities | Failure: ...

    def next_card(self) -> ScheduledCard | QueueExhausted | Failure:
        """Offer the next scheduled card, or report a valid empty queue."""

    def read_card(self, card_id: int) -> ScheduledCard | Failure:
        """Re-read one card by ID for freshness and post-write verification."""


class SpeechOutput(Protocol):
    """Speaks one utterance. Receives Prompt text only for question audio."""

    def speak(self, token: OperationToken, utterance: Utterance) -> PlaybackResult: ...

    def cancel(self, token: OperationToken) -> None: ...


class SpeechInput(Protocol):
    """Captures one spoken answer. A failure is never an incorrect answer."""

    def listen(self, token: OperationToken, language: str) -> CaptureEvent: ...

    def finish_answer(self, token: OperationToken) -> None:
        """AV-012's Done: stop the microphone and let the recognizer finish.

        Not a cancel and not a verdict — the attempt stays alive until its final
        arrives or its deadline expires. ``listen`` blocks for the whole attempt,
        so this is called from another thread and every binding must accept that.
        Idempotent; a call for an attempt that is not capturing does nothing.
        """

    def cancel(self, token: OperationToken) -> None: ...


class Grader(Protocol):
    """Advisory semantic grading. Never returns or implies an Anki rating."""

    def grade(self, request: GradingRequest) -> GradingReply: ...

    def cancel(self, request: GradingRequest) -> None: ...


class ReviewTransport(Protocol):
    """The single-shot write. Called at most once per intent, never replayed."""

    def answer_card(self, identity: CardIdentity, rating: int,
                    elapsed_ms: int) -> RawAcknowledgement: ...


class ReviewWriter(Protocol):
    """Commits one review under the AV-004 limits and classifies the outcome."""

    def commit(self, intent: ReviewIntent) -> ReviewOutcome: ...


# --------------------------------------------------------------------------- #
# The guarded write
# --------------------------------------------------------------------------- #

def is_one_review_transition(pre: CardState, post: CardState) -> tuple[bool, str]:
    """AV-004's verification route: the only evidence an ordinary app can read.

    Anki owns scheduling; validate readable structural invariants, never calculate
    an expected interval. Unsupported transitions remain unconfirmed.
    """
    if post.reps != pre.reps + 1:
        return False, f"reps {pre.reps} -> {post.reps}, expected {pre.reps + 1}"
    if post.last_review_time_secs is None or post.last_review_time_secs <= 0:
        return False, "last review time is unpopulated"
    if pre.last_review_time_secs and post.last_review_time_secs < pre.last_review_time_secs:
        return False, "last review time moved backwards"
    if (post.card_type, post.queue) not in {(1, 1), (1, 3), (2, 2), (3, 1), (3, 3)}:
        return False, "unexpected post-review type/queue"
    if post.interval_days < 0:
        return False, "negative review interval"
    if (post.card_type, post.queue, post.due, post.interval_days) == \
            (pre.card_type, pre.queue, pre.due, pre.interval_days):
        return False, "type, queue, due and interval are all unchanged"
    return True, "reps + 1, populated last review time, scheduling advanced"


class GuardedReviewWriter:
    """The required ReviewWriter algorithm, independent of any transport.

    Re-queries immediately before the write, submits once, then verifies. A race
    remains between the freshness check and the write: the reviewed contract
    offers no transaction, idempotency key or atomic compare-and-write, so the
    post-write read and the pause rule are load-bearing, not belt and braces.
    """

    def __init__(self, provider: CardProvider, transport: ReviewTransport,
                 capabilities: Capabilities) -> None:
        self.provider = provider
        self.transport = transport
        self.capabilities = capabilities

    def commit(self, intent: ReviewIntent) -> ReviewOutcome:
        if intent.state is not ReviewState.PENDING:
            raise ValueError("Only a pending review may be committed")
        snapshot = intent.card

        # A rejected rating stays rejected. It is never converted to Again.
        if type(intent.rating) is not int or intent.rating not in snapshot.permitted_ratings:
            return self._reject(intent, ReviewWriterFailure.RATING_REJECTED,
                                f"rating {intent.rating} is not in "
                                f"{list(snapshot.permitted_ratings)} for this card")
        if type(intent.elapsed_ms) is not int or intent.elapsed_ms < 0:
            return self._reject(intent, ReviewWriterFailure.INVALID_REVIEW_TIME,
                                "elapsed time must be a nonnegative integer in milliseconds")
        expected_stored = min(intent.elapsed_ms, self.capabilities.max_review_time_ms)
        if not intent.has_confirmation():
            return self._reject(intent, ReviewWriterFailure.CONFIRMATION_REQUIRED,
                                "a current explicit learner confirmation is required")

        offered = self.provider.next_card()
        if isinstance(offered, Failure):
            return self._reject(intent, ReviewWriterFailure.PRECOMMIT_READ_FAILED,
                                str(offered), cause=offered)
        if (isinstance(offered, QueueExhausted)
                or offered.identity != snapshot.identity or offered.state != snapshot.state):
            return self._reject(intent, ReviewWriterFailure.STALE_IDENTITY,
                                "the scheduled card changed or is no longer offered")
        fresh = self.provider.read_card(snapshot.identity.card_id)
        if isinstance(fresh, Failure):
            return self._reject(intent, ReviewWriterFailure.PRECOMMIT_READ_FAILED,
                                str(fresh), cause=fresh)
        if fresh.identity != snapshot.identity:
            return self._reject(intent, ReviewWriterFailure.STALE_IDENTITY,
                                f"identity moved: {snapshot.identity} -> {fresh.identity}")
        if fresh.state != snapshot.state:
            return self._reject(intent, ReviewWriterFailure.STALE_IDENTITY,
                                f"stored state moved: {snapshot.state} -> {fresh.state}")
        if fresh.fields != snapshot.fields:
            return self._reject(intent, ReviewWriterFailure.STALE_IDENTITY,
                                "VoiceQA content changed since the question was offered")
        if intent.rating not in fresh.permitted_ratings or intent.rating not in offered.permitted_ratings:
            return self._reject(intent, ReviewWriterFailure.RATING_REJECTED,
                                f"rating {intent.rating} is no longer offered for this card")
        if intent.state is not ReviewState.PENDING or not intent.has_confirmation():
            return self._reject(intent, ReviewWriterFailure.CONFIRMATION_REQUIRED,
                                "confirmation was cancelled during the precommit read")

        intent.state = ReviewState.SUBMITTING
        ack = self.transport.answer_card(fresh.identity, intent.rating, intent.elapsed_ms)
        base = dict(acknowledgement=ack.update_count, pre_state=fresh.state,
                    submitted_time_ms=intent.elapsed_ms,
                    expected_stored_time_ms=expected_stored, write_attempted=True)

        if intent.interrupted:
            return self._settle(intent, ReviewOutcome(
                ReviewState.OUTCOME_UNKNOWN,
                "single active reviewer precondition broke during submission", **base))

        if not self.capabilities.supports_post_write_verification:
            return self._settle(intent, ReviewOutcome(
                ReviewState.OUTCOME_UNKNOWN,
                "post-write verification is unavailable on this provider",
                failure=ack.failure, **base))
        if ack.failure is not None or ack.update_count is None:
            return self._settle(intent, ReviewOutcome(
                ReviewState.OUTCOME_UNKNOWN,
                f"null or error response to the write ({ack.failure or 'null cursor'})",
                failure=ack.failure, **base))

        after = self.provider.read_card(fresh.identity.card_id)
        if intent.interrupted:
            return self._settle(intent, ReviewOutcome(
                ReviewState.OUTCOME_UNKNOWN,
                "single active reviewer precondition broke during verification", **base))
        if isinstance(after, Failure):
            return self._settle(intent, ReviewOutcome(
                ReviewState.OUTCOME_UNKNOWN,
                f"post-write state is unavailable ({after})", failure=after, **base))
        base["post_state"] = after.state
        if after.identity != fresh.identity:
            return self._settle(intent, ReviewOutcome(
                ReviewState.OUTCOME_UNKNOWN,
                f"post-write identity does not match: {after.identity}", **base))

        consistent, note = is_one_review_transition(fresh.state, after.state)
        if ack.update_count == 0:
            if after.state == fresh.state:
                return self._settle(intent, ReviewOutcome(
                    ReviewState.FAILED,
                    "provider rejected the write and the card is unchanged",
                    failure=Failure(ReviewWriterFailure.WRITE_REJECTED,
                                    "explicit zero, no state change"), **base))
            return self._settle(intent, ReviewOutcome(
                ReviewState.OUTCOME_UNKNOWN,
                f"provider reported no update but the card changed ({note})", **base))
        if ack.update_count != 1 or not consistent:
            return self._settle(intent, ReviewOutcome(
                ReviewState.OUTCOME_UNKNOWN,
                f"acknowledged write without a consistent one-review transition ({note})",
                **base))
        return self._settle(intent, ReviewOutcome(ReviewState.CONFIRMED, note, **base))

    def _reject(self, intent: ReviewIntent, mode: ReviewWriterFailure, detail: str,
                cause: Failure | None = None) -> ReviewOutcome:
        """No write was handed over, so the review provably did not land."""
        return self._settle(intent, ReviewOutcome(
            ReviewState.FAILED, detail, failure=Failure(mode, detail, cause),
            pre_state=intent.card.state, submitted_time_ms=intent.elapsed_ms,
            write_attempted=False))

    @staticmethod
    def _settle(intent: ReviewIntent, outcome: ReviewOutcome) -> ReviewOutcome:
        intent.state = outcome.state
        return outcome


# --------------------------------------------------------------------------- #
# Session
# --------------------------------------------------------------------------- #

class SessionState(Enum):
    IDLE = "idle"
    ASKING = "asking"
    LISTENING = "listening"
    GRADING = "grading"
    PROPOSING = "proposing"      # a pending review is open for correction
    COMMITTING = "committing"    # the single write is in flight
    COMMITTED = "committed"      # confirmed; awaiting advance or a handoff
    PAUSED = "paused"            # halted; the learner may resolve and resume
    STOPPED = "stopped"          # halted; reload the collection to continue
    EXHAUSTED = "exhausted"      # the queue emptied normally


HALTED_STATES = frozenset(
    {SessionState.PAUSED, SessionState.STOPPED, SessionState.EXHAUSTED})


@dataclass(frozen=True)
class Halt:
    reason: str
    detail: str
    resumable: bool                  # may continue once the learner fixes the cause
    reconciliation_required: bool    # a write may have landed; check AnkiDroid first


@dataclass(frozen=True)
class Event:
    step: str
    detail: str = ""


class Interruption(Enum):
    """Events that break the single-active-reviewer precondition."""

    APP_SWITCH = "app_switch"
    PROCESS_RESUME = "process_resume"
    SYNC = "sync"
    CONCURRENT_MODIFICATION = "concurrent_modification"
    LOCK = "lock"
    EXTERNAL_AUDIO = "external_audio"


class MonotonicClock:
    """Elapsed review time only. Never a wall clock, never used for scheduling."""

    def now_ms(self) -> int:
        return time.monotonic_ns() // 1_000_000


class ReviewSession:
    """The deterministic turn loop. Owns no scheduling and no persistence.

    #20 owns the persistent journal that survives process loss; #28 owns
    sync-handoff and stale-session validation. This class deliberately keeps
    nothing across a restart.
    """

    def __init__(self, provider: CardProvider, speech_output: SpeechOutput,
                 speech_input: SpeechInput, grader: Grader, writer: ReviewWriter,
                 capabilities: Capabilities, language: str = "en-US",
                 clock: MonotonicClock | None = None,
                 session_id: str | None = None) -> None:
        self.provider = provider
        self.speech_output = speech_output
        self.speech_input = speech_input
        self.grader = grader
        self.writer = writer
        self.capabilities = capabilities
        self.language = language
        self.clock = clock or MonotonicClock()
        self.state = SessionState.IDLE
        self.visited: list[SessionState] = [SessionState.IDLE]
        self.halt: Halt | None = None
        self.intent: ReviewIntent | None = None
        self.card: ScheduledCard | None = None
        self.events: list[Event] = []
        self.outcomes: list[ReviewOutcome] = []
        self._started_ms: int | None = None
        self.session_id = session_id or str(uuid4())
        self._turn = 0
        self._sequence = 0
        self.playback_token: OperationToken | None = None
        self.capture_token: OperationToken | None = None
        self.grading_request: GradingRequest | None = None
        self.answer: CaptureEvent | None = None
        self.transcript_revision = 0
        self.suggestion: GradingResult | None = None
        self.last_failure: Failure | None = None

    def _token(self) -> OperationToken:
        self._sequence += 1
        return OperationToken(self.session_id, self._turn, self._sequence)

    def _invalidate(self) -> None:
        """Invalidate first; cancellation itself can cause late callbacks."""
        playback, capture, grading = (self.playback_token, self.capture_token,
                                      self.grading_request)
        self.playback_token = self.capture_token = self.grading_request = None
        self.suggestion = None
        if self.intent is not None and self.intent.state is ReviewState.PENDING:
            self.intent.confirmation = None
            self.intent.state = ReviewState.FAILED
        elif self.intent is not None and self.intent.state is ReviewState.SUBMITTING:
            self.intent.interrupted = True
        if playback is not None:
            self.speech_output.cancel(playback)
        if capture is not None:
            self.speech_input.cancel(capture)
        if grading is not None:
            self.grader.cancel(grading)

    @property
    def halted(self) -> bool:
        return self.state in HALTED_STATES

    def _log(self, step: str, detail: str = "") -> None:
        self.events.append(Event(step, detail))

    def _enter(self, state: SessionState) -> None:
        self.state = state
        self.visited.append(state)

    def _pause(self, reason: str, detail: str,
               reconciliation_required: bool = False) -> Halt:
        return self._end(SessionState.PAUSED, reason, detail, True, reconciliation_required)

    def _stop(self, reason: str, detail: str,
              reconciliation_required: bool = False) -> Halt:
        return self._end(SessionState.STOPPED, reason, detail, False, reconciliation_required)

    def _end(self, state: SessionState, reason: str, detail: str, resumable: bool,
             reconciliation_required: bool) -> Halt:
        # No later halt may erase the obligation to reconcile an ambiguous write.
        reconciliation_required = (reconciliation_required
            or bool(self.halt and self.halt.reconciliation_required)
            or bool(self.intent and self.intent.state in (
                ReviewState.SUBMITTING, ReviewState.OUTCOME_UNKNOWN)))
        self._enter(state)
        self._invalidate()
        self.halt = Halt(reason, detail, resumable, reconciliation_required)
        self._log(state.value, f"{reason}: {detail}" if detail else reason)
        return self.halt

    def _halt_on_failure(self, failure: Failure) -> Halt:
        """A transport or provider fault. Never a rating, never an answer."""
        self.last_failure = failure
        if failure.mode in (CardProviderFailure.COLLECTION_CHANGED,
                            CardProviderFailure.DECK_MISSING,
                            CardProviderFailure.UNSUPPORTED_NOTE_TYPE):
            return self._stop(failure.mode.value, failure.detail)
        return self._pause(failure.mode.value, failure.detail)

    # -- learner-initiated halts ------------------------------------------ #

    def interrupt(self, kind: Interruption) -> Halt:
        """Stop rather than reconcile silently: reps and time cannot attribute a
        competing native or sync write to this caller."""
        unresolved = self.intent is not None and self.intent.state in (
            ReviewState.SUBMITTING, ReviewState.OUTCOME_UNKNOWN)
        return self._stop(kind.value, "single active reviewer precondition broken",
                          reconciliation_required=unresolved)

    def request_skip(self, exit_session: bool = False) -> Halt:
        """No skip exists. Halt without any write; never rate, bury or suspend."""
        if self.capabilities.supports_skip:
            raise ValueError("A real skip operation would need its own contract")
        if self.halted or self.state in (SessionState.COMMITTING, SessionState.COMMITTED):
            raise ValueError("Skip is only available before submission in an active turn")
        detail = "halted without a write; no non-mutating skip operation exists"
        return self._stop("skip_requested", detail) if exit_session \
            else self._pause("skip_requested", detail)

    def request_correction_after_commit(self) -> Halt:
        """Correction is pre-commit only. Hand off to AnkiDroid's native Undo."""
        if self.capabilities.supports_programmatic_undo:
            raise ValueError("Programmatic undo would need its own contract")
        if self.intent is None or self.intent.state is not ReviewState.CONFIRMED:
            raise ValueError("There is no confirmed review to hand off")
        return self._stop("native_undo_handoff",
                          "use AnkiDroid's Undo, then reload; no programmatic "
                          "or durable undo is available")

    # -- the turn --------------------------------------------------------- #

    def start(self) -> None:
        if self.state is not SessionState.IDLE or self.events:
            raise ValueError("The session has already started")
        self._log("start", f"language {self.language}")

    def offer_card(self) -> ScheduledCard | QueueExhausted | Halt:
        if self.state is not SessionState.IDLE:
            raise ValueError(f"The session is {self.state.value}")
        if self.intent is not None and self.intent.state is ReviewState.PENDING:
            raise ValueError("A pending review must be committed or discarded first")
        card = self.provider.next_card()
        if self.halted:
            return self.halt
        if isinstance(card, Failure):
            return self._halt_on_failure(card)
        if isinstance(card, QueueExhausted):
            self._enter(SessionState.EXHAUSTED)
            self._log("exhausted", "the queue emptied normally")
            return card
        self.card = card
        self.intent = None
        self.answer = None
        self.suggestion = None
        self.last_failure = None
        self.transcript_revision = 0
        self._started_ms = None
        self._turn += 1
        self._enter(SessionState.ASKING)
        self._log("offer_card", f"card {card.identity.card_id}, "
                                f"ratings {list(card.permitted_ratings)}")
        return card

    def ask(self) -> Utterance | Halt:
        if self.state is not SessionState.ASKING:
            raise ValueError(f"Cannot ask while {self.state.value}")
        if self.playback_token is not None:
            raise ValueError("Cancel or complete the current playback before asking again")
        utterance = question_utterance(self.card, self.language)
        self.playback_token = self._token()
        result = self.speech_output.speak(self.playback_token, utterance)
        completion = self.finish_playback(result)
        if isinstance(completion, Halt):
            return completion
        if self.halted:
            return self.halt
        return utterance

    def finish_playback(self, result: PlaybackResult) -> bool | Halt:
        if result.token != self.playback_token or self.halted:
            self._log("stale_playback", "ignored")
            return False
        if result.failure is not None:
            return self._halt_on_failure(result.failure)
        self.playback_token = None
        # Reveal/feedback playback never starts a new answer capture.
        if self.state is not SessionState.ASKING:
            return True
        self._started_ms = self.clock.now_ms()
        self.capture_token = self._token()
        self._enter(SessionState.LISTENING)
        self._log("ask", self.card.fields.prompt)
        return True

    def listen(self) -> CaptureEvent | Halt | None:
        if self.state is not SessionState.LISTENING:
            raise ValueError(f"Cannot listen while {self.state.value}")
        event = self.speech_input.listen(self.capture_token,
                                        card_language(self.card, self.language))
        return self.accept_capture(event)

    def accept_capture(self, event: CaptureEvent) -> CaptureEvent | Halt | None:
        if event.token != self.capture_token or self.state is not SessionState.LISTENING:
            self._log("stale_capture", "ignored")
            return None
        if event.failure is not None:
            return self._halt_on_failure(event.failure)
        if event.kind is TranscriptKind.PARTIAL:
            self._log("partial", event.text)
            return None
        if event.kind is not TranscriptKind.FINAL:
            raise ValueError("Only learner actions may create corrected transcripts")
        if not event.text.strip():
            return self._halt_on_failure(Failure(SpeechInputFailure.NO_MATCH,
                                                 "empty final; cause unknown"))
        if event.confidence is not Confidence.SUFFICIENT:
            return self._halt_on_failure(Failure(SpeechInputFailure.LOW_CONFIDENCE,
                                                 "learner correction or retry required"))
        token = self.capture_token
        self.capture_token = None
        self.answer = event
        self.transcript_revision += 1
        self._enter(SessionState.GRADING)
        self.speech_input.cancel(token)
        if self.halted:
            return self.halt
        self._log("listen", event.text)
        return event

    def correct_transcript(self, text: str) -> CaptureEvent:
        fallback = (self.state is SessionState.PAUSED and self.last_failure is not None
                    and isinstance(self.last_failure.mode, (SpeechInputFailure, GraderFailure))
                    and not self.halt.reconciliation_required)
        if (self.state not in (SessionState.GRADING, SessionState.PROPOSING)
                and not fallback) or not text.strip():
            raise ValueError("A transcript edit requires an answer turn and nonempty text")
        self._invalidate()
        if self.state is SessionState.STOPPED:
            raise ValueError("The session stopped during cancellation; reload required")
        self.intent = None
        self.halt = None
        self.last_failure = None
        self.transcript_revision += 1
        self.answer = CaptureEvent(self._token(), text, TranscriptKind.CORRECTED)
        self._enter(SessionState.GRADING)
        self._log("correct_transcript", f"revision {self.transcript_revision}: {text}")
        return self.answer

    def begin_grade(self) -> GradingRequest:
        if self.state is not SessionState.GRADING:
            raise ValueError(f"Cannot grade while {self.state.value}")
        if self.answer is None:
            raise ValueError("Grading requires a final or learner-corrected transcript")
        if self.grading_request is not None:
            old_request = self.grading_request
            self.grading_request = None
            self.grader.cancel(old_request)
        if self.state is not SessionState.GRADING:
            raise ValueError("The session stopped during grading cancellation")
        self.suggestion = None
        self.grading_request = GradingRequest(
            self._token(), self.transcript_revision,
            grading_context(self.card, self.answer.text, self.language))
        return self.grading_request

    def grade(self, transcript: CaptureEvent | None = None) -> GradingResult | Halt | None:
        if transcript is not None and transcript != self.answer:
            raise ValueError("Only the current accepted transcript may be graded")
        return self.accept_grade(self.grader.grade(self.begin_grade()))

    def accept_grade(self, reply: GradingReply) -> GradingResult | Halt | None:
        if (self.state is not SessionState.GRADING
                or reply.request != self.grading_request
                or reply.request.transcript_revision != self.transcript_revision):
            self._log("stale_grade", "ignored")
            return None
        self.grading_request = None
        result = reply.result
        if isinstance(result, Failure):
            return self._halt_on_failure(result)
        self.suggestion = result
        self._log("grade", f"{result.label.value}: {result.reason}")
        return result

    def self_grade(self, rating: int) -> ReviewIntent | Failure:
        """Explicit learner fallback; a provider failure never supplies a rating."""
        if (self.state is SessionState.PAUSED and self.last_failure is not None
                and isinstance(self.last_failure.mode, GraderFailure)
                and not self.halt.reconciliation_required and self.answer is not None):
            self.halt = None
            self._enter(SessionState.GRADING)
        if self.state not in (SessionState.GRADING, SessionState.PROPOSING):
            raise ValueError("Self-grade requires an accepted or corrected answer")
        self._log("self_grade", f"learner selected {rating}")
        return self.propose(rating)

    def reveal(self, include_extra: bool = False) -> Utterance | Halt:
        if self.state not in (SessionState.GRADING, SessionState.PROPOSING) or self.answer is None:
            raise ValueError("Reveal requires an accepted or learner-corrected answer")
        utterance = (elaboration_utterance(self.card, self.language) if include_extra
                     else reveal_utterance(self.card, self.language))
        if utterance is None:
            raise ValueError("This card has no Extra")
        if self.playback_token is not None:
            raise ValueError("Cancel or complete the current playback before reveal")
        self.playback_token = self._token()
        result = self.finish_playback(self.speech_output.speak(self.playback_token, utterance))
        if self.halted:
            return self.halt
        return result if isinstance(result, Halt) else utterance

    def propose(self, rating: int) -> ReviewIntent | Failure:
        """Open the correction window. Nothing is written until commit."""
        if self.state not in (SessionState.GRADING, SessionState.PROPOSING):
            raise ValueError(f"Cannot propose while {self.state.value}")
        rejection = self._reject_unoffered(rating)
        if rejection is not None:
            return rejection
        if self.answer is None:
            raise ValueError("A rating requires an accepted or learner-corrected answer")
        self._invalidate()
        if self.halted:
            raise ValueError("The session stopped during cancellation; reload required")
        now = self.clock.now_ms()
        started = now if self._started_ms is None else self._started_ms
        elapsed = now - started
        self.intent = ReviewIntent(self.card, rating, max(0, elapsed),
                                   token=self._token(),
                                   transcript_revision=self.transcript_revision)
        self._enter(SessionState.PROPOSING)
        self._log("propose", f"rating {rating}, {self.intent.elapsed_ms} ms")
        return self.intent

    def correct(self, rating: int) -> ReviewIntent | Failure:
        if self.state is not SessionState.PROPOSING:
            raise ValueError("Correction is only permitted before commit")
        rejection = self._reject_unoffered(rating)
        if rejection is not None:
            return rejection
        self.intent.correct(rating)
        self.intent.token = self._token()
        self._log("correct", f"rating {rating}")
        return self.intent

    def _reject_unoffered(self, rating: int) -> Failure | None:
        """The window stays open and the rating is never replaced with Again."""
        if type(rating) is int and rating in self.card.permitted_ratings:
            return None
        failure = Failure(ReviewWriterFailure.RATING_REJECTED,
                          f"rating {rating} is not in "
                          f"{list(self.card.permitted_ratings)} for this card")
        self._log("rating_rejected", failure.detail)
        return failure

    def confirm(self, event: RatingConfirmation) -> bool:
        if self.state is not SessionState.PROPOSING:
            self._log("confirmation_rejected", "no pending rating")
            return False
        self.intent.confirmation = event
        if not self.intent.has_confirmation():
            self.intent.confirmation = None
            self._log("confirmation_rejected", "stale, mismatched or uncertain event")
            return False
        self._log("confirm", f"{event.source.value}: rating {event.rating}")
        return True

    def commit(self) -> ReviewOutcome:
        if self.state is not SessionState.PROPOSING:
            raise ValueError(f"Cannot commit while {self.state.value}")
        if not self.intent.has_confirmation():
            raise ValueError("Explicit learner confirmation is required")
        self.intent.elapsed_ms = max(0, self.clock.now_ms() - self._started_ms)
        self._enter(SessionState.COMMITTING)
        outcome = self.writer.commit(self.intent)
        self.outcomes.append(outcome)
        self._log("commit", f"{outcome.state.value}: {outcome.reason}")
        if self.state is SessionState.STOPPED:
            # An interruption during the single write cannot be undone by its callback.
            return outcome
        if outcome.state is ReviewState.OUTCOME_UNKNOWN:
            # No replay, no advance, no success announcement.
            self._pause("outcome_unknown", outcome.reason, reconciliation_required=True)
        elif outcome.state is ReviewState.FAILED:
            cause = outcome.failure.cause
            if (outcome.failure.mode is ReviewWriterFailure.STALE_IDENTITY
                    or (cause is not None and cause.mode in (
                        CardProviderFailure.COLLECTION_CHANGED, CardProviderFailure.DECK_MISSING))):
                self._stop(outcome.failure.mode.value, outcome.reason)
            else:
                self._pause(outcome.failure.mode.value, outcome.reason)
        else:
            self._enter(SessionState.COMMITTED)
        return outcome

    def announce_result(self, outcome: ReviewOutcome) -> Utterance:
        """Only a confirmed review may be announced as saved."""
        if (outcome.state is not ReviewState.CONFIRMED
                or self.state is not SessionState.COMMITTED
                or not self.outcomes or outcome is not self.outcomes[-1]):
            raise ValueError("Only a confirmed review may be announced as saved")
        return Utterance(UtterancePurpose.ANNOUNCEMENT,
                         f"Saved rating {self.intent.rating}.", self.language)

    def advance(self) -> None:
        """Permitted only after a confirmed review."""
        if (self.state is not SessionState.COMMITTED or self.intent is None
                or self.intent.state is not ReviewState.CONFIRMED):
            raise ValueError("Advance requires a confirmed review")
        self.intent = None
        self.card = None
        self.answer = None
        self._enter(SessionState.IDLE)
        self._log("advance", "confirmed; moving to the next card")

    def resume(self) -> None:
        """Explicit learner action after a resumable pause. Never automatic."""
        if self.state is not SessionState.PAUSED or not self.halt.resumable:
            raise ValueError("Only a resumable pause may be resumed")
        if self.halt.reconciliation_required:
            raise ValueError("Reconcile the unconfirmed review before resuming")
        self.intent = None  # the snapshot is dead; re-query for a fresh card
        self.card = None
        self.answer = None
        self.last_failure = None
        self.halt = None
        self._enter(SessionState.IDLE)
        self._log("resume", "learner resolved the cause; re-querying")

    def reconcile(self, learner_confirmed_saved: bool) -> None:
        """The learner reports what AnkiDroid actually shows. Never inferred."""
        if self.halt is None or not self.halt.reconciliation_required:
            raise ValueError("There is nothing to reconcile")
        self._log("reconcile",
                  f"learner reports the review was "
                  f"{'saved' if learner_confirmed_saved else 'not saved'}; "
                  "reload the collection before studying again")


def transcript(session: ReviewSession) -> str:
    return "\n".join(f"  {e.step:<17} {e.detail}".rstrip() for e in session.events)
