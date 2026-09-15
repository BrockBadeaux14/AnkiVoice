# AV-040: Live microphone capture and foreground interruptions

- Issue: [#45](https://github.com/BrockBadeaux14/AnkiVoice/issues/45).
- **No-go. The bounded investigation is complete; the live route is not approved.**
- Branch: `codex/av040-live-microphone-interruptions`.
- Investigated September 15, 2026. #5/#6 were closed and their merged changes were
  ancestors of this branch; #45 had no discussion comments when inspected.

## Decision and blocker

The native route has not established safe foreground voice study. The proposed
interruption rule caught two emulated calls during playback and missed both
calls during capture. One missed call allowed a human transcript to be delivered.
A completed investigation does **not** satisfy the live-speech prerequisite of
[#13](https://github.com/BrockBadeaux14/AnkiVoice/issues/13) or
[#26](https://github.com/BrockBadeaux14/AnkiVoice/issues/26).

Only the disposable probe, evidence checks and documentation changed. There is
no production speech implementation, provider/account/payment change, injected
input, Anki collection access, rating or review write in this work. Earlier
AV-005/AV-006 raw evidence is preserved separately.

## Environment and scope

The [original preflight](av040/evidence/environment-preflight.json),
[headset resumption](av040/evidence/environment-resume.json), and
[MacBook return](av040/evidence/environment-macbook-return.json) record the setup.

| Component | Measured configuration |
| --- | --- |
| Host / AVD | macOS 26.6.2 (25G83), ARM64; existing `AnkiVoice_AV005`, serial `emulator-5588` |
| Image | API 36 / Android 16, ARM64 Google Play revision 7; `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094:user/release-keys` |
| Emulator / adb | 37.1.11.0 / 37.0.1 |
| Speech | `com.google.android.tts`, `googletts.google-speech-apk_20241125.02_p2.702443970`, code 210526444; default `GoogleTTSRecognitionService`; local `en-US-language` TTS |
| Locale / network | en-US, signed-out AVD, online recognition permitted, validated internet recorded per scenario |
| Probe | AGP 9.1.0, Gradle 9.3.1, compile SDK 36, target SDK 35, JBR 25.0.2 |
| Playback | Guest media 5/15; baseline prompts confirmed audible; later routing qualification below |

These match the speech/image pins in [AV-005](av005-foreground-speech.md) and
[AV-006](../decisions/0006-speech-and-grading-providers.md). Baseline used the
MacBook microphone/speakers. At resumption the owner selected WH-1000XM5 input
and output. After the original 24 follow-up attempts the owner requested MacBook
input/output; those defaults already matched when inspected, and the headphones
were absent. **The time of disconnection/change is unknown.** Do not attribute
every resumed turn to a continuously verified headset route. The owner confirmed
that prompts in echo attempts 23/24 were inaudible and that they did not speak.
Those attempts are not valid audible echo evidence.

The test AVD was restarted before the explicit MacBook extension; host output was
36, input 64, unmuted. The separate `Medium_Phone` AVD was left running because
no host device switch by the investigator was needed. No default input was
changed by the investigator while an emulator was running. APK hashes and
protocol changes are in the [first plan](av040/evidence/candidate-plan.json),
[second plan](av040/evidence/candidate2-plan.json) and
[extension authorization](av040/evidence/extension-authorization.json).

## Bounded capture comparison

The original twelve-turn loop ran once. Its zero transcripts comprise nine
`ERROR_NO_MATCH` and three empty final bundles. The owner attested three answers
as spoken and nine as interrupted, confirming early closure as the main reason.
This is twelve human-operated attempts, not twelve successfully spoken answers.
Capture lasted 1,136–6,495 ms, median 1,248 ms.

Candidate 1 (`explicit_start_done_v1`) moves thinking before recognition, then
opens capture with Start answer and finalizes with Done. Its four attempts all
closed before the owner began; all were attested Interrupted. Capture lasted
1,281–1,710 ms. The two-second scenario was repeated; all failures remain counted.

Candidate 2 (`explicit_start_segmented_done_v2`) uses the same controls and
interruption rule, and requests segmented recognition with a 15-second minimum.
Only nonempty final segments form a transcript; partial hypotheses do not.
Android explicitly makes segmented/minimum-duration support dependent on the
recognizer implementation. A request is not proof of an honored duration.
[RecognizerIntent reference](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_SEGMENTED_SESSION).

| Setting | Single experimental configuration / interpretation |
| --- | --- |
| Thinking | Wait before Start answer with no active recognizer; tested minimums 2 and 5 seconds |
| Default / maximum active capture | 15 seconds / 15 seconds; the active clock starts at `startListening`, not while thinking |
| Finalization | Done or candidate-2 capture cap calls `stopListening`; wait at most 5 more seconds |
| Retry / re-arm | Zero automatic re-arms and zero same-turn retries; explicit fresh trials consume the investigation budget |
| Fallback | Preserve failure, pause and require explicit retry/manual action; no inferred answer, grade or rating |

This reconciles the earlier 15-second response proposal with the 30-second
ceiling: idle thinking is separate, and active capture uses the smaller 15-second
bound plus five seconds to finalize. It does not add the two proposed windows
or restart them on a retry. The inherited `requested_settings.watchdog_ms=30000`
is the base scenario setting; AV-040's runtime limits are the separately recorded
`capture_limit_ms=15000` and `finalization_limit_ms=5000`.

These are tested experimental settings, **not approved production defaults**.
No supported capture/interruption combination was selected. #13 must keep its
live route blocked rather than treating a passing evidence checker as a go.

## Measured results

The original 24-turn follow-up bound was exhausted. The owner then explicitly
authorized **at most four additional turns**, using the same second policy with
MacBook microphone/speakers. All four were used. There were **40 actual turns**:
12 baseline plus 28 follow-up attempts, two capture candidates and one interruption
rule. No additional speech trials are authorized. The persistent attempt counter
was not reset; separate zero-turn checks confirmed refusal at both the original
24 cap and the authorized 28 cap.

| Run | Turns | Error | Raw nonempty transcript | Halted | Cancelled | Human-attested transcripts |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Original baseline | 12 | 12 | 0 | 0 | 0 | 0 |
| Candidate 1, attempts 1–4 | 4 | 4 | 0 | 0 | 0 | 0 |
| Candidate 2, attempts 5–24 | 20 | 8 | 3 | 7 | 2 | 2 |
| Same candidate, MacBook extension 25–28 | 4 | 3 | 1 | 0 | 0 | 1 |
| **Total** | **40** | **27** | **4** | **7** | **2** | **3** |

Across all turns the owner attested 15 as spoken, 23 as interrupted and two as
silent. Two spoken attestations were deviations from the intended echo protocol.
Raw `status=success` means only that the recognizer supplied text; it does not
mean a correct answer, successful interruption handling or a valid echo trial.

| Attempt | Expected wording / attestation | Raw final text | Interpretation |
| --- | --- | --- | --- |
| 8 | “It puts two first, then five, then seven.” / spoken | `it inputs two first five and then seven` | Number order preserved; additional ambiguous verb mismatch (`puts` → `inputs`) |
| 15 | “No, round does not satisfy square.” / spoken | `no Brown does not satisfy Square` | One clear meaning-changing substitution (`round` → `Brown`); also a missed capture call |
| 23 | Silent; prompt later confirmed inaudible | `how many` | Unattributed text, not a human answer or established acoustic echo |
| 25 | “Green, blue, red.” / spoken | `green blue red` | Exact after case/punctuation normalization; MacBook extension |

The owner confirmed reading the displayed wording for the sorting and negation
answers. There is no voice recording for phonetic adjudication. These three human
transcripts do not support an accuracy rate or reliable coverage of all four
prompt types. **Neither “Five” nor “Six” produced text.** The baseline “Six” turn
was attested Interrupted, so it is not a completed spoken short-answer test. The
optional dedicated finish/short-number pair was not run after repeated attempts
used its allocation; Done was exercised in the other capture trials.

### Thinking, finish and manual actions

- The two-second cases opened capture after 3,035–7,206 ms of measured thinking
  across eight attempts. The five-second cases opened after 6,749–15,826 ms across
  five attempts; a sixth was halted during playback by the investigator's scenario
  switch. Thinking occurs after the 400 ms settle delay and before recognition.
  These demonstrate the interaction's minimum waits, not exact two/five-second
  endpoint tolerance while a recognizer is active.
- Every completed candidate-2 capture used Done. The 15 Done-to-final waits were
  **116–389 ms**. Candidate-2 capture durations ranged from 422 ms (Cancel) to
  14,289 ms. **Neither the automatic 15-second capture cap nor the five-second
  finalization timeout fired in a human trial**; their fallback paths remain
  implemented but not exercised to expiry here. Per-turn partial, final-segment
  and terminal callback timings are in the [results ledger](av040/results.md).
- Follow-up controls required 28 Start/Next initiations and 28 attestations,
  **18 Start answer taps and 15 Done taps**. The two Cancel taps were investigator
  actions. The baseline required one Start and 12 attestations. These are recorded
  study controls, not a count of every host/emulator setup or navigation touch.
- There were zero automatic re-arms, zero same-turn retries and zero successful
  same-turn recoveries. Six extra repeated trials (3/4 and 9–12) all failed and
  remain in the totals. The later MacBook reversal success is a fresh trial after
  an AVD restart, not an in-window recovery. Returning from Home/lock and the
  investigator's selection/Cancel actions are recorded in the controller notes.

### Echo remains unverified

Attempts 23/24 were silent but **inaudible**, according to the owner's corrected
confirmation. “How many” does not match attempt 23's reversal prompt. Its source
cannot be determined from callbacks, routing observations or the owner's report
of no other speech/audio. It is not counted as a human recognition success.

Attempts 27/28 used verified MacBook defaults. The owner confirmed **audible
prompts and that they spoke**, consistent with the app's I SPOKE IT attestations;
both also used Done and ended in no-match. Those are spoken protocol deviations,
not silent echo passes. There is **no valid pair combining audible playback and
attested silence**. The exhausted bound and confirmed capture-call failure make
the final outcome no-go; no further echo trials were started.

## Interruption result

One rule was tested: lifecycle exit, telephony/communication audio-mode changes,
or unsolicited TTS stop halt the turn. Focus changes are recorded without a
threshold. Audio-mode observation uses the public
[AudioManager listener](https://developer.android.com/reference/android/media/AudioManager#addOnModeChangedListener(java.util.concurrent.Executor,%20android.media.AudioManager.OnModeChangedListener)).

- **Calls during playback: 2/2 detected.** Both produced mode 1 without `onPause`;
  the probe invalidated tokens before stopping audio. No capture opened afterward.
- **Calls during capture: 0/2 detected.** Android Telecom recorded calls TC@3 and
  TC@4 as RINGING. The recognizer held exclusive recording focus, Android suppressed
  the ringtone, and Telecom did not acquire focus or change the mode. The probe
  received no `onPause` and kept listening. One turn produced a transcript; the
  other ended in no-match after the owner's Done action.
- **Home, lock and Cancel: six trials, one per action in each phase.** Each stopped
  its active work and invalidated the originating token. The three capture trials
  delivered late `ERROR_CLIENT` callbacks; all were rejected with older tokens and
  `advanced_turn=false`. Later TTS-stop callbacks in playback trials did not restart
  capture. Returning to the screen did not resume audio automatically.

The [call trigger records](av040/evidence/call-capture-trigger.json),
[Telecom excerpt](av040/evidence/capture-call-system-logcat.txt),
[audio-service excerpt](av040/evidence/call-audio-volume-logcat.txt), and
[focus/volume dump](av040/evidence/capture-call-audio-dump.txt) identify the missed
calls independently of recognizer errors. The persistent ring setting was 5/7;
Telecom's effective inaudible result during recording is not evidence that the
owner manually set ring volume to zero.

Ordinary capture focus losses reached 12,354 ms before the echo trials, exceeding
both new playback-call losses (2,781 and 3,131 ms) and AV-005's 5,597 ms sample.
A duration threshold cannot separate these observations. Cancelling on the
recognizer's initial focus loss would cancel normal handoffs; it would not supply
a later signal for an incoming call during that same loss.

The three late capture callbacks were cancellation errors after cleanup. A
delayed final transcript arriving after a newly active turn was not provoked in
these trials; the token-bound listener rejects older tokens by construction and
the validator checks the recorded old-token cases. Calls were not retested during
the four-turn MacBook extension.

## Validation

- Probe debug APK build passed with JBR 25.0.2. The extension/guard build SHA-256
  is `c82128378707b6f4ce7717fc759b4ad811e099226243a9df4231990edbb8e39e`.
- **182 Python tests passed**, including rejection of duplicate/over-budget
  evidence, missing attestations, retained interrupted transcripts and invalid
  callback tokens. These tests validate evidence handling, not microphone quality.
- The four canonical human files passed **538 evidence assertions**: 40 turns,
  four raw transcripts, three human-attested transcripts, 28/28 follow-up attempts.
  All capability failures and echo deviations remain visible in the output.
- The accepted AV-005 no-voice matrix still passed **334 assertions / 38 turns**.
  It was neither overwritten nor recounted as new human evidence.
- Runtime guard/UI checks recorded zero additional turns at caps 24 and 28. The
  selected-case UI cleared the previous result before the MacBook echo selection.
  See [validation output](av040/evidence/validation.txt),
  [verification summary](av040/evidence/verification.json),
  [guard records](av040/evidence/budget-guard-28-adb.json) and
  [selection UI](av040/evidence/macbook-echo-selected.xml).

Integrity checks passing does not overturn the no-go findings above. The
[teardown record](av040/evidence/teardown.json) confirms that host input forwarding
was disabled and the test probe/AVD stopped; MacBook defaults and the separate
`Medium_Phone` AVD were left in place.

## Evidence, limits and handoff

The [runbook](av040/runbook.md) explains controls, extraction and validation.
[Operator attestations](av040/evidence/operator-attestation.json) retain corrections
as well as initial replies. [Controller notes](av040/evidence/controller-notes.json)
record the accidental scenario switch, launcher recovery, wake actions and
investigator Cancel taps. Repeated runs were retained and counted, not replaced.

- **#13:** the interaction can separate thinking from capture. A usable live route
  and production defaults remain unresolved. Display/correct final text and keep
  failures distinct from answers; partials and errors cannot authorize grading.
- **#26:** the tested interruption monitor is insufficient during native capture.
  A further bounded decision must establish an independently observable call or
  interruption signal and verify cleanup. Do not copy the probe as production code.
- **#23:** revisit the speech-route/interruption assumption using this result. The
  evidence does not by itself change Kotlin or component ownership. No replacement
  provider, permission design or API route was evaluated in this bounded work.
  The [dated architecture addendum](../decisions/0022-android-implementation.md#av-040-follow-up-september-15-2026)
  records this handoff.
- **#29:** the human integrated 30-turn acceptance remains required. This spike
  does not demonstrate the integrated MVP or release readiness.

Physical Android hardware, Android Bluetooth routing, real calls, acoustic echo
cancellation and other devices/services remain unverified. Host headphones do
not establish Android Bluetooth support. The report's callback/timing checks do
not measure waveform-level audio latency or statistically representative accuracy.
