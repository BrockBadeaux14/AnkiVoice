"""AV-017 evidence guard: fail loudly rather than publish a measurement that is not what
it claims to be.

    python tools/av017-qa/validate.py [--evidence docs/testing/av017/evidence/<dir>]

It writes nothing and contacts no network. It checks the corpus against the table the card
fixed, the split discipline, the STT sourcing rule, the held-out run ledger, and that no
credential ever reached the evidence.
"""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from configuration import (  # noqa: E402
    CORPUS,
    EVIDENCE,
    HELD_OUT_LEDGER,
    CONFIGURATION_FILE,
)

CATEGORY_TABLE = {
    "paraphrase": (3, 6),
    "negation": (3, 5),
    "number-or-unit": (3, 5),
    "incomplete": (3, 6),
    "stt-mistake": (3, 6),
    "correct-short": (3, 6),
    "incorrect-short": (2, 6),
}
LABELS = {"correct", "partial", "incorrect"}
CAPTURED = {"recorded", "live", "live-pending"}
KEY_PREFIX = "sk-or-"


def check_corpus(failures):
    corpus = json.loads(CORPUS.read_text())
    answers = corpus["answers"]
    if len(answers) != 60:
        failures.append(f"the corpus holds {len(answers)} answers; AV-017 fixes it at 60")
    for split, expected in (("tuning", 20), ("held-out", 40)):
        actual = sum(1 for a in answers if a["split"] == split)
        if actual != expected:
            failures.append(f"the {split} split holds {actual} answers, not {expected}")
    for category, (tuning, held) in CATEGORY_TABLE.items():
        inside = [a for a in answers if a["category"] == category]
        got = (
            sum(1 for a in inside if a["split"] == "tuning"),
            sum(1 for a in inside if a["split"] == "held-out"),
        )
        if got != (tuning, held):
            failures.append(f"{category} is {got[0]}/{got[1]}; the card's table says {tuning}/{held}")

    for answer in answers:
        provenance = answer["provenance"]["kind"]
        if provenance == "live-pending":
            if answer["answer"] is not None or answer["label"] is not None:
                failures.append(f"{answer['id']} is live-pending but already carries text or a label")
            continue
        if answer["label"] not in LABELS:
            failures.append(f"{answer['id']} carries label {answer['label']!r}")
        if not answer["answer"]:
            failures.append(f"{answer['id']} carries no answer text")
        if not answer.get("label_rationale"):
            failures.append(f"{answer['id']} carries no written label rationale")
        if answer["category"] == "stt-mistake" and provenance not in CAPTURED:
            failures.append(
                f"{answer['id']} is an STT-mistake answer with provenance {provenance!r}; "
                "synthetic corruption is not an acceptable substitute"
            )

    # Every baseline card is represented, so the corpus is over the package, not one card.
    covered = {a["fixture_card"] for a in answers}
    missing = set(corpus["baseline_cards"]) - covered
    if missing:
        failures.append(f"these baseline cards carry no answer: {sorted(missing)}")

    # An AV-006 or AV-042 transcript is regression context: it may be reused as an STT
    # answer, but never scored as held-out evidence.
    for answer in answers:
        if answer["provenance"]["kind"] == "recorded" and answer["split"] == "held-out":
            failures.append(
                f"{answer['id']} reuses recorded evidence and is in the held-out split; "
                "previously inspected output is not held-out evidence"
            )
    return corpus


def check_live_sourcing(corpus, failures, warnings):
    stt = [a for a in corpus["answers"] if a["category"] == "stt-mistake"]
    live = [a for a in stt if a["provenance"]["kind"] == "live"]
    if len(live) < 6:
        warnings.append(
            f"{len(live)} of the 9 STT-mistake answers come from live capture; AV-017 requires at "
            "least 6. Any scored run is reported as INCOMPLETE rather than passed."
        )
    for answer in live:
        if not answer["provenance"].get("evidence"):
            failures.append(f"{answer['id']} is live-sourced but names no raw evidence")


def check_evidence(evidence, failures, warnings):
    """Check one run directory, or every run directory under an evidence root."""
    evidence = Path(evidence)
    if not evidence.is_dir():
        warnings.append(f"{evidence} does not exist yet; nothing to check")
        return
    runs = [evidence] if (evidence / "captures.jsonl").is_file() or (
        evidence / "measurements.json"
    ).is_file() else sorted(d for d in evidence.iterdir() if d.is_dir())
    if not runs:
        warnings.append(f"{evidence} holds no run directory yet; nothing to check")
        return
    for run in runs:
        check_run(run, failures, warnings)


def check_run(evidence, failures, warnings):
    """One run directory: its captures, its measurements, and that no key reached it."""
    captures = evidence / "captures.jsonl"
    if captures.is_file():
        entries = [json.loads(line) for line in captures.read_text().splitlines() if line.strip()]
        faults = sum(1 for e in entries if e["result"].get("environmentFault"))
        for entry in entries:
            result = entry["result"]
            if result.get("environmentFault") and result.get("usableAsSttMistake"):
                failures.append(
                    f"{result.get('slot')} is marked an environment fault and still usable; a "
                    "capture on a dead microphone is not a recognition result"
                )
        if faults and faults == len(entries):
            warnings.append(f"all {faults} captures are environment faults; none is a result")

    measurements = evidence / "measurements.json"
    if measurements.is_file():
        found = json.loads(measurements.read_text())
        if found["gates"]["target_error_rate"] is not None or found["gates"]["pass_fail"] is not None:
            failures.append("the measurements carry a pass/fail gate; AV-017 defines none")
        ledger = evidence / HELD_OUT_LEDGER
        held = found["held_out"]["answers"]
        if held and not ledger.is_file():
            failures.append(f"the held-out 40 was scored and {HELD_OUT_LEDGER} does not exist")
        if held and not (evidence / CONFIGURATION_FILE).is_file():
            failures.append(f"the held-out 40 was scored with no {CONFIGURATION_FILE}")
        if ledger.is_file():
            runs = [json.loads(line) for line in ledger.read_text().splitlines() if line.strip()]
            for run in runs:
                if not run.get("configuration_hash"):
                    failures.append("a held-out run is recorded with no configuration hash")

    # AV-020's rule: nothing key-shaped may appear anywhere in the evidence.
    for path in evidence.rglob("*"):
        if not path.is_file():
            continue
        try:
            text = path.read_text(errors="ignore")
        except OSError:
            continue
        if KEY_PREFIX in text and "replay" not in text:
            failures.append(f"{path} contains something key-shaped")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, default=EVIDENCE)
    args = parser.parse_args()

    failures, warnings = [], []
    corpus = check_corpus(failures)
    check_live_sourcing(corpus, failures, warnings)
    check_evidence(args.evidence, failures, warnings)

    for warning in warnings:
        print(f"note: {warning}")
    if failures:
        for failure in failures:
            print(f"FAIL: {failure}")
        raise SystemExit(f"{len(failures)} check(s) failed")
    print(f"AV-017 evidence checks passed ({len(warnings)} note(s)).")


if __name__ == "__main__":
    main()
