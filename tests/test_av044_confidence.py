"""AV-044 drift guard: segment scores reach the transport, the minimum rule, and what stays put.

The `:speech` tests prove the behaviour against the fake platform. This one parses the
Kotlin sources and fails if the decisions behind it drift: the bridge hands each segment's
score through instead of discarding it, the transport takes the minimum over the segments
that contributed text, a partial result never carries a score, :core's three-way
classification and #15's guard rule are untouched, and the discovery observer decides
nothing. It also runs the evidence validator's consistency rules over a fabricated attempt.
No Gradle run, no emulator, no network.
"""
import importlib.util
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEECH = ROOT / "android" / "speech" / "src" / "main" / "kotlin" / "org" / "ankivoice" / "speech"
SPEECH_TEST = ROOT / "android" / "speech" / "src" / "test" / "kotlin" / "org" / "ankivoice" / "speech"
CORE = ROOT / "android" / "core" / "src" / "main" / "kotlin" / "org" / "ankivoice" / "core"
PLATFORM = SPEECH / "AndroidSpeechPlatform.kt"
TRANSPORT = SPEECH / "SpeechTransport.kt"
SEAM = SPEECH / "SpeechPlatform.kt"
TRANSPORT_TEST = SPEECH_TEST / "SpeechTransportTest.kt"
HARNESS = (ROOT / "android" / "app" / "src" / "androidTest" / "kotlin" / "org" / "ankivoice" / "app" /
           "ConfidenceInstrumentation.kt")
RESULTS = ROOT / "docs" / "testing" / "av044" / "results.md"


def code(path):
    """A Kotlin source with its comments removed, so prose about a symbol is not a use of it."""
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class SegmentScoresReachTheTransport(unittest.TestCase):
    def test_the_bridge_hands_each_segment_over_with_its_score(self):
        platform = code(PLATFORM)
        self.assertIn("listener.onSegment(generation, text(segmentResults), confidence(segmentResults))", platform)
        self.assertIn("listener.onEndOfSegments(generation)", platform)
        # The AV-014 finding: the bridge no longer joins segments and reports null itself.
        self.assertNotIn("segments.joinToString", platform)
        self.assertNotIn("onFinal(generation, segments", platform)
        self.assertIn("getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)", platform)

    def test_the_seam_carries_segments_and_their_end(self):
        seam = code(SEAM)
        self.assertIn("fun onSegment(generation: Long, text: String, confidence: Float?)", seam)
        self.assertIn("fun onEndOfSegments(generation: Long)", seam)

    def test_the_transport_takes_the_minimum_over_contributing_segments(self):
        transport = code(TRANSPORT)
        aggregate = re.search(r"fun aggregate\(segments: List<Pair<String, Float\?>>\): Pair<String, Float\?> \{(.*?)\n        \}",
                              transport, re.S)
        self.assertIsNotNone(aggregate, "SpeechTransport.aggregate is the one place the rule lives")
        body = aggregate.group(1)
        self.assertIn("text.isNotBlank()", body, "blank segments contribute nothing")
        self.assertIn("scores.any { it == null }", body, "a contributing segment without a score makes the capture unknown")
        self.assertIn(".min()", body)
        self.assertIn("val (text, confidence) = synchronized(lock) {", transport)
        self.assertIn("aggregate(segments)", transport)

    def test_a_partial_result_carries_no_score(self):
        transport = code(TRANSPORT)
        partial = re.search(r"override fun onPartial\(generation: Long, text: String\) \{(.*?)\n        \}", transport, re.S)
        self.assertIsNotNone(partial)
        self.assertNotIn("onfidence", partial.group(1))
        self.assertIn("lastPartial = text", partial.group(1))

    def test_a_segment_never_settles_the_capture(self):
        transport = code(TRANSPORT)
        segment = re.search(r"override fun onSegment\(generation: Long, text: String, confidence: Float\?\) \{(.*?)\n        \}",
                            transport, re.S)
        self.assertIsNotNone(segment)
        self.assertNotIn("offer(", segment.group(1))


class WhatStaysPut(unittest.TestCase):
    def test_cores_classification_is_unchanged(self):
        events = code(CORE / "contracts" / "Events.kt")
        self.assertIn('SUFFICIENT("sufficient"),\n    LOW("low"),\n    ABSENT("absent"),', events)
        transport = code(TRANSPORT)
        self.assertIn("confidence == null -> Confidence.ABSENT", transport)
        self.assertIn("confidence <= 0f -> Confidence.LOW", transport)
        self.assertIn("else -> Confidence.SUFFICIENT", transport)

    def test_the_guard_rule_is_unchanged(self):
        vocabulary = code(CORE / "commands" / "VoiceCommands.kt")
        self.assertIn("if (command.guarded && confidence != Confidence.SUFFICIENT) {", vocabulary)
        router = code(CORE / "commands" / "CommandRouter.kt")
        # A confidence score gates a spoken command; it never confirms anything by itself.
        self.assertIn("if (source == ConfirmationSource.TOUCH) Confidence.ABSENT else confidence", router)

    def test_the_answer_policy_is_unchanged(self):
        answers = code(CORE / "answer" / "AnswerBoundaries.kt")
        self.assertIn("AnswerStatus.FINAL -> text.isNotBlank() && confidence == Confidence.SUFFICIENT", answers)
        self.assertIn("status == AnswerStatus.FINAL && confidence != Confidence.SUFFICIENT", answers)

    def test_the_observer_is_told_and_decides_nothing(self):
        platform = code(PLATFORM)
        self.assertIn("var recognizerObserver: RecognizerObserver? = null", platform)
        observe = re.search(r"private fun observe\(callback: String, bundle: Bundle\?\) \{(.*?)\n        \}", platform, re.S)
        self.assertIsNotNone(observe)
        self.assertIn("runCatching { observer.onCallback(generation, callback, bundle) }", observe.group(1))
        # Every use of the observer is inside observe(); nothing reads a value back from it.
        uses = [line for line in platform.splitlines() if "recognizerObserver" in line]
        self.assertEqual(2, len(uses), uses)
        self.assertNotIn("recognizerObserver", code(TRANSPORT))


class TheOfflineSuiteCoversTheCard(unittest.TestCase):
    def test_the_four_named_cases_are_in_the_speech_suite(self):
        suite = TRANSPORT_TEST.read_text(encoding="utf-8")
        for name in (
            "a segment score above zero classifies as sufficient",
            "a segment score of zero classifies as low",
            "a segment without a score classifies as absent",
            "a fault after scored segments is still a failure and carries no transcript",
            "an empty segment contributes neither text nor a score",
            "the capture carries the minimum score across the segments that contributed text",
        ):
            self.assertIn(f"fun `{name}`", suite, name)


class TheHarnessTouchesNoCard(unittest.TestCase):
    FORBIDDEN = ("CardProvider", "ReviewWriter", "ReviewTransport", "AnkiDroid", "ReviewSession", "commit(")

    def test_the_discovery_harness_has_no_route_to_a_collection(self):
        harness = code(HARNESS)
        for forbidden in self.FORBIDDEN:
            self.assertNotIn(forbidden, harness, forbidden)
        self.assertIn('check(args.getString("confirm") == CONFIRMATION)', harness)


class EvidenceValidator(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("av044_validate", ROOT / "tools" / "av044-qa" / "validate.py")
        self.validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.validator)
        self.record = {
            "requested": {"id": "x", "say": ["confirm"], "kind": "test"},
            "result": {
                "source": "operator",
                "capture": {"kind": "transcript", "text": "confirm", "confidence": "sufficient"},
                "segments": [{"text": ["confirm"], "hasConfidenceScores": True, "confidenceScores": [0.9]}],
                "microphoneDuring": {"verdict": "live"},
            },
        }

    def check(self):
        failures = []
        self.validator.check_attempt(self.record, "test", failures)
        return failures

    def test_a_consistent_attempt_passes(self):
        self.assertEqual([], self.check())

    def test_a_transcript_that_is_not_the_segments_text_is_rejected(self):
        self.record["result"]["capture"]["text"] = "yes"
        self.assertTrue(self.check())

    def test_a_confidence_that_does_not_follow_from_the_scores_is_rejected(self):
        self.record["result"]["segments"][0]["confidenceScores"] = [0.0]
        self.assertTrue(self.check())

    def test_a_failed_capture_with_text_is_rejected(self):
        self.record["result"]["capture"] = {"kind": "failed", "failure": "noMatch", "text": "confirm"}
        self.assertTrue(self.check())

    def test_the_minimum_rule_matches_the_transport(self):
        segments = [
            {"text": ["green blue"], "confidenceScores": [0.9]},
            {"text": [""], "confidenceScores": [0.0]},
            {"text": ["red"], "confidenceScores": [0.4]},
        ]
        self.assertEqual("sufficient", self.validator.expected_confidence(segments))
        segments[2]["confidenceScores"] = [0.0]
        self.assertEqual("low", self.validator.expected_confidence(segments))
        segments[2]["confidenceScores"] = None
        self.assertEqual("absent", self.validator.expected_confidence(segments))

    def test_the_results_page_records_the_finding(self):
        if not RESULTS.is_file():
            self.skipTest("results not yet written")
        text = RESULTS.read_text(encoding="utf-8")
        self.assertIn("CONFIDENCE_SCORES", text)
        self.assertIn("minimum", text)


if __name__ == "__main__":
    unittest.main()
