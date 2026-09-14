#!/usr/bin/env python3
"""Validate an AV-005 operator run and regenerate the measured tables.

The suite is operator-driven, so this checker's job is to make sure the recorded
evidence is internally consistent and actually describes live microphone turns:
prompts must match the merged AV-002 fixtures, capture must start after playback
plus the recorded settling interval, an error must never carry a transcript, a
stale callback must never have advanced a turn, and no turn may be presented as
spoken unless the operator attested to speaking it.

It rechecks captured evidence. It does not run the emulator.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import statistics
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "fixtures" / "voiceqa" / "note-type.json"
REPORT = ROOT / "docs" / "testing" / "av005-foreground-speech.md"
BEGIN = "<!-- av005:results:begin -->"
END = "<!-- av005:results:end -->"

SPOKEN_SCENARIOS = {"loop", "pause2", "pause5", "cancel", "late_callback", "busy"}
SILENT_SCENARIOS = {"silence", "echo"}


class Failure(Exception):
    pass


def check(condition: bool, message: str, failures: list) -> bool:
    if not condition:
        failures.append(message)
    return condition


def fixture_prompts() -> dict:
    note_type = json.loads(FIXTURE.read_text(encoding="utf-8"))
    return {
        example["id"]: {
            "prompt": example["fields"]["Prompt"],
            "answers": [case["answer"] for case in example["expected_cases"]],
        }
        for example in note_type["examples"]
    }


def merge(paths: list) -> dict:
    """Combines per-scenario runs into one document, keeping each run's own labels."""
    merged: dict | None = None
    for path in sorted(paths):
        document = json.loads(path.read_text(encoding="utf-8"))
        if merged is None:
            merged = dict(document)
            merged["scenarios"] = []
            merged["sources"] = []
        for scenario in document.get("scenarios", []):
            scenario.setdefault("operated_by", document.get("operated_by", "human"))
            merged["scenarios"].append(scenario)
        merged["sources"].append(path.name)
    return merged or {}


def validate(document: dict) -> tuple[list, list, dict]:
    """Returns (assertions, failures, summary)."""
    assertions: list = []
    failures: list = []
    prompts = fixture_prompts()

    def assert_(condition: bool, message: str) -> None:
        assertions.append(message)
        check(condition, message, failures)

    assert_(document.get("suite") == "AV-005 foreground speech", "document identifies the AV-005 suite")
    assert_(document.get("operator_driven") is True, "run is recorded as operator driven")
    assert_(document.get("anki_touched") is False, "no Anki collection was touched")
    assert_(document.get("rating_produced") is False, "no rating was produced")

    environment = document.get("environment") or {}
    assert_("emu64" in str(environment.get("fingerprint", "")), "evidence came from an emulator image")
    assert_(environment.get("locale") == "en-US", "locale is en-US")
    assert_(bool(environment.get("engine_version")), "text-to-speech engine version recorded")
    assert_(isinstance(environment.get("recognition_services"), list)
            and environment["recognition_services"], "recognition services enumerated")

    summary: dict = {"scenarios": [], "turn_count": 0, "success_count": 0, "human_transcripts": 0}
    for scenario in document.get("scenarios", []):
        name = scenario.get("scenario", "?")
        operated_by = scenario.get("operated_by")
        assert_(operated_by in {"human", "investigator_adb"},
                f"{name} records who operated the run")
        by_human = operated_by == "human"
        turns = scenario.get("turns", [])
        requested = scenario.get("requested_settings", {})
        successes = 0
        capture_times: list = []
        finalisation: list = []
        settles: list = []

        for turn in turns:
            label = f"{name}[{turn.get('turn_index')}]"
            example = turn.get("example_id")
            if example in prompts:
                assert_(turn.get("prompt") == prompts[example]["prompt"],
                        f"{label} prompt matches the AV-002 fixture")
                assert_(turn.get("expected_answer") in prompts[example]["answers"],
                        f"{label} expected answer comes from the fixture")

            status = turn.get("status")
            transcript = turn.get("transcript")
            error_name = turn.get("error_name")

            if status == "error":
                assert_(transcript in (None, "null"),
                        f"{label} an error carries no transcript")
                assert_(bool(error_name) or bool(turn.get("detail")),
                        f"{label} error is named")
            if status == "success":
                assert_(bool(transcript) and transcript != "null",
                        f"{label} a success carries a transcript")
                assert_(error_name in (None, "null"),
                        f"{label} a success carries no error")
                successes += 1
                # A run driven over adb has no voice, so it can never be live-speech evidence.
                assert_(by_human, f"{label} a transcript came from a human-operated run")
                if by_human and turn.get("operator_attestation") == "spoke_answer":
                    summary["human_transcripts"] += 1

            assert_(turn.get("echo_suspected") is not True,
                    f"{label} transcript was not the prompt echoed back")

            playback_done = turn.get("playback_done_ms")
            listen_at = turn.get("listen_requested_ms")
            if (isinstance(playback_done, (int, float)) and isinstance(listen_at, (int, float))
                    and not requested.get("capture_during_playback")):
                assert_(listen_at >= playback_done,
                        f"{label} capture started after playback finished")
                measured = turn.get("measured_settle_ms")
                if isinstance(measured, (int, float)):
                    settles.append(measured)
                    assert_(measured >= turn.get("settle_ms", 0) - 50,
                            f"{label} settling interval was honoured")

            if isinstance(turn.get("capture_ms"), (int, float)):
                capture_times.append(turn["capture_ms"])
            if isinstance(turn.get("finalization_after_eos_ms"), (int, float)):
                finalisation.append(turn["finalization_after_eos_ms"])

            attestation = turn.get("operator_attestation")
            if name in SPOKEN_SCENARIOS and status in {"success", "error", "cancelled"}:
                assert_(attestation is not None, f"{label} operator attested the turn")
            if name in SILENT_SCENARIOS and attestation is not None:
                assert_(attestation != "spoke_answer",
                        f"{label} a silent scenario was not attested as spoken")
            if status == "success" and attestation is not None:
                assert_(attestation == "spoke_answer",
                        f"{label} a transcript is only counted when the operator spoke")
            if not by_human:
                assert_(attestation != "spoke_answer",
                        f"{label} an adb-driven turn was not claimed as spoken")

        for entry in scenario.get("stale_callbacks", []):
            assert_(entry.get("advanced_turn") is False,
                    f"{name} stale {entry.get('callback')} did not advance a turn")

        summary["scenarios"].append({
            "scenario": name,
            "title": scenario.get("title"),
            "operated_by": operated_by,
            "voice_source": scenario.get("voice_source"),
            "turns": len(turns),
            "successes": successes,
            "stale_callbacks": len(scenario.get("stale_callbacks", [])),
            "focus_blips_ms": scenario.get("self_inflicted_focus_blips_ms", []),
            "median_capture_ms": round(statistics.median(capture_times)) if capture_times else None,
            "median_finalization_ms": round(statistics.median(finalisation)) if finalisation else None,
            "median_settle_ms": round(statistics.median(settles)) if settles else None,
            "errors": sorted({t.get("error_name") for t in turns if t.get("error_name")} - {None}),
        })
        summary["turn_count"] += len(turns)
        summary["success_count"] += successes

    return assertions, failures, summary


def render(summary: dict, environment: dict) -> str:
    lines = [
        BEGIN,
        "",
        f"Measured over {summary['turn_count']} recorded turns. "
        f"{summary['success_count']} returned a transcript, of which "
        f"{summary['human_transcripts']} came from a person speaking into the microphone.",
        "",
        "| Scenario | Operated by | Turns | Transcripts | Median capture | Median settle | Errors seen | Stale callbacks |",
        "| --- | --- | ---: | ---: | ---: | ---: | --- | ---: |",
    ]
    def order(row):
        """Scenario titles start with their position in the matrix."""
        label = (row["title"] or "").split(".", 1)[0]
        return int(label) if label.isdigit() else 999

    for row in sorted(summary["scenarios"], key=order):
        lines.append(
            "| {title} | {who} | {turns} | {successes} | {capture} | {settle} | {errors} | {stale} |".format(
                title=row["title"] or row["scenario"],
                who="person" if row["operated_by"] == "human" else "adb, no voice",
                turns=row["turns"],
                successes=row["successes"],
                capture=f"{row['median_capture_ms']} ms" if row["median_capture_ms"] is not None else "—",
                settle=f"{row['median_settle_ms']} ms" if row["median_settle_ms"] is not None else "—",
                errors=", ".join(row["errors"]) or "—",
                stale=row["stale_callbacks"],
            )
        )
    own = [b for row in summary["scenarios"] if row["scenario"] != "focus"
           for b in row["focus_blips_ms"]]
    external = [b for row in summary["scenarios"] if row["scenario"] == "focus"
                for b in row["focus_blips_ms"]]
    def span(values: list) -> str:
        return f"{values[0]} ms" if min(values) == max(values) else f"{min(values)}\u2013{max(values)} ms"

    if own:
        line = (f"Audio-focus losses caused by the app's own text to speech and recognizer: "
                f"{len(own)} recorded, {span(own)}.")
        if external:
            line += (f" Losses during the deliberate interruption scenario: "
                     f"{len(external)} recorded, {span(external)}.")
        lines += ["", line]
    lines += ["", END]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=pathlib.Path, nargs="+",
                        help="one or more runs pulled from the emulator")
    parser.add_argument("--write", action="store_true",
                        help="refresh the measured tables in the AV-005 report")
    arguments = parser.parse_args()

    missing = [path for path in arguments.evidence if not path.exists()]
    if missing:
        print(f"FAIL: {', '.join(str(m) for m in missing)} does not exist. "
              "Run the suite first; see the runbook.", file=sys.stderr)
        return 2
    document = merge(arguments.evidence)
    assertions, failures, summary = validate(document)

    for failure in failures:
        print(f"FAIL: {failure}", file=sys.stderr)
    if failures:
        print(f"{len(failures)} of {len(assertions)} evidence assertions failed.", file=sys.stderr)
        return 1

    print(f"PASS: {len(assertions)} evidence assertions "
          f"({summary['turn_count']} turns, {summary['success_count']} transcripts, "
          f"{summary['human_transcripts']} of them from human speech; "
          "captured run, not a new emulator execution)")

    if arguments.write:
        if not REPORT.exists():
            print(f"FAIL: {REPORT} is missing", file=sys.stderr)
            return 2
        text = REPORT.read_text(encoding="utf-8")
        if BEGIN not in text or END not in text:
            print(f"FAIL: {REPORT} has no results block to refresh", file=sys.stderr)
            return 2
        head, _, rest = text.partition(BEGIN)
        _, _, tail = rest.partition(END)
        REPORT.write_text(head + render(summary, document.get("environment", {})) + tail,
                          encoding="utf-8")
        print(f"Refreshed the measured tables in {REPORT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
