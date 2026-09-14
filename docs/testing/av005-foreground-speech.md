# AV-005: Foreground speech on an Android emulator

- Issue: [#5 — Investigate foreground speech on an Android emulator](https://github.com/BrockBadeaux14/AnkiVoice/issues/5).
- Result: **constrained go**, with two explicit constraints: no live transcript was
  obtained anywhere in this spike, and the recognizer ends a turn about 1.1 seconds
  after capture opens when no speech has begun, which is too fast for a learner who
  pauses to think.
- Status: ready for review; this report does not accept the issue or advance
  dependent work.
- Investigated September 14, 2026. Dependency #1 is closed; the issue had no
  discussion comments.

The whole fixed matrix was run: sixteen scenarios, 38 recorded turns, including
the twelve-turn foreground loop. **Every turn was driven over adb by the
investigator with no voice present**, so the prompt → listen → *transcript* step
is demonstrated only as far as "capture opens and the recognizer answers". Zero
transcripts were produced, and none of this is evidence that speech recognition
works on this image. Every run is labelled `operated_by: investigator_adb` and
`voice_source: none` in the evidence, and the validator refuses to count such a
turn as live-speech evidence.

An operator-driven path exists for exactly that gap: the same suite runs from
Android Studio, where a person speaks each answer and attests it. See the
[runbook](av005/runbook.md).

## Pinned environment

| Component | Tested value |
| --- | --- |
| Host | macOS 26.6.2 (25G83), ARM64 |
| Disposable AVD | `AnkiVoice_AV005`, `medium_phone`, 1080×2400 at 420 dpi |
| Android image | API 36 / Android 16, `google_apis_playstore;arm64-v8a` |
| Android build | `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094:user/release-keys` |
| Emulator / adb | 37.1.11.0 (15917651) / 37.0.1 (15733141) |
| Host audio | Host default input forwarded via `adb emu avd hostmicon on`; guest media volume 0 during these runs |
| Text to speech | `com.google.android.tts`, `googletts.google-speech-apk_20241125.02_p2.702443970`, voice `en-US-language`, local, 9 local en-US voices |
| Recognition services | `com.google.android.as/…AiAiSpeechRecognitionService` and `com.google.android.tts/…GoogleTTSRecognitionService` |
| Default recognizer | `com.google.android.tts/…GoogleTTSRecognitionService` |
| Network | Recognition attempted with `EXTRA_PREFER_OFFLINE` false; connectivity recorded per run |
| Locale | en-US, matching the AV-002 fixtures |
| Probe build | AGP 9.1.0, Gradle 9.3.1, compile SDK 36, target SDK 35, JBR 25.0.2 |
| Probe package | `org.ankivoice.av005`, `RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE` |

The AVD was created for this spike, used no Anki collection and stayed signed out
of Google. Versions and hashes are pinned in
[environment.json](av005/evidence/environment.json).

**Host microphone versus injected input.** All turns used the live microphone
path: `SpeechRecognizer.startListening` on the default audio source, with the
emulator forwarding the host's default input device. No audio was injected into
the recognizer, and no `EXTRA_AUDIO_SOURCE` was used. The recognizer reported
non-zero input level (`rms_peak` 10) on most turns, and with forwarding **off**
the same scenario ends in `ERROR_NO_MATCH` within about a second with no level at
all — so the path is genuinely live. What is missing is a human voice on it, not
the path.

## Results against the acceptance criteria

| Criterion | Result |
| --- | --- |
| Environment, host audio, engine/recognizer versions, locale, network, reproduction; host-microphone versus injected input distinguished | **Met.** Pinned above; the live-versus-injected distinction is recorded per run and enforced by the validator. |
| Attempt the twelve-turn loop; per-turn playback, capture start, transcript, elapsed times, success/failure, manual intervention; only `Prompt` spoken; capture after playback plus a recorded settling interval; no prompt echo accepted as an answer | **Attempted in full, 12/12 turns.** Playback 2,707–7,393 ms; settling interval requested 400 ms, measured 401–404 ms on every turn; capture opened on every turn; capture 1,090–1,163 ms, median 1,144 ms. **All twelve returned `ERROR_NO_MATCH` and no transcript**, because no one was speaking. No turn's transcript was the prompt echoed back. |
| Repeat/cancel, 2 s and 5 s thinking pauses, no-speech timeout, premature cutoff, endpoint behaviour; propose settings for #13 | **Met, and it found a defect.** See [the thinking-pause result](#the-turn-ends-before-a-learner-can-answer) and [settings](#settings-to-carry-forward). |
| Microphone deny/revoke, recognizer unavailable/busy, network loss, cancellation with a late callback; capture stops and cleans up; errors distinct from transcripts; no Anki, no rating | **Met.** Each produced a distinct outcome with a null transcript; see the table below. No scenario touched Anki or produced a rating. |
| Background and lock during playback and capture, explicit resume; stale callbacks must not advance a turn | **Met.** Backgrounding during playback halted both turns (`left_foreground`); locking during capture halted the turn and the late `ERROR_CLIENT` was recorded stale with `advanced_turn: false`. |
| Audio focus / route interruption, reproducible; physical speaker, Bluetooth and real calls marked unverified | **Met with limits.** An emulated GSM call is recorded; physical speaker, Bluetooth and real phone-call behaviour are **unverified**. |
| Publish results and counts; exit go / constrained go / no-go; record settings for #13/#26 and risks for #23 | **Met.** See [Exit decision](#exit-decision). |

## Measured

<!-- av005:results:begin -->

Measured over 38 recorded turns. 0 returned a transcript, of which 0 came from a person speaking into the microphone.

| Scenario | Operated by | Turns | Transcripts | Median capture | Median settle | Errors seen | Stale callbacks |
| --- | --- | ---: | ---: | ---: | ---: | --- | ---: |
| 1. Environment check | adb, no voice | 0 | 0 | — | — | — | 0 |
| 2. Twelve-turn foreground loop | adb, no voice | 12 | 0 | 1144 ms | 402 ms | ERROR_NO_MATCH | 0 |
| 3. Two-second thinking pause | adb, no voice | 4 | 0 | 1126 ms | 402 ms | ERROR_NO_MATCH | 0 |
| 4. Five-second thinking pause | adb, no voice | 4 | 0 | 1126 ms | 402 ms | ERROR_NO_MATCH | 0 |
| 5. No-speech timeout | adb, no voice | 2 | 0 | 1148 ms | 402 ms | ERROR_NO_MATCH | 0 |
| 6. Prompt echo guard | adb, no voice | 2 | 0 | 1174 ms | — | ERROR_NO_MATCH | 0 |
| 7. Repeat the prompt | adb, no voice | 2 | 0 | 3086 ms | 403 ms | ERROR_NO_MATCH | 0 |
| 8. Cancel the turn | adb, no voice | 2 | 0 | — | — | — | 0 |
| 9. Cancellation with a late callback | adb, no voice | 2 | 0 | — | 403 ms | — | 2 |
| 10. Recognizer busy | adb, no voice | 1 | 0 | 539 ms | 403 ms | ERROR_NO_MATCH | 0 |
| 11. Recognizer unavailable | adb, no voice | 1 | 0 | 2 ms | 403 ms | ERROR_TOO_MANY_REQUESTS | 0 |
| 12. Microphone permission denied | adb, no voice | 1 | 0 | 13 ms | 403 ms | ERROR_INSUFFICIENT_PERMISSIONS | 0 |
| 13. Network unavailable | adb, no voice | 1 | 0 | 1074 ms | 402 ms | ERROR_NO_MATCH | 0 |
| 14. Background during playback and capture | adb, no voice | 2 | 0 | — | — | — | 0 |
| 15. Screen lock during a turn | adb, no voice | 1 | 0 | 1141 ms | 403 ms | — | 1 |
| 16. Audio focus interruption | adb, no voice | 1 | 0 | 1168 ms | 402 ms | ERROR_NO_MATCH | 0 |

Audio-focus losses caused by the app's own text to speech and recognizer: 31 recorded, 916–1964 ms. Losses during the deliberate interruption scenario: 1 recorded, 5597 ms.

<!-- av005:results:end -->

Raw per-scenario evidence: [matrix/](av005/evidence/matrix/).

### The turn ends before a learner can answer

This is the most consequential measurement in the spike.

| Scenario | Learner pause asked for | Capture window measured |
| --- | --- | --- |
| Twelve-turn loop | none | 1,090–1,163 ms |
| Two-second thinking pause | 2,000 ms | 1,111, 1,121, 1,132, 1,220 ms |
| Five-second thinking pause | 5,000 ms | 1,101, 1,116, 1,135, 2,049 ms |
| No-speech timeout | none | 1,137, 1,159 ms |

With no speech, the recognizer closes the turn after roughly 1.1 seconds and
reports `ERROR_NO_MATCH` — **not** `ERROR_SPEECH_TIMEOUT`. In eight of eight
thinking-pause turns the window closed before the requested pause had elapsed. A
learner who takes two seconds to think would lose the turn on this image. The
requested `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` (1,200 ms),
`…POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS` (900 ms) and
`…MINIMUM_LENGTH_MILLIS` (1,000 ms) did not produce the behaviour they describe.
This report does not claim those settings take effect.

`onBeginningOfSpeech` never fired in any run, which is consistent with no speech
being present; it is not evidence that speech would fail to be detected.

### Failure matrix

| Case | Outcome | Transcript |
| --- | --- | --- |
| Microphone permission revoked | `ERROR_INSUFFICIENT_PERMISSIONS` after 13 ms | null |
| Recognizer unavailable (default service pointed at a missing component) | `ERROR_TOO_MANY_REQUESTS` after 2 ms, while `isRecognitionAvailable()` still reported **true** | null |
| Second overlapping recognition | No busy error: the second recognizer got its own `onReadyForSpeech` and `ERROR_NO_MATCH`; the primary turn's capture shortened to 539 ms | null |
| Airplane mode (no validated internet, recorded in the evidence) | `ERROR_NO_MATCH` after 1,074 ms — indistinguishable from silence online | null |
| App cancels capture 900 ms in | Turn `cancelled`; late `ERROR_CLIENT` recorded stale, `advanced_turn: false` | null |
| Operator cancels mid-turn | Turn `cancelled`, capture stopped, no callback accepted | null |
| Operator repeats the prompt | Prompt re-spoken, `repeats: 1`, fresh capture with the settling interval honoured | null |
| Home pressed during playback | Both turns `halted` / `left_foreground`; text to speech stopped; capture never opened | null |
| Screen locked during capture | Turn `halted` / `left_foreground`; late `ERROR_CLIENT` stale | null |
| Emulated incoming call during playback | 5,597 ms audio-focus loss; **the activity was not paused** and the turn ran to completion | null |

Errors never carried a transcript in any of the 38 turns, and no stale callback
advanced a turn anywhere in the matrix. The validator checks both.

## Findings that constrain #13, #23 and #26

**#13 and #26 must own the capture window.** The recognizer's endpointing closes
the turn about 1.1 seconds after capture opens when the learner has not started
speaking, and the documented silence-length extras did not change that. A session
that hands turn length to the recognizer will cut learners off. Re-arm capture, or
run a longer app-owned window, and treat `ERROR_NO_MATCH` as "nothing heard yet"
rather than "the learner said nothing".

**`ERROR_NO_MATCH` is heavily overloaded.** It was returned for genuine silence,
for the host microphone being disconnected, and for a total loss of network. An
app cannot tell those apart from the error code, so it must not turn
`ERROR_NO_MATCH` into a learner-facing "I didn't catch that" without other
evidence — and must never convert it into a rating. This mirrors #4's conclusion
that an acknowledgement is not proof.

**Availability checks and error codes mislead.** With the default recognition
service pointed at a missing component, `isRecognitionAvailable()` and
`isOnDeviceRecognitionAvailable()` both still reported `true`, and the failure
surfaced as `ERROR_TOO_MANY_REQUESTS` — a code that invites a backoff-and-retry
loop that would never succeed. Treat an immediate capture failure as "recognizer
unusable, pause and tell the user", not as a rate limit.

**There is no busy signal to rely on.** Two `SpeechRecognizer` instances ran
concurrently without `ERROR_RECOGNIZER_BUSY`; the only visible effect was the
first turn's capture shrinking to 539 ms. #26 must serialise capture itself.

**Neither audio focus nor `onPause` alone detects an interruption.** Across the
matrix, 31 audio-focus losses caused by the app's own text to speech and
recognizer measured 916–1,964 ms, and the emulated incoming call measured
5,597 ms. Acting on focus loss without discrimination makes the loop cancel
itself — an earlier revision of this probe did exactly that. But the emulated call
did **not** pause the activity, so `onPause` alone missed it too. A duration
threshold would separate these observations, but it rests on a single interruption
sample and is a proposal for #13, not a finding. **#23 must resolve how
interruptions are detected before the foreground loop is built on either signal.**

**Changing the host audio device while the emulator runs kills the emulator.**
Switching the macOS default input mid-run produced
`coreaudio: Could not initialize record`, `kAudioHardwareIllegalOperationError`
and `Failed to create voice 'virtio-snd-mic0'`, after which the emulator exited.
See [emulator-audio-crash.log](av005/evidence/emulator-audio-crash.log). This is an
environment constraint for anyone reproducing the run, not a property of Android.

## Settings to carry forward

Starting points for #13, derived from the measurements above. The silence-length
extras demonstrably did not produce their documented behaviour on this image, so
these are values to request and then verify, not settings known to take effect.

| Setting | Proposed | Why |
| --- | --- | --- |
| Settling interval after playback | 400 ms | Honoured to within 4 ms on all 38 turns, and enough to keep the prompt out of the capture window. |
| App-owned maximum response window | 15 s, monotonic clock | The recognizer will not hold the turn open; the app must, as #4 concluded for review timing. |
| Re-arm after `ERROR_NO_MATCH` | Up to 3 times within the response window | The measured 1.1 s endpoint is shorter than a learner's thinking pause. |
| Complete silence length | 1,500 ms | Request it, measure it, and do not assume it applies. |
| Possibly-complete silence length | 1,000 ms | Kept below the complete value so a pause inside an answer does not finalise it. |
| Interruption detection | Activity lifecycle **plus** focus-loss duration | Neither alone caught the emulated call; see the finding above. |
| Rating on a failed turn | None | No error state in this matrix carries enough information to justify one. |

## Limits of this evidence

- **No live transcript was obtained.** Every run was investigator-driven with no
  voice, so recognition accuracy, real endpointing against a speaking learner, and
  the repeat/cancel flows as a person would use them remain unmeasured. Nothing
  here should be read as speech recognition having been demonstrated.
- The prompt-echo scenario ran with the guest media volume at 0, so it verifies
  only that opening capture during playback does not let prompt text become the
  answer in software. **Acoustic** echo rejection is unverified.
- Emulator evidence does not establish physical-device behaviour. Physical
  speaker and microphone characteristics, Bluetooth routing and real phone-call
  interruption are **unverified**; the emulated GSM call is not the same thing.
  These are evidence limits, not prerequisites for this spike.
- One AVD and one system image were used, as the issue bounds. The alternative
  recognizer allowance was not needed.
- The default recognition service on this image is provided by the text-to-speech
  package. A device where `com.google.android.as` is the default may differ.
- No provider selection is implied. Provider choice and cost belong to #6, and the
  application framework to #23; a disposable probe decides neither.

## Exit decision

**Constrained go.**

The foreground loop's mechanics hold up on this emulator: text to speech is local
and reliable, capture opens on every turn after a settling interval that is
honoured to within 4 ms, the live host-microphone path delivers audio to the
recognizer, every failure in the fixed matrix produced a distinct outcome with a
null transcript, backgrounding and locking halt a turn safely, and no stale
callback advanced a turn in 38 turns.

It is constrained, not a clean go, because:

1. **No live transcript was ever produced in this spike.** The loop is proven up to
   the recognizer's answer, not through it. #13 and #26 must not treat recognition
   as demonstrated until an operator run produces transcripts.
2. **The recognizer's own endpointing is unusable for study.** It closes the turn
   about 1.1 seconds in, before a learner who pauses has begun, and the documented
   settings did not change it. The app must own the capture window.
3. **Interruption detection is unresolved** and is a risk #23 must close.

This becomes a **no-go** if an operator run yields no transcripts on the live
microphone path, since that would make the demo loop unbuildable on this image.
To settle it, run the [runbook](av005/runbook.md) and then:

```sh
python3 tools/av005-probe/validate_evidence.py \
  docs/testing/av005/evidence/matrix/*.json docs/testing/av005/evidence/operator-run.json --write
```

The validator will count only the human-operated turns as live-speech evidence.

## Validation and evidence

```sh
cd tools/av005-probe && ./gradlew :app:assembleDebug
python3 tools/av005-probe/validate_evidence.py docs/testing/av005/evidence/matrix/*.json
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python -m pip check
git diff --check
```

The validator checks fixture-prompt identity, that capture followed playback plus
the settling interval, that an error never carries a transcript and a success
never carries an error, that no transcript was the prompt echoed back, that no
stale callback advanced a turn, that every run records who operated it, and that a
transcript is only counted as live speech when a person operated the run and
attested to speaking. It re-checks captured evidence; it does not rerun the
emulator.
