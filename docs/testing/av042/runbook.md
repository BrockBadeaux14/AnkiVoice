# Live voice input runbook

Current scope is [human voice input](scope.md), as narrowed by the owner during #51.
The [initial investigation](plan.md) and two diagnostic turns remain historical.
This is a disposable probe, separate from the production `android/` application.

## Start with a healthy emulator microphone

1. Stop the test emulator before changing the Mac's input device or input gain.
   Also stop other running emulators before switching the host input device.
2. Select MacBook Pro Microphone and MacBook Pro Speakers. Speaker volume was 60% and guest media volume 9/15.
   Input gain was set to 40% before reboot; the final system read showed 84%.
   The timing/source of that intervening gain change was not observed.
   The speaker changes were explicitly requested because the prompt was quiet.
3. Start the existing AVD without a snapshot. Do not clear its data: the cumulative
   AV-042 ledger and the preserved AV-040 counter must survive.

```sh
~/Library/Android/sdk/emulator/emulator -avd AnkiVoice_AV005 -port 5588 \
  -no-snapshot -no-boot-anim -timezone America/Chicago
```

In a separate terminal, once Android has booted:

```sh
bash tools/av042-probe/build.sh
~/Library/Android/sdk/platform-tools/adb -s emulator-5588 install -r build/av042/probe/av042-probe.apk
~/Library/Android/sdk/platform-tools/adb -s emulator-5588 shell pm grant org.ankivoice.av042 android.permission.RECORD_AUDIO
~/Library/Android/sdk/platform-tools/adb -s emulator-5588 emu avd hostmicon
~/Library/Android/sdk/platform-tools/adb -s emulator-5588 shell am start -n org.ankivoice.av042/.ProbeActivity --es case spoken --ei index 0
```

The build uses Android SDK/build-tools 36/36.0.0, minimum SDK 33, target 35,
Java 17 bytecode, and the Android Studio bundled JDK. No paid provider or key.
`build.sh` also runs the Java guard checks; these contain no audio or human input.

## Speak and see the result

Tap **START TRIAL**, listen to the prompt, then tap **START ANSWER**. Wait for
**SPEAK NOW**, say the displayed answer, and tap **DONE**. After the completed turn,
tap **I SPOKE THE DISPLAYED ANSWER** only if you actually said it. The prompt and
silent-turn attestations are independent. No audio or next turn starts from an
attestation. A retry is a new counted attempt and needs another explicit Start.

Index `0` uses “Green, blue, red”; index `2` uses “Five”; index `6` uses “Six”.
Restart the Activity with the desired index while idle to change the phrase.
The recognizer gets live mono PCM16 at 16 kHz through `EXTRA_AUDIO_SOURCE`, uses
`EXTRA_SEGMENTED_SESSION=EXTRA_AUDIO_SOURCE`, `en-US` and `EXTRA_PREFER_OFFLINE=false`,
matching AV-006's selected online-permitted setting. Thinking happens before
capture. Done closes capture and appends 500 ms of silence before closing the pipe,
allowing the final word to finish. The 15-second capture/five-second finalization
limits prevent a stuck attempt. They are probe limits, not a complete product policy.

## If recognition fails

`recognizer_error_7` means no match; it does not identify microphone availability.
Use the PCM recording and Android audio HAL logs to distinguish causes:

```sh
~/Library/Android/sdk/platform-tools/adb -s emulator-5588 logcat -d -s 'android.hardware.audio@7.1-impl.ranchu:V' '*:S'
~/Library/Android/sdk/platform-tools/adb -s emulator-5588 exec-out run-as org.ankivoice.av042 cat files/last-input.pcm > build/av042/last-input.pcm
```

The diagnostic PCM is app-private, overwritten on each capture, and kept out of Git.
It has no WAV header: signed 16-bit little-endian, mono, 16 kHz. It contains the
microphone samples only; the appended silence is not written into that file.

A specific failure was observed during attempts 5/6: `pcm_prepare` returned
I/O error and the goldfish audio HAL substituted a full-scale 220 Hz tone. The app
still received `AudioRecord.RECORDSTATE_RECORDING` and an unsilenced configuration.
The availability Boolean and input level alone therefore did not prove live input.
The exact callback/log correlation is in [audio-hal-errors.txt](evidence/audio-hal-errors.txt).
The upstream [goldfish DevicePortSource implementation](https://android.googlesource.com/device/generic/goldfish/+/refs/heads/main/hals/audio/device_port_source.cpp)
explains the 220 Hz fallback when TinyalsaSource creation fails. This identifies
those tone attempts; it does not prove that every earlier failure had the same cause.
Restart the emulator after the desired host audio configuration is stable. Do not
keep retrying speech against a known generated-tone input.

Initial probe attempts also mistakenly preferred offline recognition; later trials
correct that to the already selected `false` setting. The final results must identify
the configuration actually used. Quiet-prompt fixes do not establish transcription.

## Evidence and cleanup

Pull the cumulative ledger; it includes failed and abandoned attempts:

```sh
~/Library/Android/sdk/platform-tools/adb -s emulator-5588 exec-out run-as org.ankivoice.av042 cat files/ledger.json > docs/testing/av042/evidence/live-ledger.json
```

The frozen `evidence/ledger.json` contains only the two initial diagnostic attempts.
Do not overwrite it when saving later trials. The initial no-go verdict predates the
owner's scope change; its passing integrity validator proves only accurate evidence.
No phone-call or interruption acceptance claim is made for the narrowed MVP.

When finished, stop the probe and disable forwarding:

```sh
~/Library/Android/sdk/platform-tools/adb -s emulator-5588 shell am force-stop org.ankivoice.av042
~/Library/Android/sdk/platform-tools/adb -s emulator-5588 emu avd hostmicoff
```

`hostmicon off` is not the disable command. Neither app-data clearing nor a baseline
rerun is part of this workflow.
