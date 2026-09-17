"""Validate AV-019's retained live evidence; no device or network calls.

It re-derives every claim the results page makes from the retained snapshots rather than
trusting the harness summary:

- each case actually performed the steps it is named for, so a run the harness skipped
  cannot pass as one that proved something;
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

It validates every case the run has completed and names the ones still outstanding. A
partial run is reported as partial rather than passed: the exit status stays zero while
cases are outstanding, because an unfinished run is not a regression, but a case that is
present and unsound fails the script. Attempts that proved nothing live in
`evidence/inconclusive/` and are deliberately not read here; see the README beside them.
"""
import json
from pathlib import Path
import sys

EVIDENCE = Path(__file__).resolve().parents[2] / "docs/testing/av019/evidence"
# Whether the case hands the writer an intent at all. This is the property the exchange
# owns, and it never depends on what the operator did afterwards.
WRITES = {
    "confirmed": 1,
    "corrected": 1,
    "correction-only": 0,
    "abandoned": 0,
    "undo-handoff": 1,
}
CASES = WRITES

# The operator's report on the handoff screen that means the review was taken back.
UNDO_USED = "AnkiDroid offered Undo and I used it"


def expected_reviews(name, result):
    """How many reviews should have survived in the revlog.

    `undo-handoff` is the only conditional one, and only on the operator's own report:
    AnkiVoice writes one review and stops, and AnkiDroid's native Undo — if it was offered
    and used — takes it back. A net delta of zero there is the handoff working, not a
    missing write; the write itself is checked through the transport and the journal.
    """
    if name != "undo-handoff":
        return WRITES[name]
    steps = result.get("steps", [])
    handoff = next((s for s in steps if s.get("step") == "undo-handoff"), {})
    return 0 if handoff.get("operatorReport") == UNDO_USED else 1

# The steps a case has to have actually reached. Without this a case the harness skipped —
# because the rules abstained and nothing was pending — passes the no-write checks while
# demonstrating nothing at all.
REQUIRED_STEPS = {
    "confirmed": ("confirm", "duplicate-confirm"),
    "corrected": ("correct", "confirm"),
    "correction-only": ("correct", "finish"),
    "abandoned": ("pause", "finish"),
    "undo-handoff": ("confirm", "undo-handoff"),
}
checks = 0


def check(value, reason):
    global checks
    assert value, reason
    checks += 1


def card_states(rows):
    return {row["id"]: {k: row[k] for k in ("type", "queue", "due", "ivl", "reps", "lapses")} for row in rows}


def validate(name, record):
    result = record["result"]
    wrote = WRITES[name]
    survived = expected_reviews(name, result)
    check(record["case"] == name, f"{name}: the record names a different case")
    check(record["integrityBefore"] == record["integrityAfter"] == "ok", f"{name}: collection integrity")

    # The collection's own revlog, not the app's account of what it did.
    added = record["revlogAdded"]
    check(len(added) == survived, f"{name}: {len(added)} reviews survived, expected {survived}")
    check(record["reviewsAdded"] == survived, f"{name}: the driver counted {record['reviewsAdded']}")

    check("error" not in result, f"{name}: {result.get('error')}")
    check(result["case"] == name, f"{name}: the harness ran a different case")

    # What the exchange did is separate from what survived it: the transport call and the
    # journal entry are the evidence that a write happened at all.
    writes = result.get("writes", [])
    check(len(writes) == wrote, f"{name}: the transport was called {len(writes)} times, expected {wrote}")

    journal = result.get("journalAfter", [])
    check(result.get("journalEntriesAdded") == wrote,
          f"{name}: {result.get('journalEntriesAdded')} journal entries added, expected {wrote}")
    check(len(journal) == wrote, f"{name}: {len(journal)} journal entries retained")

    # Every rating announced carries a source and the revision it was computed from.
    announcements = result.get("announcements", [])
    check(announcements, f"{name}: nothing was announced")
    # A case has to have had a pending rating to act on. Without this, a turn whose answer
    # never settled reaches Pause and Finish as no-ops and passes the no-write checks while
    # never opening an exchange at all.
    check(any(a["rating"] is not None for a in announcements),
          f"{name}: no rating was ever pending, so there was no exchange to act on")
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
    taken = [step.get("step") for step in steps if step.get("step")]
    # A skipped case writes nothing and would otherwise sail through the no-write checks.
    check(not any("skipped" in step for step in steps), f"{name}: the harness skipped a step: {steps}")
    check(taken, f"{name}: the case ran no steps at all")
    for required in REQUIRED_STEPS[name]:
        check(required in taken, f"{name}: never reached the {required} step; ran {taken}")

    confirmations = [step for step in steps if step.get("step") == "confirm"]
    if wrote:
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
        check(entry["rating"] == confirmation["rating"],
              f"{name}: journalled rating {entry['rating']} but {confirmation['rating']} was confirmed")
        if survived:
            check(entry["rating"] == added[0]["ease"],
                  f"{name}: journalled rating {entry['rating']} but the revlog records {added[0]['ease']}")
            check(entry["cardId"] == added[0]["cid"],
                  f"{name}: the journal and the revlog name different cards")
            check(not result["stateUnchanged"], f"{name}: a confirmed review left the card untouched")
        else:
            # The review was written and then taken back in AnkiDroid. The card is back where
            # it started, which is the handoff working rather than a write that never happened.
            check(result["stateUnchanged"],
                  f"{name}: Undo was reported used but the card did not return to its prior state")
    else:
        check(not confirmations, f"{name}: a case that must not write ran a confirmation")
        check(result["stateUnchanged"], f"{name}: the card changed without a write")
        check(card_states(record["cardsBefore"]) == card_states(record["cardsAfter"]),
              f"{name}: a card's scheduling moved without a review")

    if name == "confirmed":
        duplicate = next(step for step in steps if step.get("step") == "duplicate-confirm")
        check(duplicate["offered"] is False, f"{name}: a second confirm was still offered")
        check(duplicate["outcome"]["kind"] == "untouched", f"{name}: the duplicate reached the exchange")
    if name in ("corrected", "correction-only"):
        correction = next(step for step in steps if step.get("step") == "correct")
        check(correction.get("from") != correction.get("to"), f"{name}: the correction changed nothing")
        check(correction["outcome"]["kind"] == "announced", f"{name}: the correction was not re-announced")
        check(correction["sessionState"] == "proposing",
              f"{name}: the correction left the session {correction['sessionState']}")
    if name == "corrected":
        correction = next(step for step in steps if step.get("step") == "correct")
        check(added[0]["ease"] == correction["to"],
              f"{name}: the review recorded {added[0]['ease']}, not the corrected {correction['to']}")
        check(added[0]["ease"] != correction["from"],
              f"{name}: the replaced rating {correction['from']} is what reached the collection")
    if name == "abandoned":
        pause = next(step for step in steps if step.get("step") == "pause")
        # A pause that was refused because the turn had already halted abandons nothing.
        check("not available" not in pause["outcome"]["notice"],
              f"{name}: the pause was a no-op: {pause['outcome']['notice']}")
        check(pause["sessionState"] == "paused", f"{name}: the pause left the session "
                                                 f"{pause['sessionState']}")
    if name == "undo-handoff":
        handoff = next(step for step in steps if step.get("step") == "undo-handoff")
        check(handoff.get("reason") == "native_undo_handoff", f"{name}: the handoff did not stop the session")
        check(handoff.get("sessionState") == "stopped", f"{name}: the session is {handoff.get('sessionState')}")
        check(handoff.get("resumable") is False, f"{name}: a stopped handoff offered a resume")
        check(handoff.get("operatorReport") is not None, f"{name}: the operator did not report what AnkiDroid showed")

    check(record["passed"], f"{name}: the driver did not pass it")


def main():
    if not EVIDENCE.is_dir() or not (EVIDENCE / "summary.json").is_file():
        print("AV-019: no live evidence retained yet; run tools/av019-qa/run.py on the pinned AVD.")
        return 0
    summary = json.loads((EVIDENCE / "summary.json").read_text())
    recorded = [entry["case"] for entry in summary["cases"]]
    unknown = [name for name in recorded if name not in CASES]
    if unknown:
        raise SystemExit(f"AV-019: the summary names cases that do not exist: {unknown}")

    for name in recorded:
        path = EVIDENCE / f"{name}.json"
        if not path.is_file():
            raise SystemExit(f"AV-019: {name} is in the summary but its evidence is missing")
        validate(name, json.loads(path.read_text()))

    outstanding = [name for name in CASES if name not in recorded]
    total = sum(entry["reviewsAdded"] for entry in summary["cases"])
    written = sum(WRITES[entry["case"]] for entry in summary["cases"])
    done = f"{checks} checks over {len(recorded)} of {len(CASES)} cases; " \
           f"{written} reviews written, all from an explicit confirmation; " \
           f"{total} surviving in the collection."
    if outstanding:
        print(f"AV-019 evidence (incomplete): {done} Still to run: {', '.join(outstanding)}.")
    else:
        print(f"AV-019 evidence: {done}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
