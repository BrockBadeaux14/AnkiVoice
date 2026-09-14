#!/usr/bin/env python3
"""Drive one AV-005 scenario over adb, as the investigator rather than as a learner.

This exists so the parts of the fixed matrix that need no human voice — permission
loss, recognizer unavailable or busy, network loss, cancellation with a late
callback, backgrounding, screen lock, route interruption and the silence cases —
can actually be exercised and measured, instead of being handed over untested.

Every run it starts is labelled `operated_by: investigator_adb` and
`voice_source: none` in the evidence, and the validator refuses to count such a
turn as live-speech evidence. Turns that need a person speaking are run from
Android Studio by a human; see the runbook.
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import subprocess
import time

PACKAGE = "org.ankivoice.av005"
ACTIVITY = f"{PACKAGE}/.ProbeActivity"
SDK = pathlib.Path(os.environ.get("ANDROID_SDK_ROOT", pathlib.Path.home() / "Library/Android/sdk"))
ADB = str(SDK / "platform-tools" / "adb")
NODE = re.compile(
    r'text="([^"]*)"[^>]*class="android\.widget\.Button"[^>]*enabled="(true|false)"'
    r'[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
)


def adb(serial: str, *arguments: str, check: bool = True) -> str:
    done = subprocess.run([ADB, "-s", serial, *arguments], capture_output=True, text=True)
    if check and done.returncode != 0:
        raise RuntimeError(f"adb {' '.join(arguments)}: {done.stderr.strip()}")
    return done.stdout


def buttons(serial: str) -> dict:
    """Maps button label to (centre_x, centre_y, enabled), read from the live UI tree."""
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/av005-ui.xml", check=False)
    tree = adb(serial, "shell", "cat", "/sdcard/av005-ui.xml", check=False)
    found = {}
    for label, enabled, x1, y1, x2, y2 in NODE.findall(tree):
        found[label.strip().lower()] = (
            (int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2, enabled == "true"
        )
    return found


def tap(serial: str, label: str, timeout: float = 45.0) -> bool:
    """Taps a button once it is enabled. Returns False if it never became tappable."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        found = buttons(serial).get(label.lower())
        if found and found[2]:
            adb(serial, "shell", "input", "tap", str(found[0]), str(found[1]))
            return True
        time.sleep(1.0)
    return False


def markers(serial: str) -> list:
    lines = adb(serial, "logcat", "-d", "-s", "AV005:I", check=False).splitlines()
    return [line for line in lines if "MARK" in line or "AV005   :   " in line]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--attest", default="i stayed silent",
                        choices=["i stayed silent", "i spoke it", "interrupted"])
    parser.add_argument("--turns", type=int, default=1, help="how many turns to attest")
    parser.add_argument("--settle", type=float, default=4.0)
    parser.add_argument("--turn-timeout", type=float, default=90.0)
    parser.add_argument("--before", help="host shell command run before Start")
    parser.add_argument("--during", help="host shell command run once capture opens")
    parser.add_argument("--during-tap", help="tap this button once capture opens, e.g. REPEAT")
    parser.add_argument("--playback-tap", help="tap this button while the prompt is still playing")
    parser.add_argument("--during-playback", help="host shell command run once playback starts")
    parser.add_argument("--after", help="host shell command run after the scenario")
    parser.add_argument("--out", type=pathlib.Path)
    arguments = parser.parse_args()
    serial = arguments.serial

    name = adb(serial, "emu", "avd", "name").strip().splitlines()[0].strip()
    if name != "AnkiVoice_AV005":
        raise SystemExit(f"refusing to drive {serial}: it is {name}, not AnkiVoice_AV005")

    adb(serial, "shell", "am", "force-stop", PACKAGE)
    # Clear prior evidence so the file pulled below can only come from this run.
    adb(serial, "shell", "rm", "-f",
        f"/storage/emulated/0/Android/data/{PACKAGE}/files/*", check=False)
    adb(serial, "logcat", "-c", check=False)
    if arguments.before:
        subprocess.run(arguments.before, shell=True, check=False)
    adb(serial, "shell", "am", "start", "-W", "-n", ACTIVITY,
        "--es", "scenario", arguments.scenario,
        "--es", "operator", "investigator_adb")
    time.sleep(arguments.settle)

    if not tap(serial, "start"):
        raise SystemExit("Start never became tappable")

    for index in range(arguments.turns):
        if arguments.during_playback or arguments.playback_tap:
            deadline = time.monotonic() + 30
            seen = len([m for m in markers(serial) if "playback_start" in m])
            while time.monotonic() < deadline:
                if len([m for m in markers(serial) if "playback_start" in m]) > seen:
                    break
                time.sleep(0.3)
            if arguments.during_playback:
                subprocess.run(arguments.during_playback, shell=True, check=False)
            if arguments.playback_tap and not tap(serial, arguments.playback_tap, timeout=10):
                print(f"turn {index}: {arguments.playback_tap} never became tappable")
        if arguments.during:
            deadline = time.monotonic() + 40
            seen = len([m for m in markers(serial) if "capture_ready" in m])
            while time.monotonic() < deadline:
                if len([m for m in markers(serial) if "capture_ready" in m]) > seen:
                    break
                time.sleep(0.4)
            subprocess.run(arguments.during, shell=True, check=False)
        if arguments.during_tap:
            deadline = time.monotonic() + 40
            seen = len([m for m in markers(serial) if "capture_ready" in m])
            while time.monotonic() < deadline:
                if len([m for m in markers(serial) if "capture_ready" in m]) > seen:
                    break
                time.sleep(0.4)
            time.sleep(0.6)
            if not tap(serial, arguments.during_tap, timeout=10):
                print(f"turn {index}: {arguments.during_tap} never became tappable")
        if not tap(serial, arguments.attest, timeout=arguments.turn_timeout):
            print(f"turn {index}: attestation button never became tappable")
            break

    time.sleep(3)
    if arguments.after:
        subprocess.run(arguments.after, shell=True, check=False)

    listing = adb(serial, "shell", "ls", "-1",
                  f"/storage/emulated/0/Android/data/{PACKAGE}/files/", check=False)
    files = sorted(f.strip() for f in listing.splitlines() if f.strip().endswith(".json"))
    if not files:
        raise SystemExit("no evidence file was written")
    newest = files[-1]
    target = arguments.out or pathlib.Path("build/av005/pulled") / f"{arguments.scenario}.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    adb(serial, "pull", f"/storage/emulated/0/Android/data/{PACKAGE}/files/{newest}", str(target))

    document = json.loads(target.read_text(encoding="utf-8"))
    recorded = [s.get("scenario") for s in document.get("scenarios", [])]
    if arguments.scenario not in recorded:
        raise SystemExit(f"pulled evidence holds {recorded}, not {arguments.scenario}: "
                         "the scenario did not complete")
    for scenario in document.get("scenarios", []):
        print(f"{scenario['scenario']}: operated_by={scenario.get('operated_by')} "
              f"voice={scenario.get('voice_source')}")
        for turn in scenario.get("turns", []):
            print("   turn {i} {status} {err} capture={cap} settle={settle} rms={rms}".format(
                i=turn.get("turn_index"), status=turn.get("status"),
                err=turn.get("error_name") or turn.get("detail") or "",
                cap=turn.get("capture_ms"), settle=turn.get("measured_settle_ms"),
                rms=turn.get("rms_peak")))
        stale = scenario.get("stale_callbacks", [])
        if stale:
            print(f"   stale: {[(e['callback'], e['reason']) for e in stale]}")
    print(f"-> {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
