#!/usr/bin/env python3
"""Time a bounded AV-040 interruption; the human starts and attests every turn.

Never starts a scenario, opens capture, injects audio, or attests speech. It waits
for fresh probe markers, triggers only the selected emulator interruption, and
records the command/time. Run before the operator taps Start. Call cancellation
ends the emulated ringing; it does not resume the probe.
"""
from __future__ import annotations

import argparse
import datetime
import json
import pathlib
import selectors
import subprocess
import time

from drive import ADB, adb, buttons


def stamp() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case", choices=[f"{action}_{phase}" for action in
                        ("call", "home", "lock", "cancel") for phase in ("playback", "capture")])
    parser.add_argument("--serial", default="emulator-5588")
    parser.add_argument("--out", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if adb(args.serial, "emu", "avd", "name").splitlines()[0].strip() != "AnkiVoice_AV005":
        parser.error("Only the disposable AnkiVoice_AV005 AVD is permitted")
    if args.out.exists():
        parser.error("Output already exists; preserve it and choose a new filename")
    action, phase = args.case.split("_")
    scenario = "av040_" + args.case
    needed = 2 if action == "call" else 1
    # Before playback, derive Cancel coordinates from the UI tree. The button is
    # above dynamic turn text, so its bounds remain fixed during that scenario.
    cancel = buttons(args.serial).get("cancel") if action == "cancel" else None
    if action == "cancel" and cancel is None:
        parser.error("Cancel button missing from the current scenario's UI tree")
    record = {"started_at_utc": stamp(), "scenario": scenario, "serial": args.serial,
              "human_controls_start_and_attestation": True, "required_triggers": needed,
              "trigger_delay_ms": 200, "emulated_call_hold_ms": 3000, "events": []}
    args.out.parent.mkdir(parents=True, exist_ok=True)

    def save() -> None:
        args.out.write_text(json.dumps(record, indent=2) + "\n")

    save()
    stream = subprocess.Popen([ADB, "-s", args.serial, "logcat", "-v", "threadtime",
                               "-T", "1", "-s", "AV005:I", "*:S"], stdout=subprocess.PIPE)
    selector = selectors.DefaultSelector()
    selector.register(stream.stdout, selectors.EVENT_READ)
    buffer = b""
    armed = False
    call_active = False
    deadline = time.monotonic() + 600
    try:
        print(f"ARMED {scenario}: waiting for {needed} human-started turn(s)", flush=True)
        while time.monotonic() < deadline and len(record["events"]) < needed:
            if not selector.select(timeout=1):
                continue
            chunk = stream.stdout.read1(8192)
            if not chunk:
                raise RuntimeError("adb log stream ended")
            buffer += chunk
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                text = line.decode("utf-8", errors="replace")
                if f"MARK scenario={scenario} " not in text:
                    continue
                if " event=playback_request " in text:
                    armed = True
                marker = "playback_start" if phase == "playback" else "capture_ready"
                if not armed or f" event={marker} " not in text:
                    continue
                armed = False
                event = {"trigger_marker": text, "marker_received_at_utc": stamp()}
                record["events"].append(event)
                save()
                time.sleep(0.2)
                event["dispatched_at_utc"] = stamp()
                if action == "call":
                    call_active = True
                    event["command"] = ["emu", "gsm", "call", "5550400"]
                elif action == "home":
                    event["command"] = ["shell", "input", "keyevent", "3"]
                elif action == "lock":
                    event["command"] = ["shell", "input", "keyevent", "223"]
                else:
                    event["command"] = ["shell", "input", "tap", str(cancel[0]), str(cancel[1])]
                    event["coordinate_source"] = "idle scenario UI tree; Cancel button bounds"
                event["result"] = adb(args.serial, *event["command"]).strip()
                if action == "call":
                    time.sleep(3)
                    event["call_end_at_utc"] = stamp()
                    event["call_end_result"] = adb(args.serial, "emu", "gsm", "cancel", "5550400").strip()
                    call_active = False
                save()
                print(f"TRIGGERED {len(record['events'])}/{needed}: {action} during {phase}", flush=True)
                if len(record["events"]) >= needed:
                    break
        record["all_triggers_dispatched"] = len(record["events"]) == needed
        return 0 if record["all_triggers_dispatched"] else 1
    finally:
        if call_active:
            record["cleanup_call_cancel"] = adb(args.serial, "emu", "gsm", "cancel", "5550400", check=False)
        record["finished_at_utc"] = stamp()
        save()
        selector.close()
        stream.terminate()
        stream.wait(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())
