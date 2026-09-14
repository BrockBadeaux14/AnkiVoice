# AV-001: Platform and pilot constraints

- Issue: [#1 — Confirm platform and pilot constraints](https://github.com/BrockBadeaux14/AnkiVoice/issues/1)
- Status: Ready for review — awaiting acceptance.
- Date: 2026-09-14
- Dependencies: None; verified against the issue description and GitHub dependency list.

## Decisions

| Constraint | Decision |
| --- | --- |
| Implementation path | Android-first, as selected by the user. |
| Available test devices | Android Studio emulation, as specified by the user. No physical device has been identified. |
| Purpose | College project. |
| Pilot audience | Personal coursework/demo; only the user runs AnkiVoice. |
| Deadline | September 18, 2026, using the specific date in the user's response. |
| Spending cap | No budget amount or spending cap has been set. No paid-service allocation is committed by this record. |

## Confirmed MVP scope

- Use a dedicated custom VoiceQA note type.
- Cloud speech recognition and AI grading are allowed.
- Study runs while the app remains in the foreground.
- Offline use and locked-screen study are not MVP requirements.
- General card compatibility and direct sync are deferred.

## Planning rules

Keep task owners unassigned and estimate with relative sizes.

The September 18 deadline is a project constraint. The remaining implementation
issues have not been estimated against that date. Provider selection (#6) must
record costs and usage limits before a paid-service allowance can be specified.

## Android-first planning baseline

The selected path supersedes the board's provisional desktop → Android assumption.
Desktop and iPhone implementations are deferred; neither is a prerequisite for the
Android MVP. Framework and provider choices remain with #23 and #6, respectively.

Start with the synthetic collection (#2), AnkiDroid integration feasibility (#4),
foreground speech feasibility (#5), and provider selection (#6). Use the fixtures
from #2 for #6's measured comparisons. Do not claim that Android review access or
speech is proven merely because Android-first was selected.

The following dependency replacements define the Android-first plan for review.
Issue numbers are GitHub issue numbers, which do not always match AV plan IDs.
Dependencies not listed here retain their existing relationships.

| Issue | Android-first dependencies and scope |
| --- | --- |
| #6 — Provider selection | #1 and #2; measure latency, quality, and cost using the synthetic fixtures. |
| #7 — Integration contracts | #1 and #4; use Android review evidence in place of desktop spike #3. |
| #23 — Android implementation decision | #4, #5, #6, and #7; remove desktop package #22 as a prerequisite. Specify new Android implementation work rather than assuming existing Python logic to port. |
| #10 — VoiceQA provisioning | #2 and #24; implement the provisioning route established by Android feasibility rather than requiring desktop CLI #8. |
| #11 — VoiceQA eligibility | #25 and #10; use the AnkiDroid adapter in place of desktop adapter #9. |
| #13 — Transcription and answer boundaries | #6 and #24; implement with the Android speech layer rather than desktop CLI #8. |
| #14 — Session state machine | #7, #26, and #13; use mobile speech #26 in place of desktop TTS #12. |
| #17 — Credentials, usage controls, and diagnostics | #6 and #24; use Android configuration rather than desktop CLI #8. |
| #20 — Review commit tracking | #25, #14, and #16; use the AnkiDroid adapter in place of desktop adapter #9. |
| #25 — AnkiDroid adapter | #24 and #7; implement the verified contracts directly, without assuming a desktop implementation exists. |
| #26 — Mobile speech | Keep #24 and #6. Carry forward #12's voice/rate selection, repeat/cancel, playback-before-capture, and failure-without-review criteria. |
| #27 — Complete mobile study flow | #25, #26, #15, #18, #19, #20, and #21; integrate and verify commands, grading, evaluation, commit recovery, and correction on Android. |

Shared behavior and evaluation in #10–#11 and #13–#21 remain required on the Android
path. Desktop-only #3, #8, #9, #12, and #22 are deferred. The final Android gates
remain sync handoff/stale-session validation (#28) and foreground pilot validation
(#29), including the 30-turn acceptance run. Later expansion work (#30–#39) remains
deferred and is not added to the MVP by this decision.

This document is the reviewable planning change for #1. Other issue bodies and
their board statuses have not been rewritten or advanced by this task. Apply this
baseline to their planning when this decision is accepted; retain their acceptance
criteria rather than silently dropping behavior previously assigned to desktop.

## Test environment and evidence limits

The initial target is an Android virtual device in Android Studio. The AVD profile,
Android API/system image, AnkiDroid release, and speech-service configuration must
be pinned and recorded during #4/#5 and in #23's support matrix. These versions
were not supplied by the user and are not chosen by this platform decision.

Emulator availability is user-reported. This task does not establish that an AVD,
AnkiDroid integration, microphone, or speech provider has been configured or tested.
Report physical speaker, Bluetooth, and phone-call behavior as unverified until
tested on suitable hardware. #28 must also identify a second Anki client for its
sync verification; no second client has been confirmed here.
