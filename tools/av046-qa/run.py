"""AV-046 (#74): which layer drops the guest microphone audio on the pinned AVD?

    python tools/av046-qa/run.py --plan
    python tools/av046-qa/run.py --evidence docs/testing/av046/evidence/layers-YYYYMMDD
    python tools/av046-qa/run.py --only 03-confirm --no-boot
    python tools/av046-qa/run.py --smoke        # one unattended, silent capture; a harness check, not evidence

Per capture, `CaptureLayerInstrumentation` runs one spoken phrase through the shipped
`SpeechTransport` over `AndroidSpeechPlatform` — AV-025's pipe exactly as built — with two
pass-through observers on it (`CaptureDiagnostics` keeps the samples the transport's
recorder delivered, `PumpDiagnostics` keeps when they arrived and what reached the
recognizer's pipe) and, in the same window, a reference `AudioRecord` from the test APK that
uses nothing of that pipe. This driver adds what the process cannot see: the microphone-open
index within the boot, the emulator's own log lines for its audio backend, the guest audio
HAL's logcat, and a snapshot of the audio server while the microphone is open. Every attempt
is appended to `attempts.jsonl`, the inconclusive ones included, and read against the one
attribution rule in `validate.py` as it lands.

Budget: at most three cold boots of two captures each (`--boots`, `--per-boot`); the driver
stops when it is spent. The operator is the owner speaking at the emulator; nothing here
synthesizes a voice or injects audio, and no prompt is played.
"""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shlex
import struct
import subprocess
import sys
import threading
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "av017-qa"))
import microphone  # noqa: E402  (adb discovery and the PCM classifier)

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("av046_validate", Path(__file__).resolve().parent / "validate.py")
validate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(validate)

APP = "org.ankivoice"
TEST = "org.ankivoice.test"
COMPONENT = f"{TEST}/org.ankivoice.app.CaptureLayerInstrumentation"
CONFIRMATION = "AV046_LIVE_LAYERS"
DEVICE = "emulator-5588"
AVD = "AnkiVoice_AV005"
PORT = 5588
SDK = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
           or Path.home() / "Library" / "Android" / "sdk")
EMULATOR = SDK / "emulator" / "emulator"
EVIDENCE = ROOT / "docs" / "testing" / "av046" / "evidence"
BUILD = ROOT / "build" / "av046"
APKS = [ROOT / "android/app/build/outputs/apk/debug/app-debug.apk",
        ROOT / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]

# The AV-042 launch flags. Without `-allow-host-audio` the guest microphone is zeroed.
FLAGS = ["-allow-host-audio", "-no-snapshot", "-no-boot-anim"]
# The emulator's own lines for AV-017's coreaudio fault; `Failed to create voice` is the one
# the earlier drivers key on, the others precede it.
EMULATOR_FAULT = re.compile(r"Failed to create voice|coreaudio: Could not|kAudioHardware")
EMULATOR_AUDIO = re.compile(r"(?i)coreaudio|voice|audio|snd|hostmic")
# The guest side: the ranchu audio HAL, the audio server and the recognizer.
LOGCAT_KEEP = re.compile(r"(?i)audio|snd|alsa|ranchu|virtio|record|speech|recogni|googletts|ankivoice|silenc")
HAL_READ_FAILURE = re.compile(r"pcm_readi failed|pcmRead:\d+ failure")
HAL_SILENCE_INSERT = re.compile(r"inserting \d+ us of silence")

# Six captures over three boots: the AV-042 phrases the pinned engine has recognized in
# the owner's voice before, and two guarded command words. A no-match on any of them is a
# result; the layers are what is measured, not recall of the phrase.
DEFAULT_PLAN = [
    {"id": "01-colors", "say": "green blue red"},
    {"id": "02-five", "say": "five"},
    {"id": "03-confirm", "say": "confirm"},
    {"id": "04-colors", "say": "green blue red"},
    {"id": "05-good", "say": "good"},
    {"id": "06-five", "say": "five"},
]
SMOKE_PLAN = [{"id": "00-smoke", "say": "", "interactive": False}]


def adb(*arguments, device=DEVICE, timeout=120, check=False, binary=False):
    finished = subprocess.run([microphone.adb(), "-s", device, *arguments],
                              capture_output=True, text=not binary, timeout=timeout)
    if check and finished.returncode != 0:
        raise RuntimeError(f"adb {' '.join(arguments)}: {finished.stderr.strip()}")
    return finished.stdout


def utc():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# ------------------------------------------------------------------ the emulator

def attached():
    listed = subprocess.run([microphone.adb(), "devices"], capture_output=True, text=True).stdout
    return [line.split()[0] for line in listed.splitlines()[1:] if line.strip().endswith("device")]


def running(device=DEVICE):
    return device in attached()


def avd_name(serial):
    """Which AVD a serial actually is; the serial alone does not say."""
    out = adb("emu", "avd", "name", device=serial, timeout=15)
    for line in out.splitlines():
        if line.strip() and line.strip() != "OK":
            return line.strip()
    return None


def clear_strays():
    """A second copy of the pinned AVD cannot start; stop any stray one, and refuse a wrong one."""
    for serial in attached():
        if not serial.startswith("emulator-"):
            continue
        name = avd_name(serial)
        if serial == DEVICE and name not in (None, AVD):
            raise SystemExit(f"{DEVICE} is running {name!r}, not {AVD}; stop it first")
        if name == AVD and serial != DEVICE:
            print(f"stopping a stray {AVD} on {serial}")
            kill(serial)
        elif serial != DEVICE:
            print(f"note: {serial} ({name}) is also running and shares the host microphone")


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


def boot(log_path, device=DEVICE):
    """Cold-boot the pinned AVD, logging the emulator's own output to log_path."""
    kill(device)
    command = [str(EMULATOR), "-avd", AVD, "-port", str(PORT), *FLAGS]
    log = open(log_path, "ab")
    subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                     start_new_session=True)
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        if running(device) and adb("shell", "getprop", "sys.boot_completed", device=device).strip() == "1":
            break
        time.sleep(2)
    else:
        raise SystemExit(f"{AVD} did not boot within five minutes; see {log_path}")
    adb("shell", "pm", "grant", APP, "android.permission.RECORD_AUDIO", device=device)
    # The virtual microphone must be told to use the host input as well as allowed to.
    adb("emu", "avd", "hostmicon", device=device)
    # The recognition service takes a while after boot.
    time.sleep(20)


def install(device=DEVICE):
    for apk in APKS:
        if not apk.is_file():
            raise SystemExit(f"{apk} is missing; build with ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest")
        out = adb("install", "-r", "-d", str(apk), device=device, timeout=300)
        if "Success" not in out:
            raise SystemExit(f"install {apk.name}: {out.strip()}")
    listed = adb("shell", "pm", "list", "instrumentation", device=device)
    if "CaptureLayerInstrumentation" not in listed:
        raise SystemExit("CaptureLayerInstrumentation did not survive packaging; see the androidTest manifest")
    adb("shell", "pm", "grant", APP, "android.permission.RECORD_AUDIO", device=device)


def guest_time(device=DEVICE):
    return adb("shell", "date", "+%m-%d %H:%M:%S.000", device=device).strip()


# ------------------------------------------------------------------ the guest's view

def logcat_since(start, device=DEVICE):
    """The guest's audio-related log lines since `start` (guest time), and what they say."""
    text = adb("logcat", "-d", "-v", "threadtime", "-T", start, device=device, timeout=120)
    kept = [line for line in text.splitlines() if LOGCAT_KEEP.search(line)]
    return "\n".join(kept), {
        "lines": len(kept),
        "readFailures": sum(1 for line in kept if HAL_READ_FAILURE.search(line)),
        "silenceInserts": sum(1 for line in kept if HAL_SILENCE_INSERT.search(line)),
        "silencedMentions": sum(1 for line in kept if "silenc" in line.lower()),
    }


def snapshot_audio_server(stem, device=DEVICE):
    """The audio server while the microphone is open: input threads, tracks and the HAL stream."""
    flinger = adb("shell", "dumpsys", "media.audio_flinger", device=device, timeout=60)
    Path(f"{stem}-audio_flinger.txt").write_text(flinger, encoding="utf-8")
    audio = adb("shell", "dumpsys", "audio", device=device, timeout=60)
    kept = [line for line in audio.splitlines() if re.search(r"(?i)record|captur|input|mic|silenc|ankivoice", line)]
    Path(f"{stem}-audio.txt").write_text("\n".join(kept), encoding="utf-8")
    return {
        "audioFlingerLines": len(flinger.splitlines()),
        "inputThreads": sum(1 for line in flinger.splitlines() if re.search(r"(?i)input thread|RecordThread", line)),
        "activeRecordTracks": sum(1 for line in flinger.splitlines() if re.search(r"(?i)active.*record|record.*active", line)),
    }


def emulator_log_since(log_path, offset):
    try:
        with open(log_path, "rb") as log:
            log.seek(offset)
            text = log.read().decode("utf-8", errors="replace")
    except FileNotFoundError:
        return [], False
    lines = [line for line in text.splitlines() if line.strip()]
    return lines, any(EMULATOR_FAULT.search(line) for line in lines)


# ------------------------------------------------------------------ the PCM

def per_second(raw, rate=16_000):
    count = len(raw) // 2
    samples = struct.unpack("<%dh" % count, raw[: count * 2]) if count else ()
    profile = []
    for second in range(0, count, rate):
        chunk = samples[second: second + rate]
        peak = max(abs(v) for v in chunk)
        rms = (sum(v * v for v in chunk) / len(chunk)) ** 0.5
        profile.append({"second": second // rate, "samples": len(chunk), "peak": peak, "rms": round(rms, 1),
                        "zeroFraction": round(sum(1 for v in chunk if v == 0) / len(chunk), 4)})
    return profile


def pull_pcm(name, save_to, device=DEVICE):
    raw = microphone.read_device_pcm(device, f"files/{name}")
    if not raw or raw.startswith(b"cat:") or raw.startswith(b"run-as:"):
        return None
    save_to.parent.mkdir(parents=True, exist_ok=True)
    save_to.write_bytes(raw)
    analysis = microphone.analyse(raw)
    analysis["perSecond"] = per_second(raw)
    analysis["speechSeconds"] = sum(1 for s in analysis["perSecond"] if s["peak"] >= validate.SPEECH_PEAK)
    return analysis


# ------------------------------------------------------------------ one attempt

def run_attempt(entry, args, number, boot_number, open_index, records_opened, evidence, log_path):
    """Start the harness for one attempt and collect every view of it."""
    device = args.device
    stem = evidence / f"attempt-{number:02d}-{entry['id']}"
    adb("shell", "am", "force-stop", TEST, device=device)
    adb("shell", "am", "force-stop", APP, device=device)
    for name in ("av046-transport.pcm", "av046-reference.pcm", "av046-result.json"):
        adb("shell", "run-as", APP, "rm", "-f", f"files/{name}", device=device)

    offset = log_path.stat().st_size if log_path.is_file() else 0
    start = guest_time(device)
    interactive = entry.get("interactive", True)
    command = [
        microphone.adb(), "-s", device, "shell", "am", "instrument", "-w", "-r",
        "-e", "confirm", CONFIRMATION,
        "-e", "attempt", entry["id"],
        "-e", "say", shlex.quote(entry["say"]),
        "-e", "boot", str(boot_number),
        "-e", "openIndex", str(open_index),
        "-e", "reference", args.reference,
        "-e", "referenceMs", str(args.reference_ms),
        "-e", "speakMs", str(entry.get("speak_ms", args.speak_ms)),
        "-e", "interactive", "true" if interactive else "false",
    ]
    if entry.get("note") or args.note:
        command += ["-e", "note", shlex.quote(entry.get("note") or args.note)]
    command.append(COMPONENT)

    snapshot = {}

    def take_snapshot():
        # A moment after the operator is told to speak, so both recorders are open.
        time.sleep(1.5)
        try:
            snapshot.update(snapshot_audio_server(str(stem), device))
        except (OSError, subprocess.SubprocessError) as error:
            snapshot["error"] = str(error)

    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    phases, raw = [], []
    snapshotter = None
    for line in process.stdout:
        raw.append(line)
        match = re.match(r"INSTRUMENTATION_STATUS: av046Phase=(\S+)", line)
        if match:
            phase = match.group(1)
            phases.append({"utc": utc(), "phase": phase})
            print(f"    {phase}", flush=True)
            if phase == "speak-now" and snapshotter is None:
                snapshotter = threading.Thread(target=take_snapshot, daemon=True)
                snapshotter.start()
    process.wait(timeout=900)
    if snapshotter is not None:
        snapshotter.join(timeout=90)
    text = "".join(raw)
    match = re.search(r"INSTRUMENTATION_RESULT: av046=(\{.*\})", text)
    result = None
    if match:
        try:
            result = json.loads(match.group(1))
        except json.JSONDecodeError:
            result = None
    if result is None:
        stored = adb("exec-out", "run-as", APP, "cat", "files/av046-result.json", device=device, binary=True)
        try:
            result = json.loads(stored.decode("utf-8", errors="replace"))
        except (json.JSONDecodeError, AttributeError):
            result = {"error": "no instrumentation result", "raw": text[-3000:]}

    alive = running(device)
    pcm_dir = BUILD / "pcm"
    transport_analysis = pull_pcm("av046-transport.pcm", pcm_dir / f"{number:02d}-{entry['id']}-transport.pcm", device) if alive else None
    reference_analysis = pull_pcm("av046-reference.pcm", pcm_dir / f"{number:02d}-{entry['id']}-reference.pcm", device) if alive else None
    logcat, hal = ("", {}) if not alive else logcat_since(start, device)
    if logcat:
        Path(f"{stem}-logcat.txt").write_text(logcat + "\n", encoding="utf-8")
    emulator_lines, emulator_fault = emulator_log_since(log_path, offset)

    record = {
        "utc": utc(),
        "device": device,
        "boot": boot_number,
        "openIndex": open_index,
        "audioRecordsOpenedBefore": records_opened,
        "requested": entry,
        "spokenBy": "the operator; see result.attestation for what they confirmed" if interactive
                    else "nobody: an unattended silent probe",
        "phases": phases,
        "result": result,
        "transportAnalysis": transport_analysis,
        "referenceAnalysis": reference_analysis,
        "audioServer": snapshot,
        "guestHal": hal,
        "guestHalFault": bool(hal.get("readFailures")),
        "emulatorLog": emulator_lines[-80:],
        "emulatorFault": emulator_fault,
        "emulatorExited": not alive,
    }
    record["attribution"] = validate.attribute(record)
    return record


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
    default_input = _device_block(audio, "Default Input Device: Yes")
    return {
        "utc": utc(),
        "host": {"platform": platform.platform(), "machine": platform.machine(),
                 "macos": host("sw_vers", "-productVersion"),
                 "default_input": default_input.get("name"),
                 "default_input_transport": default_input.get("transport"),
                 "default_output": _device_block(audio, "Default Output Device: Yes").get("name"),
                 "volume": host("osascript", "-e", "get volume settings")},
        "source": "operator",
        "emulator": {"version_line": first, "avd": AVD, "config": config, "flags": FLAGS},
        "device": {"serial": device,
                   "fingerprint": adb("shell", "getprop", "ro.build.fingerprint", device=device).strip(),
                   "sdk": adb("shell", "getprop", "ro.build.version.sdk", device=device).strip(),
                   "tts": [l.strip() for l in adb("shell", "dumpsys", "package", "com.google.android.tts",
                                                  device=device).splitlines() if "versionName" in l]},
    }


def _device_block(report, marker):
    """The name and transport of the `system_profiler SPAudioDataType` device block carrying `marker`."""
    current, transport, found = None, None, {}
    for line in report.splitlines():
        if re.match(r"^\s{8}\S.*:$", line):
            current, transport = line.strip().rstrip(":"), None
        elif "Transport:" in line:
            transport = line.split(":", 1)[1].strip()
        elif marker in line:
            found = {"name": current, "transport": transport}
    if found and found.get("transport") is None:
        # The transport line follows the marker in some blocks; take the block's own.
        block = re.search(re.escape(found["name"] or "") + r":\n((?:\s{10}.*\n)+)", report)
        if block:
            hit = re.search(r"Transport:\s*(.+)", block.group(1))
            if hit:
                found["transport"] = hit.group(1).strip()
    return found


def reading(record):
    result = record["result"] or {}
    capture = result.get("capture") or {}
    transport = validate.level(result.get("transportRecorder"), record.get("transportAnalysis"))
    reference = validate.level(validate.reference_of(result), record.get("referenceAnalysis"))
    outcome = capture.get("text") if capture.get("kind") == "transcript" else capture.get("failure", result.get("error"))
    return (f"  reference {reference} · transport recorder {transport} · "
            f"{'transcript' if capture.get('kind') == 'transcript' else 'failed'}: {outcome!r} · "
            f"emulator {'EXITED' if record['emulatorExited'] else 'fault' if record['emulatorFault'] else 'clean'} · "
            f"HAL {'read failures' if record['guestHalFault'] else 'clean'} → {record['attribution']['layer']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--evidence", type=Path, default=EVIDENCE / f"layers-{datetime.now(timezone.utc):%Y%m%d}")
    parser.add_argument("--device", default=DEVICE)
    parser.add_argument("--plan", action="store_true", help="print the plan and exit")
    parser.add_argument("--only", help="comma-separated attempt ids to run")
    parser.add_argument("--boots", type=int, default=validate.BUDGET_BOOTS, help="cold boots the run may spend")
    parser.add_argument("--per-boot", type=int, default=2, help="captures per cold boot before rebooting proactively")
    parser.add_argument("--speak-ms", type=int, default=8000)
    parser.add_argument("--reference", default="concurrent", choices=["concurrent", "before", "after", "none"],
                        help="where the reference recorder runs relative to the transport's capture")
    parser.add_argument("--reference-ms", type=int, default=5000, help="the sequential reference's window")
    parser.add_argument("--note", help="an instruction shown to the operator with every phrase")
    parser.add_argument("--no-boot", action="store_true", help="use the running emulator; never reboot")
    parser.add_argument("--skip-first-boot", action="store_true", help="start on the running emulator, then cold-boot as usual")
    parser.add_argument("--no-install", action="store_true", help="do not reinstall the APKs on the first boot")
    parser.add_argument("--smoke", action="store_true",
                        help="one unattended silent capture under build/av046/, to check the harness; not evidence")
    args = parser.parse_args()

    plan = SMOKE_PLAN if args.smoke else DEFAULT_PLAN
    if args.only:
        wanted = {each.strip() for each in args.only.split(",")}
        plan = [entry for entry in plan if entry["id"] in wanted]
    if args.plan:
        for entry in plan:
            print(f"{entry['id']:12} say: {entry['say']!r}" + ("" if entry.get("interactive", True) else "  (unattended)"))
        return
    if args.smoke:
        args.evidence = BUILD / f"smoke-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}"

    args.evidence.mkdir(parents=True, exist_ok=True)
    BUILD.mkdir(parents=True, exist_ok=True)
    ledger = args.evidence / "attempts.jsonl"
    existing = sum(1 for line in ledger.open() if line.strip()) if ledger.is_file() else 0
    boots = 0
    log_path = args.evidence / f"emulator-boot-{boots:02d}.log"
    since_boot = 0
    records_opened = 0
    need_boot = not args.no_boot and not args.skip_first_boot
    installed = args.no_install

    if not args.no_boot:
        clear_strays()

    def cold_boot(reason):
        nonlocal boots, log_path, since_boot, records_opened, need_boot, installed
        if boots >= args.boots:
            print(f"budget spent: {boots} cold boots; stopping")
            return False
        boots += 1
        log_path = args.evidence / f"emulator-boot-{boots:02d}.log"
        print(f"cold boot {boots} ({reason}) → {log_path.name}", flush=True)
        boot(log_path, device=args.device)
        if not installed:
            install(args.device)
            installed = True
        since_boot = 0
        records_opened = 0
        need_boot = False
        return True

    queue = list(plan)
    number = existing
    while queue:
        entry = queue[0]
        fault_now = log_path.is_file() and bool(EMULATOR_FAULT.search(
            log_path.read_text(encoding="utf-8", errors="replace")))
        if not args.no_boot and (need_boot or since_boot >= args.per_boot or fault_now or not running(args.device)):
            reason = ("start" if need_boot else "emulator gone" if not running(args.device)
                      else "fault in emulator log" if fault_now else "proactive")
            if not cold_boot(reason):
                break
        elif args.no_boot and not running(args.device):
            raise SystemExit(f"{args.device} is not running")
        if not (args.evidence / "environment.json").exists():
            (args.evidence / "environment.json").write_text(
                json.dumps(environment(args.device, log_path), indent=2) + "\n", encoding="utf-8")
        queue.pop(0)
        number += 1
        since_boot += 1
        print(f"attempt {entry['id']} (boot {boots}, open {since_boot}): say {entry['say']!r}"
              if entry.get("interactive", True) else f"attempt {entry['id']} (boot {boots}, open {since_boot}): silent probe",
              flush=True)
        record = run_attempt(entry, args, number, boots, since_boot, records_opened, args.evidence, log_path)
        records_opened += 1 + (1 if args.reference != "none" else 0)
        with ledger.open("a", encoding="utf-8") as out:
            out.write(json.dumps(record) + "\n")
        print(reading(record), flush=True)
        if record["emulatorExited"]:
            need_boot = True

    rows = []
    failures = []
    for index, line in enumerate(ledger.read_text(encoding="utf-8").splitlines(), 1):
        if line.strip():
            rows.append(validate.check_attempt(json.loads(line), f"{ledger.name}:{index}", failures))
    summary = validate.summarize(rows)
    print(f"\n{ledger}: {summary}")
    for failure in failures:
        print(f"inconsistent: {failure}")
    if summary["exit_criterion"]:
        print(f"exit criterion {summary['exit_criterion']} is met; see validate.py --table for the record")
    if not args.no_boot and running(args.device):
        adb("shell", "sync", device=args.device)


if __name__ == "__main__":
    main()
