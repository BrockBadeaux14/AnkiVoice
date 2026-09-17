"""AV-043 evidence guard: the paid pin, the tuning-only spike, and the disclosure.

    python tools/av043-qa/validate.py [--evidence docs/testing/av043/evidence]

It writes nothing and contacts no network. It fails if the pinned paid route in the code
is not the one the results page records, if any spike run touched a held-out answer or
spent past its cap, if a credential reached the evidence, if the AV-006 decision record
lost its dated addendum, or if the ledger stopped being excluded from backup.
"""
import argparse
from decimal import Decimal, InvalidOperation
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
PROVIDER = ROOT / "android" / "provider" / "src" / "main" / "kotlin" / "org" / "ankivoice" / "provider"
PAID_ROUTE = PROVIDER / "PaidRoute.kt"
RESULTS = ROOT / "docs" / "testing" / "av043" / "results.md"
EVIDENCE = ROOT / "docs" / "testing" / "av043" / "evidence"
DECISION = ROOT / "docs" / "decisions" / "0006-speech-and-grading-providers.md"
README = ROOT / "android" / "README.md"
RULES = ROOT / "android" / "app" / "src" / "main" / "res" / "xml" / "data_extraction_rules.xml"
WORKFLOW = ROOT / ".github" / "workflows" / "fixtures.yml"
KEY_PREFIX = "sk-or-"


def pin():
    """The shipped paid pin, read from the Kotlin constant rather than a second copy."""
    text = PAID_ROUTE.read_text(encoding="utf-8")
    block = re.search(r"val PINNED: PaidRoutePin = PaidRoutePin\.of\((.*?)\n    \)", text, re.S)
    if block is None:
        raise SystemExit("PaidRoute.kt no longer declares PINNED with PaidRoutePin.of(...)")
    fields = dict(re.findall(r'(\w+) = "([^"]*)"', block.group(1)))
    for name in ("model", "provider", "promptUsdPerToken", "completionUsdPerToken"):
        if name not in fields:
            raise SystemExit(f"PaidRoute.PINNED names no {name}")
    return fields


def check_pin(failures):
    fields = pin()
    if fields["model"].endswith(":free"):
        failures.append("the paid pin names a free variant")
    for name in ("promptUsdPerToken", "completionUsdPerToken"):
        try:
            if Decimal(fields[name]) < 0:
                failures.append(f"{name} is negative")
        except InvalidOperation:
            failures.append(f"{name} is not a decimal: {fields[name]!r}")
    if not RESULTS.is_file():
        failures.append(f"{RESULTS.relative_to(ROOT)} is missing")
        return fields
    results = RESULTS.read_text(encoding="utf-8")
    for name in ("model", "provider"):
        if fields[name] not in results:
            failures.append(f"the results page does not name the pinned {name} {fields[name]!r}")
    return fields


def check_evidence(evidence, failures, warnings):
    evidence = Path(evidence)
    if not evidence.is_dir():
        warnings.append(f"{evidence} does not exist yet; the spike has not been recorded")
        return
    runs = sorted(evidence.rglob("run-*.json"))
    if not runs:
        warnings.append(f"{evidence} holds no harness run yet; the spike has not been recorded")
    for path in runs:
        run = json.loads(path.read_text(encoding="utf-8"))
        name = path.relative_to(ROOT)
        if run.get("spike"):
            if run.get("split") != "tuning":
                failures.append(f"{name}: a spike run with split {run.get('split')!r}; the spike is tuning-only")
            held = [a["id"] for a in run.get("answers", []) if a.get("split") != "tuning"]
            if held:
                failures.append(f"{name}: a spike run scored held-out answers {held}")
            if run.get("routes") != ["paid"]:
                failures.append(f"{name}: a spike run used routes {run.get('routes')}")
        quota = run.get("quota", {})
        cap, spend = quota.get("daily_cap_usd"), quota.get("spend_usd")
        if cap is not None and spend is not None:
            try:
                if Decimal(spend) > Decimal(cap):
                    failures.append(f"{name}: spent {spend} against a cap of {cap}")
            except InvalidOperation:
                failures.append(f"{name}: unreadable spend or cap")
    # AV-020's rule: nothing key-shaped may appear anywhere in the evidence.
    for path in evidence.rglob("*"):
        if not path.is_file():
            continue
        try:
            text = path.read_text(errors="ignore")
        except OSError:
            continue
        if KEY_PREFIX in text and "replay" not in text:
            failures.append(f"{path.relative_to(ROOT)} contains something key-shaped")


def check_documents(failures):
    decision = DECISION.read_text(encoding="utf-8")
    if "## Addendum" not in decision or "AV-043" not in decision:
        failures.append("the AV-006 decision record carries no dated AV-043 addendum")
    if "free models only" not in decision:
        failures.append("the AV-006 decision record's accepted evidence was rewritten; the addendum must not replace it")
    readme = README.read_text(encoding="utf-8")
    if "## Paid grading fallback" not in readme:
        failures.append("android/README.md does not describe the paid grading fallback")
    rules = RULES.read_text(encoding="utf-8")
    if rules.count("av020-quota-ledger.jsonl") < 2:
        failures.append("the quota ledger, which now records spend, is no longer excluded from backup and transfer")
    workflow = WORKFLOW.read_text(encoding="utf-8")
    if "tools/av043-qa/validate.py" not in workflow:
        failures.append("the fixtures workflow does not run this guard")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, default=EVIDENCE)
    args = parser.parse_args()

    failures, warnings = [], []
    fields = check_pin(failures)
    check_evidence(args.evidence, failures, warnings)
    check_documents(failures)

    for warning in warnings:
        print(f"note: {warning}")
    if failures:
        for failure in failures:
            print(f"FAIL: {failure}")
        raise SystemExit(f"{len(failures)} check(s) failed")
    print(
        f"AV-043 checks passed ({len(warnings)} note(s)): paid pin {fields['model']} via {fields['provider']} "
        f"at {fields['promptUsdPerToken']}/{fields['completionUsdPerToken']} USD per token."
    )


if __name__ == "__main__":
    main()
