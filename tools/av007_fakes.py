"""In-memory fakes for the AV-007 contracts. No emulator, no network, no clock.

These exist so #23, #25, #16, #20 and #21 can be developed and tested without a
device. They are deliberately not a scheduler: the state a review produces here
is a legible stand-in, and only the shape AV-004 verified (reps + 1, a populated
last review time, scheduling that moved) is meaningful. The real adapter is #25
and the persistent journal is #20.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, replace
from enum import Enum

from tools.av007_contracts import (
    Capabilities, CardIdentity, CardProviderFailure, CardState, Failure,
    GraderFailure, GradingContext, GradingResult, GuardedReviewWriter, MonotonicClock, QueueExhausted,
    RawAcknowledgement, ScheduledCard, SpeechInputFailure, Utterance,
    VOICEQA_MODEL, VoiceQAFields,
    CaptureEvent, Confidence, GradingReply, GradingRequest, OperationToken,
    PlaybackResult,
)

# Stand-in scheduling, not Anki's algorithm. Again drops the interval; the other
# ratings grow it. Only the fact that scheduling moved is contractually meaningful.
INTERVAL_FACTORS = {1: 0.0, 2: 1.2, 3: 2.5, 4: 3.25}
CARD_TYPE_REVIEW = 2
QUEUE_REVIEW = 2


class FakeClock(MonotonicClock):
    """Deterministic elapsed time. Advanced explicitly by a scenario."""

    def __init__(self, start_ms: int = 0) -> None:
        self.value_ms = start_ms

    def now_ms(self) -> int:
        return self.value_ms

    def advance(self, ms: int) -> None:
        self.value_ms += ms


@dataclass
class StoredCard:
    identity: CardIdentity
    state: CardState
    fields: VoiceQAFields
    permitted_ratings: tuple[int, ...] = (1, 2, 3, 4)


class FakeCollection:
    """The store the provider reads and the transport writes.

    ``reviews`` is the offline-evidence analogue of the revlog AV-004 inspected
    outside the app. No contract may read it; tests use it to prove that exactly
    one review, or none, was recorded.
    """

    def __init__(self, cards: list[StoredCard], max_review_time_ms: int = 60_000) -> None:
        self.cards = {card.identity.card_id: card for card in cards}
        self.order = [card.identity.card_id for card in cards]
        self.max_review_time_ms = max_review_time_ms
        self.reviews: list[dict] = []
        self._epoch = 1_789_414_023

    def scheduled(self, card_id: int) -> ScheduledCard:
        card = self.cards[card_id]
        return ScheduledCard(card.identity, card.state, card.fields,
                             card.permitted_ratings)

    def rebuild_queue(self, order: list[int]) -> None:
        """AV-004: force-stopping AnkiDroid replaced the offered card."""
        self.order = list(order)

    def apply_review(self, card_id: int, rating: int, elapsed_ms: int,
                     repetitions: int = 1, source: str = "api") -> None:
        card = self.cards[card_id]
        for _ in range(repetitions):
            self._epoch += 60
            state = card.state
            if rating == 1:
                interval = 0
            else:
                interval = max(1, round(max(state.interval_days, 1)
                                        * INTERVAL_FACTORS[rating]))
            card.state = CardState(
                reps=state.reps + 1, card_type=CARD_TYPE_REVIEW, queue=QUEUE_REVIEW,
                due=state.due + interval + 1, interval_days=interval,
                last_review_time_secs=self._epoch)
            self.reviews.append({
                "card_id": card_id, "rating": rating, "source": source,
                # Anki truncates at the deck's maxTaken on store; AV-004 saw
                # 98,765 ms persisted as 60,000 ms.
                "time_taken_ms": min(elapsed_ms, self.max_review_time_ms),
            })
        if card_id in self.order:
            self.order.remove(card_id)

    def native_answer(self, card_id: int, rating: int = 3) -> None:
        """A competing write by the native reviewer or a sync."""
        self.apply_review(card_id, rating, 4_200, source="native")

    def set_permitted_ratings(self, card_id: int, ratings: tuple[int, ...]) -> None:
        self.cards[card_id].permitted_ratings = ratings

    def mutate_state(self, card_id: int, **changes) -> None:
        card = self.cards[card_id]
        card.state = replace(card.state, **changes)


def _pop(script: deque):
    return script.popleft() if script else None


class FakeCardProvider:
    """Reads only. ``script`` entries replace one call each, in order."""

    def __init__(self, collection: FakeCollection,
                 capabilities: Capabilities | None = None) -> None:
        self.collection = collection
        self._capabilities = capabilities or Capabilities(
            max_review_time_ms=collection.max_review_time_ms)
        self.next_card_script: deque = deque()
        self.read_card_script: deque = deque()
        self.reads: list[int] = []
        self.capabilities_script: deque = deque()

    def capabilities(self) -> Capabilities | Failure:
        return _pop(self.capabilities_script) or self._capabilities

    @staticmethod
    def _validate(card: ScheduledCard) -> ScheduledCard | Failure:
        if card.identity.model != VOICEQA_MODEL:
            return Failure(CardProviderFailure.UNSUPPORTED_NOTE_TYPE, "VoiceQA required")
        if (card.identity.ordinal != 0 or not card.fields.prompt.strip()
                or not card.fields.reference_answer.strip()):
            return Failure(CardProviderFailure.MALFORMED_CARD, "invalid VoiceQA card")
        return card

    def next_card(self) -> ScheduledCard | QueueExhausted | Failure:
        scripted = _pop(self.next_card_script)
        if scripted is not None:
            return scripted
        if not self.collection.order:
            return QueueExhausted()
        card_id = self.collection.order[0]
        if card_id not in self.collection.cards:
            return Failure(CardProviderFailure.CARD_NOT_FOUND, f"card {card_id}")
        return self._validate(self.collection.scheduled(card_id))

    def read_card(self, card_id: int) -> ScheduledCard | Failure:
        self.reads.append(card_id)
        scripted = _pop(self.read_card_script)
        if scripted is not None:
            return scripted
        if card_id not in self.collection.cards:
            return Failure(CardProviderFailure.CARD_NOT_FOUND, f"card {card_id}")
        return self._validate(self.collection.scheduled(card_id))


class WriteAnomaly(Enum):
    """Provider responses AV-004 observed or the contract must survive."""

    ACKNOWLEDGE_WITHOUT_WRITE = "acknowledge_without_write"  # observed: 1, nothing saved
    REJECT_WITH_ZERO = "reject_with_zero"                    # observed: invalid rating
    NULL_RESPONSE = "null_response"                          # null cursor
    ERROR_RESPONSE = "error_response"
    DOUBLE_APPLY = "double_apply"                            # reps + 2
    SUSPENDED_INSTEAD = "suspended_instead"                  # unexpected delta
    ZERO_BUT_APPLIED = "zero_but_applied"                    # contradictory zero
    UNEXPECTED_COUNT = "unexpected_count"


class FakeReviewTransport:
    """One single-shot write per intent. Records every call so replay is visible."""

    def __init__(self, collection: FakeCollection) -> None:
        self.collection = collection
        self.anomalies: deque = deque()
        self.calls: list[tuple[CardIdentity, int, int]] = []

    def answer_card(self, identity: CardIdentity, rating: int,
                    elapsed_ms: int) -> RawAcknowledgement:
        self.calls.append((identity, rating, elapsed_ms))
        anomaly = _pop(self.anomalies)
        card_id = identity.card_id
        if anomaly is WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE:
            return RawAcknowledgement(update_count=1)
        if anomaly is WriteAnomaly.REJECT_WITH_ZERO:
            return RawAcknowledgement(update_count=0)
        if anomaly is WriteAnomaly.NULL_RESPONSE:
            return RawAcknowledgement(update_count=None)
        if anomaly is WriteAnomaly.ERROR_RESPONSE:
            return RawAcknowledgement(
                update_count=None,
                failure=Failure(CardProviderFailure.API_DISABLED,
                                "AnkiDroid API switched off mid-write"))
        if anomaly is WriteAnomaly.DOUBLE_APPLY:
            self.collection.apply_review(card_id, rating, elapsed_ms, repetitions=2)
            return RawAcknowledgement(update_count=1)
        if anomaly is WriteAnomaly.SUSPENDED_INSTEAD:
            self.collection.apply_review(card_id, rating, elapsed_ms)
            self.collection.mutate_state(card_id, queue=-1)
            return RawAcknowledgement(update_count=1)
        if anomaly is WriteAnomaly.ZERO_BUT_APPLIED:
            self.collection.apply_review(card_id, rating, elapsed_ms)
            return RawAcknowledgement(update_count=0)
        self.collection.apply_review(card_id, rating, elapsed_ms)
        if anomaly is WriteAnomaly.UNEXPECTED_COUNT:
            return RawAcknowledgement(update_count=2)
        return RawAcknowledgement(update_count=1)


class FakeSpeechOutput:
    """Records every attempted utterance, including ones that failed to play."""

    def __init__(self) -> None:
        self.script: deque = deque()
        self.attempted: list[Utterance] = []
        self.spoken: list[Utterance] = []
        self.cancelled: list[OperationToken] = []
        self.on_speak = None

    def speak(self, token: OperationToken, utterance: Utterance) -> PlaybackResult:
        self.attempted.append(utterance)
        if self.on_speak is not None:
            self.on_speak(token)
        scripted = _pop(self.script)
        if isinstance(scripted, PlaybackResult):
            return scripted
        if isinstance(scripted, Failure):
            return PlaybackResult(token, scripted)
        if token not in self.cancelled:
            self.spoken.append(utterance)
        return PlaybackResult(token)

    def cancel(self, token: OperationToken) -> None:
        if token not in self.cancelled:
            self.cancelled.append(token)


class FakeReviewWriter(GuardedReviewWriter):
    """In-memory ReviewWriter using the specified guards over the fake provider
    and transport. Script reads/acknowledgements there to exercise real guards,
    rather than scripting a successful outcome that bypasses verification."""


class FakeSpeechInput:
    """Each script entry is a transcript or a Failure; a Failure is not an answer."""

    def __init__(self, script=()) -> None:
        self.script: deque = deque(script)
        self.languages: list[str] = []
        self.cancelled: list[OperationToken] = []

    def listen(self, token: OperationToken, language: str) -> CaptureEvent:
        self.languages.append(language)
        if not self.script:
            return CaptureEvent(token, failure=Failure(
                SpeechInputFailure.EARLY_CLOSURE, "script exhausted without a final"))
        event = self.script.popleft()
        if isinstance(event, CaptureEvent):
            return event
        if isinstance(event, Failure):
            return CaptureEvent(token, failure=event)
        return CaptureEvent(token, event, confidence=Confidence.SUFFICIENT)

    def cancel(self, token: OperationToken) -> None:
        if token not in self.cancelled:
            self.cancelled.append(token)


class FakeGrader:
    """Advisory only. ``seen`` proves Extra never reaches the grading criteria."""

    def __init__(self, script=()) -> None:
        self.script: deque = deque(script)
        self.seen: list[GradingContext] = []
        self.requests: list[GradingRequest] = []
        self.cancelled: list[GradingRequest] = []

    def grade(self, request: GradingRequest) -> GradingReply:
        self.requests.append(request)
        self.seen.append(request.context)
        if not self.script:
            return GradingReply(request, Failure(GraderFailure.PROVIDER_ERROR, "script exhausted"))
        result = self.script.popleft()
        return result if isinstance(result, GradingReply) else GradingReply(request, result)

    def cancel(self, request: GradingRequest) -> None:
        if request not in self.cancelled:
            self.cancelled.append(request)


# --------------------------------------------------------------------------- #
# A small VoiceQA collection built from the AV-002 fixture shapes
# --------------------------------------------------------------------------- #

def demo_card(card_id: int, prompt: str, reference: str, state: CardState,
              concepts: tuple[str, ...] = (), accepted: tuple[str, ...] = (),
              extra: str = "", deck_id: int = 1789414083100,
              permitted: tuple[int, ...] = (1, 2, 3, 4)) -> StoredCard:
    return StoredCard(
        identity=CardIdentity(card_id=card_id, note_id=card_id, deck_id=deck_id,
                              ordinal=0, model=VOICEQA_MODEL),
        state=state,
        fields=VoiceQAFields(prompt=prompt, reference_answer=reference,
                             required_concepts=concepts, accepted_answers=accepted,
                             language="en-US", extra=extra),
        permitted_ratings=permitted,
    )


def demo_collection() -> FakeCollection:
    """Two VoiceQA cards taken from the fixture examples, with distinct states."""
    return FakeCollection([
        demo_card(
            1789414083106,
            "A box has three red blocks and two blue blocks. How many blocks are there in total?",
            "Five blocks.",
            CardState(reps=0, card_type=0, queue=0, due=1, interval_days=0),
            concepts=("Five",), accepted=("Five", "5", "There are five blocks"),
            extra="Three plus two equals five."),
        demo_card(
            1789414083109,
            "Reverse the sequence red, blue, green.",
            "Green, blue, red.",
            CardState(reps=3, card_type=2, queue=2, due=90, interval_days=30,
                      last_review_time_secs=1_789_400_000),
            concepts=("Green first", "Blue second", "Red last"),
            accepted=("Green blue red",),
            extra="Reversing changes the order, not the items."),
    ])
