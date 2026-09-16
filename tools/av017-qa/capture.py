"""AV-017 live STT capture: one corpus slot per run, on the pinned AVD.

    python tools/av017-qa/capture.py --list
    python tools/av017-qa/capture.py --slot stt-live-1 --evidence docs/testing/av017/evidence/live-YYYYMMDD

It drives `EvaluationInstrumentation`, which runs one bounded answer turn through the
shipped #13 + #26 path and constructs no card provider and no writer. Every attempt is
appended to captures.jsonl, including the ones that come back correct, that fail, and that
the environment breaks — a capture that does not produce a misrecognition is a result, not
a retry cue.

Only an attempt whose transcript differs from the phrase the operator attests to speaking
can fill an STT-mistake slot. This script never decides that a slot is filled and never
writes a label; see apply_captures.py.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from configuration import CORPUS, EVIDENCE  # noqa: E402
import microphone  # noqa: E402

COMPONENT = "org.ankivoice.test/org.ankivoice.app.EvaluationInstrumentation"
CONFIRMATION = "AV017_LIVE_CAPTURE"
DEVICE = "emulator-5588"


def pending_slots():
    """The corpus's live-pending STT answers, in corpus order."""
    corpus = json.loads(CORPUS.read_text())
    examples = corpus["examples"]
    slots = []
    for answer in corpus["answers"]:
        provenance = answer["provenance"]
        if provenance["kind"] != "live-pending":
            continue
        slots.append(
            {
                "slot": provenance["slot"],
                "answer_id": answer["id"],
                "split": answer["split"],
                "fixture_card": answer["fixture_card"],
                "example_id": answer["example_id"],
                "prompt": examples[answer["example_id"]]["Prompt"],
                "speak": provenance["spoken_target"],
            }
        )
    return slots


def capture(slot, device, speak_ms, diagnose=True):
    """Run one capture and return the harness's JSON, whatever it says."""
    # A run that crashes before recording leaves the previous attempt's PCM in place, and
    # classifying that would attribute the last attempt's microphone to this one.
    subprocess.run(
        [microphone.adb(), "-s", device, "shell", "run-as", "org.ankivoice", "rm", "-f", "files/av017-input.pcm"],
        capture_output=True,
        timeout=60,
    )
    command = [
        microphone.adb(), "-s", device, "shell", "am", "instrument", "-w",
        "-e", "confirm", CONFIRMATION,
        "-e", "slot", slot["slot"],
        "-e", "answerId", slot["answer_id"],
        "-e", "fixtureCard", slot["fixture_card"],
        # adb shell joins these into one device-shell command line, so anything with a
        # space must arrive quoted. Single quotes also stop the device shell expanding it.
        "-e", "prompt", shlex.quote(slot["prompt"]),
        "-e", "expect", shlex.quote(slot["speak"]),
        "-e", "speakMs", str(speak_ms),
        "-e", "diagnose", "true" if diagnose else "false",
        COMPONENT,
    ]
    finished = subprocess.run(command, capture_output=True, text=True, timeout=1800)
    raw = finished.stdout + finished.stderr
    match = re.search(r"INSTRUMENTATION_RESULT: av017=(\{.*\})", raw)
    if match is None:
        return {"error": "no instrumentation result", "raw": raw[-2000:]}
    return json.loads(match.group(1))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, default=EVIDENCE)
    parser.add_argument("--slot", help="one slot id; omit with --list")
    parser.add_argument("--device", default=DEVICE)
    parser.add_argument("--speak-ms", type=int, default=12000)
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--no-diagnose", action="store_true", help="skip the raw PCM copy")
    parser.add_argument("--no-preflight", action="store_true", help="skip the microphone check")
    args = parser.parse_args()

    slots = pending_slots()
    if args.list or not args.slot:
        for slot in slots:
            print(f"{slot['slot']:14} {slot['answer_id']:26} {slot['split']:9} "
                  f"{slot['fixture_card']:15} say: {slot['speak']!r}")
        if not args.slot:
            return

    selected = next((s for s in slots if s["slot"] == args.slot), None)
    if selected is None:
        raise SystemExit(f"{args.slot} is not a live-pending slot; --list shows the ones that are")

    args.evidence.mkdir(parents=True, exist_ok=True)

    # Never ask the operator to speak into a microphone that is not there. AV-040, AV-042
    # and AV-014 each lost attempts to the HAL fallback tone; this refuses instead.
    before = None
    if not args.no_preflight:
        before = microphone.preflight(args.device)
        print(f"microphone preflight: {before}")
        if before["verdict"] != microphone.LIVE:
            with (args.evidence / "captures.jsonl").open("a", encoding="utf-8") as ledger:
                ledger.write(json.dumps({
                    "utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                    "device": args.device,
                    "requested": selected,
                    "result": {"slot": selected["slot"], "environmentFault": True,
                               "microphoneBefore": before},
                }) + "\n")
            raise SystemExit(
                f"Refusing to capture: the microphone reads {before['verdict']}, so nothing "
                f"spoken now would be a recognition result.\n\n{microphone.REMEDY}"
            )

    result = capture(selected, args.device, args.speak_ms, not args.no_diagnose)

    # Classify the audio the recognizer actually received for this attempt.
    raw = microphone.read_device_pcm(args.device, "files/av017-input.pcm")
    after = microphone.analyse(raw) if raw else {"verdict": microphone.EMPTY, "samples": 0}
    fault = after["verdict"] != microphone.LIVE
    result["microphoneDuring"] = after
    result["environmentFault"] = fault
    if fault:
        # An environment fault is not a recognition result and is never scored as one.
        result["usableAsSttMistake"] = False

    entry = {
        "utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "device": args.device,
        "requested": selected,
        "microphoneBefore": before,
        "result": result,
    }
    with (args.evidence / "captures.jsonl").open("a", encoding="utf-8") as ledger:
        ledger.write(json.dumps(entry) + "\n")

    transcript = result.get("transcript")
    print(json.dumps(result, indent=2))
    print(
        f"\nslot {selected['slot']}: asked for {selected['speak']!r}, "
        f"transcript {transcript!r}, microphone {after['verdict']}, "
        f"usableAsSttMistake={result.get('usableAsSttMistake')}"
    )
    if fault:
        print(f"\nENVIRONMENT FAULT, not a recognition result.\n\n{microphone.REMEDY}")


if __name__ == "__main__":
    main()
