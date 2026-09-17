"""AV-046 evidence guard, attribution rule and summary.

    python tools/av046-qa/validate.py [--evidence docs/testing/av046/evidence] [--table] [--json]

It writes nothing and contacts no network. Over every `attempts.jsonl` under the evidence
root it checks that each attempt is internally consistent — the bytes the pump wrote to the
recognizer's pipe are the samples the transport's recorder delivered, a failed capture
carries no transcript, a concurrent reference covers the transport's window, and the layer
the driver recorded is the layer this rule derives — and prints the per-attempt table the
results page records.

The attribution rule is here and nowhere else, so the driver's live reading and the
retained evidence cannot disagree:

  no-drop           the capture returned a transcript; there was nothing to attribute
  emulator-or-host  the emulator logged its audio-backend fault or exited, the guest HAL
                    logged read failures, or both recorders received no speech while the
                    operator attests to speaking                          (exit criterion 2)
  app-recorder      the reference recorder received speech and the transport's recorder
                    received none                                         (exit criterion 1)
  app-pipe          the transport's recorder received speech and the pump did not carry
                    all of it to the recognizer's pipe                    (exit criterion 1)
  after-the-pipe    both recorders received speech and the pump carried it all: the audio
                    reached the recognizer, and a no-match is a recognition result, not a
                    dropped capture
  no-speech         neither recorder received speech and nobody attests to speaking
  reference-anomaly the transport's recorder received more than the reference did
  inconclusive      no reference to compare against, or the harness did not finish

Thresholds are heuristics, stated once: a PCM16 peak at or above SPEECH_PEAK reads as
speech; the zeroed rule is AV-017's. The operator's attestation is the only statement that
a phrase was spoken; the rule never infers it from the audio.
"""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "docs" / "testing" / "av046" / "evidence"

# A PCM16 peak at or above this reads as carrying speech. Room noise on the evidence host
# read in the low hundreds in AV-017/AV-044; spoken phrases peaked in the thousands.
SPEECH_PEAK = 2_000
# AV-017's rule for a zeroed microphone: a peak under this with at least half the samples zero.
ZERO_PEAK = 20
# One 20 ms frame of 16 kHz PCM16: the pump may have read one frame before a stop landed.
FRAME_BYTES = 640
# SpeechTimings.trailingSilenceMs of generated padding, at 16 kHz PCM16.
PADDING_BYTES = 16_000

CRITERION_1 = {"app-recorder", "app-pipe"}
CRITERION_2 = {"emulator-or-host"}
NO_SPEECH = {"no-samples", "zeroed", "quiet", "tone"}
BUDGET_BOOTS = 3
BUDGET_CAPTURES = 6


def level(stats, analysis=None):
    """What a recorder received: absent, no-samples, zeroed, tone, quiet or speech."""
    if not isinstance(stats, dict):
        return "absent"
    count = stats.get("sampleCount") or 0
    if count == 0:
        return "no-samples"
    if isinstance(analysis, dict) and analysis.get("verdict") == "goldfish-220hz-tone":
        return "tone"
    peak = stats.get("peak") or 0
    zeros = stats.get("zeroSamples") or 0
    if peak < ZERO_PEAK and zeros >= count / 2:
        return "zeroed"
    if peak >= SPEECH_PEAK:
        return "speech"
    return "quiet"


def pipe_carried(result):
    """Did the pump write to the recognizer's pipe every sample the recorder delivered, and close it?"""
    pump = result.get("pump") or {}
    recorder = result.get("transportRecorder") or {}
    if pump.get("opened") is not True or pump.get("writeErrors"):
        return False
    samples = recorder.get("sampleCount") or 0
    if abs((pump.get("micBytes") or 0) - samples * 2) > FRAME_BYTES:
        return False
    return pump.get("closeMs") is not None


def reference_of(result):
    """The reference recorder's record, or None when none ran."""
    reference = result.get("reference")
    return reference if isinstance(reference, dict) else None


def attribute(record):
    """The layer this attempt points at, with the reason, from the record alone."""
    result = record.get("result") or {}
    if "error" in result and "capture" not in result:
        return {"layer": "inconclusive", "reason": f"the harness did not finish: {result['error']}"}
    capture = result.get("capture")
    if capture is None:
        return {"layer": "inconclusive", "reason": f"no capture opened ({result.get('operatorAction', 'no start')})"}
    if capture.get("kind") == "transcript":
        return {"layer": "no-drop", "reason": f"transcript {capture.get('text')!r}"}

    spoke = bool((result.get("attestation") or {}).get("spokeExpectedPhrase"))
    unattended = result.get("interactive") is False
    transport = level(result.get("transportRecorder"), record.get("transportAnalysis"))
    reference = level(reference_of(result), record.get("referenceAnalysis"))

    if record.get("emulatorExited"):
        return {"layer": "emulator-or-host", "reason": "the emulator exited during the attempt"}
    if record.get("emulatorFault"):
        return {"layer": "emulator-or-host", "reason": "the emulator logged its coreaudio fault in this window"}
    if record.get("guestHalFault"):
        return {"layer": "emulator-or-host", "reason": "the guest audio HAL logged read failures in this window"}

    if reference == "absent":
        if transport == "speech":
            return ({"layer": "after-the-pipe", "reason": "no reference; the transport's recorder received speech and the pump carried it"}
                    if pipe_carried(result) else
                    {"layer": "app-pipe", "reason": "no reference; the transport's recorder received speech the pump did not carry"})
        return {"layer": "inconclusive", "reason": f"no reference to compare against; transport recorder {transport}"}

    if reference == "speech" and transport in NO_SPEECH:
        return {"layer": "app-recorder", "reason": f"the reference received speech; the transport's recorder read {transport}"}
    if reference == "speech" and transport == "speech":
        if pipe_carried(result):
            return {"layer": "after-the-pipe", "reason": "both recorders received speech and the pump carried all of it to the pipe"}
        return {"layer": "app-pipe", "reason": "both recorders received speech; the pump did not carry all of it"}
    if reference in NO_SPEECH and transport in NO_SPEECH:
        if spoke:
            return {"layer": "emulator-or-host",
                    "reason": f"both recorders received no speech (reference {reference}, transport {transport}) while the operator attests to speaking"}
        return {"layer": "no-speech",
                "reason": "unattended silent probe" if unattended else "nobody attests to speaking; nothing to attribute"}
    if transport == "speech" and reference != "speech":
        return {"layer": "reference-anomaly", "reason": f"the transport's recorder received speech; the reference read {reference}"}
    return {"layer": "inconclusive", "reason": f"reference {reference}, transport {transport}"}


def check_attempt(record, name, failures):
    result = record.get("result") or {}
    requested = record.get("requested") or {}
    label = f"{name}: {requested.get('id', '?')}"
    row = {
        "id": requested.get("id"),
        "boot": record.get("boot"),
        "open_index": record.get("openIndex"),
        "say": requested.get("say"),
        "source": result.get("source"),
        "status": "ok",
    }
    if record.get("boot") is None or record.get("openIndex") is None:
        failures.append(f"{label}: boot or microphone-open index missing")
    if not result.get("source"):
        failures.append(f"{label}: no audio source recorded")
    if "error" in result and "capture" not in result:
        row.update({"status": "harness-error", "detail": result["error"]})
    capture = result.get("capture")
    if isinstance(capture, dict):
        if capture.get("kind") == "failed" and "text" in capture:
            failures.append(f"{label}: a failed capture carries text")
        if capture.get("kind") not in ("failed", "transcript"):
            failures.append(f"{label}: unknown capture kind {capture.get('kind')!r}")
    pump = result.get("pump") or {}
    recorder = result.get("transportRecorder") or {}
    if pump.get("opened") is True and isinstance(recorder, dict) and "sampleCount" in recorder:
        if abs((pump.get("micBytes") or 0) - (recorder.get("sampleCount") or 0) * 2) > FRAME_BYTES:
            failures.append(f"{label}: the pump wrote {pump.get('micBytes')} bytes but the recorder delivered "
                            f"{recorder.get('sampleCount')} samples")
    reference = reference_of(result)
    if result.get("referenceMode") == "concurrent" and reference and pump.get("opened") is True:
        started, stopped = reference.get("startedMs"), reference.get("stoppedMs")
        opened, stop = pump.get("openReturnedMs"), pump.get("stopMicrophoneMs")
        if started is not None and opened is not None and started > opened:
            failures.append(f"{label}: the concurrent reference started after the transport's recorder opened")
        if stopped is not None and stop is not None and stopped < stop:
            failures.append(f"{label}: the concurrent reference stopped before the transport's microphone did")
    derived = attribute(record)
    recorded = record.get("attribution")
    if recorded is not None and recorded.get("layer") != derived["layer"]:
        failures.append(f"{label}: recorded layer {recorded.get('layer')!r} is not the derived {derived['layer']!r}")
    row.update({
        "spoke": bool((result.get("attestation") or {}).get("spokeExpectedPhrase")),
        "interactive": result.get("interactive", True),
        "reference_mode": result.get("referenceMode"),
        "reference": level(reference, record.get("referenceAnalysis")),
        "reference_peak": (reference or {}).get("peak"),
        "transport": level(recorder, record.get("transportAnalysis")),
        "transport_peak": recorder.get("peak") if isinstance(recorder, dict) else None,
        "transport_samples": recorder.get("sampleCount") if isinstance(recorder, dict) else None,
        "pipe": ("carried" if pipe_carried(result) else "not carried") if pump.get("opened") is True
                else ("not opened" if pump else "—"),
        "capture": capture.get("kind") if isinstance(capture, dict) else None,
        "text": capture.get("text") if isinstance(capture, dict) else None,
        "failure": capture.get("failure") if isinstance(capture, dict) else None,
        "emulator_fault": bool(record.get("emulatorFault")),
        "emulator_exited": bool(record.get("emulatorExited")),
        "hal_fault": bool(record.get("guestHalFault")),
        "hal_silence_inserts": (record.get("guestHal") or {}).get("silenceInserts"),
        "silenced_clients": silenced(result),
        "layer": derived["layer"],
        "reason": derived["reason"],
    })
    return row


def silenced(result):
    """How many recording clients the audio server reported silenced while the microphone was open."""
    clients = result.get("recordingClientsAtSpeakNow") or []
    return sum(1 for client in clients if isinstance(client, dict) and client.get("clientSilenced") is True)


def summarize(rows):
    captures = [r for r in rows if r.get("interactive", True) and r["status"] == "ok"]
    by_layer = {}
    for row in rows:
        by_layer[row["layer"]] = by_layer.get(row["layer"], 0) + 1
    boots = len({r["boot"] for r in rows if r.get("boot") is not None})
    criterion_1 = any(r["layer"] in CRITERION_1 and r["spoke"] for r in rows)
    criterion_2 = any(r["layer"] in CRITERION_2 and (r["spoke"] or r["emulator_fault"] or r["hal_fault"] or r["emulator_exited"])
                      for r in rows)
    if criterion_1:
        met = 1
    elif criterion_2:
        met = 2
    elif boots >= BUDGET_BOOTS or len(captures) >= BUDGET_CAPTURES:
        met = 3
    else:
        met = None
    return {
        "attempts": len(rows),
        "boots": boots,
        "spoken_captures": sum(1 for r in captures if r["spoke"]),
        "captures": len(captures),
        "unattended_probes": sum(1 for r in rows if r.get("interactive") is False),
        "by_layer": by_layer,
        "dropped_captures": sum(1 for r in captures if r["capture"] == "failed"),
        "transcripts": sum(1 for r in captures if r["capture"] == "transcript"),
        "exit_criterion": met,
    }


def table(rows):
    lines = ["| # | Attempt | Boot · open | Said | Reference | Transport recorder | Pipe | Recognizer | Emulator | Guest HAL | Layer |",
             "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for index, row in enumerate(rows, 1):
        if row["status"] != "ok":
            lines.append(f"| {index} | `{row['id']}` | {row.get('boot')} · {row.get('open_index')} | — | — | — | — | "
                         f"**{row['status']}**: {row.get('detail')} | — | — | inconclusive |")
            continue
        said = ("“%s” (attested)" % row["say"]) if row["spoke"] else (
            "silence (unattended)" if row.get("interactive") is False else "“%s” (not attested)" % row["say"])
        reference = (f"{row['reference']} (peak {row['reference_peak']})" if row["reference"] != "absent"
                     else "none")
        transport = f"{row['transport']} (peak {row['transport_peak']}, {row['transport_samples']} samples)"
        recognizer = (f"transcript “{row['text']}”" if row["capture"] == "transcript" else f"`{row['failure']}`")
        emulator = ("exited" if row["emulator_exited"] else "fault" if row["emulator_fault"] else "clean")
        hal = ("read failures" if row["hal_fault"] else
               f"{row['hal_silence_inserts']} silence inserts" if row.get("hal_silence_inserts") else "clean")
        if row["silenced_clients"]:
            hal += f"; {row['silenced_clients']} client(s) silenced"
        lines.append(f"| {index} | `{row['id']}` | {row['boot']} · {row['open_index']} | {said} | {reference} | "
                     f"{transport} | {row['pipe']} | {recognizer} | {emulator} | {hal} | **{row['layer']}** |")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--evidence", type=Path, default=EVIDENCE)
    parser.add_argument("--table", action="store_true", help="print the per-attempt markdown table")
    parser.add_argument("--json", action="store_true", help="print the summary as JSON")
    args = parser.parse_args()

    failures, warnings = [], []
    evidence = args.evidence.resolve()
    ledgers = sorted(evidence.rglob("attempts.jsonl")) if evidence.is_dir() else []
    if not ledgers:
        warnings.append(f"{evidence} holds no attempts.jsonl yet")
    for ledger in ledgers:
        rows = []
        for number, line in enumerate(ledger.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                failures.append(f"{ledger.relative_to(ROOT)}:{number}: not JSON ({error})")
                continue
            rows.append(check_attempt(record, f"{ledger.relative_to(ROOT)}:{number}", failures))
        summary = summarize(rows)
        print(f"{ledger.relative_to(ROOT)}: {json.dumps(summary) if args.json else summary}")
        if args.table:
            print()
            print(table(rows))
            print()
    for warning in warnings:
        print(f"warning: {warning}")
    for failure in failures:
        print(f"FAIL: {failure}")
    if failures:
        sys.exit(1)
    print("AV-046 evidence is consistent." if ledgers else "AV-046: nothing to validate yet.")


if __name__ == "__main__":
    main()
