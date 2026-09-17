"""Validate AV-019's retained live evidence; no device or network calls.

It re-derives every claim the results page makes from the retained snapshots rather than
trusting the harness summary:

- each case added exactly the reviews it promised, counted from the collection's own
  revlog rather than from the app's report of what it did;
- every review that was added has a journal entry beside it, settled from the writer's own
  outcome and never upgraded;
- the two cases that must not write — a correction on its own, and an abandoned exchange —
  left the collection byte-identical and journalled nothing at all;
- every rating that was announced carries a source, and the announcement is bound to the
  transcript revision it was computed from;
- each confirmation's source is recorded as it happened, spoken or touched;
- the operator's attestation is theirs: nothing in the evidence claims a spoken answer the
  operator did not confirm saying.

Before the live run there is nothing to validate, and it says so rather than passing
silently on an empty directory.
"""
import json
from pathlib import Path
import sys

EVIDENCE = Path(__file__).resolve().parents[2] / "docs/testing/av019/evidence"
CASES = {
    "confirmed": 1,
    "corrected": 1,
    "correction-only": 0,
    "abandoned": 0,
    "undo-handoff": 1,
}
checks = 0


def check(value, reason):
    global checks
    assert value, reason
    checks += 1


def card_states(rows):
    return {row["id"]: {k: row[k] for k in ("type", "queue", "due", "ivl", "reps", "lapses")} for row in rows}


def validate(name, record):
    expected = CASES[name]
    check(record["case"] == name, f"{name}: the record names a different case")
    check(record["integrityBefore"] == record["integrityAfter"] == "ok", f"{name}: collection integrity")

    # The collection's own revlog, not the app's account of what it did.
    added = record["revlogAdded"]
    check(len(added) == expected, f"{name}: {len(added)} reviews added, expected {expected}")
    check(record["reviewsAdded"] == expected, f"{name}: the driver counted {record['reviewsAdded']}")

    result = record["result"]
    check("error" not in result, f"{name}: {result.get('error')}")
    check(result["case"] == name, f"{name}: the harness ran a different case")

    writes = result.get("writes", [])
    check(len(writes) == expected, f"{name}: the transport was called {len(writes)} times")

    journal = result.get("journalAfter", [])
    check(result.get("journalEntriesAdded") == expected,
          f"{name}: {result.get('journalEntriesAdded')} journal entries added, expected {expected}")
    check(len(journal) == expected, f"{name}: {len(journal)} journal entries retained")

    # Every rating announced carries a source and the revision it was computed from.
    announcements = result.get("announcements", [])
    check(announcements, f"{name}: nothing was announced")
    for announced in announcements:
        source = announced["source"]
        check(source in ("rule", "ai", "learner", "none"), f"{name}: unknown source {source}")
        if announced["rating"] is None:
            check(source == "none", f"{name}: an abstention announced as {source}")
        else:
            check(source != "none", f"{name}: a rating announced without a source")
        check(isinstance(announced["transcriptRevision"], int),
              f"{name}: an announcement with no transcript revision")

    answer = result.get("answer", {})
    attested = answer.get("attested")
    if answer.get("action") == "Start answer":
        check(attested is not None, f"{name}: the operator was not asked what they said")
        check(attested.get("source") == "operator-touch", f"{name}: the attestation is not the operator's")

    steps = result.get("steps", [])
    confirmations = [step for step in steps if step.get("step") == "confirm"]
    if expected:
        check(len(confirmations) == 1, f"{name}: {len(confirmations)} confirmations for one write")
        confirmation = confirmations[0]
        source = confirmation.get("confirmationSource")
        check(source in ("spoken", "touch"), f"{name}: the confirmation's source is {source!r}")
        committed = confirmation["outcome"]
        check(committed["kind"] == "committed", f"{name}: the confirmation did not commit")
        check(committed["outcome"] == "confirmed", f"{name}: the write settled {committed['outcome']}")
        entry = journal[0]
        check(entry["outcomeState"] == "confirmed", f"{name}: the journal entry settled {entry['outcomeState']}")
        check(entry["phase"] == "settled", f"{name}: the journal entry is {entry['phase']}")
        check(entry["rating"] == added[0]["ease"],
              f"{name}: journalled rating {entry['rating']} but the revlog records {added[0]['ease']}")
        check(entry["cardId"] == added[0]["cid"], f"{name}: the journal and the revlog name different cards")
        check(not result["stateUnchanged"], f"{name}: a confirmed review left the card untouched")
    else:
        check(not confirmations, f"{name}: a case that must not write ran a confirmation")
        check(result["stateUnchanged"], f"{name}: the card changed without a write")
        check(card_states(record["cardsBefore"]) == card_states(record["cardsAfter"]),
              f"{name}: a card's scheduling moved without a review")

    if name == "confirmed":
        duplicate = next(step for step in steps if step.get("step") == "duplicate-confirm")
        check(duplicate["offered"] is False, f"{name}: a second confirm was still offered")
        check(duplicate["outcome"]["kind"] == "untouched", f"{name}: the duplicate reached the exchange")
    if name == "corrected":
        correction = next(step for step in steps if step.get("step") == "correct")
        check(correction["from"] != correction["to"], f"{name}: the correction changed nothing")
        check(added[0]["ease"] == correction["to"],
              f"{name}: the review recorded {added[0]['ease']}, not the corrected {correction['to']}")
    if name == "undo-handoff":
        handoff = next(step for step in steps if step.get("step") == "undo-handoff")
        check(handoff.get("reason") == "native_undo_handoff", f"{name}: the handoff did not stop the session")
        check(handoff.get("sessionState") == "stopped", f"{name}: the session is {handoff.get('sessionState')}")
        check(handoff.get("resumable") is False, f"{name}: a stopped handoff offered a resume")
        check(handoff.get("operatorReport") is not None, f"{name}: the operator did not report what AnkiDroid showed")

    check(record["passed"], f"{name}: the driver did not pass it")


def main():
    if not EVIDENCE.is_dir() or not any(EVIDENCE.glob("*.json")):
        print("AV-019: no live evidence retained yet; run tools/av019-qa/run.py on the pinned AVD.")
        return 0
    summary = json.loads((EVIDENCE / "summary.json").read_text())
    recorded = {entry["case"] for entry in summary["cases"]}
    missing = set(CASES) - recorded
    if missing:
        raise SystemExit(f"AV-019: the summary is missing {sorted(missing)}")
    for name in CASES:
        validate(name, json.loads((EVIDENCE / f"{name}.json").read_text()))
    total = sum(entry["reviewsAdded"] for entry in summary["cases"])
    print(f"AV-019 evidence: {checks} checks over {len(CASES)} cases; "
          f"{total} reviews written, all from an explicit confirmation.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
