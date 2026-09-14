# Run the AV-005 foreground speech suite

This suite measures whether the Android Studio emulator can run a foreground
English prompt → listen → transcript loop, and how it behaves when that loop is
interrupted. **It requires a person.** Every turn speaks a prompt out loud, opens
the live microphone, and waits for you to answer and then attest what you did.
There is no unattended mode, and no synthesised stand-in for your voice: injected
audio would not prove the live demo path that [#5](https://github.com/BrockBadeaux14/AnkiVoice/issues/5)
asks about.

The suite never reads or writes an Anki collection and never produces a rating.

## Before you start

You will need about twenty minutes, a quiet room, and a microphone you can speak
into. The emulator speaks each prompt out loud, so use a setting where that is
acceptable.

### 1. Pick the host microphone *before* launching the emulator

The emulator binds the Mac's **default input device** when it starts. Changing
that device while the emulator is running kills its audio backend: in this
investigation the emulator logged `coreaudio: Could not initialize record` and
`Failed to create voice 'virtio-snd-mic0'`, and then exited. Set the input you
want in System Settings → Sound → Input first, and leave it alone afterwards.

```sh
# Optional helper: report or set the default devices from the command line.
swiftc -O -o build/av005/hostaudio tools/av005-probe/hostaudio.swift
./build/av005/hostaudio list
./build/av005/hostaudio get
```

### 2. Create the disposable AVD if it does not exist

Do not reuse a personal AVD, and keep it signed out of Google.

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager" create avd \
  --name AnkiVoice_AV005 \
  --package 'system-images;android-36;google_apis_playstore;arm64-v8a' \
  --device medium_phone
```

## Run it from Android Studio

1. **Open the project.** Android Studio → Open → `tools/av005-probe`. It is a
   standalone Gradle project; the repository root is not an Android project.
   `local.properties` is generated on first build and is not committed.
2. **Start the emulator** from Device Manager, choosing `AnkiVoice_AV005`.
3. **Turn on the virtual microphone.** In the emulator window, open Extended
   controls (⋯) → Microphone and enable **Virtual microphone uses host audio
   input**. Without this the recognizer receives no audio and every turn ends in
   `ERROR_NO_MATCH` within about a second. The equivalent from a terminal is:

   ```sh
   adb -s emulator-5588 emu avd hostmicon on
   ```

4. **Raise the emulator media volume.** The prompts are useless if you cannot
   hear them. Use the emulator volume keys, or:

   ```sh
   for i in $(seq 1 10); do adb -s emulator-5588 shell input keyevent KEYCODE_VOLUME_UP; done
   ```

5. **Run the app** (Run ▶ or Shift+F10) and grant the microphone permission when
   Android asks. To open straight onto one scenario, add
   `--es scenario <id>` to the run configuration's *Launch Flags*; the ids are
   `env`, `loop`, `pause2`, `pause5`, `silence`, `echo`, `repeat`, `cancel`,
   `late_callback`, `busy`, `permission`, `network`, `background`, `lock` and
   `focus`. Preselecting only changes the dropdown — a person still taps Start.

## Work through the scenarios

Run them **in order**, top to bottom in the dropdown. Each screen states what you
have to do; the notes below add what is easy to get wrong.

| # | Scenario | What you do |
| --- | --- | --- |
| 1 | Environment check | Nothing. Tap Start; it records the engine, voice, recognizer services and permission state. |
| 2 | Twelve-turn loop | The headline run. Listen to the prompt, wait for **SPEAK NOW**, read the answer shown on screen aloud at a normal pace, then attest. |
| 3 | Two-second pause | As above, but wait about two seconds after SPEAK NOW before speaking. |
| 4 | Five-second pause | As above with a five-second wait. Watch for the turn ending before you speak. |
| 5 | No-speech timeout | Say nothing and let the turn end by itself. |
| 6 | Prompt echo guard | Say nothing. Capture opens while the prompt is still audible, to check the prompt cannot be transcribed as your answer. |
| 7 | Repeat | Tap **REPEAT** instead of answering, then answer the second time. |
| 8 | Cancel | Start speaking, then tap **CANCEL** mid-sentence. |
| 9 | Late callback | Speak normally; the app cancels capture after 900 ms by itself. |
| 10 | Recognizer busy | Speak normally; the app starts a second overlapping recognition. |
| 11 | Permission denied | **Revoke the microphone permission before tapping Start** (long-press the app icon → App info → Permissions → Microphone → Don't allow). Re-grant it afterwards. |
| 12 | Network unavailable | **Turn on Airplane mode before tapping Start.** Turn it off afterwards. |
| 13 | Background | Tap Start, then press Home while the prompt is still speaking. Reopen from Recents. On the second turn press Home after SPEAK NOW. |
| 14 | Screen lock | Tap Start, then lock the screen with the power button. Unlock and reopen the app explicitly. |
| 15 | Audio focus | Tap Start, then interrupt: place an emulated call from Extended controls → Phone, or start playback in another app. |

Attest every turn honestly with **I spoke it**, **I stayed silent** or
**Interrupted**. The attestation is written into the evidence and the validator
checks it against what the recognizer reported; a turn you did not actually speak
must not be recorded as a spoken turn.

Scenarios 11, 12, 14 and 15 change device state. Put the emulator back
(permission granted, Airplane mode off, screen unlocked) before the next one.

## Collect the evidence

The app writes a JSON document each time a scenario finishes, and again whenever
you tap **Save evidence**. The newest file contains every scenario you have run
in that app session, so pull the last one.

```sh
adb -s emulator-5588 shell ls /storage/emulated/0/Android/data/org.ankivoice.av005/files/
adb -s emulator-5588 pull \
  /storage/emulated/0/Android/data/org.ankivoice.av005/files/<newest>.json \
  docs/testing/av005/evidence/operator-run.json
```

Then validate it and regenerate the results table:

```sh
python3 tools/av005-probe/validate_evidence.py docs/testing/av005/evidence/operator-run.json
python3 tools/av005-probe/validate_evidence.py docs/testing/av005/evidence/operator-run.json --write
```

`--write` refreshes the measured tables in
[the report](../av005-foreground-speech.md). Plain validation checks the evidence
without modifying anything.

## Reset

```sh
adb -s emulator-5588 emu avd hostmicon off          # stop forwarding the host microphone
adb -s emulator-5588 uninstall org.ankivoice.av005  # remove the disposable probe
```

The AVD is disposable: delete it in Device Manager when the spike is closed.
