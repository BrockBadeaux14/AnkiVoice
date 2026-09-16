# AV-042 investigation plan

Issue #51; branch `codex/av042-live-speech-blockers`; base
`200d1aca181429884ace22476a968b3c31883891`. Dependencies #45/#5/#6 are closed;
PR #50 is merged. #51 had no comments when fetched. No user changes existed.

## Bound and sequence

Use only AnkiVoice_AV005, emulator-5588, the API 36 ARM64 Google Play revision 7
image and AV-040's pinned Google speech package and local en-US-language voice.
MacBook Pro microphone/speakers. The unrelated AV024 emulator was stopped before
changing the host defaults from WH-1000XM5. AV-040 evidence and app storage are
preserved. All new trials use a different package, org.ankivoice.av042.

The app atomically reserves each turn in its private ledger before audio begins.
The ledger is cumulative across process restarts. Never clear app data or uninstall
the package during the investigation. A reserved turn with no terminal event is
still spent. All failed, silent and automated captures count. Limit 30, including
12 spoken prompt/interaction turns, four calls, two audible silent echo turns,
six Home/lock/Cancel turns, two deadline turns and four diagnostic/recovery turns.
No AV-005 baseline repeat. Zero-audio Java tests are separate from live evidence.

Candidate 1: AudioRecord (MIC, mono PCM16 at 16 kHz) feeds the pinned recognizer's
EXTRA_AUDIO_SOURCE pipe with EXTRA_SEGMENTED_SESSION=EXTRA_AUDIO_SOURCE.
No recorded or synthesized answer is injected. PCM level summaries establish
capture activity independently of recognizer no-match; raw audio is not retained.
Prompt TTS is synthesized with the same local voice and played via MediaPlayer
under app-owned transient focus. This avoids conflating the TTS engine's focus
handoff with an external interruption. This is one experimental playback/capture
route, not a production architecture change.

First test raw app-owned capture and the observable call signals, then the same
capture with the recognizer attached. Observe focus, mode, recorder silencing and
route changes. The revised monitor pauses immediately on focus loss, non-normal
mode, client silencing, device removal, screen-off or activity pause. Results are
invalidated before cleanup, and resumption requires explicit action. No focus
loss duration heuristic. Capture call diagnostics must be independently confirmed
through Telecom/audio evidence; they are not human transcript evidence.

Only if deterministic evidence establishes no RECORD_AUDIO-only signal during
capture may candidate 2 evaluate READ_PHONE_STATE as #51 authorizes. No other
service, device, account or paid route is authorized. Stop under the issue's
no-go/budget criteria; preserve failures and report gaps rather than expanding.

Before spoken trials, verify routing and audibility. Operator starts a turn,
listens, waits the indicated thinking interval outside capture, taps START ANSWER,
waits for SPEAK NOW, says the displayed answer, and taps DONE. Attest the actual
completed turn; never future behavior. Echo turns require both audible-prompt and
stayed-silent attestations. A UI attestation is not inferred from PCM or a callback.

Experimental limits start at 15 seconds active capture and five seconds finalizing,
400 ms playback settling, no automatic retry. These remain experiments until
measured; an explicit retry consumes another turn and must fit the original
answer budget. Manual fallback means typed correction/self-grade in the future
product; this probe does not grade, submit reviews, or implement that product flow.

## API evidence

- [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE): API 33 external PCM source and source-closed segmented endpoint; implementation support is conditional.
- [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord#registerAudioRecordingCallback(java.util.concurrent.Executor,%20android.media.AudioManager.AudioRecordingCallback)): app-owned recording configuration and client-silencing callbacks.
- Installed API 36 SDK source inspected at android/speech/RecognizerIntent.java and android/media/AudioRecord.java.

These sources justify candidates, not capability findings. The report must separate
injected callbacks and emulator calls from live operator speech and physical devices.

## User scope change — September 15, 2026

After the two diagnostic attempts the owner instructed:

> I need to just ship a MVP, the only case that needs to work is the human voice imput working. Ignore other requirements for now.

This instruction supersedes the broad speech/interruption acceptance gate and the
no-go stopping rule for the current task. Resume only the practical live human
voice → displayed transcript flow. Do not run more call, Home/lock, echo or formal
matrix cases merely to satisfy the previous criteria. Existing negative evidence
remains accurate and preserved. It no longer blocks work on the owner's narrowed
MVP. It does not become evidence that interruptions are handled.

The next trial uses the same MacBook/AVD/pinned service and candidate 1, with the
owner explicitly starting and speaking. Keep the cumulative ledger and distinguish
real operator speech from the two automated diagnostic attempts. A successful
zero-audio test or a recognizer-ready callback alone still cannot prove voice input.
The no-PR/no-merge/no-deployment workflow remains in effect.
