# AV-002 validation record

Validated September 14, 2026 on macOS 26.6.2 ARM64, Python 3.13.7, and the
official Anki Python backend 25.9.2. No existing user collection was opened.

## Results

The documented build command generated three disposable collection workspaces
and three modern `.colpkg` packages: baseline (8 cards), limits (6), and rejection
(4). Each package was imported into a fresh collection by Anki's backend during
the tests. Each generated profile passed SQLite integrity checks.

```text
.venv/bin/python -m unittest discover -s tests -v
Ran 12 tests
OK

.venv/bin/python -m pip check
No broken requirements found.

git diff --check
Passed.
```

| Acceptance criterion | Verified evidence |
| --- | --- |
| Proposed VoiceQA type, factual/conceptual examples, concepts and paraphrases | Exact fields/templates/CSS from #10; one card per note; four synthetic examples; real rendered fronts exclude answer/grading fields |
| New, learning, relearning, mature, suspended, buried, and deck limits | Imported cards have the expected persisted types, queues, due-date units, history and intervals; same-day scheduler excludes suspended, both buried states, and future reviews |
| Usable learning/relearning instances | Backend accepts rating submissions for both and records exactly one additional review per submission |
| Deck limits | Only one new and two review cards are offered from six available cards; after those are rated Easy, the queue is empty with two unseen and one due review withheld; limits survive reopening |
| Repeatable creation | Separate fresh builds reproduce content, fixture IDs, relative due dates, history semantics, limits and initial counts; manifests agree with imported collections |
| Reset and backup/restore | A reviewed/edited collection and a synthetic media file survive export/import; restoring the pristine package restores initial state and counts |
| Incompatible cards only for clear rejection cases | Basic, Cloze and empty-reference inputs are isolated in the rejection profile, with explicit expected reasons and a valid control |
| Preserve existing data | Existing build/restore directories and backup files are refused; unmarked profiles are refused; invalid/missing packages report errors without modifying their source |

The commands and actual test assertions are in
[`tests/test_voiceqa_fixtures.py`](../../tests/test_voiceqa_fixtures.py).
The fixtures use the real Anki backend rather than mocked scheduler responses.
The GitHub Actions workflow runs the same suite with Python 3.13 on Ubuntu 24.04;
that remote workflow has not been run as part of this local task.

## Evidence limits

- AnkiDroid/AVD and desktop GUI import, UI rendering, provider access, and review
  submission were not exercised. Those integration claims belong to #4 and later
  app work. Pin the target client and validate these generated packages there.
- Anki accepts the negative fixtures as cards. Product eligibility rejection,
  user-facing messages and no-write behavior remain for #11; this task provides
  labelled inputs and expected outcomes, not the product implementation.
- Scheduling history is synthetic. Same-day initial counts are verified; packages
  naturally age and buried cards/limits change at rollover. Rebuild on the target
  testing day in the target timezone as described in the runbook.
- Numeric Anki IDs, build timestamps, archive bytes and fuzzed future intervals
  are not stable across builds. Stable fixture tags, content, relative initial
  dates and manifest mappings provide repeatability.

There is no remaining implementation work for #2's fixture scope. The issue/card
must remain in review until accepted; this record does not mark it Done.
