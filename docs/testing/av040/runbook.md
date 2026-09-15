# AV-040 operator runbook and evidence guide

Issue [#45](https://github.com/BrockBadeaux14/AnkiVoice/issues/45);
[report](../av040-live-microphone.md);
branch `codex/av040-live-microphone-interruptions`.

## Bounds and saved evidence

The original baseline ran **once**. The original **24 additional turns** are
preserved; the owner then explicitly authorized **at most four more** using the
same second capture policy and MacBook microphone/speakers. See
[extension authorization](evidence/extension-authorization.json). There are still
only two capture candidates and one interruption rule. No counter was reset.
Do not repeat a baseline or comparison to replace a failure.

| Source | Meaning |
| --- | --- |
| [baseline-human.json](evidence/baseline-human.json) | Original twelve-turn human-operated loop; built-in MacBook route |
| [candidate1-human.json](evidence/candidate1-human.json) | Attempts 1–4, explicit Start/Done; all closed before speech |
| [candidate2-human.json](evidence/candidate2-human.json) | Attempts 5–24, segmented Start/Done; includes every repeated/failed/interrupted turn |
| [macbook-extension-human.json](evidence/macbook-extension-human.json) | Owner-authorized attempts 25–28, same second policy after restarting the AVD with MacBook defaults |
| [operator-attestation.json](evidence/operator-attestation.json) | Owner confirmations and corrections; inaudible original echo prompts remain qualified |
| [controller-notes.json](evidence/controller-notes.json) | Investigator triggers/returns and coordination deviations |

Each human JSON is one cumulative snapshot per Activity instance. Earlier saves
remain on the AVD; combining them would count the same attempts more than once.
The validator rejects duplicate attempt IDs. Guard-only adb runs contain zero
turns and are separate from human evidence.

## Recorded environment

Use the [preflight](evidence/environment-preflight.json),
[resumption](evidence/environment-resume.json) and
[MacBook return](evidence/environment-macbook-return.json) records. The one test
AVD is `AnkiVoice_AV005`, serial `emulator-5588`, API 36 ARM64 Google Play revision
7. Do not use or clear the separate `Medium_Phone` AVD. The selected Google
recognizer/TTS package and local `en-US-language` voice match AV-005/AV-006.

**Do not change the Mac's default input while an emulator is running.** AV-005
recorded a crash doing so. Inspect routing before starting the test AVD. In this
run the owner chose WH-1000XM5 at resumption and later requested built-in devices;
when inspected, the MacBook defaults already matched. The timing of that earlier
route change is unknown. Restarting the test AVD established the extension setup.

ADB on this Mac is
`/Users/brockbadeaux/Library/Android/sdk/platform-tools/adb`.
Use `adb -s emulator-5588 emu avd hostmicon` to enable host input and
`adb -s emulator-5588 emu avd hostmicoff` to disable it.
**`hostmicon off` does not disable input**; the trailing argument is ignored.
See [emulator help](evidence/emulator-microphone-help.txt). Force-stop/shutdown ended
the earlier pause even though its original off command was ineffective.

## Controls and recorded protocol

The original AV-005 loop automatically plays/listens after each attestation. The
AV-040 comparison requires explicit actions:

1. The investigator selects a case while idle, confirms its title and arms any
   interruption helper. Only then does the owner tap **START**.
2. For spoken turns, read the displayed answer, listen to the prompt and allow
   the required two/five-second thinking interval. Tap **START ANSWER**, speak
   immediately, and tap **DONE** when finished if capture remains active.
3. Tap **I SPOKE IT**, **I STAYED SILENT**, or **INTERRUPTED** according to what
   actually happened. Never attest a hoped-for answer or a future turn.
4. **NEXT / RESUME** explicitly starts the next turn within that case. An
   attestation or return to the app does not start audio.
5. At **Scenario complete — stop here**, wait. **RERUN SELECTED CASE** repeats
   that case and consumes more attempts. It does not select the next case.
6. For echo turns, keep playback audible and remain silent. Capture opens after
   playback plus 400 ms. In the MacBook extension, leave Done untouched and let
   capture finish automatically. Attest silence, and separately confirm audibility.

If a prompt is inaudible, cancel and report it; do not label the trial as an
acoustic echo pass. Original echo attempts 23/24 were later confirmed inaudible;
their Done actions and unwanted text remain in raw evidence.
The owner spoke during the audible MacBook echo attempts 27/28 and used Done;
these deviations also remain in the evidence. Neither pair is a valid silent,
audible echo check. All 28 authorized follow-up attempts are now used.

The post-measurement UI fix clears the previous result/completion text when a
new case is selected. The old display contributed to repeat/coordination errors,
which remain counted. On Home, return through **Recent apps**. The launcher icon
can stack a new default AV-005 screen; in the recorded case the investigator
verified the two Activities and pressed Back to restore the original run.

## Interruption controller

[interrupt_live.py](../../../tools/av005-probe/interrupt_live.py) waits for fresh
probe playback/capture markers, then dispatches an emulated call, Home, lock or a
Cancel tap. It never starts a scenario, opens capture, injects audio or attests
speech. Start it before the owner begins its matching case, for example:

```sh
python3 tools/av005-probe/interrupt_live.py call_capture \
  --serial emulator-5588 --out <new-trigger-file>.json
```

Calls use the disposable number 5550400 and are cancelled after three seconds.
Cancel coordinates come from the selected idle case's UI tree; do not run another
uiautomator dump concurrently with the helper's dump. Home/lock returns are
explicit owner/investigator actions. The app must remain stopped until a fresh
Start/Resume action. The helper refuses an existing output path and any AVD other
than `AnkiVoice_AV005`.

The signal timestamp is when the controller receives a log marker, plus a 200 ms
requested delay. Host and guest clocks/log delivery are not calibrated. Use the
probe's monotonic event order for cleanup timing; do not infer exact end-to-end
call latency by subtracting unrelated clocks.

## Build and inspect without adding speech trials

```sh
cd tools/av005-probe
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:assembleDebug --console=plain
```

The usual comparison extras are `--ez av040 true --ez av040_segmented true`.
The owner's four-turn exception additionally uses
`--ez av040_authorized_four_turn_extension true`; this raises the persistent cap
from 24 to 28 and records the exception per scenario. It does not erase existing
attempts or enable a third policy. Omit the extra to retain the original cap.
`--es operator investigator_adb` labels zero-turn guard/UI inspections accurately.
Do not clear app data, reinstall without `-r`, or reset preferences to bypass a cap.

## Extract and validate

Pull the newest completed snapshot for the relevant Activity; never merge its
interim snapshots. Do not use `drive.py` to operate human runs: its main routine
is intended for the earlier adb-only matrix and clears probe evidence.

```sh
python3 tools/av005-probe/validate_evidence.py \
  --av040-attempt-limit 28 \
  docs/testing/av040/evidence/baseline-human.json \
  docs/testing/av040/evidence/candidate1-human.json \
  docs/testing/av040/evidence/candidate2-human.json \
  docs/testing/av040/evidence/macbook-extension-human.json
.venv/bin/python -m unittest discover -s tests
```

Use the 28 option only with the documented owner authorization. Without it the
checker still enforces 24. Do not use `--write`; that rewrites the accepted AV-005
report. Honest no-match, inaudible/contaminated and failed interruption observations
must remain visible even when evidence integrity passes.

The final report owns the go/no-go decision and downstream handoff. A no-go does
not unblock #13/#26. #45 moves to In review only when the investigation/report and
validation are complete; acceptance, PR creation, merge and deployment are separate.
