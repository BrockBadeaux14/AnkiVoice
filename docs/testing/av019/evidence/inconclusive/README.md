# Attempts that proved nothing, kept as the record of what happened

September 17, 2026. Three cases ran before `ExchangeInstrumentation` knew how to take
AV-019's abstain path, and none of them demonstrated what it was named for. They are kept
rather than deleted, in the manner of AV-044's faulted attempts: an attempt that came back
wrong is part of the record, and re-running until one comes back right and keeping only
that one is not evidence.

None of the three is counted in `../summary.json`, and
[`tools/av019-qa/validate.py`](../../../../../tools/av019-qa/validate.py) does not read
this directory.

| Attempt | What happened | Why it proved nothing |
| --- | --- | --- |
| `corrected.json` | The card graded `uncertain`, so the exchange opened as an **abstention** with no pending rating. | The case script had nothing to correct and skipped: `{"skipped": "no rating is pending: grading"}`. No review was written, which is correct but is not what the case is for. |
| `correction-only.json` | The answer never settled — the session was already `paused` when the case reached its steps, and nothing was announced at all. | It wrote nothing, but a case that never opened an exchange cannot show that a correction writes nothing. |
| `abandoned.json` | The card graded `uncertain`; the abstention was announced, then Pause and Finish ran. | It did pause and finish without a write, but over an **abstention** rather than over a pending rating, so it is not the abandoned exchange the card asks for. |

The exchange itself behaved correctly in all three: a `uncertain` grade opens Announced with
no pending rating and is announced as an abstention, never as a rating, which is exactly
what AV-019 requires. The gap was in the harness, and in the validator that let
`correction-only` pass while skipping.

Both were fixed the same day:

- `ExchangeInstrumentation.nameRating` now takes the abstain path — `selfGrade` opens
  Announced with the rating announced as learner-named — so every case reaches a pending
  rating whatever the rules make of the answer, and the abstain path itself gets live
  evidence.
- `validate.py` now requires each case to have reached the steps it is named for, so a
  skipped case can no longer pass the no-write checks while demonstrating nothing.

These three cases are re-run from scratch with the fixed harness; their replacements go in
the parent directory.

## Second round, later the same day

`abandoned` and `correction-only` passed on the re-run and moved to the parent directory.
Two did not, both for the same reason and neither of them an exchange fault.

| Attempt | What happened | Why it proved nothing |
| --- | --- | --- |
| `corrected-2.json` | The exchange ran in full — abstention, `self-grade` to Again, corrected to Hard, both announced at revision 1 — and the confirmation was spoken. The command capture returned `noMatch`. | The router refused it `recognition-failed` and the session paused with the card kept, so no review was written. Correct behaviour; the case still owes its write. |
| `undo-handoff-2.json` | Three answer attempts, the first two empty. The host-side driver was stopped during the third to run a microphone diagnostic, which ended the instrumentation. | Abandoned by the operator, not by the app. Nothing was written. |

### The capture problem, stated as what is actually known

Several captures across the session returned nothing at all — `noMatch`, code 7 — with no
audio reaching a transcript, on first and later opens of a boot alike. What is established:

- The owner used Google's voice search **inside the same emulator** and it worked, so the
  guest microphone can receive host audio. The host hardware is not the fault.
- `Failed to create voice` — AV-017's coreaudio listener leak — appears once, in
  `build/av019/emulator-boot-00.log`.
- The emulator process **exited** later in the session, which is the other symptom AV-017
  documented for that leak.
- An unattended five-second probe returned `no-samples`. That is ambiguous: it means no PCM
  file was read, which can equally mean the diagnostic did not run.

What is **not** established is which layer drops the audio. AV-017 and AV-044 already
record this emulator's capture as unreliable after a few microphone opens per boot, and
AV-019's retry path was added because of it; whether these particular empty captures are
that same fault, or something in AV-025's own pipe, is an AV-025 (#26) question and not one
this card can answer. It is recorded here rather than guessed at.

The practical consequence for anyone resuming: **tap the confirmation rather than speaking
it.** The spoken path is already proven by `confirmed`, and a spoken confirm costs a second
microphone open in a boot that may not have one left.
