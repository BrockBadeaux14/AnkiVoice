#!/usr/bin/env python3
"""AV-047's live check: automatic grading on the app's own study screen.

Five turns of the real study screen on the pinned AVD, against a disposable AV-002
collection. Two of them write a review and one of those writes it **without a
confirmation** — that is the whole point of the card, so a run that wrote nothing would
prove nothing about it. Back the collection up first.

  automatic-rule        option ON.  answer; touch nothing; let it save.    1 review, saved as `auto`
  automatic-cancelled   option ON.  answer; tap Keep it manual; Finish.    0 reviews
  automatic-abstain     option ON.  answer so the grader abstains;
                        name a rating; confirm.                            1 review, saved as `touch`
  automatic-unavailable option ON, daily limit 0. answer in other words;
                        name a rating; confirm.                            1 review (needs the key)
  automatic-off         option OFF. answer; confirm.                       1 review, saved as `touch`

**The toggle is yours to set.** Nothing here writes the `automatic_grading` preference: a
check that set the toggle itself would not have checked the toggle. The driver prints what
each turn needs, and judges the run from what the session actually published — so a turn
driven with the switch the wrong way fails rather than passing quietly.

Everything else — the emulator, the deck snapshots, the journal clearing, the per-turn
evidence file — is AV-026's driver, reused rather than forked, because AV-047's live layer
is the same harness on the same screen.

    .venv/bin/python tools/av047-qa/run.py build/av047/deck.json
    .venv/bin/python tools/av047-qa/run.py build/av047/deck.json --turns automatic-cancelled

One turn per boot: the emulator's coreaudio backend leaks a listener per microphone open
and exits on the second or third of a boot.
"""

import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("av026_qa", ROOT / "tools/av026-qa/run.py")
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)

TURNS = [
    "automatic-rule", "automatic-cancelled", "automatic-abstain",
    "automatic-unavailable", "automatic-off",
]

# Writes each turn hands the writer. None of these is undone afterwards.
WRITES = {
    "automatic-rule": 1, "automatic-cancelled": 0, "automatic-abstain": 1,
    "automatic-unavailable": 1, "automatic-off": 1,
}

# Whether the setup screen's Automatic grading switch must be on for this turn, and what
# the session must therefore report having run with.
OPTION_ON = {
    "automatic-rule": True, "automatic-cancelled": True, "automatic-abstain": True,
    "automatic-unavailable": True, "automatic-off": False,
}

# Whether a cancel window must have been open on the screen at some point in the turn.
WINDOW = {
    "automatic-rule": True, "automatic-cancelled": True, "automatic-abstain": False,
    "automatic-unavailable": False, "automatic-off": False,
}

# What must have authorized each write. `automatic-rule` is the only turn in this
# repository that may pass with a review the operator never confirmed.
SOURCE = {
    "automatic-rule": "auto", "automatic-cancelled": None, "automatic-abstain": "touch",
    "automatic-unavailable": "touch", "automatic-off": "touch",
}

NEEDS_KEY = {"automatic-unavailable"}

ROUTINE = {
    "automatic-rule":
        "Turn Automatic grading ON in setup. Start studying, Play prompt, Start answer, say the\n"
        "    reference answer, Done — then TOUCH NOTHING. The screen counts down and saves it.\n"
        "    Tap Finish once the review is saved.",
    "automatic-cancelled":
        "Turn Automatic grading ON in setup. Answer as above, then tap KEEP IT MANUAL while the\n"
        "    countdown is still running. Leave the rating unconfirmed and tap Finish.",
    "automatic-abstain":
        "Turn Automatic grading ON in setup. Answer with something only partly right, so the rules\n"
        "    abstain and no rating is proposed. Pick a rating yourself, tap Confirm, then Finish.",
    "automatic-unavailable":
        "Turn Automatic grading ON in setup and set Daily limit to 0. Answer in your own words so\n"
        "    the rules miss and the route is refused. Pick a rating yourself, tap Confirm, Finish.\n"
        "    Set the daily limit back afterwards.",
    "automatic-off":
        "Turn Automatic grading OFF in setup. Answer with the reference answer, tap Confirm, Finish.\n"
        "    Nothing may count down, and no control to stop one may appear.",
}


def automatic_verdict(name, result):
    """What only AV-047 checks: the option, the window, and who authorized the write.

    The harness already judges its own account and the driver already counts the revlog.
    This adds the three facts that make an automatic commit an automatic commit, read from
    the snapshots the session published rather than from what the turn was asked to do.
    """
    if not isinstance(result, dict) or "error" in result:
        return False, "no usable result"
    snapshots = result.get("snapshots") or []
    ran_automatic = any(s.get("automaticGrading") for s in snapshots)
    saw_window = any(s.get("autoCommitWindowMs") is not None for s in snapshots)
    if ran_automatic != OPTION_ON[name]:
        state = "on" if OPTION_ON[name] else "off"
        return False, f"the session ran with Automatic grading {'on' if ran_automatic else 'off'}, not {state}"
    if saw_window != WINDOW[name]:
        return False, f"a cancel window {'appeared' if saw_window else 'never appeared'}, which this turn forbids"
    journal = ((result.get("evidence") or {}).get("journal")) or []
    sources = {entry.get("confirmationSource") for entry in journal}
    if SOURCE[name] is None:
        if journal:
            return False, f"a turn that must write nothing left {len(journal)} journal entries"
    elif sources != {SOURCE[name]}:
        return False, f"the journal says {sorted(s or 'none' for s in sources)}, not {SOURCE[name]!r}"
    return True, "ok"


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("deck", type=Path, help='JSON [<deckId>, "<deck name>"]')
    parser.add_argument("--turns", default=",".join(TURNS),
                        help="comma-separated subset, in order (default: all five)")
    parser.add_argument("--no-boot", action="store_true",
                        help="use the running emulator; never reboot (one turn per boot is the rule)")
    arguments = parser.parse_args()

    deck_id, deck_name = json.loads(arguments.deck.read_text())
    if not str(deck_name).startswith("AV002"):
        raise SystemExit(f"Refusing a deck that is not disposable: {deck_name!r}")
    wanted = [name.strip() for name in arguments.turns.split(",") if name.strip()]
    unknown = [name for name in wanted if name not in TURNS]
    if unknown:
        raise SystemExit(f"Unknown turns {unknown}; choose from {TURNS}")

    # AV-026's driver, pointed at AV-047's turns, tokens and evidence directory.
    qa.CARD = "av047"
    qa.CONFIRMATION = "AV047_LIVE_AUTOMATIC"
    qa.TURNS = TURNS
    qa.WRITES = WRITES
    qa.NEEDS_KEY = NEEDS_KEY
    qa.OUT = ROOT / "docs/testing/av047/evidence"
    qa.BUILD = ROOT / "build/av047"

    attached = [line.split()[0] for line in
                subprocess.run([str(qa.av.ADB), "devices"], capture_output=True, check=True)
                .stdout.decode().splitlines()[1:] if line.strip().endswith("device")]
    if not arguments.no_boot:
        qa.av.SERIAL = qa.DEVICE
    elif len(attached) == 1:
        qa.av.SERIAL = attached[0]
    else:
        raise SystemExit(f"Exactly one attached device is required; found {attached}")

    qa.BUILD.mkdir(parents=True, exist_ok=True)
    records = []
    for index, name in enumerate(wanted):
        switch = "ON" if OPTION_ON[name] else "OFF"
        print(f"\n---- {name}: Automatic grading must be {switch} ----")
        print(f"    {ROUTINE[name]}", flush=True)
        log_path = qa.BUILD / f"emulator-boot-{index:02d}.log"
        record = qa.turn(name, deck_id, log_path, reboot=not arguments.no_boot)

        automatic_ok, why = automatic_verdict(name, record.get("result"))
        record["automaticVerdict"] = why
        record["automaticGradingExpected"] = OPTION_ON[name]
        record["cancelWindowExpected"] = WINDOW[name]
        record["confirmationSourceExpected"] = SOURCE[name]
        record["passed"] = bool(record["passed"]) and automatic_ok
        (qa.OUT / f"{name}.json").write_text(json.dumps(record, indent=2) + "\n")
        if not automatic_ok:
            print(f"  automatic grading check: {why}", flush=True)
        records.append(record)

    qa.summarize(deck_name)
    failed = [r["turn"] for r in records if not r["passed"]]
    print("\nAV-047 live check complete" if not failed else f"\nAV-047 live check: {failed} did not pass")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
