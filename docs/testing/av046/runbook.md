# AV-046 runbook: which layer drops the guest microphone audio

Issue [#74 — Establish which layer drops guest microphone audio on the pinned AVD](https://github.com/BrockBadeaux14/AnkiVoice/issues/74).

A bounded discovery, not a feature. Three layers, deliberately separate:

| Layer | Needs | What it establishes |
| --- | --- | --- |
| 1. Offline | nothing | That the diagnostic observes the shipped pipe and changes nothing; the attribution rule on fabricated attempts |
| 2. Smoke check | the pinned AVD, nobody speaking | That the harness runs end to end on this host: both recorders open, the record comes out. **Not evidence** |
| 3. Live diagnostic | the pinned AVD and the owner's voice, at most three cold boots of two captures | Which layer between the host microphone and `SpeechTransport` loses the audio when a capture comes back empty |

Nothing here reads a collection, grades an answer, writes a review, plays a prompt or
synthesizes a voice. A live run cannot alter a card, and it cannot be produced without a
person speaking into the emulator.

## 1. Offline: the guard and the rule, with no emulator

```sh
.venv/bin/python -m unittest tests.test_av046_layers
```

```sh
.venv/bin/python tools/av046-qa/validate.py
```

```sh
cd android && ./gradlew --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
```

`tests/test_av046_layers.py` fails if the diagnostic gains a route to a card, if the
reference recorder starts using anything of `:speech`, if the harness stops observing the
shipped `SpeechTransport` over the shipped `AndroidSpeechPlatform`, if an observer stops
passing a call straight through, or if any shipped module acquires the card's marker. It
also runs the attribution rule below over one fabricated attempt per layer, and the
validator over whatever the live run retained.

## 2. What one attempt records

Every attempt is one capture through the transport exactly as it ships, with three views
of the same window taken inside the process and three more taken from the host:

| View | Taken by | What it says |
| --- | --- | --- |
| The transport's recorder | `CaptureDiagnostics` on the shipped platform | every sample the pump read from the app's `AudioRecord`: count, zeros, peak, RMS |
| The pump | `PumpDiagnostics`, a pass-through on the same stream | when the open was asked for and answered, the first frame and the first non-silent one, how long reads and pipe writes blocked, the bytes written to the recognizer's pipe before Done and the padding after, when the microphone stopped and the pipe closed |
| The reference recorder | `ReferenceMicrophone`, a bare `AudioRecord` from the test APK | the same statistics and a per-second profile from a recorder that uses none of AV-025's pipe, running **concurrently** through the window by default |
| The recognizer | AV-044's inert `recognizerObserver` | every raw callback with its bundle keys, text and scores |
| The audio server | `AudioManager.getActiveRecordingConfigurations()` at Speak now | every recording client this app can see, its format and device, and whether the platform **silenced** it |
| The emulator | the driver, from the emulator's own log | the coreaudio fault lines, and whether the emulator exited |
| The guest HAL | the driver, from logcat | `pcm_readi` read failures and silence inserts from `android.hardware.audio@7.1-impl.ranchu` |
| The audio server again | the driver, `dumpsys media.audio_flinger` and `dumpsys audio` a moment after Speak now | the input thread, its tracks and the HAL stream while both recorders are open |

The two recorders ask for the same source, rate and format, so a difference between what
they receive is a difference in the app's pipe, not in the request. The reference can also
run alone before or after the transport's capture (`--reference before`, `--reference
after`), at the cost of a microphone open of its own; use that only if the concurrent
reference itself turns out to disturb the capture (the transport's open failing with the
reference open would show that, and the record would say so).

## 3. Prerequisites

- The pinned AVD `AnkiVoice_AV005`. If it is missing, recreate it exactly as
  [AV-005's runbook](../av005/runbook.md#2-create-the-disposable-avd-if-it-does-not-exist)
  does. The driver cold-boots it on port 5588 with `-allow-host-audio -no-snapshot
  -no-boot-anim` and turns the host microphone on over the console, because without those
  the guest microphone is zeroed. Check which AVD a serial actually is before assuming —
  `adb -s <serial> emu avd name` — and let the driver stop a stray copy; it refuses to
  proceed if `emulator-5588` is some other AVD.
- **Pick the host input device before the emulator starts, and leave it alone.** The
  emulator binds the Mac's default input at launch, and AV-005 recorded that changing it
  afterwards kills the audio backend. The driver records which device it was, and whether
  it is Bluetooth, in `environment.json` for every run. AV-044's clean discovery used the
  MacBook Pro microphone; if the default is a Bluetooth headset, that is a difference worth
  keeping in mind when reading the result, and one boot on the built-in microphone is the
  cheapest control.
- `RECORD_AUDIO` granted to `org.ankivoice`; the driver grants it after every boot.
- A person at the machine with a working microphone. Every attempt but the smoke check
  needs the owner to say one short phrase. Nothing here synthesizes a voice.
- No OpenRouter key, no AnkiDroid, no collection: the harness cannot reach any of them.

Two captures per boot, then a cold boot. The emulator's coreaudio backend leaks a listener
per microphone open and has exited on the second or third open of a boot; the budget the
card sets is three cold boots, and the driver stops when it is spent.

## 4. Build and install

```sh
cd android && ./gradlew --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
```

The driver installs both APKs on the first boot of a run (`--no-install` skips that) and
checks that the component survived packaging — AGP rewrites the *first* manifest entry to
the configured default runner, which is why `ReviewInstrumentation` is declared first:

```sh
adb shell pm list instrumentation | grep CaptureLayerInstrumentation
```

## 5. The smoke check, with nobody speaking

```sh
.venv/bin/python tools/av046-qa/run.py --smoke
```

One unattended capture of the room, on a fresh boot, under ignored `build/av046/`. It
checks that the harness runs on this host — both recorders open, the audio server lists
both clients, the record comes out and validates — before any of the owner's budget is
spent. It records silence and it says so (`source: nobody`); it is not an attempt and it
is not evidence. Its reading should be `no-speech`; anything else is a harness problem to
fix before the run.

## 6. The live diagnostic, in the owner's voice

```sh
.venv/bin/python tools/av046-qa/run.py --plan
```

```sh
.venv/bin/python tools/av046-qa/run.py --evidence docs/testing/av046/evidence/layers-<date>
```

Six attempts over three cold boots: `green blue red`, `five`, `confirm`, `green blue red`,
`good`, `five` — phrases the pinned engine has recognized in the owner's voice before, so a
no-match on one of them is a result rather than a vocabulary question. For each attempt the
driver cold-boots when the boot's two captures are spent or the emulator's log shows the
fault, turns the host microphone on, and starts the harness; the emulator screen then leads:

1. **Start answer** — the screen shows the phrase, the boot and the open index. Tap it.
2. Wait for **Speak now**. The reference recorder is already open; the transport's opens
   when you tap, and the screen changes when the recognizer reports ready.
3. **Say the phrase once**, naturally. Tap **Done**.
4. The result screen shows what the recognizer returned and the peak level each recorder
   read. Tick **I said: …** only if you did. Tap **Save result**.

The driver prints one line per attempt as it lands — what the reference received, what the
transport's recorder received, what the recognizer returned, whether the emulator or the
guest HAL logged a fault, and the layer that reading points at. **Every attempt is
appended**, including the ones that prove nothing; a `no-speech` reading on an attempt you
spoke into is a result, not a retry cue. Keep the whole ledger.

If the emulator exits mid-attempt the record says so and the driver cold-boots for the
next one. If the transport's open fails while the concurrent reference is open, re-run the
remaining attempts with `--reference after` so the two recorders never share a window, and
say so in the results.

Stopping the driver does **not** stop the instrumentation on the device. Force-stop
`org.ankivoice.test` as well, or the next run meets a screen from the last one.

## 7. Read it

```sh
.venv/bin/python tools/av046-qa/validate.py --evidence docs/testing/av046/evidence/layers-<date> --table
```

The validator re-derives every attempt's layer from the record with the one rule below,
fails if the driver's recorded layer disagrees, if the pump's byte count is not the
recorder's sample count, if a failed capture carries text, or if a concurrent reference did
not cover the transport's window, and prints the table the results page records.

| Layer | Meaning | Exit criterion |
| --- | --- | --- |
| `no-drop` | the capture returned a transcript; nothing to attribute | — |
| `app-recorder` | the reference received speech; the transport's recorder received none | 1 |
| `app-pipe` | the transport's recorder received speech the pump did not carry to the pipe | 1 |
| `emulator-or-host` | the emulator logged its coreaudio fault or exited, the guest HAL logged read failures, or both recorders received no speech while the owner attests to speaking | 2 |
| `after-the-pipe` | both recorders received speech and the pump carried all of it: the audio reached the recognizer, and a no-match is a recognition result, not a dropped capture | informs 3 |
| `no-speech` | neither recorder received speech and nobody attests to speaking | — |
| `reference-anomaly` | the transport's recorder received more than the reference | — |
| `inconclusive` | no reference to compare against, or the harness did not finish | — |

"Speech" is a PCM16 peak at or above 2,000; "zeroed" is AV-017's rule. The rule never
infers from the audio that a phrase was spoken: only the ticked attestation says that.

## What a completed run does and does not establish

It establishes, for the captures spent, which side of the app's `AudioRecord` the audio was
lost on, and whether the emulator's own backend logged a fault for the window. It is at
most six captures from one speaker on one host on one day; it is not a rate, and the
30-turn run stays in [#29](https://github.com/BrockBadeaux14/AnkiVoice/issues/29). If no
capture drops within the budget, the card closes on exit criterion 3 with the mitigation
stated from what was observed, and the question stays open. Emulator results say nothing
about physical devices, Bluetooth audio inside the guest, real calls, backgrounding or
screen lock.
