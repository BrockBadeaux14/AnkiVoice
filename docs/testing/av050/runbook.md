# AV-050 runbook: consecutive hands-free cards on the pinned AVD

Issue [#81](https://github.com/BrockBadeaux14/AnkiVoice/issues/81). This is the live layer.
The offline layer is in [results](results.md) and passes with no emulator and no network.

What this run has to show, in one sitting, on the app's own study screen:

1. A card is offered, **its front is heard with no tap**, and the microphone opens after the
   settle **with no tap**.
2. The learner speaks, stops, and **the microphone closes itself** — no Done.
3. The screen **never** says "Automatic grading", for the whole session, with the option on.

It needs the owner at the machine speaking every answer. Microphone evidence comes from a
person speaking; nothing here injects or synthesizes audio.

## 1. Offline first

```sh
cd android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew --console=plain :core:test :speech:test :app:testDebugUnitTest
```

That must pass before the emulator is booted. In particular
`StudySurfaceGuardTest` and `AutomaticStudyTest.no snapshot of a whole automatic session
names the mode` are the offline halves of claim 3; this run is what checks the screen a
person actually sees.

## 2. Prerequisites

Identical to [AV-026's](../av026/runbook.md#prerequisites): the pinned AVD
`AnkiVoice_AV005` cold-booted on port 5588 with `-allow-host-audio -no-snapshot
-no-boot-anim`, AnkiDroid 2.24.1 with its API enabled and both permissions granted, a
**disposable** AV-002 collection, and a person at the machine with a working microphone.

Turn **Automatic grading ON** on the setup screen before starting, so claim 3 is checked in
the mode that used to announce itself. The option applies to the next session, so set it
before tapping Start studying.

**Ask the owner which host input device to use before booting, and record it.** The
emulator binds the default input at launch, and a Bluetooth headset switches profile when
opened. Never change the system setting yourself.

**Budget two cards per boot, and record the boots spent.** The emulator's coreaudio backend
leaks a listener per microphone open and exits on the second or third open of a boot — and
AV-050 makes that worse, because every card now opens the microphone by itself rather than
waiting for a tap that may never come. Sync before every `emu kill`.

## 3. Build and install

```sh
cd android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
```

```sh
~/Library/Android/sdk/platform-tools/adb install -r -d android/app/build/outputs/apk/debug/app-debug.apk
```

```sh
~/Library/Android/sdk/platform-tools/adb install -r -d android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

## 4. Drive it

Reuse AV-026's harness on the same screen, as AV-047 does:

```sh
mkdir -p build/av050 && echo '[<deckId>, "AV002 Baseline"]' > build/av050/deck.json
```

```sh
.venv/bin/python tools/av047-qa/run.py build/av050/deck.json --turns automatic-rule
```

Then, for each card, **touch nothing at all** except Finish at the end of the sitting:

| Step | What you do | What must happen |
| --- | --- | --- |
| 1 | Tap **Start studying** | the card and its question appear **before** any audio starts — not an "Opening your deck…" screen with a voice over it (D.1) |
| 2 | wait | the question is spoken. You tapped no Play prompt |
| 3 | wait | the status becomes "Listening…" a moment after the question ends. You tapped no Start answer |
| 4 | say the reference answer, then stop | the status leaves "Listening…" **on its own**, within a second or two. You tapped no Done |
| 5 | wait | the rating appears and, with the option on, the review saves itself. No banner, no countdown, no **Keep it manual**, and no sentence anywhere containing "Automatic grading" |
| 6 | wait | the next card is offered and step 2 happens again with no tap |

Two consecutive cards like that is the acceptance line. If the emulator exits first, that is
the known AV-046 fault: record the boot and continue in a second one; the criterion is two
consecutive cards **within one sitting**, not within one boot.

## 5. Record it

Save under `docs/testing/av050/`:

- the driver's per-turn evidence JSON, which carries `automaticOpens` and `captureStops`
  per turn — `automaticOpens` must be exactly 1 per attempt and `captureStops` must contain
  `endpoint`, not `done`, for a card you did not tap Done on;
- `adb logcat` for the sitting, so an endpoint that came from the engine can be told from
  one the trailing-silence fallback produced;
- the host input device, the boots spent, and the emulator's exit if it exited;
- a short note per card: what you said, whether the microphone closed itself, and how long
  after you stopped speaking it took.

## What a failure here means

- **The microphone does not close itself.** Check logcat for `onEndOfSpeech`. If the pinned
  engine never reports it on the segmented external-audio route, the fallback should have
  fired at 1,500 ms of silence — if neither did, the amplitude threshold
  (`SpeechPins.SPEECH_FRAME_AMPLITUDE`) is wrong for this microphone. Both are selected
  bounds and moving them is expected; record the value that worked and why.
- **It closes too early.** The hold (`endpointHoldMs`) is too short for a real mid-answer
  pause, or the minimum capture (`minCaptureMs`) is too short for a false start.
- **Fifteen seconds of pre-roll is not enough to recall an answer.** That is a genuine
  finding about a pinned bound and belongs in the results page, not a quiet edit.
- **The screen names the mode anywhere.** That is claim 3 failing and a bug: the two offline
  guards did not cover whatever produced it, and the guard is what should be extended first.
