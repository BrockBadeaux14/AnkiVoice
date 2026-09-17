"""AV-026 (#27) live check: ten turns on the real study screen, against a real collection.

    python tools/av026-qa/run.py build/av026/deck.json
    python tools/av026-qa/run.py build/av026/deck.json --turns rule-match,skip
    python tools/av026-qa/run.py build/av026/deck.json --no-boot --turns pause-resume

One turn per invocation of `StudyInstrumentation`, and by default one cold boot per turn,
because the emulator's coreaudio backend leaks a listener per microphone open and exits on
the second or third of a boot -- and every turn but two needs at least one spoken answer.
The driver cold-boots the pinned AVD, turns the host microphone on, clears the journal,
selects the disposable deck in the app, snapshots the collection either side, launches the
harness -- which launches the **real app** and watches the shipped controller -- and
retains what it recorded.

  rule-match                answer with the reference answer; confirm.        1 review
  ai-labelled               answer in other words; confirm the AI suggestion. 1 review (needs the key)
  abstain-self-grade        answer so the grader abstains; name a rating;
                            confirm.                                          1 review
  corrected-confirmed       answer; change the rating; confirm the new one.   1 review
  transcript-edit           answer; edit the transcript; confirm.             1 review
  skip                      answer; skip; finish.                             0 reviews
  pause-resume              pause; resume; finish.                            0 reviews
  interruption-reload       press Home mid-turn; return; reload; finish.      0 reviews
  undo-handoff              answer; confirm; use AnkiDroid's own Undo.        1 write, then Undo
  route-refused-self-grade  with the daily limit at 0, answer in other words;
                            name a rating; confirm.                           1 review (needs the key)

**Seven of the ten write a real review**, deliberately: the study screen is the learner's
one path to the writer, and a check that wrote nothing would prove nothing about it. Back
the collection up first; the driver refuses any deck whose name does not start with AV002
and the harness refuses it again on the device.

The operator is a person at the emulator, following docs/testing/av026/runbook.md on the
app's own screen. The harness taps nothing and speaks nothing; it watches, and it exports
the controller's evidence when the session closes. The `undo-handoff` turn ends with a
question here on the terminal, because only the operator saw what AnkiDroid offered.
"""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("av004", ROOT / "tools/av004-probe/run.py")
av = importlib.util.module_from_spec(spec)
spec.loader.exec_module(av)

APP = "org.ankivoice"
TEST = "org.ankivoice.test"
ENTRY = f"{TEST}/org.ankivoice.app.StudyInstrumentation"
JOURNAL_ENTRY = f"{TEST}/org.ankivoice.app.JournalInstrumentation"
CONFIRMATION = "AV026_LIVE_STUDY"
AVD = "AnkiVoice_AV005"
DEVICE = "emulator-5588"
PORT = 5588
EMULATOR = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
                or Path.home() / "Library" / "Android" / "sdk") / "emulator" / "emulator"
# The AV-042 launch flags. Without `-allow-host-audio` the guest microphone is zeroed.
FLAGS = ["-allow-host-audio", "-no-snapshot", "-no-boot-anim"]

OUT = ROOT / "docs/testing/av026/evidence"
BUILD = ROOT / "build/av026"

TURNS = [
    "rule-match", "ai-labelled", "abstain-self-grade", "corrected-confirmed", "transcript-edit",
    "skip", "pause-resume", "interruption-reload", "undo-handoff", "route-refused-self-grade",
]
# Writes each turn hands the writer. `undo-handoff` writes one and then hands the learner to
# AnkiDroid's Undo, so how many reviews *survive* depends on the operator's report.
WRITES = {
    "rule-match": 1, "ai-labelled": 1, "abstain-self-grade": 1, "corrected-confirmed": 1,
    "transcript-edit": 1, "skip": 0, "pause-resume": 0, "interruption-reload": 0,
    "undo-handoff": 1, "route-refused-self-grade": 1,
}
NEEDS_KEY = {"ai-labelled", "route-refused-self-grade"}

# The operator's report on the terminal that means the review was taken back.
UNDO_USED = "AnkiDroid offered Undo and I used it"
UNDO_REPORTS = [UNDO_USED, "AnkiDroid did not offer Undo", "I did not check"]


def expected_reviews(name, report):
    """How many reviews this turn should leave in the revlog, given what actually happened."""
    if name != "undo-handoff":
        return WRITES[name]
    return 0 if report == UNDO_USED else 1


def verdict(name, result, added):
    """Judge the turn from the collection and the journal, not from the harness alone.

    The harness judges the app's own account -- the evidence export. The driver adds what
    the app cannot see: the revlog either side of the turn.
    """
    if not isinstance(result, dict) or "error" in result or result.get("timedOut"):
        return False
    if not result.get("passed"):
        return False
    evidence = result.get("evidence") or {}
    confirmed = [o for o in evidence.get("outcomes", []) if o.get("state") == "confirmed"]
    if len(confirmed) != WRITES[name]:
        return False
    if len([j for j in evidence.get("journal", []) if j.get("outcomeState") == "confirmed"]) != WRITES[name]:
        return False
    if added != expected_reviews(name, result.get("operatorReport")):
        return False
    if WRITES[name] == 0 and not result.get("stateUnchanged"):
        return False
    return True


def utc():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def adb(*arguments, check=True):
    return av.adb(*arguments, check=check)


# ------------------------------------------------------------------ the emulator

def running():
    listed = subprocess.run([str(av.ADB), "devices"], capture_output=True, check=True).stdout.decode()
    return any(line.startswith(DEVICE + "\t") and line.strip().endswith("device")
               for line in listed.splitlines())


def kill():
    if not running():
        return
    # `emu kill` returns before the guest flushes its last writes, and a fresh install, a
    # pushed file and the engine's voice data were all lost that way.
    adb("shell", "sync", check=False)
    adb("emu", "kill", check=False)
    for _ in range(60):
        if not running():
            break
        time.sleep(1)
    time.sleep(3)


def boot(log_path):
    """Cold-boot the pinned AVD, logging the emulator's own output."""
    kill()
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with open(log_path, "ab") as log:
        subprocess.Popen([str(EMULATOR), "-avd", AVD, "-port", str(PORT), *FLAGS],
                         stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                         start_new_session=True)
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        if running() and adb("shell", "getprop", "sys.boot_completed", check=False).decode().strip() == "1":
            break
        time.sleep(2)
    else:
        raise SystemExit(f"{AVD} did not boot within five minutes; see {log_path}")
    adb("shell", "pm", "grant", APP, "android.permission.RECORD_AUDIO", check=False)
    adb("shell", "cmd", "media_session", "volume", "--stream", "3", "--set", "9", check=False)
    # The virtual microphone must be told to use the host input as well as allowed to.
    adb("emu", "avd", "hostmicon", check=False)
    # The pinned engine takes a while after boot.
    time.sleep(30)


# ------------------------------------------------------------------ evidence

def database(label):
    """Read-only collection snapshot. Full DBs stay under ignored build/."""
    previous = av.OUT
    av.OUT = BUILD / "databases"
    av.OUT.mkdir(exist_ok=True, parents=True)
    shutil.rmtree(av.OUT / label, ignore_errors=True)
    try:
        return av.database(label)
    finally:
        av.OUT = previous


def revlog(snapshot):
    return [dict(row) for row in snapshot["revlog"]]


def clear_journal(deck):
    adb("shell", "am", "instrument", "-w",
        "-e", "confirm", "AV018_SYNTHETIC_ONLY", "-e", "mode", "clear",
        "-e", "deck", str(deck), JOURNAL_ENTRY, check=False)


def prepare(deck):
    """Select the disposable deck in the app, and stop the app so the next launch is clean."""
    adb("shell", "am", "force-stop", APP, check=False)
    adb("shell", "am", "force-stop", TEST, check=False)
    adb("shell", "am", "instrument", "-w",
        "-e", "confirm", CONFIRMATION, "-e", "mode", "prepare",
        "-e", "deck", str(deck), ENTRY, check=False)
    adb("shell", "am", "force-stop", APP, check=False)


def attempt(turn, deck):
    """Launch the harness for one turn and collect what it recorded."""
    command = [str(av.ADB), "-s", av.SERIAL, "shell", "am", "instrument", "-w", "-r",
               "-e", "confirm", CONFIRMATION,
               "-e", "turn", turn,
               "-e", "deck", str(deck),
               ENTRY]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, bufsize=1)
    phases, raw = [], []
    for line in process.stdout:
        raw.append(line)
        match = re.match(r"INSTRUMENTATION_STATUS: livePhase=(.*)", line)
        if match:
            phase = match.group(1).strip()
            phases.append({"utc": utc(), "phase": phase})
            print(f"    on screen: {phase}", flush=True)
    process.wait(timeout=1500)
    text = "".join(raw)
    match = re.search(r"INSTRUMENTATION_RESULT: av026=(\{.*\})", text)
    try:
        result = json.loads(match.group(1)) if match else {"error": "no instrumentation result"}
    except json.JSONDecodeError as error:
        result = {"error": f"unparseable instrumentation result: {error}"}
    if not match:
        # The bundle is also written to the app's files; a truncated stdout is not lost evidence.
        stored = adb("exec-out", "run-as", APP, "cat", "files/av026-result.json", check=False).decode(errors="replace")
        try:
            result = json.loads(stored)
        except json.JSONDecodeError:
            result["raw"] = text[-3000:]
    return result, phases


def ask_operator(turn):
    """Only the operator saw what AnkiDroid offered after the handoff."""
    if turn != "undo-handoff":
        return None
    print("\n  What did AnkiDroid show after the handoff?")
    for index, option in enumerate(UNDO_REPORTS, 1):
        print(f"    {index}. {option}")
    while True:
        answer = input("  Enter 1, 2 or 3: ").strip()
        if answer in ("1", "2", "3"):
            return UNDO_REPORTS[int(answer) - 1]


def turn(name, deck, log_path, reboot):
    print(f"\n== {name} ==", flush=True)
    if name in NEEDS_KEY:
        print("  needs the owner's OpenRouter key entered in the app"
              + (" and the daily limit set to 0" if name == "route-refused-self-grade" else ""), flush=True)
    if reboot:
        print(f"  cold-booting {AVD}", flush=True)
        boot(log_path)
    clear_journal(deck)
    prepare(deck)
    before = database(f"{name}-before")
    print("  the operator drives the study screen from here; tap Start studying", flush=True)
    result, phases = attempt(name, deck)
    after = database(f"{name}-after")
    if isinstance(result, dict):
        report = ask_operator(name)
        if report is not None:
            result["operatorReport"] = report

    before_log, after_log = revlog(before), revlog(after)
    added = len(after_log) - len(before_log)
    expected = expected_reviews(name, result.get("operatorReport") if isinstance(result, dict) else None)
    record = {
        "turn": name,
        "utc": utc(),
        "deck": deck,
        "expectedReviewsAdded": expected,
        "reviewsAdded": added,
        "integrityBefore": before["integrity"],
        "integrityAfter": after["integrity"],
        "revlogAdded": after_log[len(before_log):],
        "cardsBefore": [{k: row[k] for k in CARD_FIELDS} for row in before["cards"]],
        "cardsAfter": [{k: row[k] for k in CARD_FIELDS} for row in after["cards"]],
        "phases": phases,
        "spokenBy": "the operator, on the app's own study screen; nothing was synthesized",
        "result": result,
    }
    record["harnessPassed"] = bool(result.get("passed")) if isinstance(result, dict) else False
    record["passed"] = verdict(name, result, added)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.json").write_text(json.dumps(record, indent=2) + "\n")
    evidence = result.get("evidence") if isinstance(result, dict) else None
    journal_added = None
    if isinstance(evidence, dict):
        journal_added = len(evidence.get("journal", []))
    print(f"  reviews added {added} (expected {expected}) · "
          f"journal entries {journal_added} · passed {record['passed']}", flush=True)
    return record


CARD_FIELDS = ("id", "nid", "did", "ord", "type", "queue", "due", "ivl", "reps", "lapses", "mod")


def summarize(deck_name):
    """Rebuild the summary from every retained turn, not only the ones this run drove."""
    turns = []
    for name in TURNS:
        path = OUT / f"{name}.json"
        if not path.is_file():
            continue
        record = json.loads(path.read_text())
        evidence = (record["result"] or {}).get("evidence") or {}
        first = (evidence.get("turns") or [{}])[0]
        turns.append({"turn": name, "utc": record["utc"],
                      "reviewsAdded": record["reviewsAdded"],
                      "expectedReviewsAdded": record["expectedReviewsAdded"],
                      "gradingPath": first.get("gradingPath"),
                      "confirmationSource": first.get("confirmationSource"),
                      "passed": record["passed"]})
    (OUT / "summary.json").write_text(json.dumps({
        "utc": utc(), "avd": AVD, "flags": FLAGS, "deck": deck_name, "turns": turns,
    }, indent=2) + "\n")
    return turns


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("deck", type=Path, help='JSON [<deckId>, "<deck name>"]')
    parser.add_argument("--turns", default=",".join(TURNS),
                        help="comma-separated subset, in order (default: all ten)")
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

    attached = [line.split()[0] for line in
                subprocess.run([str(av.ADB), "devices"], capture_output=True, check=True)
                .stdout.decode().splitlines()[1:] if line.strip().endswith("device")]
    if not arguments.no_boot:
        av.SERIAL = DEVICE
    elif len(attached) == 1:
        av.SERIAL = attached[0]
    else:
        raise SystemExit(f"Exactly one attached device is required; found {attached}")

    BUILD.mkdir(parents=True, exist_ok=True)
    records = []
    for index, name in enumerate(wanted):
        log_path = BUILD / f"emulator-boot-{index:02d}.log"
        records.append(turn(name, deck_id, log_path, reboot=not arguments.no_boot))

    summarize(deck_name)
    failed = [r["turn"] for r in records if not r["passed"]]
    print("\nAV-026 live check complete" if not failed else f"\nAV-026 live check: {failed} did not pass")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
