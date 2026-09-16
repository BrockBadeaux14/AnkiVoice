"""Fill one AV-017 STT-mistake slot from a live capture, with a human label.

    python tools/av017-qa/apply_captures.py --slot stt-live-6 \
        --label partial --rationale "..." --evidence docs/testing/av017/evidence/live-YYYYMMDD

It refuses to fill a slot from an environment fault, from a correct recognition, or
without a written label and rationale: the label is a human judgement against the card's
ReferenceAnswer, RequiredConcepts and AcceptedAnswers, and nothing here derives one.

Run this before any grader sees the answer. A label written after seeing a grader's output
is not the label this evaluation measures against.
"""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from configuration import CORPUS, EVIDENCE, ROOT  # noqa: E402

LABELS = ("correct", "partial", "incorrect")


def usable_capture(evidence, slot):
    """The most recent capture for `slot` that may fill it, or the reason none may."""
    ledger = Path(evidence) / "captures.jsonl"
    if not ledger.is_file():
        raise SystemExit(f"{ledger} does not exist; capture the slot first")
    entries = [json.loads(line) for line in ledger.read_text().splitlines() if line.strip()]
    mine = [e for e in entries if e["result"].get("slot") == slot]
    if not mine:
        raise SystemExit(f"no capture recorded for {slot}")
    for entry in reversed(mine):
        result = entry["result"]
        if result.get("usableAsSttMistake"):
            return entry
    latest = mine[-1]["result"]
    raise SystemExit(
        f"{slot} has {len(mine)} recorded attempt(s), none usable as an STT mistake "
        f"(last: status={latest.get('answerStatus')}, transcript={latest.get('transcript')!r}, "
        f"environmentFault={latest.get('environmentFault')}). A correct recognition and an "
        "environment fault are both results; neither fills this slot, and synthetic "
        "corruption is not an acceptable substitute."
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slot", required=True)
    parser.add_argument("--label", required=True, choices=LABELS)
    parser.add_argument("--rationale", required=True, help="written against the card's answer key")
    parser.add_argument("--evidence", type=Path, default=EVIDENCE)
    args = parser.parse_args()

    entry = usable_capture(args.evidence, args.slot)
    result = entry["result"]
    corpus = json.loads(CORPUS.read_text())
    answer = next(
        (a for a in corpus["answers"] if a["provenance"].get("slot") == args.slot),
        None,
    )
    if answer is None:
        raise SystemExit(f"{args.slot} is not an open slot in {CORPUS.name}")
    if answer["provenance"]["kind"] != "live-pending":
        raise SystemExit(f"{args.slot} is already filled from {answer['provenance']['kind']}")

    # What the operator was asked to say and attested to, as the capture recorded it; the
    # slot's planned phrase is a fallback for records that predate --say.
    spoken = result.get("expectedPhrase") or answer["provenance"]["spoken_target"]
    answer["answer"] = result["transcript"]
    answer["label"] = args.label
    answer["label_rationale"] = args.rationale
    answer["provenance"] = {
        "kind": "live",
        "spoken": spoken,
        "attested": result.get("attestation"),
        "source": f"AV-017 live capture on the pinned AVD, slot {args.slot}",
        "evidence": str(Path(args.evidence).resolve().relative_to(ROOT) / "captures.jsonl"),
        "utc": entry["utc"],
        "done_to_final_ms": result.get("doneToFinalMs"),
        "confidence": result.get("confidence"),
        "microphone": result.get("microphoneDuring"),
    }
    CORPUS.write_text(json.dumps(corpus, indent=2, ensure_ascii=False) + "\n")
    print(f"{args.slot} -> {answer['id']}: {result['transcript']!r} labeled {args.label}")
    print(f"  spoken: {spoken!r}")
    print(f"  {args.rationale}")


if __name__ == "__main__":
    main()
