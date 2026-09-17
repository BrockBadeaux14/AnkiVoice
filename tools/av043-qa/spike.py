"""AV-043 spike comparison: two or three paid candidates on the AV-017 tuning 20.

    python tools/av043-qa/spike.py --evidence docs/testing/av043/evidence/spike-<date> [--write]

Each candidate is one directory under the evidence root holding the harness's
`run-record-tuning.json` (and, once replayed, `run-replay-tuning.json`). The comparison
reads those runs, never a grader, and contacts no network. It refuses any run that touched
a held-out answer: the spike spends the tuning 20 only.

The numbers are the same three rates AV-017 defines — false acceptance, false rejection
and abstention — plus label agreement, the measured cost per request from the ledger's
own record, and p50/p95 latency. Nothing here defines a threshold; the pin is a decision
recorded in docs/testing/av043/results.md, and the exit criterion in both directions is
stated there.
"""
import argparse
from decimal import Decimal
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "av017-qa"))

from score import measure, percentile  # noqa: E402

EVIDENCE = ROOT / "docs" / "testing" / "av043" / "evidence"
RUN_FILES = ("run-replay-tuning.json", "run-record-tuning.json")


def load_run(directory):
    """The replayed run when it exists — it reproduces the live pass offline — else the live one."""
    for name in RUN_FILES:
        path = directory / name
        if path.is_file():
            return name, json.loads(path.read_text(encoding="utf-8"))
    return None, None


def check_tuning_only(candidate, run):
    if not run.get("spike"):
        raise SystemExit(f"{candidate}: the run is not a spike run (no 'spike' block); refusing to compare it")
    if run.get("split") != "tuning":
        raise SystemExit(f"{candidate}: the run's split is {run.get('split')!r}; the spike runs on the tuning 20 only")
    held = [a["id"] for a in run["answers"] if a["split"] != "tuning"]
    if held:
        raise SystemExit(f"{candidate}: the run scored held-out answers {held}; the spike must never touch them")
    if run.get("routes") != ["paid"]:
        raise SystemExit(f"{candidate}: the run used routes {run.get('routes')}; a spike measures the paid candidate alone")


def summarize(candidate, source, run):
    answers = run["answers"]
    ai = [a for a in answers if a["source"] == "ai"]
    unreached = [a for a in answers if a["source"] is None]
    measured = measure(ai + unreached, "paid")
    costs = [Decimal(a.get("costUsd") or "0") for a in answers]
    total = sum(costs, Decimal(0))
    requests = int(run["quota"]["counts"].get("reservedTotal", 0))
    latencies = [a["latencyMs"] for a in ai if a["latencyMs"] is not None]
    failures = {}
    for a in unreached:
        failures[a["failure"]] = failures.get(a["failure"], 0) + 1
    return {
        "candidate": candidate,
        "source_run": source,
        "model": run["paid_route"]["model"],
        "provider": run["paid_route"]["provider"],
        "price_ceiling_usd_per_token": {
            "prompt": run["paid_route"]["prompt_usd_per_token"],
            "completion": run["paid_route"]["completion_usd_per_token"],
        },
        "endpoints": [s.get("routes") for s in run.get("sessions", [])],
        "answers": len(answers),
        "rule_matched": sum(1 for a in answers if a["source"] == "rule"),
        "labels_returned": len(ai),
        "abstentions": len(unreached),
        "failures": failures,
        "label_agreement": measured["label_agreement"],
        "false_acceptance": measured["false_acceptance"],
        "false_rejection": measured["false_rejection"],
        "abstention": measured["abstention"],
        "cost_usd": {
            "total": str(total),
            "requests": requests,
            "per_request_mean": str((total / requests).quantize(Decimal("0.0000001"))) if requests else None,
            "per_answer_max": str(max(costs)) if costs else None,
        },
        "latency_ms": {
            "samples": len(latencies),
            "p50": percentile(latencies, 0.50),
            "p95": percentile(latencies, 0.95),
        },
        "usable_two_key_labels": len(ai) > 0,
    }


def table(rows):
    lines = [
        "| Candidate | Labels / answers | Agreement | False accept | False reject | Abstain | Cost / request | Total | p50 | p95 |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for r in rows:
        fa, fr, ab, ag = r["false_acceptance"], r["false_rejection"], r["abstention"], r["label_agreement"]
        lines.append(
            f"| `{r['model']}` via `{r['provider']}` | {r['labels_returned']} / {r['answers'] - r['rule_matched']} "
            f"| {ag['count']}/{ag['of']} | {fa['count']}/{fa['of']} | {fr['count']}/{fr['of']} | {ab['count']}/{ab['of']} "
            f"| ${r['cost_usd']['per_request_mean'] or 'n/a'} | ${r['cost_usd']['total']} "
            f"| {r['latency_ms']['p50']} ms | {r['latency_ms']['p95']} ms |"
        )
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True, help="the spike's evidence directory")
    parser.add_argument("--write", action="store_true", help="write comparison.json and comparison.md beside the runs")
    args = parser.parse_args()

    rows = []
    for directory in sorted(d for d in args.evidence.iterdir() if d.is_dir()):
        source, run = load_run(directory)
        if run is None:
            print(f"note: {directory.name} holds no run yet")
            continue
        check_tuning_only(directory.name, run)
        rows.append(summarize(directory.name, source, run))
    if not rows:
        raise SystemExit(f"no candidate run under {args.evidence}")
    if not 2 <= len(rows) <= 3:
        print(f"note: AV-043 bounds the spike to two or three candidates; {len(rows)} found")

    print(table(rows))
    for r in rows:
        if not r["usable_two_key_labels"]:
            print(f"finding: {r['candidate']} returned no usable two-key label ({r['failures']})")
    if args.write:
        (args.evidence / "comparison.json").write_text(json.dumps({"candidates": rows}, indent=2) + "\n", encoding="utf-8")
        (args.evidence / "comparison.md").write_text(table(rows) + "\n", encoding="utf-8")
        print(f"wrote {args.evidence / 'comparison.json'} and comparison.md")


if __name__ == "__main__":
    main()
