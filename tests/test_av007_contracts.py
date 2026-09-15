"""AV-007 acceptance checks. In-memory only: no emulator, network or provider."""
from pathlib import Path
import unittest

from tools.av007_contracts import (
    ALL_FAILURE_MODES, Capabilities, CardIdentity, CardProviderFailure, CardState,
    Failure, GradeLabel, GradingResult, GuardedReviewWriter, Halt, Interruption,
    QueueExhausted, ReviewIntent, ReviewState, ReviewWriterFailure, SessionState,
    SpeechInputFailure,
    UtterancePurpose, VOICEQA_MODEL, grading_context, is_one_review_transition,
    question_utterance, reveal_utterance,
)
from tools.av007_fakes import (
    FakeCardProvider, FakeClock, FakeReviewTransport, WriteAnomaly,
    demo_collection,
)
from tools import av007_scenarios as scenarios

ROOT = Path(__file__).resolve().parents[1]
SPECIFICATION = ROOT / "docs/contracts/av007-session-contracts.md"
TRANSCRIPTS = ROOT / "docs/contracts/av007/transcripts.md"
CARD_ID = 1789414083106


def committed(harness):
    """Reviews written through the contracts, not by a competing native writer."""
    return [r for r in harness.collection.reviews if r["source"] == "api"]


def turn(harness, rating=3, elapsed_ms=12_345):
    """Drive one turn to a committed outcome."""
    scenarios.ask_listen_grade(harness, elapsed_ms=elapsed_ms)
    harness.session.propose(rating)
    return scenarios.confirm_and_commit(harness.session)


class IdentityAndStalenessTests(unittest.TestCase):
    """A scheduled card is a snapshot, not a reservation."""

    def test_identity_is_the_tuple_av004_proved_readable(self):
        card = demo_collection().scheduled(CARD_ID)
        self.assertEqual(
            (card.identity.card_id, card.identity.note_id, card.identity.ordinal,
             card.identity.model),
            (CARD_ID, CARD_ID, 0, VOICEQA_MODEL))
        self.assertGreater(card.identity.deck_id, 0)

    def test_commit_rereads_the_card_immediately_before_writing(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness)
        harness.session.propose(3)
        self.assertEqual(harness.provider.reads, [])
        scenarios.confirm_and_commit(harness.session)
        # One freshness read before the write and one verification read after it.
        self.assertEqual(harness.provider.reads, [CARD_ID, CARD_ID])

    def test_moved_stored_state_rejects_the_write_before_it_is_attempted(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness)
        harness.session.propose(3)
        harness.collection.native_answer(CARD_ID)
        outcome = scenarios.confirm_and_commit(harness.session)
        self.assertEqual(outcome.state, ReviewState.FAILED)
        self.assertEqual(outcome.failure.mode, ReviewWriterFailure.STALE_IDENTITY)
        self.assertFalse(outcome.write_attempted)
        self.assertEqual(harness.transport.calls, [])
        self.assertEqual(committed(harness), [])

    def test_moved_identity_rejects_the_write(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness)
        harness.session.propose(3)
        moved = CardIdentity(CARD_ID, CARD_ID, 999, 0, VOICEQA_MODEL)
        harness.collection.cards[CARD_ID].identity = moved
        outcome = scenarios.confirm_and_commit(harness.session)
        self.assertEqual(outcome.failure.mode, ReviewWriterFailure.STALE_IDENTITY)
        self.assertFalse(outcome.write_attempted)

    def test_a_rebuilt_queue_does_not_reserve_the_previously_offered_card(self):
        harness = scenarios.build()
        offered = harness.session.offer_card()
        harness.collection.rebuild_queue([1789414083109])
        self.assertNotEqual(harness.provider.next_card().identity, offered.identity)

    def test_the_remaining_race_is_recorded_in_the_contract(self):
        self.assertIn("race", GuardedReviewWriter.__doc__.lower())


class QuestionAnswerSeparationTests(unittest.TestCase):
    """The question side never exposes the reference answer."""

    def setUp(self):
        self.card = demo_collection().scheduled(CARD_ID)

    def test_question_audio_carries_the_prompt_and_nothing_else(self):
        utterance = question_utterance(self.card, "en-US")
        self.assertEqual(utterance.purpose, UtterancePurpose.QUESTION)
        self.assertEqual(utterance.text, self.card.fields.prompt)
        self.assertNotIn(self.card.fields.reference_answer.lower(), utterance.text.lower())
        self.assertNotIn(self.card.fields.extra.lower(), utterance.text.lower())

    def test_the_reference_answer_is_a_separate_purpose(self):
        self.assertEqual(reveal_utterance(self.card, "en-US").purpose,
                         UtterancePurpose.REVEAL)

    def test_grading_criteria_exclude_extra(self):
        context = grading_context(self.card, "Five blocks.", "en-US")
        self.assertEqual(
            set(vars(context)),
            {"prompt", "reference_answer", "required_concepts", "accepted_answers",
             "learner_answer", "language"})
        self.assertNotIn(self.card.fields.extra, str(vars(context)))
        self.assertEqual(context.learner_answer, "Five blocks.")

    def test_no_scripted_session_ever_speaks_the_answer_as_a_question(self):
        for run in scenarios.all_runs():
            answers = {c.fields.reference_answer.lower()
                       for c in run.harness.collection.cards.values()}
            for utterance in run.harness.speech_output.attempted:
                if utterance.purpose is UtterancePurpose.QUESTION:
                    for answer in answers:
                        self.assertNotIn(answer, utterance.text.lower(), run.name)

    def test_no_scripted_session_ever_shows_extra_to_the_grader(self):
        for run in scenarios.all_runs():
            extras = {c.fields.extra for c in run.harness.collection.cards.values()}
            for context in run.harness.grader.seen:
                for extra in extras:
                    self.assertNotIn(extra, str(vars(context)), run.name)


class RatingTests(unittest.TestCase):
    """Permitted ratings are what the provider offered for this card."""

    def test_ratings_are_read_per_card_not_assumed_constant(self):
        collection = demo_collection()
        collection.set_permitted_ratings(CARD_ID, (1, 3))
        provider = FakeCardProvider(collection)
        self.assertEqual(provider.next_card().permitted_ratings, (1, 3))
        self.assertEqual(provider.read_card(1789414083109).permitted_ratings,
                         (1, 2, 3, 4))

    def test_out_of_range_rating_is_rejected_and_never_becomes_again(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness)
        outcome = harness.session.writer.commit(
            ReviewIntent(harness.session.card, 5, 12_345))
        self.assertEqual(outcome.state, ReviewState.FAILED)
        self.assertEqual(outcome.failure.mode, ReviewWriterFailure.RATING_REJECTED)
        self.assertFalse(outcome.write_attempted)
        self.assertEqual(harness.transport.calls, [])
        self.assertEqual(committed(harness), [])

    def test_a_rejected_rating_leaves_the_correction_window_open(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness)
        rejection = harness.session.propose(0)
        self.assertIsInstance(rejection, Failure)
        self.assertEqual(harness.session.state, SessionState.GRADING)
        self.assertIsNone(harness.session.intent)
        self.assertIsNotNone(harness.session.propose(2).card)

    def test_a_rating_withdrawn_before_commit_is_rejected(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness)
        harness.session.propose(4)
        harness.collection.set_permitted_ratings(CARD_ID, (1, 2, 3))
        outcome = scenarios.confirm_and_commit(harness.session)
        self.assertEqual(outcome.failure.mode, ReviewWriterFailure.RATING_REJECTED)
        self.assertEqual(harness.transport.calls, [])

    def test_an_uncertain_grade_never_proposes_a_rating(self):
        for label in (GradeLabel.PARTIAL, GradeLabel.UNCERTAIN):
            result = GradingResult(label, "unclear")
            self.assertIsNone(result.proposed_rating((1, 2, 3, 4)), label)
        self.assertEqual(
            GradingResult(GradeLabel.CORRECT, "ok").proposed_rating((1, 2, 3, 4)), 3)


class ReviewTimeTests(unittest.TestCase):
    """Elapsed milliseconds from a monotonic clock, nonnegative, silently capped."""

    def test_elapsed_is_measured_from_the_monotonic_clock(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness, elapsed_ms=7_500)
        harness.session.propose(3)
        self.assertEqual(harness.session.intent.elapsed_ms, 7_500)

    def test_zero_elapsed_is_a_valid_measurement(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness, elapsed_ms=0)
        harness.session.propose(3)
        self.assertEqual(harness.session.intent.elapsed_ms, 0)
        self.assertEqual(scenarios.confirm_and_commit(harness.session).state, ReviewState.CONFIRMED)

    def test_the_cap_truncates_the_stored_value_without_failing_verification(self):
        harness = scenarios.build()
        outcome = turn(harness, elapsed_ms=98_765)
        self.assertEqual(outcome.state, ReviewState.CONFIRMED)
        self.assertEqual(outcome.submitted_time_ms, 98_765)
        self.assertEqual(outcome.expected_stored_time_ms, 60_000)
        self.assertTrue(outcome.time_was_capped)
        self.assertEqual(committed(harness)[0]["time_taken_ms"], 60_000)
        self.assertTrue(outcome.stored_time_is_expected(60_000))
        self.assertFalse(outcome.stored_time_is_expected(98_765))

    def test_an_uncapped_value_is_submitted_unchanged(self):
        outcome = turn(scenarios.build(), elapsed_ms=12_345)
        self.assertFalse(outcome.time_was_capped)
        self.assertTrue(outcome.stored_time_is_expected(12_345))

    def test_negative_elapsed_time_is_refused_without_a_write(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness)
        outcome = harness.session.writer.commit(
            ReviewIntent(harness.session.card, 3, -1))
        self.assertEqual(outcome.failure.mode, ReviewWriterFailure.INVALID_REVIEW_TIME)
        self.assertEqual(harness.transport.calls, [])

    def test_a_clock_that_went_backwards_is_clamped_to_zero(self):
        harness = scenarios.build()
        harness.session.offer_card()
        harness.session.ask()
        harness.clock.value_ms -= 50  # a clock that went backwards
        harness.session.listen()
        harness.session.grade()
        self.assertEqual(harness.session.propose(3).elapsed_ms, 0)


class CapabilityTests(unittest.TestCase):
    """Flags let the session adapt without branching on a device."""

    def test_defaults_record_the_av004_measurements(self):
        capabilities = Capabilities()
        self.assertEqual(capabilities.permitted_ratings, (1, 2, 3, 4))
        self.assertEqual(capabilities.max_review_time_ms, 60_000)
        self.assertTrue(capabilities.supports_post_write_verification)
        for absent in ("supports_skip", "supports_programmatic_undo",
                       "supports_revlog_query", "supports_transactions",
                       "supports_idempotency_key",
                       "supports_atomic_compare_and_write"):
            self.assertFalse(getattr(capabilities, absent), absent)

    def test_skip_halts_without_any_write(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness)
        harness.session.propose(3)
        halt = harness.session.request_skip()
        self.assertEqual(harness.session.state, SessionState.PAUSED)
        self.assertTrue(halt.resumable)
        self.assertEqual(harness.transport.calls, [])
        self.assertEqual(harness.collection.reviews, [])
        self.assertEqual(harness.collection.scheduled(CARD_ID).state.reps, 0)

    def test_skip_can_exit_the_session_instead_of_pausing(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness)
        harness.session.request_skip(exit_session=True)
        self.assertEqual(harness.session.state, SessionState.STOPPED)
        self.assertEqual(harness.transport.calls, [])

    def test_correction_after_commit_hands_off_to_native_undo_and_stops(self):
        harness = scenarios.build()
        self.assertEqual(turn(harness).state, ReviewState.CONFIRMED)
        with self.assertRaises(ValueError):
            harness.session.correct(1)
        halt = harness.session.request_correction_after_commit()
        self.assertEqual(harness.session.state, SessionState.STOPPED)
        self.assertFalse(halt.resumable)
        self.assertIn("Undo", halt.detail)
        self.assertEqual(len(harness.transport.calls), 1)

    def test_undo_handoff_requires_a_confirmed_review(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness)
        harness.session.propose(3)
        with self.assertRaises(ValueError):
            harness.session.request_correction_after_commit()

    def test_without_post_write_verification_every_commit_is_unknown(self):
        harness = scenarios.build(
            capabilities=Capabilities(supports_post_write_verification=False))
        outcome = turn(harness)
        self.assertEqual(outcome.state, ReviewState.OUTCOME_UNKNOWN)
        self.assertEqual(harness.session.state, SessionState.PAUSED)
        self.assertTrue(harness.session.halt.reconciliation_required)


class ReviewStateMachineTests(unittest.TestCase):
    """pending, submitting, confirmed, failed, outcome-unknown."""

    def test_confirmed_requires_a_consistent_one_review_transition(self):
        harness = scenarios.build()
        outcome = turn(harness)
        self.assertEqual(outcome.state, ReviewState.CONFIRMED)
        self.assertEqual(outcome.post_state.reps, outcome.pre_state.reps + 1)
        self.assertTrue(outcome.post_state.last_review_time_secs)
        self.assertNotEqual(outcome.post_state.due, outcome.pre_state.due)
        self.assertEqual(len(committed(harness)), 1)

    def test_the_transition_check_rejects_each_inconsistent_post_state(self):
        pre = CardState(reps=3, card_type=2, queue=2, due=90, interval_days=30,
                        last_review_time_secs=1_789_400_000)
        cases = {
            "reps": CardState(3, 2, 2, 187, 97, 1_789_414_000),
            "unpopulated": CardState(4, 2, 2, 187, 97, None),
            "suspended": CardState(4, 2, -1, 187, 97, 1_789_414_000),
            "unmoved": CardState(4, 2, 2, 90, 30, 1_789_414_000),
        }
        for label, post in cases.items():
            consistent, _ = is_one_review_transition(pre, post)
            self.assertFalse(consistent, label)
        good = CardState(4, 2, 2, 187, 97, 1_789_414_000)
        self.assertTrue(is_one_review_transition(pre, good)[0])

    def test_every_ambiguous_acknowledgement_resolves_to_outcome_unknown(self):
        ambiguous = (WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE, WriteAnomaly.NULL_RESPONSE,
                     WriteAnomaly.ERROR_RESPONSE, WriteAnomaly.DOUBLE_APPLY,
                     WriteAnomaly.SUSPENDED_INSTEAD, WriteAnomaly.ZERO_BUT_APPLIED)
        for anomaly in ambiguous:
            harness = scenarios.build()
            harness.transport.anomalies.append(anomaly)
            outcome = turn(harness)
            self.assertEqual(outcome.state, ReviewState.OUTCOME_UNKNOWN, anomaly)
            self.assertEqual(harness.session.state, SessionState.PAUSED, anomaly)
            self.assertTrue(harness.session.halt.reconciliation_required, anomaly)

    def test_an_unreadable_post_state_is_outcome_unknown(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness)
        harness.session.propose(3)
        harness.provider.read_card_script.extend(
            [None, Failure(CardProviderFailure.ACCESS_DENIED, "revoked mid-turn")])
        outcome = scenarios.confirm_and_commit(harness.session)
        self.assertEqual(outcome.state, ReviewState.OUTCOME_UNKNOWN)
        self.assertTrue(outcome.write_attempted)

    def test_an_explicit_zero_with_an_unchanged_card_is_failed_not_unknown(self):
        harness = scenarios.build()
        harness.transport.anomalies.append(WriteAnomaly.REJECT_WITH_ZERO)
        outcome = turn(harness)
        self.assertEqual(outcome.state, ReviewState.FAILED)
        self.assertEqual(outcome.failure.mode, ReviewWriterFailure.WRITE_REJECTED)
        self.assertFalse(harness.session.halt.reconciliation_required)
        self.assertEqual(committed(harness), [])

    def test_outcome_unknown_blocks_replay_advance_announcement_and_resume(self):
        harness = scenarios.build()
        harness.transport.anomalies.append(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
        outcome = turn(harness)
        session = harness.session
        for call in (lambda: session.writer.commit(session.intent), session.advance,
                     lambda: session.announce_result(outcome), session.resume):
            with self.assertRaises(ValueError):
                call()
        self.assertEqual(len(harness.transport.calls), 1)

    def test_a_confirmed_review_may_be_announced_and_advanced(self):
        harness = scenarios.build()
        outcome = turn(harness)
        self.assertIn("Saved", harness.session.announce_result(outcome).text)
        harness.session.advance()
        self.assertEqual(harness.session.state, SessionState.IDLE)

    def test_intent_state_tracks_the_review_state(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness)
        intent = harness.session.propose(3)
        self.assertEqual(intent.state, ReviewState.PENDING)
        scenarios.confirm_and_commit(harness.session)
        self.assertEqual(intent.state, ReviewState.CONFIRMED)

    def test_correction_is_open_while_pending_and_closed_afterwards(self):
        harness = scenarios.build()
        scenarios.ask_listen_grade(harness)
        intent = harness.session.propose(1)
        harness.session.correct(2)
        harness.session.correct(4)
        self.assertEqual((intent.rating, intent.corrections), (4, (1, 2)))
        self.assertEqual(harness.transport.calls, [])
        scenarios.confirm_and_commit(harness.session)
        self.assertEqual(harness.transport.calls[0][1], 4)
        with self.assertRaises(ValueError):
            intent.correct(1)

    def test_reconciliation_is_explicit_and_never_inferred(self):
        harness = scenarios.build()
        harness.transport.anomalies.append(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
        turn(harness)
        harness.session.reconcile(learner_confirmed_saved=False)
        self.assertEqual(harness.session.events[-1].step, "reconcile")
        clean = scenarios.build()
        turn(clean)
        with self.assertRaises(ValueError):
            clean.session.reconcile(learner_confirmed_saved=True)


class ErrorClassificationTests(unittest.TestCase):
    """A transport or provider error is never an incorrect learner answer."""

    def test_no_failure_mode_produces_a_rating(self):
        for run in scenarios.failure_sweep():
            mode = run.name.rsplit(".", 1)[1]
            expected_writes = 1 if mode == "write_rejected" else 0
            self.assertEqual(len(run.harness.transport.calls), expected_writes, run.name)
            self.assertEqual(committed(run.harness), [], run.name)
            self.assertNotIn(ReviewState.CONFIRMED,
                             [o.state for o in run.session.outcomes], run.name)

    def test_a_null_cursor_is_not_an_empty_queue(self):
        empty = scenarios.build()
        empty.collection.rebuild_queue([])
        self.assertIsInstance(empty.session.offer_card(), QueueExhausted)
        self.assertEqual(empty.session.state, SessionState.EXHAUSTED)

        disabled = scenarios.build()
        disabled.provider.next_card_script.append(
            Failure(CardProviderFailure.API_DISABLED, "switched off"))
        halt = disabled.session.offer_card()
        self.assertIsInstance(halt, Halt)
        self.assertEqual(disabled.session.state, SessionState.PAUSED)
        self.assertTrue(halt.resumable)

    def test_a_missing_deck_is_not_exhaustion(self):
        harness = scenarios.build()
        harness.provider.next_card_script.append(
            Failure(CardProviderFailure.DECK_MISSING, "deck removed"))
        harness.session.offer_card()
        self.assertEqual(harness.session.state, SessionState.STOPPED)
        self.assertFalse(harness.session.halt.resumable)

    def test_silence_and_a_denied_microphone_are_not_wrong_answers(self):
        for mode in (SpeechInputFailure.PERMISSION_DENIED,
                     SpeechInputFailure.NO_SPEECH_DETECTED,
                     SpeechInputFailure.RECOGNIZER_ERROR):
            harness = scenarios.build(transcripts=(Failure(mode, "scripted"),))
            halt = scenarios.ask_listen_grade(harness)
            self.assertEqual(halt.reason, mode.value)
            self.assertEqual(harness.session.state, SessionState.PAUSED, mode)
            self.assertEqual(harness.transport.calls, [], mode)
            self.assertEqual(harness.grader.seen, [], mode)

    def test_a_resumable_pause_discards_the_dead_snapshot(self):
        harness = scenarios.build()
        harness.provider.next_card_script.append(
            Failure(CardProviderFailure.ACCESS_DENIED, "revoked"))
        harness.session.offer_card()
        harness.session.resume()
        self.assertEqual(harness.session.state, SessionState.IDLE)
        self.assertIsNone(harness.session.card)
        self.assertIsNone(harness.session.intent)


class SingleActiveReviewerTests(unittest.TestCase):
    """Reps and time cannot attribute a competing write to this caller."""

    def test_every_interruption_stops_the_session(self):
        for kind in Interruption:
            harness = scenarios.build()
            scenarios.ask_listen_grade(harness)
            harness.session.propose(3)
            halt = harness.session.interrupt(kind)
            self.assertEqual(harness.session.state, SessionState.STOPPED, kind)
            self.assertFalse(halt.resumable, kind)
            self.assertFalse(halt.reconciliation_required, kind)
            self.assertEqual(harness.transport.calls, [], kind)

    def test_an_interruption_after_an_unconfirmed_write_needs_reconciliation(self):
        harness = scenarios.build()
        harness.transport.anomalies.append(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
        turn(harness)
        halt = harness.session.interrupt(Interruption.SYNC)
        self.assertEqual(harness.session.state, SessionState.STOPPED)
        self.assertTrue(halt.reconciliation_required)

    def test_a_stopped_session_cannot_offer_another_card(self):
        harness = scenarios.build()
        harness.session.interrupt(Interruption.PROCESS_RESUME)
        with self.assertRaises(ValueError):
            harness.session.offer_card()


class ScenarioCatalogTests(unittest.TestCase):
    """The fakes drive the whole machine with no emulator and no network."""

    def test_every_enumerated_failure_mode_has_a_scripted_session(self):
        covered = {run.name.split("/", 1)[1] for run in scenarios.failure_sweep()}
        self.assertEqual(covered, {f"{mode.contract}.{mode.value}" for mode in ALL_FAILURE_MODES})
        self.assertEqual(len(covered), len(ALL_FAILURE_MODES))

    def test_the_named_scenarios_cover_the_required_demonstrations(self):
        names = {run.name for run in
                 [scenario() for scenario in scenarios.NAMED_SCENARIOS]}
        self.assertLessEqual(
            {"stale-identity-rejection", "capped-review-time",
             "ambiguous-acknowledgement", "rating-out-of-range",
             "precommit-correction", "confirmed-commit"}, names)

    def test_no_scenario_reports_a_prohibited_action_succeeding(self):
        for run in scenarios.all_runs():
            for note in run.notes:
                self.assertNotIn("PROBLEM", note, run.name)

    def test_every_review_state_is_reached_by_the_catalog(self):
        reached = {outcome.state for run in scenarios.all_runs()
                   for outcome in run.session.outcomes}
        self.assertLessEqual({ReviewState.CONFIRMED, ReviewState.FAILED,
                              ReviewState.OUTCOME_UNKNOWN}, reached)

    def test_the_catalog_is_deterministic(self):
        self.assertEqual(scenarios.render(scenarios.all_runs()),
                         scenarios.render(scenarios.all_runs()))

    def test_the_committed_transcripts_match_the_generator(self):
        self.assertEqual(TRANSCRIPTS.read_text(),
                         scenarios.render(scenarios.all_runs()),
                         "Regenerate with tools/av007_scenarios.py --output")

    def test_the_fakes_need_no_network_clock_or_emulator(self):
        harness = scenarios.build()
        self.assertIsInstance(harness.clock, FakeClock)
        self.assertEqual(harness.clock.now_ms(), 0)
        turn(harness)
        self.assertEqual(harness.clock.now_ms(), 12_345)


class SpecificationDocumentTests(unittest.TestCase):
    """The specification is published for review."""

    def setUp(self):
        self.text = SPECIFICATION.read_text()

    def test_it_names_every_contract_and_review_state(self):
        for name in ("CardProvider", "SpeechOutput", "SpeechInput", "Grader",
                     "ReviewWriter", "pending", "submitting", "confirmed", "failed",
                     "outcome-unknown"):
            self.assertIn(name, self.text, name)

    def test_it_names_the_downstream_owners(self):
        for issue in ("#20", "#28", "#25", "#19"):
            self.assertIn(issue, self.text, issue)

    def test_it_records_the_confirmed_product_decisions(self):
        for phrase in ("supportsSkip", "supportsProgrammaticUndo", "maxTaken",
                       "update_count"):
            self.assertIn(phrase, self.text, phrase)


if __name__ == "__main__":
    unittest.main()
