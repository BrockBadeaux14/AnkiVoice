"""AV-017 scoring: turn one harness run into the measurements the card asks for.

    python tools/av017-qa/score.py <run.json> --evidence docs/testing/av017/evidence/<dir> \
        [--freeze] [--label NAME] [--note "why this run was discarded"]

It reads a run, never a grader, and contacts no network. Every held-out scoring appends a
line to held-out-runs.jsonl with the configuration hash it ran against, including runs
that are later discarded: a silently re-scored run is a failed run, not a better one.

This card defines no target error rate and gates nothing. The numbers below are advisory
quality evidence for a path that always requires explicit learner confirmation.
"""
import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from configuration import EVIDENCE, HELD_OUT_LEDGER, describe, freeze, load_frozen  # noqa: E402

# 1 is Again. The shipped mapping proposes only 3 (correct) or 1 (incorrect); a rating is
# "passing" when it is offered at all and is not Again.
AGAIN = 1
CATEGORIES = (
    "paraphrase",
    "negation",
    "number-or-unit",
    "incomplete",
    "stt-mistake",
    "correct-short",
    "incorrect-short",
)


def percentile(values, fraction):
    """Nearest-rank percentile; None for an empty sample, which is not zero."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, min(len(ordered), math.ceil(fraction * len(ordered))))
    return ordered[rank - 1]


def rate(numerator, denominator):
    """A rate, or None when the sample is empty. An empty sample is never reported as 0."""
    if denominator == 0:
        return None
    return round(numerator / denominator, 4)


def measure(answers, label):
    """False acceptance, false rejection, abstention and latency over `answers`."""
    incorrect = [a for a in answers if a["humanLabel"] == "incorrect"]
    correct = [a for a in answers if a["humanLabel"] == "correct"]
    partial = [a for a in answers if a["humanLabel"] == "partial"]

    def passing(answer):
        return answer["proposedRating"] is not None and answer["proposedRating"] != AGAIN

    def failing(answer):
        return answer["proposedRating"] == AGAIN

    false_accepts = [a for a in incorrect if passing(a)]
    false_rejects = [a for a in correct if failing(a)]
    abstentions = [a for a in answers if a["proposedRating"] is None]
    latencies = [a["latencyMs"] for a in answers if a["latencyMs"] is not None]
    agreed = [a for a in answers if a["graderLabel"] == a["humanLabel"]]

    return {
        "path": label,
        "answers": len(answers),
        "human_labels": {
            "correct": len(correct),
            "partial": len(partial),
            "incorrect": len(incorrect),
        },
        "false_acceptance": {
            "count": len(false_accepts),
            "of": len(incorrect),
            "rate": rate(len(false_accepts), len(incorrect)),
            "ids": [a["id"] for a in false_accepts],
        },
        "false_rejection": {
            "count": len(false_rejects),
            "of": len(correct),
            "rate": rate(len(false_rejects), len(correct)),
            "ids": [a["id"] for a in false_rejects],
        },
        "abstention": {
            "count": len(abstentions),
            "of": len(answers),
            "rate": rate(len(abstentions), len(answers)),
        },
        "label_agreement": {
            "count": len(agreed),
            "of": len(answers),
            "rate": rate(len(agreed), len(answers)),
            "note": "Secondary. The card's three rates are the measurement; this is context.",
        },
        "per_category": {
            name: {
                "answers": sum(1 for a in answers if a["category"] == name),
                "false_acceptance": sum(1 for a in false_accepts if a["category"] == name),
                "false_rejection": sum(1 for a in false_rejects if a["category"] == name),
                "abstention": sum(1 for a in abstentions if a["category"] == name),
            }
            for name in CATEGORIES
        },
        "latency_ms": {
            "samples": len(latencies),
            "p50": percentile(latencies, 0.50),
            "p95": percentile(latencies, 0.95),
        },
    }


def split_paths(answers, ai_path_run):
    """The rule-only path and the AI path, which are never merged."""
    rule = [a for a in answers if a["source"] == "rule"]
    ai = [a for a in answers if a["source"] == "ai"]
    unreached = [a for a in answers if a["source"] is None]
    paths = {"rule-only": measure(rule, "rule-only")}
    if ai_path_run:
        # An AI request that failed is an abstention on the AI path: the rules already
        # declined, so the learner self-grades. It is measured, not excluded.
        paths["ai"] = measure(ai + unreached, "ai")
    else:
        paths["ai"] = {
            "path": "ai",
            "status": "not-run",
            "answers_the_rules_left": len(unreached),
            "note": (
                "No credential was supplied, so the AI leg was never attempted. This is not an "
                "abstention and is not scored as one. Re-run the harness in record mode to "
                "measure this path."
            ),
        }
    return paths


def score(run, evidence, freeze_now, label, note):
    evidence = Path(evidence)
    evidence.mkdir(parents=True, exist_ok=True)
    answers = run["answers"]
    ai_path_run = bool(run["ai_path_run"])

    frozen = freeze(evidence, run) if freeze_now else load_frozen(evidence)
    current = describe(run)
    held_out = [a for a in answers if a["split"] == "held-out"]

    if held_out:
        if frozen is None:
            raise SystemExit(
                "Refusing to score the held-out 40: no frozen configuration. Inspect the tuning "
                "20, then freeze with --freeze before the held-out answers are scored."
            )
        if frozen["hash"] != current["hash"]:
            raise SystemExit(
                f"Refusing to score the held-out 40: the graded configuration is "
                f"{current['hash'][:12]}, and {evidence}/configuration.json freezes "
                f"{frozen['hash'][:12]}. Score a frozen configuration once, in a new evidence "
                "directory, rather than re-scoring after a change."
            )

    corpus_total = run["scored"] + len(run["awaiting_live_capture"])
    live_stt = sum(1 for a in answers if a["category"] == "stt-mistake" and a["provenance"] == "live")
    complete = live_stt >= 6 and not run["awaiting_live_capture"]

    measurements = {
        "schema_version": 1,
        "card": "AV-017",
        "utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "label": label,
        "mode": run["mode"],
        "configuration_hash": current["hash"],
        "corpus": {
            "file": run["corpus"],
            "sha256": run["corpus_sha256"],
            "answers": corpus_total,
            "scored": run["scored"],
            "awaiting_live_capture": run["awaiting_live_capture"],
        },
        "completeness": {
            "complete": complete,
            "live_stt_answers": live_stt,
            "live_stt_required": 6,
            "verdict": (
                "complete"
                if complete
                else "INCOMPLETE — reported as incomplete rather than passed, per AV-017: a scored "
                "run that used fewer than 6 genuine live misrecognitions is not a pass."
            ),
        },
        "quota": run["quota"],
        "gates": {
            "target_error_rate": None,
            "pass_fail": None,
            "note": (
                "AV-017 defines no target error rate and gates nothing. Automatic acceptance stays "
                "out of the MVP, model confidence is an uncalibrated signal, and explicit user "
                "confirmation is enabled throughout."
            ),
        },
        "held_out": {
            "answers": len(held_out),
            "paths": split_paths(held_out, ai_path_run),
        },
        "tuning": {
            "answers": len([a for a in answers if a["split"] == "tuning"]),
            "paths": split_paths([a for a in answers if a["split"] == "tuning"], ai_path_run),
        },
        "note": note,
    }

    (evidence / "measurements.json").write_text(json.dumps(measurements, indent=2) + "\n")

    if held_out:
        entry = {
            "utc": measurements["utc"],
            "label": label,
            "mode": run["mode"],
            "configuration_hash": current["hash"],
            "held_out_answers": len(held_out),
            "complete": complete,
            "live_stt_answers": live_stt,
            "note": note,
        }
        with (evidence / HELD_OUT_LEDGER).open("a", encoding="utf-8") as ledger:
            ledger.write(json.dumps(entry) + "\n")

    return measurements


def report(measurements):
    print(f"AV-017 · {measurements['label']} · mode {measurements['mode']}")
    print(f"configuration {measurements['configuration_hash'][:12]}")
    print(f"completeness: {measurements['completeness']['verdict']}")
    for split in ("tuning", "held_out"):
        block = measurements[split]
        print(f"\n{split.replace('_', '-')} ({block['answers']} answers)")
        for name, path in block["paths"].items():
            if path.get("status") == "not-run":
                print(f"  {name:9} not run ({path['answers_the_rules_left']} answers the rules left)")
                continue
            fa, fr, ab = path["false_acceptance"], path["false_rejection"], path["abstention"]
            print(
                f"  {name:9} n={path['answers']:3}  "
                f"false-accept {fa['count']}/{fa['of']} ({fa['rate']})  "
                f"false-reject {fr['count']}/{fr['of']} ({fr['rate']})  "
                f"abstain {ab['count']}/{ab['of']} ({ab['rate']})  "
                f"p50={path['latency_ms']['p50']}ms p95={path['latency_ms']['p95']}ms"
            )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path, help="the harness's run-<mode>.json")
    parser.add_argument("--evidence", type=Path, default=EVIDENCE)
    parser.add_argument("--freeze", action="store_true", help="freeze this graded configuration")
    parser.add_argument("--label", default="unlabelled", help="a short name for this run")
    parser.add_argument("--note", default="", help="why this run was discarded, if it was")
    args = parser.parse_args()
    report(score(json.loads(args.run.read_text()), args.evidence, args.freeze, args.label, args.note))


if __name__ == "__main__":
    main()
