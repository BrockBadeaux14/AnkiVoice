"""AV-044 evidence guard and summary.

    python tools/av044-qa/validate.py [--evidence docs/testing/av044/evidence] [--table]

It writes nothing and contacts no network. Over every `attempts.jsonl` under the evidence
root it checks that each attempt is internally consistent — the transport's text is the
segments' text, its classified confidence follows from the segment scores it was handed,
a failure carries no transcript, and every attempt names its audio source — and prints
the per-attempt table the results page records. It fails on a fabricated or edited
attempt, and it never decides whether a phrase was "really" spoken: that is the source
field's job, and a human voice pass is only what an operator attested to.
"""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "docs" / "testing" / "av044" / "evidence"
HUMAN_SOURCES = {"operator"}


def classify(score):
    """:core's rule, restated for the check: null is unknown, <= 0 is low, else sufficient."""
    if score is None:
        return "absent"
    return "low" if score <= 0 else "sufficient"


def expected_confidence(segments):
    """What the shipped rule yields for these segments: the minimum over the ones with text,
    and unknown when any of them came without a score."""
    contributing = [s for s in segments if "".join(s.get("text") or []).strip()]
    if not contributing:
        return "absent"
    scores = []
    for segment in contributing:
        values = segment.get("confidenceScores")
        if not values:
            return "absent"
        scores.append(values[0])
    return classify(min(scores))


def check_attempt(record, name, failures):
    result = record.get("result", {})
    requested = record.get("requested", {})
    label = f"{name}: {requested.get('id', '?')}"
    if "error" in result and "capture" not in result:
        # A harness that did not finish is recorded as such; nothing more to check.
        return {"id": requested.get("id"), "status": "harness-error", "detail": result["error"]}
    capture = result.get("capture")
    segments = result.get("segments", [])
    source = result.get("source")
    if not source:
        failures.append(f"{label}: no audio source recorded")
    if capture is None:
        return {"id": requested.get("id"), "status": "no-capture", "detail": result.get("playback")}
    joined = " ".join(" ".join(s.get("text") or []) for s in segments).split()
    if capture["kind"] == "transcript":
        if capture["text"].split() != joined:
            failures.append(f"{label}: transcript {capture['text']!r} is not the segments' text {' '.join(joined)!r}")
        # The shipped transport before this card always reported absent; after it, the
        # minimum rule. Either is consistent; anything else is not the transport's output.
        allowed = {"absent", expected_confidence(segments)}
        if capture["confidence"] not in allowed:
            failures.append(f"{label}: confidence {capture['confidence']} does not follow from the segments")
    elif capture["kind"] == "failed":
        if "text" in capture:
            failures.append(f"{label}: a failed capture carries text")
    else:
        failures.append(f"{label}: unknown capture kind {capture.get('kind')!r}")
    for segment in segments:
        has = segment.get("hasConfidenceScores")
        values = segment.get("confidenceScores")
        if has and not isinstance(values, list):
            failures.append(f"{label}: a segment claims scores but lists none")
        if not has and values:
            failures.append(f"{label}: a segment lists scores it did not carry")
    mic = result.get("microphoneDuring", {}).get("verdict")
    return {
        "id": requested.get("id"),
        "kind": requested.get("kind"),
        "say": " | ".join(requested.get("say", [])),
        "source": source,
        "microphone": mic,
        "fault": bool(result.get("environmentFault")),
        "segments": len(segments),
        "with_scores": sum(1 for s in segments if s.get("hasConfidenceScores")),
        "scores": [round(s["confidenceScores"][0], 3) for s in segments if s.get("confidenceScores")],
        "texts": [" ".join(s.get("text") or []) for s in segments],
        "capture": capture["kind"],
        "text": capture.get("text"),
        "confidence": capture.get("confidence"),
        "failure": capture.get("failure"),
        "done_to_final_ms": result.get("doneToFinalMs"),
        "status": "ok",
    }


def summarize(rows):
    live = [r for r in rows if r["status"] == "ok" and not r["fault"]]
    return {
        "attempts": len(rows),
        "faults": sum(1 for r in rows if r.get("fault")),
        "harness_errors": sum(1 for r in rows if r["status"] != "ok"),
        "captures_with_text": sum(1 for r in live if r["capture"] == "transcript"),
        "no_match": sum(1 for r in live if r["capture"] == "failed"),
        "segments": sum(r["segments"] for r in live),
        "segments_with_text": sum(1 for r in live for t in r["texts"] if t.strip()),
        "text_segments_with_scores": sum(
            1 for r in live for t, s in zip(r["texts"], r["scores"] + [None] * (len(r["texts"]) - len(r["scores"])))
            if t.strip() and s is not None
        ),
        "human_voice_attempts": sum(1 for r in live if r["source"] in HUMAN_SOURCES),
    }


def table(rows):
    lines = ["| # | Attempt | Spoken (source) | Mic | Segments: text → score | Transport result | Done→final |",
             "| --- | --- | --- | --- | --- | --- | --- |"]
    for index, row in enumerate(rows, 1):
        if row["status"] != "ok":
            lines.append(f"| {index} | `{row['id']}` | — | — | — | **{row['status']}**: {row['detail']} | — |")
            continue
        scores = row["scores"] + [None] * (len(row["texts"]) - len(row["scores"]))
        segs = "; ".join(f"“{t}” → {s if s is not None else 'no score'}" if t.strip() else f"(empty) → {s if s is not None else 'no score'}"
                         for t, s in zip(row["texts"], scores)) or "none"
        result = (f"transcript “{row['text']}”, `{row['confidence']}`" if row["capture"] == "transcript"
                  else f"failed: `{row['failure']}`")
        mic = row["microphone"] + (" (fault)" if row["fault"] else "")
        lines.append(f"| {index} | `{row['id']}` | “{row['say']}” ({row['source']}) | {mic} | {segs} | {result} | {row['done_to_final_ms']} ms |")
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
    print("AV-044 evidence is consistent." if ledgers else "AV-044: nothing to validate yet.")


if __name__ == "__main__":
    main()
