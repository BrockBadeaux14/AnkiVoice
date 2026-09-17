"""AV-019 (#21) live check: the pre-commit exchange against a real AnkiDroid collection.

    python tools/av019-qa/run.py build/av019/deck.json
    python tools/av019-qa/run.py build/av019/deck.json --cases confirmed,corrected

Five cases, one per invocation of `ExchangeInstrumentation`, because the emulator's
coreaudio backend leaks a listener per microphone open and exits on the second or third of
a boot — and every case needs one spoken answer. This driver cold-boots the pinned AVD
between cases, turns the host microphone on, clears the journal, snapshots the collection
either side, runs the harness, and retains what it recorded.

  confirmed        announce, confirm as proposed, then try to confirm again.
                   One review; the journal entry settles `confirmed`; the duplicate is
                   refused before it reaches the writer.
  corrected        announce, correct to another rating, confirm that one.
                   One review at the corrected rating; the journal entry settles
                   `confirmed`.
  correction-only  announce, correct, finish the session. Nothing written, nothing
                   journalled: a correction is not a commit.
  abandoned        announce, pause, finish the session. Nothing written, nothing
                   journalled: an unconfirmed rating expires into neither.
  undo-handoff     confirm, then hand off to AnkiDroid's own Undo. One review, the session
                   stopped, and the card read again afterwards.

**Three of the five write a real review**, deliberately, because the exchange is the only
path to the writer and a run that wrote nothing would prove nothing about it. Back the
collection up first; the driver refuses any deck whose name does not start with AV002 and
the instrumentation refuses it again on the device.

The operator is a person. The harness shows each step on the emulator screen, the operator
speaks the answer and chooses whether to say or tap each confirmation, and the source is
recorded as it happened. Nothing here synthesizes a voice or attests on their behalf.
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
ENTRY = f"{TEST}/org.ankivoice.app.ExchangeInstrumentation"
JOURNAL_ENTRY = f"{TEST}/org.ankivoice.app.JournalInstrumentation"
CONFIRMATION = "AV019_LIVE_EXCHANGE"
AVD = "AnkiVoice_AV005"
DEVICE = "emulator-5588"
PORT = 5588
EMULATOR = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
                or Path.home() / "Library" / "Android" / "sdk") / "emulator" / "emulator"
# The AV-042 launch flags. Without `-allow-host-audio` the guest microphone is zeroed.
FLAGS = ["-allow-host-audio", "-no-snapshot", "-no-boot-anim"]

OUT = ROOT / "docs/testing/av019/evidence"
BUILD = ROOT / "build/av019"

CASES = ["confirmed", "corrected", "correction-only", "abandoned", "undo-handoff"]
# What each case promises. The driver checks it rather than trusting the harness summary.
WRITES = {"confirmed": 1, "corrected": 1, "correction-only": 0, "abandoned": 0, "undo-handoff": 1}


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

def app_file(name):
    return adb("exec-out", "run-as", APP, "cat", f"files/{name}", check=False).decode(errors="replace")


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


def attempt(case, deck):
    """Start the interactive harness for one case and collect what it recorded."""
    command = [str(av.ADB), "-s", av.SERIAL, "shell", "am", "instrument", "-w", "-r",
               "-e", "confirm", CONFIRMATION,
               "-e", "case", case,
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
    process.wait(timeout=2400)
    text = "".join(raw)
    match = re.search(r"INSTRUMENTATION_RESULT: av019=(\{.*\})", text)
    try:
        result = json.loads(match.group(1)) if match else {"error": "no instrumentation result"}
    except json.JSONDecodeError as error:
        result = {"error": f"unparseable instrumentation result: {error}"}
    if not match:
        result["raw"] = text[-3000:]
    return result, phases


def case(name, deck, log_path, reboot):
    print(f"\n== {name} ==", flush=True)
    if reboot:
        print(f"  cold-booting {AVD}", flush=True)
        boot(log_path)
    clear_journal(deck)
    before = database(f"{name}-before")
    print("  the operator drives the emulator screen from here", flush=True)
    result, phases = attempt(name, deck)
    after = database(f"{name}-after")

    before_log, after_log = revlog(before), revlog(after)
    added = len(after_log) - len(before_log)
    expected = WRITES[name]
    record = {
        "case": name,
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
        "spokenBy": "the operator; see result.answer.attested for what they confirmed",
        "result": result,
    }
    record["passed"] = bool(result.get("passed")) and added == expected
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.json").write_text(json.dumps(record, indent=2) + "\n")
    print(f"  reviews added {added} (expected {expected}) · "
          f"journal entries added {result.get('journalEntriesAdded')} · "
          f"passed {record['passed']}", flush=True)
    return record


CARD_FIELDS = ("id", "nid", "did", "ord", "type", "queue", "due", "ivl", "reps", "lapses", "mod")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("deck", type=Path, help='JSON [<deckId>, "<deck name>"]')
    parser.add_argument("--cases", default=",".join(CASES),
                        help="comma-separated subset, in order (default: all five)")
    parser.add_argument("--no-boot", action="store_true",
                        help="use the running emulator; never reboot (one case per boot is the rule)")
    arguments = parser.parse_args()

    deck_id, deck_name = json.loads(arguments.deck.read_text())
    if not str(deck_name).startswith("AV002"):
        raise SystemExit(f"Refusing a deck that is not disposable: {deck_name!r}")
    wanted = [name.strip() for name in arguments.cases.split(",") if name.strip()]
    unknown = [name for name in wanted if name not in CASES]
    if unknown:
        raise SystemExit(f"Unknown cases {unknown}; choose from {CASES}")

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
        records.append(case(name, deck_id, log_path, reboot=not arguments.no_boot))

    (OUT / "summary.json").write_text(json.dumps({
        "utc": utc(),
        "avd": AVD,
        "flags": FLAGS,
        "deck": deck_name,
        "cases": [{"case": r["case"], "reviewsAdded": r["reviewsAdded"],
                   "expectedReviewsAdded": r["expectedReviewsAdded"],
                   "journalEntriesAdded": r["result"].get("journalEntriesAdded"),
                   "passed": r["passed"]} for r in records],
    }, indent=2) + "\n")
    failed = [r["case"] for r in records if not r["passed"]]
    print("\nAV-019 live check complete" if not failed else f"\nAV-019 live check: {failed} did not pass")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
