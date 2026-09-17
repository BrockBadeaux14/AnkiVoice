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
