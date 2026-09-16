"""Tell a live emulator microphone apart from the two ways it silently is not one.

AV-040 and AV-042 both lost attempts to the goldfish audio HAL substituting a generated
220 Hz tone for the host microphone, and AV-014 lost a `reveal` measurement the same way.
A capture recorded on either fallback says nothing about recognition: AV-014's runbook is
explicit that an all-`noMatch` sweep with a dead microphone is not a result.

This reads the raw PCM the capture already copies and classifies it, so an environment
fault is recorded as one instead of being reported as a recognition failure.

Values are mono PCM16, so full scale is 32,768. No network, no device state.
"""
import math
import os
from pathlib import Path
import shutil
import struct
import subprocess

# The tone the goldfish HAL generates when it cannot open the host input device.
FALLBACK_HZ = 220
FALLBACK_TOLERANCE_HZ = 15

LIVE = "live"
FALLBACK_TONE = "goldfish-220hz-tone"
ZEROED = "zeroed"
EMPTY = "no-samples"


def adb():
    """The platform-tools adb, from PATH, ANDROID_HOME, ANDROID_SDK_ROOT or the default SDK.

    The operator's shell need not have the SDK on PATH; Android Studio installs it without
    doing so on macOS, and the evidence host is exactly that setup.
    """
    found = shutil.which("adb")
    if found:
        return found
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
             str(Path.home() / "Library" / "Android" / "sdk")]
    for root in roots:
        if root and (Path(root) / "platform-tools" / "adb").is_file():
            return str(Path(root) / "platform-tools" / "adb")
    raise SystemExit(
        "adb not found: add platform-tools to PATH, or set ANDROID_HOME to the Android SDK."
    )


def analyse(raw):
    """Peak, RMS, dominant frequency and a verdict for one PCM16 capture."""
    count = len(raw) // 2
    if count == 0:
        return {"verdict": EMPTY, "samples": 0}
    samples = struct.unpack("<%dh" % count, raw[: count * 2])
    peak = max(abs(value) for value in samples)
    rms = math.sqrt(sum(value * value for value in samples) / count)
    zeros = sum(1 for value in samples if value == 0)

    # Dominant frequency from zero crossings over the loudest whole second, which is
    # where a tone is unambiguous and where speech is least likely to be silence.
    rate = 16_000
    window = min(rate, count)
    loudest, best = 0, 0
    for start in range(0, max(1, count - window + 1), max(1, window // 4)):
        segment = samples[start : start + window]
        energy = sum(value * value for value in segment)
        if energy > best:
            best, loudest = energy, start
    segment = samples[loudest : loudest + window]
    crossings = sum(1 for i in range(1, len(segment)) if (segment[i - 1] < 0) != (segment[i] < 0))
    dominant = round(crossings / 2 * (rate / max(1, len(segment))))

    if peak < 20 and zeros >= count / 2:
        verdict = ZEROED
    elif rms > 15_000 and abs(dominant - FALLBACK_HZ) <= FALLBACK_TOLERANCE_HZ:
        verdict = FALLBACK_TONE
    else:
        verdict = LIVE

    return {
        "verdict": verdict,
        "samples": count,
        "peak": peak,
        "rms": round(rms, 1),
        "zero_fraction": round(zeros / count, 4),
        "dominant_hz": dominant,
    }


def read_device_pcm(device, path):
    """The app-private PCM the capture copied, or None when there is none to read."""
    finished = subprocess.run(
        [adb(), "-s", device, "exec-out", "run-as", "org.ankivoice", "cat", path],
        capture_output=True,
        timeout=120,
    )
    return finished.stdout or None


def preflight(device, speak_ms=5000):
    """Record five unattended seconds and classify them, before any operator is asked to speak.

    Nobody speaks during this, so a live microphone reads as room noise: varying RMS with
    real silence. A steady full-scale 220 Hz sine is the HAL fallback, and a flat near-zero
    reading is a zeroed or denied microphone. Both mean: cold-boot the AVD, do not capture.
    """
    subprocess.run(
        [
            adb(), "-s", device, "shell", "am", "instrument", "-w",
            "-e", "confirm", "AV025_LIVE_SPEECH",
            "-e", "diagnose", "true",
            "-e", "speakMs", str(speak_ms),
            "-e", "prompt", "Testing",
            "org.ankivoice.test/org.ankivoice.app.SpeechInstrumentation",
        ],
        capture_output=True,
        text=True,
        timeout=600,
    )
    raw = read_device_pcm(device, "files/av025-input.pcm")
    if raw is None:
        return {"verdict": EMPTY, "samples": 0}
    return analyse(raw)


REMEDY = (
    "Cold-boot the pinned AVD before capturing:\n"
    "  adb -s {device} emu kill\n"
    "  ~/Library/Android/sdk/emulator/emulator -avd AnkiVoice_AV005 -port 5588 "
    "-allow-host-audio -no-snapshot -no-boot-anim\n"
    "If it still reads {tone}, macOS is refusing the input device: check System Settings -> "
    "Privacy & Security -> Microphone for the emulator, and that no other application holds "
    "the input device."
).format(device="emulator-5588", tone=FALLBACK_TONE)
