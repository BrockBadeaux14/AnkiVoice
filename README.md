# AnkiVoice

Voice-first review for [Anki](https://apps.ankiweb.net/) flashcards on Android. AnkiVoice
reads a card's question aloud, listens to your spoken answer, grades it on the device
first and with an AI grader only when the rules cannot decide, and writes the review to
AnkiDroid only after you explicitly confirm the rating. Nothing is ever rated for you.

It is a single-owner coursework project built and verified against a pinned Android
emulator. Every claim in the documentation is backed by a recorded run or a test, and the
documentation says plainly what has not been verified.

## How it works

- **Your cards stay in Anki.** AnkiVoice studies a dedicated `VoiceQA` note type through
  AnkiDroid's public content provider. It never opens the collection file, never syncs, and
  writes exactly one thing: the review you confirm.
- **Native speech, on device.** The question is spoken by Android's text-to-speech engine
  and your answer is captured by Android's recognizer through an app-owned microphone pipe,
  with a bounded answer window, an explicit Done, and a Try again that never re-arms on its
  own.
- **Rules first, AI second, you last.** Deterministic rules on the device match your
  transcript against the card's answers with no network call. Only when they cannot decide
  is the transcript sent to a pinned grading model, free route first and a budgeted paid
  route only if the free one fails, and only if you have entered your own OpenRouter key
  and acknowledged what is sent. Every suggestion is advisory: you confirm, change or name
  the rating yourself, and a transcript edit retires any earlier suggestion.
- **One write, journalled.** A confirmed rating is written once, after freshness checks, and
  verified by reading the card back. The intent is recorded in an app-private journal before
  the write and settled from the writer's own evidence, so a crash mid-write is reconciled on
  the next start rather than retried or assumed. An unconfirmable write is reported to you,
  never resubmitted.
- **Interruptions stop, they do not guess.** Leaving the app or locking the screen ends the
  session and releases the microphone; you reload, and pending writes are reconciled first.

## Repository layout

| Path | Contents |
| --- | --- |
| [`android/`](android/README.md) | The Kotlin app: one Gradle build, five modules, and the module-by-module record of how it was built and verified |
| [`docs/decisions/`](docs/decisions/) | The platform, provider and implementation decisions, with the measurements behind them |
| [`docs/contracts/`](docs/contracts/av007-session-contracts.md) | The five session contracts, the card-identity and capability rules, and the review lifecycle |
| [`docs/testing/`](docs/testing/) | Results and runbooks for every verified piece, with the retained evidence beside them |
| [`tools/`](tools/) | The Python reference binding of the contracts, fixture generation, and the emulator drivers and evidence validators |
| [`tests/`](tests/) | The Python suite: the reference binding's scenarios, the drift guards that hold the Kotlin port to it, and the evidence validators |
| [`fixtures/`](fixtures/) | The `VoiceQA` note type and the synthetic grading and collection fixtures |

## Building

Requirements: a JDK 17 or newer to run Gradle, the Android SDK with `platforms;android-36`
and `build-tools;36.0.0` (point `ANDROID_HOME` at it or set `sdk.dir` in
`android/local.properties`), and Python 3.13 for the fixtures and validators.

```sh
cd android && ./gradlew checkModuleBoundaries :core:test assembleDebug
```

```sh
cd android && ./gradlew :ankidroid:testDebugUnitTest :provider:testDebugUnitTest :speech:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
```

```sh
python3 -m venv .venv && .venv/bin/python -m pip install -r requirements-fixtures.txt && .venv/bin/python -m unittest discover -s tests
```

No test opens a network connection or needs an emulator. Continuous integration runs the
same commands on every push. Every version is pinned; see the
[Android build notes](android/README.md#pins) for the pins and the reasons behind them.

## Running the demo

The demo is the study screen on an Android emulator, with a disposable collection so
nothing you own is touched. It takes one emulator and about fifteen minutes the first time.

1. **Create the emulator.** Use the pinned image, signed out of Google so the speech engine
   stays at its pinned version, and boot it with host audio so the guest microphone hears
   yours:

   ```sh
   export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
   echo no | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager" create avd --name AnkiVoice_AV005 --package 'system-images;android-36;google_apis_playstore;arm64-v8a' --device medium_phone
   ```

   ```sh
   "$ANDROID_SDK_ROOT/emulator/emulator" -avd AnkiVoice_AV005 -port 5588 -allow-host-audio -no-snapshot -no-boot-anim
   ```

   Once it has booted, turn the host microphone on and grant the app's microphone permission:

   ```sh
   adb -s emulator-5588 emu avd hostmicon
   ```

2. **Install AnkiDroid 2.24.1 and a disposable collection.** Generate the synthetic
   collection, then follow the [fixtures guide](docs/testing/voiceqa-fixtures.md) to import
   its `collection.colpkg` into AnkiDroid on the emulator and enable AnkiDroid's API under
   Settings → Advanced:

   ```sh
   .venv/bin/python tools/voiceqa_fixtures.py build --output build/voiceqa
   ```

3. **Build and install AnkiVoice.**

   ```sh
   cd android && ./gradlew :app:installDebug
   ```

   ```sh
   adb -s emulator-5588 shell pm grant org.ankivoice android.permission.RECORD_AUDIO
   ```

4. **Set up, on the app's setup screen.** Allow AnkiDroid access when asked, tap **Check
   setup** and then **Set up VoiceQA** if the note type is missing, and choose the
   `AV002 Baseline` deck. AI grading is optional: to try it, enter your own OpenRouter key,
   read the disclosure and turn it on. Without a key the rules grade exact matches and you
   rate everything else yourself.

5. **Study.** Tap **Start studying**, then on the study screen: **Play prompt** to hear the
   question, **Start answer** and speak, **Done**, and when the rating is announced tap
   **Confirm** or tap **Speak a command** and say "confirm". **Next card** continues.
   Everything else on the screen — Try again, Edit transcript, the ratings, Pause, Skip,
   Show answer, Repeat, Finish — is reachable by touch, and nothing but Confirm writes.

The same walk-through, with the ten turns that verify it and the emulator's known audio
faults, is the [study runbook](docs/testing/av026/runbook.md). Two notes from the recorded
runs: the emulator's audio backend exits after two or three microphone opens per boot, so
cold-boot between long sessions, and a capture that comes back empty is retried with
**Try again**, not treated as a wrong answer.

## Documentation

**Decisions**

- [Platform and pilot constraints](docs/decisions/0001-platform-and-pilot.md): Android
  first, the emulator as the test environment, and the pilot's scope.
- [Speech and grading providers](docs/decisions/0006-speech-and-grading-providers.md): the
  measured native-speech and grading-model comparison, the free-only route and the later
  budgeted paid fallback.
- [Android implementation](docs/decisions/0022-android-implementation.md): Kotlin over
  Flutter, the pinned build baseline and the module ownership.

**The session and its contracts**

- [Session contracts and review lifecycle](docs/contracts/av007-session-contracts.md), with
  [scripted transcripts](docs/contracts/av007/transcripts.md) of every state and failure mode
  against in-memory fakes.
- The [Android build notes](android/README.md) describe each layer: answer boundaries,
  rule-based and semantic grading, provider credentials and budgets, the speech transport,
  the session state machine, the journal, voice commands, the pre-commit exchange, and the
  study surface.

**Verification on the pinned emulator**, each with a results page and a runbook

| Area | Results |
| --- | --- |
| AnkiDroid review access and write safeguards | [access](docs/testing/av004-ankidroid-review-access.md) · [adapter](docs/testing/av024/results.md) |
| Speech in the foreground, live microphone, human voice input | [foreground speech](docs/testing/av005-foreground-speech.md) · [live microphone](docs/testing/av040-live-microphone.md) · [voice input](docs/testing/av042/results.md) |
| Shell, onboarding and note type provisioning | [shell](docs/testing/av023/results.md) · [provisioning](docs/testing/av039/results.md) |
| Card eligibility and skipping | [eligibility](docs/testing/av010/results.md) |
| Speech transport and recognizer confidence | [transport](docs/testing/av025/results.md) · [confidence](docs/testing/av044/results.md) |
| Session state machine, voice commands, journal and recovery | [session](docs/testing/av013/results.md) · [commands](docs/testing/av014/results.md) · [journal](docs/testing/av018/results.md) |
| Grading quality and the paid fallback | [evaluation](docs/testing/av017/results.md) · [paid fallback](docs/testing/av043/results.md) |
| Rating confirmation and the single write | [exchange](docs/testing/av019/results.md) |
| The study screen and the integrated flow | [study surface](docs/testing/av026/results.md) |

Work is tracked on the [project board](https://github.com/users/BrockBadeaux14/projects/2).

## Scope and limits

The current release studies one deck at a time, in the foreground, on Android, in English,
with the `VoiceQA` note type. Offline use, locked-screen study, general card compatibility,
direct sync and other platforms are out of scope. All device evidence comes from one pinned
emulator configuration; physical devices, Bluetooth audio and phone calls are unverified.
The emulator's own audio backend is unreliable after a few microphone opens per boot, and
the cause of some empty captures is not yet established; the app treats an empty capture as
something to retry, never as a wrong answer.
