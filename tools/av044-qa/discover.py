"""AV-044 discovery: does the pinned recognizer put `CONFIDENCE_SCORES` in segment bundles?

    python tools/av044-qa/discover.py --evidence docs/testing/av044/evidence/discovery-YYYYMMDD
    python tools/av044-qa/discover.py --plan   # print the attempt plan and exit

It drives `ConfidenceInstrumentation`, which runs one capture through the shipped
transport with every raw recognizer callback logged, and appends every attempt to
`attempts.jsonl` — the ones that came back with text, the ones that failed, and the ones
the emulator's audio backend broke. A capture on a dead microphone is recorded as an
environment fault and the phrase is tried again on a fresh boot; the faulted attempt stays.

The operator is a person: the harness shows each phrase on the emulator screen, the
operator taps Start answer, speaks, taps Done and attests, and the host microphone reaches
the guest under `-allow-host-audio`. This driver only boots the AVD (cold, every two
captures, because the emulator's coreaudio backend leaks a listener per microphone open
and exits after two or three), turns the host microphone on, runs the harness, and appends
every attempt. Nothing here synthesizes a voice: the owner asked on September 16, 2026
that the discovery be spoken, and a synthesized stand-in would not be a voice pass anyway.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import re
import shlex
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "av017-qa"))
import microphone  # noqa: E402  (adb discovery and the PCM classifier)

ROOT = Path(__file__).resolve().parents[2]
COMPONENT = "org.ankivoice.test/org.ankivoice.app.ConfidenceInstrumentation"
CONFIRMATION = "AV044_LIVE_CONFIDENCE"
DEVICE = "emulator-5588"
AVD = "AnkiVoice_AV005"
PORT = 5588
EMULATOR = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
                or Path.home() / "Library" / "Android" / "sdk") / "emulator" / "emulator"
EVIDENCE = ROOT / "docs" / "testing" / "av044" / "evidence"
# The emulator's own log line for the coreaudio fault AV-017 diagnosed.
FAULT = "Failed to create voice"
PROMPT = "Say the command."

# One entry per capture. `say` is what the operator is asked to say, in order; a
# single-phrase attempt is the common case and a two-phrase one exercises more than one
# segment in a session. `kind` is why the phrase is in the plan; `note` is shown with it.
DEFAULT_PLAN = [
    {"id": "01-colors", "say": ["green blue red"], "kind": "answer (AV-042 phrase)"},
    {"id": "02-five", "say": ["five"], "kind": "answer (AV-042 phrase)"},
    {"id": "03-confirm", "say": ["confirm"], "kind": "guarded command"},
    {"id": "04-yes", "say": ["yes"], "kind": "guarded command (confirm phrase)"},
    {"id": "05-good", "say": ["good"], "kind": "guarded command (rating)"},
    {"id": "06-rate-hard", "say": ["rate hard"], "kind": "guarded command (rating)"},
    {"id": "07-easy", "say": ["easy"], "kind": "guarded command (rating)"},
    {"id": "08-skip", "say": ["skip"], "kind": "guarded command"},
    {"id": "09-reveal", "say": ["reveal the answer"], "kind": "guarded command"},
    {"id": "10-finish-session", "say": ["finish session"], "kind": "guarded command"},
    {"id": "11-five-blocks", "say": ["there are five blocks"], "kind": "answer (multi-word)"},
    {"id": "12-two-segments", "say": ["green blue red", "confirm"], "kind": "two phrases, one capture",
     "speak_ms": 15000,
     "note": "Say the first phrase, pause about two seconds, then say the second, then tap Done."},
    {"id": "13-confirm-mumbled", "say": ["confirm"], "kind": "degraded: deliberately mumbled",
     "note": "Mumble it: quiet, unclear, trailing off. This one is meant to be hard to hear."},
    {"id": "14-confirm-quiet", "say": ["confirm"], "kind": "degraded: far from the microphone",
     "note": "Say it from across the room, or with your hand over the microphone."},
    {"id": "15-nonsense", "say": ["frobnicate the quux"], "kind": "out-of-vocabulary words"},
    {"id": "16-again", "say": ["again"], "kind": "ambiguous phrase (repeat or rate-again)"},
]


def adb(*arguments, device=DEVICE, timeout=120, check=False):
    finished = subprocess.run([microphone.adb(), "-s", device, *arguments],
                              capture_output=True, text=True, timeout=timeout)
    if check and finished.returncode != 0:
        raise RuntimeError(f"adb {' '.join(arguments)}: {finished.stderr.strip()}")
    return finished.stdout


def utc():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# ------------------------------------------------------------------ the emulator

def running(device=DEVICE):
    return any(line.startswith(device + "\t") and line.strip().endswith("device")
               for line in adb("devices", device=device).splitlines())


def kill(device=DEVICE):
    if not running(device):
        return
    # `emu kill` on this host returns before the guest has flushed its last writes, and a
    # fresh install, a pushed file and the engine's voice data were all lost that way.
    adb("shell", "sync", device=device)
    adb("emu", "kill", device=device)
    for _ in range(60):
        if not running(device):
            break
        time.sleep(1)
    time.sleep(3)


# The AV-042 launch flags. Without `-allow-host-audio` the guest microphone is zeroed.
FLAGS = ["-allow-host-audio", "-no-snapshot", "-no-boot-anim"]


def boot(log_path, device=DEVICE, avd=AVD, port=PORT):
    """Cold-boot the pinned AVD, logging the emulator's own output."""
    kill(device)
    command = [str(EMULATOR), "-avd", avd, "-port", str(port), *FLAGS]
    log = open(log_path, "ab")
    subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                     start_new_session=True)
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        if running(device) and adb("shell", "getprop", "sys.boot_completed", device=device).strip() == "1":
            break
        time.sleep(2)
    else:
        raise SystemExit(f"{avd} did not boot within five minutes; see {log_path}")
    # The AV-042 configuration: RECORD_AUDIO granted, guest media volume 9/15.
    adb("shell", "pm", "grant", "org.ankivoice", "android.permission.RECORD_AUDIO", device=device)
    adb("shell", "cmd", "media_session", "volume", "--stream", "3", "--set", "9", device=device)
    # The virtual microphone must be told to use the host input as well as allowed to.
    adb("emu", "avd", "hostmicon", device=device)
    # The pinned engine takes a while after boot; the harness also retries a refused prompt.
    time.sleep(30)


def fault_seen(log_path):
    try:
        return FAULT in Path(log_path).read_text(encoding="utf-8", errors="replace")
    except FileNotFoundError:
        return False


# ---------------------------------------------------------------------- one attempt

def run_attempt(entry, device, prompt, speak_ms):
    """Start the interactive harness for one attempt and collect what it recorded."""
    adb("shell", "run-as", "org.ankivoice", "rm", "-f", "files/av044-input.pcm", device=device)
    say = " | ".join(entry["say"])
    command = [
        microphone.adb(), "-s", device, "shell", "am", "instrument", "-w", "-r",
        "-e", "confirm", CONFIRMATION,
        "-e", "attempt", entry["id"],
        "-e", "say", shlex.quote(say),
        "-e", "source", "operator",
        "-e", "speakMs", str(entry.get("speak_ms", speak_ms)),
        "-e", "diagnose", "true",
        "-e", "interactive", "true",
    ]
    if entry.get("note"):
        command += ["-e", "note", shlex.quote(entry["note"])]
    if prompt:
        command += ["-e", "prompt", shlex.quote(prompt)]
    command.append(COMPONENT)

    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    phases = []
    raw = []
    for line in process.stdout:
        raw.append(line)
        match = re.match(r"INSTRUMENTATION_STATUS: av044Phase=(\S+)", line)
        if match:
            phases.append({"utc": utc(), "phase": match.group(1)})
    process.wait(timeout=600)
    text = "".join(raw)
    match = re.search(r"INSTRUMENTATION_RESULT: av044=(\{.*\})", text)
    try:
        result = json.loads(match.group(1)) if match else {"error": "no instrumentation result"}
    except json.JSONDecodeError as error:
        result = {"error": f"unparseable instrumentation result: {error}"}
    if "error" in result and not match:
        result["raw"] = text[-3000:]

    pcm = microphone.read_device_pcm(device, "files/av044-input.pcm")
    # `cat` on a missing file prints its complaint to the same stream; that is not audio.
    if pcm and (pcm.startswith(b"cat:") or len(pcm) < 3200):
        pcm = None
    during = microphone.analyse(pcm) if pcm else {"verdict": microphone.EMPTY, "samples": 0}
    result["microphoneDuring"] = during
    result["environmentFault"] = during["verdict"] != microphone.LIVE
    return {
        "utc": utc(),
        "device": device,
        "requested": entry,
        "spoken_by": "the operator; see result.attestation for what they confirmed",
        "phases": phases,
        "result": result,
    }


def environment(device, log_path):
    def host(*command):
        try:
            return subprocess.run(command, capture_output=True, text=True, timeout=30).stdout.strip()
        except (OSError, subprocess.TimeoutExpired):
            return None
    first = ""
    try:
        first = Path(log_path).read_text(encoding="utf-8", errors="replace").splitlines()[0]
    except (FileNotFoundError, IndexError):
        pass
    config = {}
    ini = Path.home() / ".android" / "avd" / f"{AVD}.avd" / "config.ini"
    if ini.is_file():
        for line in ini.read_text().splitlines():
            key, _, value = line.partition("=")
            if key in ("image.sysdir.1", "hw.ramSize", "hw.audioInput", "PlayStore.enabled", "hw.device.name"):
                config[key] = value
    audio = host("system_profiler", "SPAudioDataType") or ""
    return {
        "utc": utc(),
        "host": {"platform": platform.platform(), "machine": platform.machine(),
                 "macos": host("sw_vers", "-productVersion"),
                 "default_input": _default_device(audio, "Default Input Device: Yes"),
                 "default_output": _default_device(audio, "Default Output Device: Yes"),
                 "volume": host("osascript", "-e", "get volume settings")},
        "source": "operator",
        "emulator": {"version_line": first, "avd": AVD, "config": config, "flags": FLAGS},
        "device": {"serial": device,
                   "fingerprint": adb("shell", "getprop", "ro.build.fingerprint", device=device).strip(),
                   "sdk": adb("shell", "getprop", "ro.build.version.sdk", device=device).strip(),
                   "tts": [l.strip() for l in adb("shell", "dumpsys", "package", "com.google.android.tts",
                                                  device=device).splitlines() if "versionName" in l]},
    }


def _default_device(report, marker):
    """The device block in `system_profiler SPAudioDataType` that carries `marker`."""
    current = None
    for line in report.splitlines():
        if re.match(r"^\s{8}\S.*:$", line):
            current = line.strip().rstrip(":")
        elif marker in line:
            return current
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--evidence", type=Path, default=EVIDENCE / f"discovery-{datetime.now(timezone.utc):%Y%m%d}")
    parser.add_argument("--device", default=DEVICE)
    parser.add_argument("--plan", action="store_true", help="print the plan and exit")
    parser.add_argument("--only", help="comma-separated attempt ids to run")
    parser.add_argument("--per-boot", type=int, default=2,
                        help="captures per cold boot before rebooting proactively (AV-017 measured 1-6 "
                             "live microphone opens per boot on this emulator)")
    parser.add_argument("--speak-ms", type=int, default=8000)
    parser.add_argument("--prompt", default=PROMPT, help="the prompt played through the transport first; '' for none")
    parser.add_argument("--no-boot", action="store_true", help="use the running emulator; never reboot")
    parser.add_argument("--skip-first-boot", action="store_true",
                        help="start on the running emulator, then cold-boot as usual")
    args = parser.parse_args()

    plan = DEFAULT_PLAN
    if args.only:
        wanted = {each.strip() for each in args.only.split(",")}
        plan = [entry for entry in plan if entry["id"] in wanted]
    if args.plan:
        for entry in plan:
            print(f"{entry['id']:20} {entry['kind']:40} say: {' | '.join(entry['say'])!r}")
        return

    args.evidence.mkdir(parents=True, exist_ok=True)
    ledger = args.evidence / "attempts.jsonl"
    boots = 0
    log_path = args.evidence / f"emulator-boot-{boots:02d}.log"
    since_boot = 0
    need_boot = not args.no_boot and not args.skip_first_boot

    def cold_boot(reason):
        nonlocal boots, log_path, since_boot, need_boot
        boots += 1
        log_path = args.evidence / f"emulator-boot-{boots:02d}.log"
        print(f"cold boot {boots} ({reason}) → {log_path.name}")
        boot(log_path, device=args.device)
        since_boot = 0
        need_boot = False

    queue = list(plan)
    retried = set()
    while queue:
        entry = queue.pop(0)
        if not args.no_boot and (need_boot or since_boot >= args.per_boot or fault_seen(log_path)):
            cold_boot("start" if need_boot else "fault in emulator log" if fault_seen(log_path) else "proactive")
        if not (args.evidence / "environment.json").exists():
            (args.evidence / "environment.json").write_text(
                json.dumps(environment(args.device, log_path), indent=2) + "\n", encoding="utf-8")
        print(f"attempt {entry['id']}: say {' | '.join(entry['say'])!r}")
        record = run_attempt(entry, args.device, args.prompt or None, args.speak_ms)
        record["boot"] = boots
        since_boot += 1
        with ledger.open("a", encoding="utf-8") as out:
            out.write(json.dumps(record) + "\n")
        result = record["result"]
        capture = result.get("capture", {})
        print(f"  microphone {result.get('microphoneDuring', {}).get('verdict')}, "
              f"segments {result.get('segmentCount')} with scores {result.get('segmentsWithConfidenceScores')}, "
              f"capture {capture.get('kind')}: {capture.get('text', capture.get('failure'))!r} "
              f"({capture.get('confidence', '-')})")
        if result.get("environmentFault") and entry["id"] not in retried and not args.no_boot:
            # The faulted attempt is kept; the phrase gets one more try on a fresh boot.
            retried.add(entry["id"])
            queue.insert(0, entry)
            need_boot = True
    print(f"\n{ledger}: {sum(1 for _ in ledger.open())} attempts over {boots} boots")


if __name__ == "__main__":
    main()
