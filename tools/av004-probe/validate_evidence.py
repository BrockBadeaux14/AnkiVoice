"""Check the captured AV004 experiment, without connecting to an Android device."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SCHEDULING = ("id", "nid", "did", "ord", "type", "queue", "due", "ivl", "factor", "reps", "lapses", "left")


def validate(folder):
    checks = []

    def load(name):
        return json.loads((folder / f"{name}.json").read_text())

    def check(value, name):
        if not value:
            raise AssertionError(name)
        checks.append(name)

    def added(before, after):
        old = {r["id"] for r in before["revlog"]}
        check(all(r in after["revlog"] for r in before["revlog"]), "existing history preserved")
        return [r for r in after["revlog"] if r["id"] not in old]

    def unchanged(before, after, name):
        check(before["revlog"] == after["revlog"], name + ": no review added")
        check([[c[k] for k in SCHEDULING] for c in before["cards"]] ==
              [[c[k] for k in SCHEDULING] for c in after["cards"]], name + ": scheduling unchanged")

    models = load("final-probe-snapshot")["models"]
    voiceqa = next(m for m in models if m["name"] == "VoiceQA")
    field_names = voiceqa["field_names"].split("\x1f")
    cases = [
        ("baseline", "baseline-api", ["learning", "relearning", "mature", "new"],
         "baseline-api-before", "baseline-api-after-new", "native-baseline-after"),
        ("limits", "limits-clean", ["review-1", "new-1", "review-2"],
         "limits-clean-before", "limits-clean-after", "native-limits-after"),
    ]
    for profile, prefix, order, initial, final, native in cases:
        manifest = load(profile + "-manifest")
        fixtures = {c["fixture_id"]: c for c in manifest["cards"]}
        before, after = load(initial), load(final)
        rows = added(before, after)
        check([r["cid"] for r in rows] == [fixtures[f]["card_id"] for f in order], profile + ": exact API order/count")
        check(all(r["ease"] == 4 and r["time"] == 12345 for r in rows), profile + ": rating/time persisted")
        for fixture in order:
            attempt = load(prefix + "-" + fixture)
            expected = fixtures[fixture]
            check(attempt.get("update_count") == 1 and "error" not in attempt, fixture + ": successful response")
            offered = attempt["schedule_before"]
            check(len(offered) == 1 and offered[0]["note_id"] == expected["note_id"] and offered[0]["ord"] == 0,
                  fixture + ": offered identity matches submitted card")
            check(offered[0]["button_count"] == 4, fixture + ": four ratings")
            old = {c["_id"]: c for c in attempt["cards_before"]}
            new = {c["_id"]: c for c in attempt["cards_after"]}
            cid = expected["card_id"]
            check(new[cid]["reps"] == old[cid]["reps"] + 1 and new[cid]["last_review_time_secs"] is not None,
                  fixture + ": observable post-write verification")
            check(all(old[i] == new[i] for i in old if i != cid), fixture + ": only intended card changed")
            # Visible native rating intervals must match the API's corresponding buttons.
            native_name = "native-" + profile + "-" + fixture + "-answer"
            texts = [n.get("text") for n in ET.parse(folder / (native_name + ".xml")).iter("node")]
            check(all(t in texts for t in json.loads(offered[0]["next_review_times"])), fixture + ": native intervals match")
            for field, value in expected["fields"].items():
                note = next(n for n in attempt["notes"] if n["_id"] == expected["note_id"])
                check(value == note["flds"].split("\x1f")[field_names.index(field)], fixture + ": field " + field)
            check(expected["fields"]["Prompt"] in old[cid]["question"] and
                  expected["fields"]["ReferenceAnswer"] not in old[cid]["question"], fixture + ": prompt-only front")
            check(expected["fields"]["ReferenceAnswer"] in old[cid]["answer"], fixture + ": rendered reference answer")
        check(load(prefix + "-" + order[-1])["schedule_after"] == [], profile + ": API exhausted")
        native_rows = added(before, load(native))
        check([[r[k] for k in ("cid", "ease", "ivl", "lastIvl", "factor", "type")] for r in rows] ==
              [[r[k] for k in ("cid", "ease", "ivl", "lastIvl", "factor", "type")] for r in native_rows],
              profile + ": native review order and scheduling match")
        changed = {fixtures[f]["card_id"] for f in order}
        check([c for c in before["cards"] if c["id"] not in changed] ==
              [c for c in after["cards"] if c["id"] not in changed], profile + ": excluded/withheld cards unchanged")
    check(load("limits-clean-reopened")["schedule_before"] == [], "limits remain exhausted after process restart")
    baseline = load("failures-before")
    for name in ("permission-denied-db-02", "invalid-ease-after", "permission-revoked-after", "api-disabled-after", "package-unavailable-after"):
        unchanged(baseline, load(name), name)
    for name in ("permission-denied", "permission-revoked"):
        check(load(name)["error_class"] == "java.lang.SecurityException", name + ": permission exception")
    for name in ("api-disabled", "package-unavailable"):
        check(load(name)["error"] == "null cursor: decks", name + ": provider unavailable")
    for number in (0, 5):
        check(load(f"invalid-ease-{number}")["update_count"] == 0, f"invalid ease {number} rejected")
    stale = load("limits-api-new-1")
    check(stale["update_count"] == 1 and stale["cards_before"] == stale["cards_after"], "stale raw submission falsely returns one")
    unchanged(load("limits-api-after-review-1"), load("limits-api-after-new-1"), "stale raw submission")
    cap = load("elapsed-cap-after")
    cap_rows = added(baseline, cap)
    check(len(cap_rows) == 1 and cap_rows[0]["time"] == 60000, "98,765 ms input capped at deck's 60,000 ms maximum")
    unchanged(cap, load("native-undo-after"), "native undo restores preceding scheduling/history")
    check(load("before-native-undo")["schedule_before"] == load("after-native-undo")["schedule_before"], "native undo restores offered card")
    check(field_names == ["Prompt", "ReferenceAnswer", "RequiredConcepts", "AcceptedAnswers", "Language", "Extra"],
          "final probe exposes ordered VoiceQA model fields")
    unchanged(baseline, load("guarded-stale-after"), "ordinary path stale guard")
    check(load("guarded-stale")["error"] == "Selected card is stale; no write attempted"
          and "update_count" not in load("guarded-stale"), "ordinary path refuses stale write")
    for path in folder.glob("*.json"):
        data = json.loads(path.read_text())
        if "integrity" in data:
            check(data["integrity"] == "ok", path.name + ": SQLite integrity")
    return checks


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, default=ROOT / "docs/testing/av004/evidence")
    args = parser.parse_args()
    checks = validate(args.evidence)
    print(f"PASS: {len(checks)} evidence assertions (captured run; not a new emulator execution)")
