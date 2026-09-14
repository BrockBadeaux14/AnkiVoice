# AV-002: Repeatable VoiceQA test collection

Issue: [#2 — Create a repeatable VoiceQA test collection](https://github.com/BrockBadeaux14/AnkiVoice/issues/2).
Dependencies: none in either the issue body or GitHub dependency list; no discussion
was present when fetched on September 14, 2026.

These fixtures support the Android-first feasibility work without choosing the
Android app architecture or implementing its Anki adapter. The generator uses
the official [Anki 25.9.2 Python backend](https://pypi.org/project/anki/25.9.2/),
corresponding to desktop Anki 25.09.2. This pins fixture generation only; #4 must
pin and validate the actual AnkiDroid release and Android image.

## Generate and test

From the repository root, using Python 3.13:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-fixtures.txt
.venv/bin/python tools/voiceqa_fixtures.py build --output build/voiceqa
.venv/bin/python -m unittest discover -s tests -v
```

Each profile below contains `collection.anki2`, its media directory, an importable
`collection.colpkg`, and `av002-manifest.json`. The latter records actual note/card
IDs, content, states, history, deck presets, initial queue counts, source hashes,
build time, timezone, and scheduler-day cutoff. The Python directories are
disposable collection workspaces; they are not registered desktop GUI profiles.
Generated output and the virtual environment are ignored by Git.

The commands refuse existing destinations, including empty directories and existing
backups. A failed run can leave a partial destination for inspection; choose a new
output path when retrying. No command discovers or opens a personal Anki profile,
connects to AnkiConnect, or syncs to AnkiWeb.

## Contents and expected behavior

The [note-type specification](../../fixtures/voiceqa/note-type.json) is copied from
the proposed JSON in [#10](https://github.com/BrockBadeaux14/AnkiVoice/issues/10),
including its six fields, exact front/back templates, CSS, four synthetic examples,
required concepts, accepted paraphrases, and expected correct/partial/incorrect
answers. The examples cover factual arithmetic, sequence reversal, a conceptual
rule explanation, and sorting. All examples are invented and contain no personal
study material. Reusing an example across scheduling states is intentional.

VoiceQA produces one card per note. `Prompt` and `ReferenceAnswer` are required;
`RequiredConcepts`, `AcceptedAnswers`, `Language`, and `Extra` are optional in the
proposed schema. The front renders only the prompt. Anki itself does not enforce
the required fields; eligibility enforcement belongs to #11.

| Profile / output directory | Contents | Fresh scheduler counts (new / learning / review) |
| --- | --- | --- |
| `baseline` / AV002 Baseline | Eight VoiceQA cards covering the states below | 1 / 2 / 1 |
| `limits` / AV002 Limits | Three new cards and three due mature reviews | 1 / 0 / 2 |
| `rejection` / AV002 Rejection Only | One valid control, Basic, Cloze, and VoiceQA with an empty reference answer | 4 / 0 / 0 in Anki itself |

Search in the Anki browser by `tag:av002`, `tag:fixture::learning`, or
`tag:state::mature`. Fixture IDs and source content remain stable across builds;
Anki's numeric IDs and timestamps are generated for each build. Use each manifest
to resolve IDs for the run rather than hard-coding them.

| Baseline fixture ID | Card type / queue | Initial scheduling expectation |
| --- | --- | --- |
| `new` | 0 / 0 | Unseen; no review history |
| `learning` | 1 / 1 | First learning step overdue by one minute; one Again review |
| `relearning` | 3 / 1 | Lapsed review in a ten-minute relearning step, overdue by one minute |
| `mature` | 2 / 2 | Due today; 30-day interval and three synthetic reviews |
| `suspended` | 0 / -1 | Excluded from the study queue |
| `buried-manual` | 0 / -3 | Manually buried; excluded for this scheduler day |
| `buried-sibling` | 0 / -2 | Scheduler-buried state; excluded for this scheduler day |
| `future` | 2 / 2 | 30-day interval, due seven days ahead; excluded today |

The sibling-buried fixture seeds that queue explicitly through Anki's bury API.
It does not imply that the one-card VoiceQA type has sibling cards. Learning due
values are Unix seconds; review due values are days since collection creation.
History and scheduling dates are deliberately seeded test data, not evidence of
human reviews or measurements of scheduler accuracy. The collection is dated
90 days back to accommodate that history.

All profiles use the v3 scheduler with SM-2, FSRS disabled, learning steps of
1 and 10 minutes, a 10-minute relearning step, no learn-ahead, and a 04:00 local
day rollover. The baseline/rejection presets allow 20 new and 20 review cards per
day. The limits preset allows one new and two reviews per day; new cards ignore
the review limit so the two limits can be tested independently. No hierarchy or
filtered decks affect these limits. Answer the three available limits cards Easy
to graduate the new card immediately: the queue then finishes while two unseen
cards and one due review remain withheld. Tests verify that exhaustion survives
closing and reopening the collection. Rating Easy here is a fixture test action,
not an automatic-grading policy.

The rejection profile is exclusively for negative integration tests. Its manifest
expects `unsupported_note_type` for Basic and Cloze, and
`missing_reference_answer` for the malformed VoiceQA card. The valid control
should pass eligibility. The future app must report a clear rejection and leave
card state and review history unchanged for rejected cards. The fixture tests
verify the inputs and labels; they do not implement or claim to verify that
future app behavior. Keep this profile out of normal study and grading evaluation.

## Disposable Android and desktop test environments

For Android, use a dedicated Android Studio AVD containing only synthetic test
data. Keep it signed out of AnkiWeb. Install the AnkiDroid version selected in #4,
then copy the chosen `collection.colpkg` to that AVD and use AnkiDroid's collection
package import. Importing a collection replaces the active collection; do not use
an AVD with personal study material. AnkiDroid does not provide desktop-style
profiles, so use separate AVDs or replace the disposable collection between these
three scenarios. Save a named AVD snapshot after confirming each baseline.
See the [AnkiDroid import documentation](https://docs.ankidroid.org/#_importing_anki_files)
and [Anki profile documentation](https://docs.ankiweb.net/profiles.html).

For optional desktop inspection, create a new profile with File → Switch Profile →
Add, name it `AnkiVoice AV002 baseline` (or `limits` / `rejection`), keep it signed
out of AnkiWeb, and import the matching `collection.colpkg`. The packages include
scheduling and presets. A collection package replaces the selected collection;
a deck package merge or text import does not provide the same reset semantics.
See [Anki collection package behavior](https://docs.ankiweb.net/exporting.html#collection-colpkg).

Before an integration run, record the app/OS versions, source hashes and build
time from the manifest, selected deck, local timezone, and initial counts. Inspect
the state tags in the browser. #4 must establish Android import, queue, and rating
behavior; these instructions do not constitute an emulator or GUI validation.

## Reset and backup/restore

Rebuild on each testing day and whenever the target timezone changes. Build and
import in the same timezone and scheduler day. Buried cards automatically return
on a later day, learning timestamps age, and daily limits reset. Restoring an old
package or an AVD snapshot preserves its old dates; it does not rebase time. Use
fresh packages to reestablish the documented initial counts after rollover.

For a fresh run, preserve the old output and choose a new directory:

```sh
.venv/bin/python tools/voiceqa_fixtures.py build --output build/voiceqa-run-02
```

To preserve a headless test workspace after exercising it, close every client or
script using that collection first. Export it to a new backup, then restore into
a new disposable directory:

```sh
.venv/bin/python tools/voiceqa_fixtures.py backup --profile build/voiceqa/baseline --output build/after-review.colpkg
.venv/bin/python tools/voiceqa_fixtures.py restore --package build/after-review.colpkg --output build/restored-after-review
```

To reset on the same scheduler day, restore the untouched initial package:

```sh
.venv/bin/python tools/voiceqa_fixtures.py restore --package build/voiceqa/baseline/collection.colpkg --output build/reset-baseline
```

For a GUI or AVD run, export the active client's **entire collection** with media
before resetting it. The host's generated database is a separate copy and does
not contain reviews performed in the app. Restore that exported package only
into a disposable test environment; restore a pristine package to repeat a case.
Anki's [manual backup instructions](https://docs.ankiweb.net/backups.html#manual-colpkg-backups)
describe the collection export/import workflow. Delete only disposable profiles,
AVDs, or generated directories once their test evidence is no longer needed.

## Validation record

See [the acceptance test record](av002-validation.md) for checks actually run and
the boundary between backend fixture verification and pending device integration.
