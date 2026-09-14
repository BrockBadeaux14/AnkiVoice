# Reproduce AV-006

Read the [decision and limits](../../decisions/0006-speech-and-grading-providers.md)
first. These disposable tools never select or rate Anki cards.

## Validate retained evidence

From the repository root, using the AV002 `.venv` setup:

```sh
python3 tools/av006-probe/validate_evidence.py
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python -m pip check
git diff --check
```

The provider tests and validator use only the standard library: no credentials,
network or emulator required. `validate_evidence.py --write` regenerates the
summary and per-case table after validating source evidence. Ordinary validation
detects drift without modifying them.

## Native probe

Prerequisites: Android Studio JBR, SDK platform/build tools 36.0.0, and
`system-images;android-36;google_apis_playstore;arm64-v8a` revision 7.
Create the dedicated AVD only if its name does not already exist; do not reuse
a personal AVD. Keep it signed out of Google.

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager" create avd \
  --name AnkiVoice_AV006 \
  --package 'system-images;android-36;google_apis_playstore;arm64-v8a' \
  --device medium_phone
"$ANDROID_SDK_ROOT/emulator/emulator" -avd AnkiVoice_AV006 -port 5586 \
  -no-window -no-boot-anim -no-snapshot -timezone America/Chicago
```

In another terminal, wait for boot to return `1` and verify the exact AVD name
before installation. Disable host microphone input so an engine ignoring audio
injection cannot capture private/ambient speech.

```sh
adb -s emulator-5586 shell getprop sys.boot_completed
adb -s emulator-5586 emu avd name
adb -s emulator-5586 emu avd hostmicon off
adb -s emulator-5586 shell cmd package query-services --brief -a android.intent.action.TTS_SERVICE
adb -s emulator-5586 shell cmd package query-services --brief -a android.speech.RecognitionService
bash tools/av006-probe/build.sh
adb -s emulator-5586 install --no-incremental build/av006/probe/av006-probe.apk
adb -s emulator-5586 shell pm grant org.ankivoice.av006 android.permission.RECORD_AUDIO
adb -s emulator-5586 shell am start -W -n org.ankivoice.av006/.ProbeActivity --es op tts
```

TTS serially synthesizes and plays four prompts, then synthesizes the 12 answer
WAVs. When `files/result.json` exists, archive the completed/failed operation to
a **new** host directory. Inspect its errors and listen to each prompt:

```sh
mkdir build/av006-rerun
adb -s emulator-5586 exec-out run-as org.ankivoice.av006 cat files/result.json > build/av006-rerun/native-tts.json
adb -s emulator-5586 exec-out run-as org.ankivoice.av006 tar -cf - files > build/av006-rerun/native-files.tar
```

After saving each result, clear only that result before the next operation:

```sh
adb -s emulator-5586 shell am force-stop org.ankivoice.av006
adb -s emulator-5586 shell run-as org.ankivoice.av006 rm files/result.json
```

Run each operation below separately, waiting for its result and repeating the
archive/clear procedure with a unique output name between operations:

```sh
# Capability query: code 14 did not establish that recognition was unusable.
adb -s emulator-5586 shell am start -W -n org.ankivoice.av006/.ProbeActivity --es op support
# Bounded download investigation; timed out after 55 seconds in the recorded run.
adb -s emulator-5586 shell am start -W -n org.ankivoice.av006/.ProbeActivity --es op download
# Offline-preferred preflight: first case returned code 13.
adb -s emulator-5586 shell am start -W -n org.ankivoice.av006/.ProbeActivity --es op stt
# Selected configuration: permits online recognition of the same answer audio.
adb -s emulator-5586 shell am start -W -n org.ankivoice.av006/.ProbeActivity --es op stt --ez prefer_offline false
```

The final probe continues on no-match/speech-timeout, stopping on other errors.
The historical probe initially stopped on the first no-match; the run continued
with `--ei start_index 8` after narrowing that stop rule. `--ei end_index N`
can bound a continuation. Count partial runs together: no more than two attempts
per native candidate/case, or 48 total native/cloud STT attempts. Do not rerun
successful cases to conceal failures. Additional metadata fields were added
during the investigation, so earlier raw captures have fewer fields.

The APK contains no key and requests neither Internet nor Anki permission.
The external recognition service can still use the network. Android framework
and lifecycle/product decisions remain outside this probe.

## Cloud measurements

The user selected free models only. The tested file was
`$HOME/.config/ankivoice/openrouter.txt`, outside the repository. It contains only
the key and has mode 600. The runner defaults to `openrouter.key`; supply
`--key-file` for other names. Never put the key itself in arguments or Git.

The runner checks public endpoint prices and key limits, pins a provider,
disables fallback, and sets price ceilings to zero. It writes per-attempt results
and stops on provider, transport or cost-verification failure. One invocation
attempts at most one pass of the fixed corpus.

```sh
python3 tools/av006_providers.py grade-a \
  --key-file "$HOME/.config/ankivoice/openrouter.txt" --output build/av006-rerun/grade-a.json
python3 tools/av006_providers.py grade-b \
  --key-file "$HOME/.config/ankivoice/openrouter.txt" --output build/av006-rerun/grade-b.json
python3 tools/av006_providers.py stt \
  --key-file "$HOME/.config/ankivoice/openrouter.txt" --output build/av006-rerun/cloud-stt.json
```

Cloud STT uses retained canonical WAVs in `docs/testing/av006/evidence/audio/`.
Newly generated speech on another installation is a new experiment, not bitwise
reproduction. The current grader instruction is pass 2. Pass 1 used retained
`grading-prompt-v1.txt` and the field `transcript` instead of `learner_answer`.
Expected labels never go into provider requests.

`build/av006/cloud-ledger.jsonl` reserves attempts before sending, including errors
and interrupted calls. It locks concurrent runs and enforces two attempts per
candidate/case, 48 per role, 48 cloud requests per UTC day, and four-second spacing.
**This comparison exhausted both graders' two-pass allowance.** Grading rerun
commands will refuse more attempts on this machine. A new comparison after review
requires a rebaselined experiment budget, not deletion of the ledger.

Raw STT `status=success` means a nonempty completion with normal finish reason,
not verified transcription. The separate evidence assessment classifies both
requests for an audio file as unusable. Check semantics and malformed/truncated
outputs as well as HTTP status. No result writes an Anki rating.

## Validation performed

September 14, 2026: native APK built/installed and TTS/STT operations exercised;
19 Python tests passed; evidence validator passed; `pip check` reported no broken
requirements; `git diff --check` passed. Remote CI was not run. See the decision
report for the explicit runtime, quality and physical-device limitations.
