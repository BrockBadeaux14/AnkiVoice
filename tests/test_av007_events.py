"""Regression cases for explicit learner actions and interrupted asynchronous work."""
from dataclasses import replace
import subprocess
import sys
import unittest

from tools import av007_scenarios as scenarios
from tools.av007_contracts import (
    CaptureEvent, CardProviderFailure, Confidence, ConfirmationSource, Failure,
    GraderFailure, GradingReply, Interruption, PlaybackResult, ReviewState,
    ReviewWriterFailure, SessionState, SpeechInputFailure, TranscriptKind,
    is_one_review_transition,
)
from tools.av007_fakes import WriteAnomaly


def pending():
    harness = scenarios.build()
    scenarios.ask_listen_grade(harness)
    harness.session.propose(3)
    return harness


class ConfirmationTests(unittest.TestCase):
    def test_suggestion_or_silence_does_not_authorize_submission(self):
        h = pending()
        h.clock.advance(60_000)
        with self.assertRaises(ValueError):
            h.session.commit()
        self.assertEqual(h.session.intent.state, ReviewState.PENDING)
        self.assertEqual(h.transport.calls, [])
        # The writer has its own guard even if the session is bypassed.
        outcome = h.session.writer.commit(h.session.intent)
        self.assertEqual(outcome.failure.mode, ReviewWriterFailure.CONFIRMATION_REQUIRED)
        self.assertFalse(outcome.write_attempted)

    def test_spoken_and_touch_confirmation_each_authorize_exactly_one_write(self):
        for source in ConfirmationSource:
            h = pending()
            self.assertTrue(h.session.confirm(scenarios.confirmation(h.session, source)))
            self.assertEqual(h.transport.calls, [])
            self.assertEqual(h.session.commit().state, ReviewState.CONFIRMED)
            with self.assertRaises(ValueError):
                h.session.commit()
            self.assertEqual(len(h.transport.calls), 1)

    def test_uncertain_partial_or_mismatched_commands_never_confirm(self):
        for changes in ({"confidence": Confidence.LOW}, {"confidence": Confidence.ABSENT},
                        {"final": False}, {"rating": 1}, {"transcript_revision": 90},
                        {"source": "not-a-user-event"}):
            with self.subTest(changes=changes):
                h = pending()
                event = scenarios.confirmation(h.session, ConfirmationSource.SPOKEN)
                self.assertFalse(h.session.confirm(replace(event, **changes)))
                with self.assertRaises(ValueError):
                    h.session.commit()
                self.assertEqual(h.transport.calls, [])

    def test_confirmations_are_bound_to_session_card_and_turn(self):
        h, other = pending(), pending()
        event = scenarios.confirmation(h.session)
        self.assertFalse(other.session.confirm(event))
        self.assertFalse(h.session.confirm(replace(event, identity=replace(event.identity, note_id=99))))
        scenarios.confirm_and_commit(h.session)
        h.session.advance()
        h.speech_input.script.append("Green blue red")
        h.grader.script.append(scenarios.CORRECT)
        scenarios.ask_listen_grade(h)
        h.session.propose(3)
        self.assertFalse(h.session.confirm(event))

    def test_rating_edits_invalidate_confirmation_even_if_changed_back(self):
        h = pending()
        event = scenarios.confirmation(h.session)
        h.session.confirm(event)
        h.session.correct(2)
        h.session.correct(3)
        self.assertFalse(h.session.confirm(event))
        with self.assertRaises(ValueError):
            h.session.commit()
        scenarios.confirm_and_commit(h.session)
        self.assertEqual(len(h.transport.calls), 1)

    def test_elapsed_time_includes_correction_and_confirmation_wait(self):
        h = pending()
        h.clock.advance(2_000)
        h.session.correct(2)
        h.clock.advance(3_000)
        outcome = scenarios.confirm_and_commit(h.session)
        self.assertEqual(outcome.submitted_time_ms, 17_345)


class TranscriptAndCancellationTests(unittest.TestCase):
    def test_partial_transcripts_cannot_grade_propose_confirm_or_submit(self):
        h = scenarios.build()
        h.session.offer_card()
        h.session.ask()
        token = h.session.capture_token
        self.assertIsNone(h.session.accept_capture(CaptureEvent(token, "Five", TranscriptKind.PARTIAL)))
        for call in (h.session.grade, lambda: h.session.propose(3), h.session.commit):
            with self.assertRaises(ValueError):
                call()
        self.assertEqual(h.grader.seen, [])
        self.assertEqual(h.transport.calls, [])

    def test_empty_low_and_absent_confidence_finals_require_learner_action(self):
        for text, confidence in (("", Confidence.SUFFICIENT), ("Five", Confidence.LOW),
                                 ("Five", Confidence.ABSENT)):
            h = scenarios.build()
            h.session.offer_card()
            h.session.ask()
            token = h.session.capture_token
            h.session.accept_capture(CaptureEvent(token, text, confidence=confidence))
            self.assertEqual(h.session.state, SessionState.PAUSED)
            self.assertIn(token, h.speech_input.cancelled)
            self.assertEqual(h.grader.seen, [])
            self.assertEqual(h.transport.calls, [])
            h.session.correct_transcript("Five")
            h.session.self_grade(3)
            self.assertEqual(scenarios.confirm_and_commit(h.session).state, ReviewState.CONFIRMED)

    def test_failure_with_text_is_never_an_answer_and_no_match_retains_ambiguity(self):
        h = scenarios.build()
        h.session.offer_card()
        h.session.ask()
        event = CaptureEvent(h.session.capture_token, "Five", confidence=Confidence.SUFFICIENT,
                             failure=Failure(SpeechInputFailure.NO_MATCH, "ERROR_NO_MATCH"))
        h.session.accept_capture(event)
        self.assertIsNone(h.session.answer)
        self.assertEqual(h.session.halt.reason, "no_match")
        self.assertEqual(h.grader.seen, [])
        self.assertEqual(h.transport.calls, [])

    def test_edit_invalidates_suggestion_pending_rating_and_delayed_grade(self):
        h = pending()
        old_event = scenarios.confirmation(h.session)
        old_reply = GradingReply(h.grader.requests[0], scenarios.CORRECT)
        old_intent = h.session.intent
        h.session.correct_transcript("Six")
        self.assertIsNone(h.session.suggestion)
        self.assertIsNone(h.session.intent)
        self.assertEqual(h.session.answer.kind, TranscriptKind.CORRECTED)
        self.assertIsNone(h.session.accept_grade(old_reply))
        self.assertFalse(h.session.confirm(old_event))
        with self.assertRaises(ValueError):
            h.session.writer.commit(old_intent)
        h.grader.script.append(scenarios.CORRECT)
        h.session.grade()
        self.assertEqual(h.grader.seen[-1].learner_answer, "Six")
        self.assertEqual(h.session.transcript_revision, 2)
        self.assertEqual(h.transport.calls, [])

    def test_outstanding_grade_is_cancelled_and_late_result_ignored(self):
        h = scenarios.build()
        scenarios.ask_listen_grade(h)
        request = h.session.begin_grade()
        h.session.correct_transcript("Five blocks")
        self.assertIn(request, h.grader.cancelled)
        newer = h.session.begin_grade()
        self.assertIsNone(h.session.accept_grade(GradingReply(request, scenarios.CORRECT)))
        self.assertEqual(h.session.grading_request, newer)
        self.assertIsNotNone(h.session.accept_grade(GradingReply(newer, scenarios.CORRECT)))
        self.assertIsNone(h.session.accept_grade(GradingReply(newer, scenarios.CORRECT)))

    def test_reveal_is_explicit_and_only_allowed_after_an_accepted_answer(self):
        h = scenarios.build()
        h.session.offer_card()
        with self.assertRaises(ValueError):
            h.session.reveal()
        h.session.ask()
        with self.assertRaises(ValueError):
            h.session.reveal()
        h.session.listen()
        h.session.reveal()
        h.session.reveal(include_extra=True)
        self.assertEqual([u.purpose.value for u in h.speech_output.spoken],
                         ["question", "reveal", "elaboration"])
        self.assertIsNone(h.session.capture_token)

    def test_every_grader_error_has_explicit_self_grade_fallback(self):
        for mode in GraderFailure:
            h = scenarios.build(grades=(Failure(mode, "advisory provider unavailable"),))
            scenarios.ask_listen_grade(h)
            self.assertIsNone(h.session.intent)
            self.assertEqual(h.transport.calls, [])
            h.session.self_grade(2)
            self.assertEqual(h.transport.calls, [])
            scenarios.confirm_and_commit(h.session)
            self.assertEqual(h.transport.calls[0][1], 2)

    def test_late_capture_after_cancel_or_resume_cannot_grade_a_new_turn(self):
        h = scenarios.build()
        h.session.offer_card()
        h.session.ask()
        token = h.session.capture_token
        h.session.request_skip()
        h.session.resume()
        h.session.offer_card()
        h.session.ask()
        self.assertNotEqual(token, h.session.capture_token)
        h.session.accept_capture(CaptureEvent(token, "Five", confidence=Confidence.SUFFICIENT))
        self.assertEqual(h.session.state, SessionState.LISTENING)
        self.assertEqual(h.grader.seen, [])

    def test_interruption_during_playback_cancels_and_ignores_completion(self):
        h = scenarios.build()
        h.session.offer_card()
        h.speech_output.on_speak = lambda token: h.session.interrupt(Interruption.EXTERNAL_AUDIO)
        h.session.ask()
        token = h.speech_output.cancelled[0]
        self.assertFalse(h.session.finish_playback(PlaybackResult(token)))
        self.assertEqual(h.session.state, SessionState.STOPPED)
        self.assertEqual(h.speech_input.languages, [])
        self.assertEqual(h.speech_output.spoken, [])

    def test_interruption_cancels_capture_and_pending_grade(self):
        for phase in ("capture", "grade"):
            h = scenarios.build()
            h.session.offer_card()
            h.session.ask()
            capture = h.session.capture_token
            if phase == "grade":
                h.session.listen()
                request = h.session.begin_grade()
            h.session.interrupt(Interruption.LOCK)
            self.assertIn(capture, h.speech_input.cancelled)
            if phase == "grade":
                self.assertIn(request, h.grader.cancelled)
                h.session.accept_grade(GradingReply(request, scenarios.CORRECT))
            h.session.accept_capture(CaptureEvent(capture, "Five", confidence=Confidence.SUFFICIENT))
            self.assertEqual(h.session.state, SessionState.STOPPED)
            self.assertEqual(h.transport.calls, [])

    def test_an_interruption_from_cleanup_cannot_resume_the_answer(self):
        h = scenarios.build()
        h.session.offer_card()
        h.session.ask()
        original_cancel = h.speech_input.cancel
        def cancel(token):
            original_cancel(token)
            h.session.interrupt(Interruption.EXTERNAL_AUDIO)
        h.speech_input.cancel = cancel
        h.session.listen()
        self.assertEqual(h.session.state, SessionState.STOPPED)
        self.assertEqual(h.grader.seen, [])
        self.assertEqual(h.transport.calls, [])

    def test_cancelling_a_grade_cannot_resume_a_stopped_session(self):
        for action in ("edit", "propose", "new-grade"):
            h = scenarios.build()
            scenarios.ask_listen_grade(h)
            h.session.begin_grade()
            h.grader.cancel = lambda request: h.session.interrupt(Interruption.APP_SWITCH)
            with self.assertRaises(ValueError):
                if action == "edit":
                    h.session.correct_transcript("Five")
                elif action == "propose":
                    h.session.propose(3)
                else:
                    h.session.begin_grade()
            self.assertEqual(h.session.state, SessionState.STOPPED)
            self.assertEqual(h.transport.calls, [])


class WriteGuardRegressionTests(unittest.TestCase):
    def test_a_withdrawn_queue_card_is_rejected_even_if_its_state_is_unchanged(self):
        h = pending()
        h.collection.rebuild_queue([1789414083109])
        outcome = scenarios.confirm_and_commit(h.session)
        self.assertEqual(outcome.failure.mode, ReviewWriterFailure.STALE_IDENTITY)
        self.assertEqual(h.transport.calls, [])

    def test_any_identity_or_stored_field_change_rejects_a_write(self):
        for field, value in (("card_id", 99), ("note_id", 99), ("deck_id", 99),
                             ("ordinal", 1), ("model", "Basic")):
            h = pending()
            card = h.collection.cards[h.session.card.identity.card_id]
            card.identity = replace(card.identity, **{field: value})
            # Feed the changed identity directly so the writer's tuple guard,
            # rather than the fake's VoiceQA validation, is exercised.
            h.provider.next_card_script.append(h.collection.scheduled(h.session.card.identity.card_id))
            outcome = scenarios.confirm_and_commit(h.session)
            self.assertEqual(outcome.failure.mode, ReviewWriterFailure.STALE_IDENTITY, field)
            self.assertEqual(h.transport.calls, [])
        for field, value in (("reps", 1), ("card_type", 1), ("queue", 1),
                             ("due", 99), ("interval_days", 3), ("last_review_time_secs", 99)):
            h = pending()
            h.collection.mutate_state(h.session.card.identity.card_id, **{field: value})
            self.assertEqual(scenarios.confirm_and_commit(h.session).failure.mode,
                             ReviewWriterFailure.STALE_IDENTITY, field)
            self.assertEqual(h.transport.calls, [])

    def test_unexpected_acknowledgement_count_is_unknown_even_after_one_review(self):
        h = pending()
        h.transport.anomalies.append(WriteAnomaly.UNEXPECTED_COUNT)
        self.assertEqual(scenarios.confirm_and_commit(h.session).state, ReviewState.OUTCOME_UNKNOWN)

    def test_submitting_state_is_visible_only_during_the_single_dispatch(self):
        h = pending()
        original = h.transport.answer_card
        observed = []
        def answer(identity, rating, elapsed):
            observed.append(h.session.intent.state)
            return original(identity, rating, elapsed)
        h.transport.answer_card = answer
        scenarios.confirm_and_commit(h.session)
        self.assertEqual(observed, [ReviewState.SUBMITTING])

    def test_content_edits_after_answer_invalidate_the_review(self):
        h = pending()
        card = h.collection.cards[h.session.card.identity.card_id]
        card.fields = replace(card.fields, reference_answer="Six")
        outcome = scenarios.confirm_and_commit(h.session)
        self.assertEqual(outcome.failure.mode, ReviewWriterFailure.STALE_IDENTITY)
        self.assertEqual(h.transport.calls, [])

    def test_provider_rejects_invalid_voiceqa_content_and_missing_cards(self):
        for changes, mode in (({"model": "Basic"}, CardProviderFailure.UNSUPPORTED_NOTE_TYPE),
                              ({"ordinal": 1}, CardProviderFailure.MALFORMED_CARD)):
            h = scenarios.build()
            card = next(iter(h.collection.cards.values()))
            card.identity = replace(card.identity, **changes)
            self.assertEqual(h.provider.next_card().mode, mode)
        h = scenarios.build()
        card = next(iter(h.collection.cards.values()))
        card.fields = replace(card.fields, reference_answer="")
        self.assertEqual(h.provider.next_card().mode, CardProviderFailure.MALFORMED_CARD)
        del h.collection.cards[h.collection.order[0]]
        self.assertEqual(h.provider.next_card().mode, CardProviderFailure.CARD_NOT_FOUND)

    def test_empty_rating_range_and_zero_time_cap_follow_capabilities(self):
        from tools.av007_contracts import Capabilities
        h = scenarios.build()
        h.collection.set_permitted_ratings(h.collection.order[0], ())
        scenarios.ask_listen_grade(h)
        self.assertIsInstance(h.session.propose(3), Failure)
        self.assertEqual(h.transport.calls, [])
        h = scenarios.build(capabilities=Capabilities(max_review_time_ms=0))
        h.collection.max_review_time_ms = 0
        scenarios.ask_listen_grade(h)
        h.session.propose(3)
        outcome = scenarios.confirm_and_commit(h.session)
        self.assertEqual(outcome.expected_stored_time_ms, 0)
        self.assertTrue(outcome.stored_time_is_expected(h.collection.reviews[0]["time_taken_ms"]))
        for cap in (None, -1, False):
            with self.assertRaises(ValueError):
                Capabilities(max_review_time_ms=cap)

    def test_impossible_post_state_and_backwards_review_time_are_not_confirmation(self):
        h = pending()
        pre = h.session.card.state
        post = replace(pre, reps=1, card_type=2, queue=2, due=90,
                       interval_days=1, last_review_time_secs=100)
        for changes in ({"card_type": 0}, {"queue": 99}, {"interval_days": -1}):
            self.assertFalse(is_one_review_transition(pre, replace(post, **changes))[0])
        pre = replace(pre, last_review_time_secs=200)
        self.assertFalse(is_one_review_transition(pre, post)[0])

    def test_post_write_identity_change_is_unknown(self):
        h = pending()
        card = h.session.card
        h.provider.read_card_script.extend([None, replace(card, identity=replace(card.identity, deck_id=99))])
        self.assertEqual(scenarios.confirm_and_commit(h.session).state, ReviewState.OUTCOME_UNKNOWN)

    def test_skip_and_other_halts_cannot_clear_an_unknown_outcome(self):
        h = pending()
        h.transport.anomalies.append(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
        scenarios.confirm_and_commit(h.session)
        with self.assertRaises(ValueError):
            h.session.request_skip()
        self.assertTrue(h.session.halt.reconciliation_required)
        h.session.interrupt(Interruption.SYNC)
        self.assertTrue(h.session.halt.reconciliation_required)
        for call in (h.session.resume, h.session.advance, h.session.offer_card):
            with self.assertRaises(ValueError):
                call()
        self.assertEqual(len(h.transport.calls), 1)

    def test_a_pending_intent_cannot_be_used_directly_after_cancellation(self):
        h = pending()
        intent = h.session.intent
        h.session.confirm(scenarios.confirmation(h.session))
        h.session.request_skip()
        with self.assertRaises(ValueError):
            h.session.writer.commit(intent)
        self.assertEqual(h.transport.calls, [])

    def test_stopped_session_cannot_advance_or_announce_an_earlier_success(self):
        h = pending()
        outcome = scenarios.confirm_and_commit(h.session)
        h.session.interrupt(Interruption.APP_SWITCH)
        for call in (h.session.advance, lambda: h.session.announce_result(outcome)):
            with self.assertRaises(ValueError):
                call()

    def test_interruption_during_submission_or_post_read_stays_unknown_and_stopped(self):
        for phase in ("write", "post-read"):
            h = pending()
            original = h.transport.answer_card
            def answer(identity, rating, elapsed):
                outcome = original(identity, rating, elapsed)
                if phase == "write":
                    h.session.interrupt(Interruption.SYNC)
                else:
                    original_read = h.provider.read_card
                    def read(card_id):
                        h.session.interrupt(Interruption.APP_SWITCH)
                        return original_read(card_id)
                    h.provider.read_card = read
                return outcome
            h.transport.answer_card = answer
            outcome = scenarios.confirm_and_commit(h.session)
            self.assertEqual(outcome.state, ReviewState.OUTCOME_UNKNOWN)
            self.assertEqual(h.session.state, SessionState.STOPPED)
            self.assertTrue(h.session.halt.reconciliation_required)
            self.assertEqual(len(h.transport.calls), 1)

    def test_interruption_during_precommit_read_prevents_dispatch(self):
        h = pending()
        original = h.provider.read_card
        def read(card_id):
            h.session.interrupt(Interruption.APP_SWITCH)
            return original(card_id)
        h.provider.read_card = read
        outcome = scenarios.confirm_and_commit(h.session)
        self.assertEqual(outcome.state, ReviewState.FAILED)
        self.assertEqual(h.session.state, SessionState.STOPPED)
        self.assertEqual(h.transport.calls, [])

    def test_full_session_advances_through_the_fake_queue_to_exhaustion(self):
        h = scenarios.build(transcripts=("Five", "Green blue red"),
                            grades=(scenarios.CORRECT, scenarios.CORRECT))
        for _ in range(2):
            scenarios.ask_listen_grade(h)
            h.session.propose(3)
            scenarios.confirm_and_commit(h.session)
            h.session.advance()
        h.session.offer_card()
        self.assertEqual(h.session.state, SessionState.EXHAUSTED)
        self.assertEqual(len(h.collection.reviews), 2)
        self.assertEqual(len({r["card_id"] for r in h.collection.reviews}), 2)

    def test_documented_cli_runs_without_pythonpath(self):
        import os
        from pathlib import Path
        root = Path(__file__).resolve().parents[1]
        env = dict(os.environ)
        env.pop("PYTHONPATH", None)
        result = subprocess.run([sys.executable, "tools/av007_scenarios.py"],
                                cwd=root, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Failure modes exercised: 34 of 34", result.stdout)


if __name__ == "__main__":
    unittest.main()
