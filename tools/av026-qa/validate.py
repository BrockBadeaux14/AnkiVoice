"""Validate AV-026's retained live evidence; no device or network calls.

It re-derives every claim the results page makes from the retained snapshots rather than
trusting the harness or the driver:

- each turn added exactly the reviews it promised, counted from the collection's own
  revlog rather than from the app's report of what it did;
- every review that was added has a journal entry beside it, settled from the writer's own
  outcome at the same rating and card, and never upgraded;
- the turns that must not write -- a skip, a pause and resume, an interruption and reload --
  left the card's scheduling byte-identical and journalled nothing;
- each turn's evidence export carries what #29 needs: the grading path, the recognition
  outcome of every attempt, the retry and edit counts, the confirmation source where a
  review was written, the corrections, and the touch actions the operator took;
- each turn actually did what it is named for, so a turn the operator abandoned cannot pass
  as one that proved something.

It validates every turn the run has completed and names the ones still outstanding. A
partial run is reported as partial rather than passed: the exit status stays zero while
turns are outstanding, because an unfinished run is not a regression, but a turn that is
present and unsound fails the script. Before the live run it reports that there is nothing
to validate rather than passing silently.
"""
import json
from pathlib import Path
import sys

EVIDENCE = Path(__file__).resolve().parents[2] / "docs/testing/av026/evidence"

WRITES = {
    "rule-match": 1, "ai-labelled": 1, "abstain-self-grade": 1, "corrected-confirmed": 1,
    "transcript-edit": 1, "skip": 0, "pause-resume": 0, "interruption-reload": 0,
    "undo-handoff": 1, "route-refused-self-grade": 1,
}
TURNS = list(WRITES)
UNDO_USED = "AnkiDroid offered Undo and I used it"
PATHS = {"rule", "ai-free", "ai-paid", "abstain", "unavailable"}
checks = 0


def check(value, reason):
    global checks
    assert value, reason
    checks += 1


def expected_reviews(name, result):
    if name != "undo-handoff":
        return WRITES[name]
    return 0 if result.get("operatorReport") == UNDO_USED else 1


def card_states(rows):
    return {row["id"]: {k: row[k] for k in ("type", "queue", "due", "ivl", "reps", "lapses")} for row in rows}


def validate(name, record):
    result = record["result"]
    wrote = WRITES[name]
    survived = expected_reviews(name, result)
    check(record["turn"] == name, f"{name}: the record names a different turn")
    check(record["integrityBefore"] == record["integrityAfter"] == "ok", f"{name}: collection integrity")
    check("error" not in result, f"{name}: {result.get('error')}")
    check(not result.get("timedOut"), f"{name}: the turn timed out")
    check(result["turn"] == name, f"{name}: the harness ran a different turn")

    # The collection's own revlog, not the app's account of what it did.
    added = record["revlogAdded"]
    check(len(added) == survived, f"{name}: {len(added)} reviews survived, expected {survived}")
    check(record["reviewsAdded"] == survived, f"{name}: the driver counted {record['reviewsAdded']}")

    evidence = result.get("evidence")
    check(isinstance(evidence, dict), f"{name}: no evidence was exported")
    turns = evidence.get("turns", [])
    check(turns, f"{name}: the evidence has no turn")
    first = turns[0]
    for key in ("gradingPath", "recognition", "retries", "transcriptEdits", "confirmationSource",
                "ratingCorrections", "touchActions", "spokenCommands", "halts"):
        check(key in first, f"{name}: the turn evidence lacks {key}")
    check(isinstance(first["retries"], int) and isinstance(first["transcriptEdits"], int),
          f"{name}: retries and edits must be counts")
    check(first["touchActions"], f"{name}: no touch action was recorded; the operator drove nothing")
    if first.get("gradingPath") is not None:
        check(first["gradingPath"] in PATHS, f"{name}: unknown grading path {first['gradingPath']}")
    for recognition in first["recognition"]:
        check(recognition["status"] in ("final", "user-corrected", "cancelled", "timed-out", "failed"),
              f"{name}: unknown recognition status {recognition['status']}")
        check(recognition["confidence"] in ("sufficient", "low", "absent"),
              f"{name}: unknown confidence {recognition['confidence']}")

    # What the app did is separate from what survived it: the outcomes and the journal are
    # the evidence that a write happened at all.
    outcomes = evidence.get("outcomes", [])
    confirmed = [o for o in outcomes if o.get("state") == "confirmed"]
    journal = evidence.get("journal", [])
    check(len(confirmed) == wrote, f"{name}: {len(confirmed)} confirmed writes, expected {wrote}")
    check(len(journal) == wrote, f"{name}: {len(journal)} journal entries, expected {wrote}")
    check(len(result.get("journalAfter", [])) - len(result.get("journalBefore", [])) == wrote,
          f"{name}: the journal file grew by something other than {wrote}")

    if wrote:
        check(first["confirmationSource"] in ("spoken", "touch"),
              f"{name}: the confirmation's source is {first['confirmationSource']!r}")
        check(first["outcome"] == "confirmed", f"{name}: the turn's outcome is {first['outcome']}")
        entry = journal[0]
        check(entry["outcomeState"] == "confirmed", f"{name}: the journal entry settled {entry['outcomeState']}")
        check(entry["phase"] == "settled", f"{name}: the journal entry is {entry['phase']}")
        check(entry["rating"] == first["rating"],
              f"{name}: journalled rating {entry['rating']} but the turn confirmed {first['rating']}")
        check(entry["cardId"] == first["cardId"], f"{name}: the journal and the turn name different cards")
        if survived:
            check(entry["rating"] == added[0]["ease"],
                  f"{name}: journalled rating {entry['rating']} but the revlog records {added[0]['ease']}")
            check(entry["cardId"] == added[0]["cid"], f"{name}: the journal and the revlog name different cards")
            check(not result["stateUnchanged"], f"{name}: a confirmed review left the card untouched")
        else:
            check(result["stateUnchanged"],
                  f"{name}: Undo was reported used but the card did not return to its prior state")
    else:
        check(first["confirmationSource"] is None, f"{name}: a no-write turn recorded a confirmation")
        check(result["stateUnchanged"], f"{name}: the card changed without a write")
        check(card_states(record["cardsBefore"]) == card_states(record["cardsAfter"]),
              f"{name}: a card's scheduling moved without a review")

    touches = first["touchActions"]
    halts = first["halts"]
    if name == "rule-match":
        check(first["gradingPath"] == "rule", f"{name}: the grading path was {first['gradingPath']}")
    if name == "ai-labelled":
        check(first["gradingPath"] in ("ai-free", "ai-paid"), f"{name}: the grading path was {first['gradingPath']}")
    if name in ("abstain-self-grade", "route-refused-self-grade"):
        check(first["gradingPath"] == ("abstain" if name == "abstain-self-grade" else "unavailable"),
              f"{name}: the grading path was {first['gradingPath']}")
        check(first["selfGrade"] is not None, f"{name}: no self-grade was named")
        check(added[0]["ease"] == first["rating"], f"{name}: the revlog records a rating other than the confirmed one")
    if name == "corrected-confirmed":
        corrections = first["ratingCorrections"]
        check(corrections, f"{name}: no correction was recorded")
        check(corrections[-1]["to"] == first["rating"] == added[0]["ease"],
              f"{name}: the review recorded {added[0]['ease']}, not the corrected {corrections[-1]['to']}")
        check(all(c["from"] != c["to"] for c in corrections), f"{name}: a correction changed nothing")
    if name == "transcript-edit":
        check(first["transcriptEdits"] >= 1, f"{name}: no transcript edit was recorded")
        check(any(g["revision"] == first["transcriptRevision"] for g in first["gradings"]),
              f"{name}: the edited version was never graded")
        check("edit transcript" in touches, f"{name}: the edit was not a touch action")
    if name == "skip":
        check("skip" in touches and "skip_requested" in halts, f"{name}: the skip never happened")
    if name == "pause-resume":
        check("pause" in touches and "resume" in touches, f"{name}: the pause and resume never happened")
        check("learner_paused" in halts, f"{name}: the pause was not recorded as a halt")
    if name == "interruption-reload":
        interrupted = result.get("interruptedEvidence")
        check(isinstance(interrupted, dict), f"{name}: no interruption was recorded")
        check(interrupted["closed"] == "interrupted", f"{name}: the first session closed as {interrupted['closed']}")
        check(any("app_switch" in t.get("halts", []) for t in interrupted.get("turns", [])),
              f"{name}: no app_switch halt was recorded")
        check(not interrupted.get("outcomes"), f"{name}: the interrupted session wrote")
        check(interrupted["sessionId"] != evidence["sessionId"], f"{name}: the reload did not open a fresh session")
        check("reload" in evidence.get("sessionActions", []) or "start" in evidence.get("sessionActions", []),
              f"{name}: the reload was not recorded")
    if name == "undo-handoff":
        check(evidence["closed"] == "undo-handoff", f"{name}: the session closed as {evidence['closed']}")
        check(result.get("operatorReport") is not None, f"{name}: the operator did not report what AnkiDroid showed")

    check(record["passed"], f"{name}: the driver did not pass it")


def main():
    if not EVIDENCE.is_dir() or not (EVIDENCE / "summary.json").is_file():
        print("AV-026: no live evidence retained yet; run tools/av026-qa/run.py on the pinned AVD.")
        return 0
    summary = json.loads((EVIDENCE / "summary.json").read_text())
    recorded = [entry["turn"] for entry in summary["turns"]]
    unknown = [name for name in recorded if name not in TURNS]
    if unknown:
        raise SystemExit(f"AV-026: the summary names turns that do not exist: {unknown}")

    for name in recorded:
        path = EVIDENCE / f"{name}.json"
        if not path.is_file():
            raise SystemExit(f"AV-026: {name} is in the summary but its evidence is missing")
        validate(name, json.loads(path.read_text()))

    outstanding = [name for name in TURNS if name not in recorded]
    total = sum(entry["reviewsAdded"] for entry in summary["turns"])
    written = sum(WRITES[entry["turn"]] for entry in summary["turns"])
    spoken = sum(1 for entry in summary["turns"] if entry.get("confirmationSource") == "spoken")
    done = (f"{checks} checks over {len(recorded)} of {len(TURNS)} turns; "
            f"{written} reviews written, all from an explicit confirmation ({spoken} spoken); "
            f"{total} surviving in the collection.")
    if outstanding:
        print(f"AV-026 evidence (incomplete): {done} Still to run: {', '.join(outstanding)}.")
    else:
        print(f"AV-026 evidence: {done}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
