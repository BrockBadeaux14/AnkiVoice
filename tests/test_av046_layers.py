"""AV-046 drift guard: the diagnostic observes the shipped pipe and touches nothing else.

The card is a bounded discovery: it may add diagnostics beside `CaptureDiagnostics` in
`androidTest`, and it may change nothing in `:speech`, `:core`, AV-025's transport or
AV-012's policy. This test parses the sources and fails if that line moves: the harness has
no route to a card, the reference recorder uses nothing of the app's pipe, the transport
under observation is the shipped one over the shipped platform, the observers pass every
call straight through, and no shipped module carries the card's marker. It also runs the
attribution rule over fabricated attempts, one per layer, and the evidence validator over
whatever the live run retained. No Gradle run, no emulator, no network.
"""
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
ANDROID_TEST = ROOT / "android" / "app" / "src" / "androidTest"
HARNESS_DIR = ANDROID_TEST / "kotlin" / "org" / "ankivoice" / "app"
HARNESS = HARNESS_DIR / "CaptureLayerInstrumentation.kt"
REFERENCE = HARNESS_DIR / "ReferenceMicrophone.kt"
PUMP = HARNESS_DIR / "PumpDiagnostics.kt"
CAPTURE = HARNESS_DIR / "CaptureDiagnostics.kt"
MANIFEST = ANDROID_TEST / "AndroidManifest.xml"
SHIPPED = [ROOT / "android" / module / "src" / "main" for module in ("speech", "core", "app", "provider", "ankidroid")]


def code(path):
    """A Kotlin source with its comments removed, so prose about a symbol is not a use of it."""
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class TheHarnessTouchesNoCard(unittest.TestCase):
    FORBIDDEN = ("CardProvider", "ReviewWriter", "ReviewTransport", "AnkiDroid", "ReviewSession", "commit(",
                 "StudyController", "JournaledReviewWriter")

    def test_the_diagnostic_has_no_route_to_a_collection(self):
        for path in (HARNESS, REFERENCE, PUMP):
            source = code(path)
            for forbidden in self.FORBIDDEN:
                self.assertNotIn(forbidden, source, f"{path.name}: {forbidden}")
        self.assertIn('check(args.getString("confirm") == CONFIRMATION)', code(HARNESS))

    def test_the_diagnostic_plays_no_prompt_and_injects_no_audio(self):
        harness = code(HARNESS)
        self.assertNotIn(".speak(", harness)
        self.assertNotIn("Utterance(", harness)
        self.assertNotIn("injectAudio", harness)
        for path in (HARNESS, REFERENCE, PUMP):
            self.assertNotIn("AudioTrack", code(path), path.name)


class TheReferenceUsesNothingOfThePipe(unittest.TestCase):
    def test_the_reference_recorder_imports_no_speech_type(self):
        reference = code(REFERENCE)
        self.assertNotIn("org.ankivoice.speech", reference)
        self.assertNotIn("ParcelFileDescriptor", reference)
        self.assertNotIn("SpeechRecognizer", reference)
        self.assertNotIn("RecognizerIntent", reference)
        self.assertIn("AudioRecord(", reference, "a bare AudioRecord, constructed directly")
        self.assertIn("MediaRecorder.AudioSource.MIC", reference)
        self.assertIn("16_000", reference)
        self.assertIn("AudioFormat.ENCODING_PCM_16BIT", reference)


class TheTransportUnderObservationIsTheShippedOne(unittest.TestCase):
    def test_the_harness_wraps_the_shipped_platform_with_pass_through_observers(self):
        harness = code(HARNESS)
        self.assertIn("AndroidSpeechPlatform(targetContext)", harness)
        self.assertIn("CaptureDiagnostics(platform)", harness)
        self.assertIn("PumpDiagnostics(recorder, origin)", harness)
        self.assertIn("SpeechTransport(pump)", harness)
        # The recognizer observer is AV-044's inert hook; the harness only records from it.
        self.assertIn("platform.recognizerObserver =", harness)

    def test_the_pump_observer_passes_every_call_through(self):
        pump = code(PUMP)
        self.assertIn("val read = original.readFrame(samples)", pump)
        self.assertIn("return read", pump)
        self.assertIn("original.write(bytes, length)", pump)
        self.assertIn("original.stopMicrophone()", pump)
        self.assertIn("original.close()", pump)
        self.assertIn("platform.startCapture(generation, language, listener)", pump)
        self.assertIn("throw e", pump, "a failing write is recorded and thrown on, never swallowed")
        # It never writes, stops or closes on its own initiative.
        self.assertEqual(1, pump.count("original.write("))
        self.assertEqual(1, pump.count("original.stopMicrophone()"))
        self.assertEqual(1, pump.count("original.close()"))

    def test_the_sample_observer_is_the_one_av025_and_av044_used(self):
        capture = code(CAPTURE)
        self.assertIn("val read = original.readFrame(samples)", capture)
        self.assertIn("return read", capture)

    def test_no_shipped_module_carries_the_card(self):
        for root in SHIPPED:
            for path in root.rglob("*.kt"):
                text = path.read_text(encoding="utf-8")
                self.assertNotIn("AV-046", text, path)
                self.assertNotIn("av046", text, path)
                self.assertNotIn("ReferenceMicrophone", text, path)
                self.assertNotIn("PumpDiagnostics", text, path)

    def test_the_manifest_keeps_the_default_runner_first_and_declares_the_diagnostic(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        names = re.findall(r'android:name="org\.ankivoice\.app\.(\w+)"', manifest)
        self.assertEqual("ReviewInstrumentation", names[0])
        self.assertIn("CaptureLayerInstrumentation", names)


class TheAttributionRule(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("av046_validate", ROOT / "tools" / "av046-qa" / "validate.py")
        self.validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.validator)

    def record(self, *, reference_peak=5000, transport_peak=5000, transport_samples=96000, mic_bytes=None,
               capture=None, spoke=True, interactive=True, reference_mode="concurrent", emulator_fault=False,
               hal_fault=False, exited=False, write_errors=None, closed=True):
        if capture is None:
            capture = {"kind": "failed", "failure": "SpeechInput.noMatch: code 7"}
        reference = None
        if reference_mode != "none":
            reference = {"sampleCount": 96000, "zeroSamples": 10, "peak": reference_peak,
                         "startedMs": 100, "stoppedMs": 9000}
        return {
            "boot": 1, "openIndex": 1,
            "requested": {"id": "x", "say": "five"},
            "result": {
                "source": "operator", "interactive": interactive, "referenceMode": reference_mode,
                "reference": reference if reference is not None else "none",
                "capture": capture,
                "attestation": {"spokeExpectedPhrase": spoke},
                "transportRecorder": {"sampleCount": transport_samples,
                                      "zeroSamples": transport_samples if transport_peak == 0 else 10,
                                      "peak": transport_peak},
                "pump": {"opened": True, "micBytes": transport_samples * 2 if mic_bytes is None else mic_bytes,
                         "paddingBytes": 16000, "writeErrors": write_errors or [],
                         "openReturnedMs": 300, "stopMicrophoneMs": 8000, "closeMs": 8600 if closed else None},
                "recordingClientsAtSpeakNow": [],
            },
            "emulatorFault": emulator_fault, "guestHalFault": hal_fault, "emulatorExited": exited,
        }

    def layer(self, **kwargs):
        return self.validator.attribute(self.record(**kwargs))["layer"]

    def test_a_transcript_is_not_a_drop(self):
        self.assertEqual("no-drop", self.layer(capture={"kind": "transcript", "text": "five", "confidence": "sufficient"}))

    def test_the_reference_hearing_speech_the_recorder_did_not_is_the_app_recorder(self):
        self.assertEqual("app-recorder", self.layer(transport_peak=0))
        self.assertEqual("app-recorder", self.layer(transport_peak=300))
        self.assertEqual("app-recorder", self.layer(transport_samples=0, transport_peak=0))

    def test_speech_the_pump_did_not_carry_is_the_app_pipe(self):
        self.assertEqual("app-pipe", self.layer(mic_bytes=1000))
        self.assertEqual("app-pipe", self.layer(write_errors=[{"error": "IOException: EPIPE"}]))
        self.assertEqual("app-pipe", self.layer(closed=False))

    def test_speech_that_reached_the_pipe_is_after_the_pipe(self):
        self.assertEqual("after-the-pipe", self.layer())

    def test_both_recorders_silent_while_the_operator_spoke_is_the_emulator_or_host(self):
        self.assertEqual("emulator-or-host", self.layer(reference_peak=0, transport_peak=0))
        self.assertEqual("emulator-or-host", self.layer(reference_peak=150, transport_peak=120))

    def test_both_recorders_silent_with_nobody_speaking_attributes_nothing(self):
        self.assertEqual("no-speech", self.layer(reference_peak=0, transport_peak=0, spoke=False))
        self.assertEqual("no-speech", self.layer(reference_peak=0, transport_peak=0, spoke=False, interactive=False))

    def test_an_emulator_fault_or_exit_wins_over_the_recorders(self):
        self.assertEqual("emulator-or-host", self.layer(emulator_fault=True))
        self.assertEqual("emulator-or-host", self.layer(exited=True, transport_peak=0))
        self.assertEqual("emulator-or-host", self.layer(hal_fault=True, transport_peak=0))

    def test_no_reference_is_inconclusive_unless_the_recorder_heard_speech(self):
        self.assertEqual("inconclusive", self.layer(reference_mode="none", transport_peak=0))
        self.assertEqual("after-the-pipe", self.layer(reference_mode="none"))

    def test_a_reference_that_heard_less_than_the_transport_is_an_anomaly(self):
        self.assertEqual("reference-anomaly", self.layer(reference_peak=0))

    def test_the_operators_attestation_is_never_inferred_from_the_audio(self):
        # Loud audio with no attestation still attributes by level, but the summary counts no spoken capture.
        rows = [self.validator.check_attempt(self.record(spoke=False, transport_peak=0), "t", [])]
        self.assertFalse(rows[0]["spoke"])
        self.assertEqual(0, self.validator.summarize(rows)["spoken_captures"])

    def test_the_consistency_checks_reject_an_edited_record(self):
        failures = []
        self.validator.check_attempt(self.record(), "t", failures)
        self.assertEqual([], failures)
        edited = self.record()
        edited["result"]["capture"]["text"] = "five"
        self.validator.check_attempt(edited, "t", failures)
        self.assertTrue(any("failed capture carries text" in f for f in failures))
        failures.clear()
        edited = self.record()
        edited["result"]["pump"]["micBytes"] = 12
        self.validator.check_attempt(edited, "t", failures)
        self.assertTrue(any("the pump wrote" in f for f in failures))
        failures.clear()
        edited = self.record()
        edited["attribution"] = {"layer": "no-drop", "reason": "edited"}
        self.validator.check_attempt(edited, "t", failures)
        self.assertTrue(any("is not the derived" in f for f in failures))
        failures.clear()
        edited = self.record()
        edited["result"]["reference"]["startedMs"] = 5000
        self.validator.check_attempt(edited, "t", failures)
        self.assertTrue(any("started after" in f for f in failures))

    def test_the_summary_names_the_exit_criterion(self):
        rows = [self.validator.check_attempt(self.record(transport_peak=0), "t", [])]
        self.assertEqual(1, self.validator.summarize(rows)["exit_criterion"])
        rows = [self.validator.check_attempt(self.record(emulator_fault=True), "t", [])]
        self.assertEqual(2, self.validator.summarize(rows)["exit_criterion"])
        rows = [self.validator.check_attempt(self.record(), "t", [])]
        self.assertIsNone(self.validator.summarize(rows)["exit_criterion"])
        rows = [self.validator.check_attempt(dict(self.record(), boot=b), "t", []) for b in (1, 2, 3)]
        self.assertEqual(3, self.validator.summarize(rows)["exit_criterion"])

    def test_the_driver_reads_attempts_with_the_same_rule(self):
        driver = (ROOT / "tools" / "av046-qa" / "run.py").read_text(encoding="utf-8")
        self.assertIn('record["attribution"] = validate.attribute(record)', driver)
        self.assertIn("validate.level(", driver)
        # Every attempt is appended; none is filtered by its outcome.
        self.assertIn('with ledger.open("a", encoding="utf-8") as out', driver)
        # Nothing on the host speaks or injects: no macOS `say`, no player, no gRPC audio.
        self.assertIsNone(re.search(r'\[\s*"say",', driver))
        self.assertNotIn("afplay", driver)
        self.assertNotIn("injectAudio", driver)
        self.assertNotIn("grpc", driver.lower())


class RetainedEvidence(unittest.TestCase):
    def test_the_retained_evidence_validates(self):
        result = subprocess.run([sys.executable, str(ROOT / "tools" / "av046-qa" / "validate.py")],
                                cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_a_results_page_names_the_criterion_it_met(self):
        results = ROOT / "docs" / "testing" / "av046" / "results.md"
        if not results.is_file():
            self.skipTest("results not yet written")
        text = results.read_text(encoding="utf-8")
        self.assertTrue(re.search(r"exit criterion", text, re.I))
        self.assertIn("#29", text)


if __name__ == "__main__":
    unittest.main()
