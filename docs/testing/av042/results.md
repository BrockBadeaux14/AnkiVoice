# AV-042: Live human voice input works in the narrowed scope

Issue [#51 — Resolve live speech capture and interruption blockers](https://github.com/BrockBadeaux14/AnkiVoice/issues/51).
Branch `codex/av042-live-speech-blockers`. September 15, 2026, America/Chicago.

**Result: the owner’s live voice was transcribed correctly as “green blue red” and
“five” on the MacBook microphone/API 36 ARM64 emulator route.** The owner confirmed
both displayed results in this task. This meets the [narrowed human-input scope](scope.md).
It is a disposable speech probe result, not a completed Anki study application.
Production integration remains with the speech/session work.

The owner explicitly instructed: “I need to just ship a MVP, the only case that needs
to work is the human voice imput working. Ignore other requirements for now.”
That superseded the original interruption/matrix gate and its stop rule for this
work. The original two-turn no-go evidence is preserved; no failed call is relabelled
as a passing test. Broader interruption behavior is deferred, not verified.

## Downstream unblock decision

**Go for the owner's narrowed human-voice MVP: this report satisfies the AV-042
capability prerequisite for both [#26 — AV-025: Integrate mobile speech and audio routing](https://github.com/BrockBadeaux14/AnkiVoice/issues/26)
and [#13 — AV-012: Integrate transcription and answer boundaries](https://github.com/BrockBadeaux14/AnkiVoice/issues/13).**
The owner explicitly requested that this report allow both tasks to be unblocked.
This is the current scope decision, not a claim that the original comprehensive
go/constrained-go thresholds passed.

When this report/PR is reviewed and accepted, #26 and #13 may move from Backlog to
Ready and the native blocked-by-#51 links can be cleared. Their other prerequisites
are satisfied: #6 and #45 are closed, and #24 is closed with PR #52 merged. #7's
contracts are also accepted. Neither task depends on completion of the other;
implement against the shared contracts/fakes and leave session integration to #14/#27.
Keep #51 In review until acceptance; PR creation alone does not accept the work.

The earlier issue wording requiring #51 to prove an interruption signal, all four
prompt types, the 12-turn threshold, echo checks, or the full interruption matrix
is superseded for this MVP. Those deferred checks must not be reintroduced as
prerequisites for starting or accepting the narrowed #26/#13 implementation.
Their results remain limitations, and no safe-call-handling claim is authorized.

### Implementation handoff

The following values are selected as the initial MVP implementation policy. Keep
the distinction between measured observations and engineering limits: accepting
these limits permits implementation; it does not make unexercised cases pass.

| Decision | Selected MVP behavior | Evidence / ownership |
| --- | --- | --- |
| Native route | `com.google.android.tts`, version `googletts.google-speech-apk_20241125.02_p2.702443970`; `GoogleTTSRecognitionService`; `en-US`; `EXTRA_PREFER_OFFLINE=false`. Local TTS voice `en-US-language`. | Same pins as AV-006; owner-confirmed live results in attempts 7/8. #26 owns platform objects and runtime resolution. |
| Capture | App-owned MIC `AudioRecord`, mono PCM16 at 16 kHz, passed through `EXTRA_AUDIO_SOURCE`; segmented session ends at pipe closure. | Working probe route, to be implemented in #26. No new provider or phone-state permission. |
| Playback to input | Complete Prompt playback, settle for at least 400 ms, then wait for explicit Start answer. Thinking is outside active capture. | Probe used the settling interval; both successful trials used explicit Start answer. #26 owns transport ordering; #13 owns answer state. |
> **Amended September 17, 2026 by [AV-050](https://github.com/BrockBadeaux14/AnkiVoice/issues/81).**
> Two of the selections below no longer describe the shipped app, and the table is left as
> the record of what AV-042 measured and chose rather than rewritten. The microphone now
> **opens itself** once per attempt, after that attempt's prompt playback settles, so
> "from Start answer" is no longer how the window starts — Start answer remains a touch
> control for starting early. The unbounded thinking time that tap bought is replaced by a
> **15,000 ms recall pre-roll** in front of the window, and the **answer window was cut to
> 5,000 ms**, because it now bounds speaking alone and AV-050's endpointing normally ends a
> capture about a second after the learner stops. Finalization, the trailing silence and the
> zero re-arm cap are unchanged. Current values: `AnswerLimits` and
> [AV-050's results](../av050/results.md).

| Answer window | Default and maximum active capture: 15,000 ms from Start answer, using a monotonic clock. Do not consume this budget while the learner is thinking. | Selected initial limit; automatic expiry observed on diagnostic attempts 1/2. No claim that 15 seconds is an optimized recall duration. #13 owns the deadline; #26 stops capture on request. |
| Done and finalization | Done or capture expiry stops the microphone. #26 sends 500 ms of trailing silence, then closes the pipe. Finalization has a separate 5,000 ms deadline from Done/expiry, including that trailing silence. | Successful Done-to-final times: 690 ms and 687 ms. The five-second ceiling is an engineering bound; forced finalization expiry remains unverified on-device. |
| Retry cap | Zero automatic re-arms and zero additional recognizer attempts inside one answer window. An explicit Try again opens a new bounded window for the same card with a new attempt/transcript revision. | Conservative initial policy; the probe only repeated on explicit Start. No automatic retry accuracy or shared-window retry behavior was validated. #13 owns the window and invalidation. |
| Failure / fallback | Preserve the card on no-match, error, timeout or Cancel. Offer explicit Try again, typed transcript correction or manual self-grade; never infer a rating. Partial text cannot start grading. | #13 owns this product behavior and fake-based verification; it is not claimed as implemented in the probe. |
| Lifecycle scope | Retain explicit Cancel and basic foreground cleanup/token invalidation. Defer detection of all external calls, audio-route combinations and the broader live interruption matrix. | #26 implements basic transport cleanup; #13 rejects stale revisions. Existing call failures remain recorded. |

For #26, the acceptance target is the foreground Prompt → explicit capture → final
transcript flow using this route, plus its basic serialization, cancellation and
error handling. For #13, it is the bounded answer/transcript policy above, including
reviewable/editable text and fake-driven stale-result/deadline checks. Neither may
turn silence, recognition failure or a final transcript into an automatic review.
The full multi-condition human matrix is deferred for both tasks; the two confirmed
phrases establish this prerequisite, not universal recognition reliability.

## What changed and why

A separate `org.ankivoice.av042` probe owns `AudioRecord` and streams the real
microphone samples into the pinned `SpeechRecognizer` using its external-audio
pipe. An explicit Start answer separates thinking from recording. Done stops the
microphone, supplies 500 ms of trailing silence, and closes the stream. The app
waits up to five seconds for a result and displays the returned transcript. It
never creates a transcript from an error or automatically starts another turn.
The active recording limit remains 15 seconds for the probe.

Question audio uses the pinned local TTS voice, synthesized to a file and played
through MediaPlayer. The prompt was initially too quiet; at the owner's request,
Mac output rose to 60% and guest media volume to 9/15.

Two distinct configuration problems were found:

- **Emulator audio-device failure.** Attempts 5/6 logged `pcm_prepare` I/O failures.
  The goldfish HAL then substituted a 220 Hz tone for the built-in microphone.
  Attempt 6's saved PCM matched that tone; a normal recording-state Boolean and
  unsilenced callback therefore did not establish live microphone input. See
  [HAL errors](evidence/audio-hal-errors.txt) and [PCM analysis](evidence/pcm-analysis.json).
  The [upstream source](https://android.googlesource.com/device/generic/goldfish/+/refs/heads/main/hals/audio/device_port_source.cpp)
  explains that fallback. The emulator was restarted after host audio setup.
- **Probe recognition setting.** The initial probe mistakenly set
  `EXTRA_PREFER_OFFLINE=true`. The successful version corrects it to `false`, the
  already accepted AV-006 online-permitted mode, using the same service/version.
  No new paid route or credential was introduced.

These corrections were applied together. The results do not isolate their separate
accuracy contributions or establish that every historical AV-040 failure had either
cause. Host input gain was set to 40% before reboot and read 84% at teardown; the
intervening timing/source was not observed. No fixed-gain claim is made.

## Every attempted turn

All eight new attempts are in the [cumulative ledger](evidence/live-ledger.json).
The AV-040 app still retains its exhausted count of 28; no old evidence was changed.

| Attempt | Requested case/phrase | Observed result |
| ---: | --- | --- |
| 1 | Automated raw microphone diagnostic | Recorder opened; mostly zero samples; 15-second deadline cleaned up. A manually triggered call was canceled too quickly for the intended controlled dwell. Later Telecom history shows a roughly 55 ms ringing interval; it is not a passing call check. |
| 2 | Automated capture with recognizer and controlled call | Telecom independently confirmed ringing, but no interruption signal reached the app. Capture continued to its deadline, then no-match. Original-scope no-go. |
| 3 | Five | No-match. In-app operator attestation records that the displayed answer was spoken. Prompt confirmed audible but quiet. |
| 4 | Green, blue, red | Raw transcript “You red”; incorrect. Prompt still too quiet. |
| 5 | Green, blue, red | No-match; audio HAL logged fallback to generated tone. |
| 6 | Green, blue, red | No-match; HAL failure and the 220 Hz PCM independently agree. |
| 7 | Green, blue, red | **“green blue red”**, confirmed by owner; 690 ms from Done event to final. |
| 8 | Five | **“five”**, confirmed by owner; 687 ms from Done event to final. |

Attempts 7/8 used the restarted AVD, online-permitted recognition and 500 ms trailing
silence. Their confirmations came through the user's replies to the live-test
instructions; the successful ledger entries do not claim in-app button attestations.
The initial expectation-to-transcript mismatch and all no-match results remain visible.
Six human-operated attempts produced two owner-confirmed correct results across
configuration changes. The last configuration passed both tested phrases; this is
not a statistical reliability estimate or four-prompt/12-turn matrix pass.

## Reproduce and inspect

Follow the [runbook](runbook.md). [Final configuration](evidence/final-configuration.json)
records the measured APK/source hashes, service/version, host volume at teardown,
and capture format. The pinned fingerprint and package match AV-040.
[Successful event log](evidence/successful-live-events.txt) and
[Five screenshot](evidence/five-result.png) show the result independently of this text.
Raw diagnostic audio stays under ignored `build/av042/`; it is not committed.

The probe has no Anki access, grading, reviews or provider credentials. Production
files under `android/` are unchanged. The app is force-stopped and microphone
forwarding is disabled after validation; the AVD is left available for review.

## Validation and limits

- Debug APK builds and signature verification passes; Java 17 bytecode, compile SDK
  36, minimum 33, target 35, build tools 36.0.0.
- 21 zero-audio Java checks cover the disposable gate's deadlines, stale-result
  rejection, invalidation and cap. They are not live interruption evidence.
- The [historical validator](evidence/validation.json) preserves the initial no-go;
  the [live validator](evidence/live-validation.json) verifies the separately narrowed
  successful human-input result. Integrity success does not waive capability failures.
- Python tests include mutations rejecting fabricated transcripts, operator
  confirmations, call detection, erased failures, bad deadlines and missing cleanup.
  Final suite count/result is recorded in [verification](evidence/verification.json).

Not tested under the final setup: “Six,” negation, all four prompt types, a full study
session, real devices, Bluetooth, real phone calls, interruption safety, automatic
finalization timeout, or the full #29 acceptance run. The owner deferred those
requirements for this task. No phone-state permission was added. A pull request is
authorized by the owner's follow-up; merge, deployment and starting another task
remain separate actions.
