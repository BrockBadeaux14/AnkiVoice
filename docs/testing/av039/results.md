# AV-039: Install and provision the custom VoiceQA note type

Issue [#10 — Install and provision the custom VoiceQA note type](https://github.com/BrockBadeaux14/AnkiVoice/issues/10).
Implementation and validation completed September 15, 2026; ready for review.
Branch: `codex/av039-voiceqa-note-type`.

## Delivered

- `:ankidroid` provisions the VoiceQA note type: it installs it when missing,
  reuses it unchanged when its ordered field names match, and stops with a named
  conflict when they do not. It adds the four sample notes to a new `VoiceQA
  Demo` deck, and skips demo content when that deck already exists.
- Provisioning runs on the same serial worker as AV-023's deck access, so a deck
  read and a collection write can never overlap, and delivers immutable reports
  on the main executor. `:app` owns the explicit setup action and the full-sync
  disclosure; no other module names the AnkiDroid authority and no second
  provisioning route exists.
- The installable specification is derived from
  [`fixtures/voiceqa/note-type.json`](../../../fixtures/voiceqa/note-type.json)
  by [`tools/av039_note_type.py`](../../../tools/av039_note_type.py) into a
  `java.util.Properties` resource that both the app and its JVM tests read. The
  AV-002 host generator stays test tooling.
- After writing a note type, the installed model is read back through `models`
  and `models/<id>/templates` and compared with the fixture: model name, ordered
  field names, template name, question format and answer format. A mismatch is
  reported as incomplete and names what differs.
- Setup never writes on its own. Inspection is read-only; a write happens only
  after the learner accepts a disclosure that adding a note type forces
  AnkiDroid's next sync to be a one-way full sync. Declining reaches the
  provisioner, which is what keeps the collection unchanged.

Real card reads and review writes (#25), speech (#26), credentials (#17),
`ReviewSession` (#14) and the study screen (#27) remain with their owners.
Provisioning submits no review and performs no sync.

## The verified provider route

Derived from AnkiDroid 2.24.1's `CardContentProvider` and `FlashCardsContract`,
and now confirmed on the pinned AVD rather than only from source.

| Step | Call | Confirmed behaviour |
| --- | --- | --- |
| Deck | `insert` on `decks` with `deck_name` | Created; a duplicate name is rejected with `IllegalArgumentException`, which the adapter reads as already provisioned. |
| Note type | `insert` on `models` with `name`, `field_names` (0x1f), `num_cards=1`, `css`, `deck_id`, `sort_field_index`, `type` | Returns the model URI. `num_cards=1` adds one placeholder template; no second template is ever inserted, so no accepted full sync is required. |
| Template | `update` on `models/<id>/templates/0` with `card_template_name`, `question_format`, `answer_format` | Accepted. `model_id` and `ord` are read from the URI and are rejected in the values. |
| Notes | `insert` on `notes` with `mid`, `flds` (0x1f), `tags` | The note lands in the note type's default deck. |
| Placement | `query` on `notes/<id>/cards`, then `update` on `notes/<id>/cards/<ord>` with `deck_id` | Every card is read back after insert. It is moved only when it did not land in the demo deck, which happens when the note type was reused rather than created. |

Binding `deck_id` on a note type this run creates is enough on a fresh install;
the move exists for the reuse case, because AV-039 may not edit a note type that
already matches.

## Dependency and environment verification

#2 and #24 were closed, and #24's PR [#52](https://github.com/BrockBadeaux14/AnkiVoice/pull/52)
was an ancestor of the starting checkout. #10 had no discussion comments. The
starting working tree was clean and on `main`; the work uses a dedicated branch.

The [environment capture](evidence/environment.json) matches AV-022's validated
macOS 26.6.2 (25G83) ARM64 host, API-36 Play Store ARM64 image, build
`BE2A.250530.026.D1/13818094`, medium_phone 1080×2400, emulator 37.1.11.0, adb
37.0.1 and AnkiDroid 2.24.1 (322401300), SHA-256
`3012692c…95afbf`. A **new** `AnkiVoice_AV039` AVD on `emulator-5584` was
created; earlier AVDs were untouched. It had no Google account and was never
signed in to AnkiWeb, and no sync ran.

## Validation

| Check | Result / evidence |
| --- | --- |
| Android workflow commands, run locally | **Pass**: module boundaries, 55 core tests, 50 `:ankidroid` tests, 29 `:app` tests, debug and release assembly. [Gradle log](evidence/gradle-checks.txt), [134 JVM cases](evidence/jvm-tests.json) |
| Lint | **0 errors, 14 warnings**, unchanged from AV-023: preserved pins, existing manifest defaults and Kotlin extension style. [Report](evidence/lint.txt) |
| VoiceQA fixtures workflow commands, run locally | **188 tests pass** (182 before, 6 new), no broken dependencies, AV-006 evidence validator passes. [Tests](evidence/python-tests.txt), [dependencies](evidence/pip-check.txt), [provider evidence](evidence/provider-evidence-check.txt) |
| AV-023 evidence still verifies | **Pass**; the shell changes did not invalidate it. [Check](evidence/av023-evidence-check.txt) |
| Resource reaches the runtime, not only the source tree | `av039/voiceqa-note-type.properties` is packaged in both the debug and release APKs, and `VoiceQaNoteTypeTest` loads it from the classpath. |
| AnkiVoice crashes | No AnkiVoice entry in the dedicated emulator's [crash buffer](evidence/crash-buffer.txt) (empty). |
| Retained evidence | [Validator](../../../tools/av039-qa/validate.py) passes over all 61 files; [SHA-256](evidence/sha256.json). |

### Emulator matrix

Every row is a real UI capture from the pinned AVD, each XML with a matching PNG,
and every row that could write has a before/after collection comparison.

| Scenario | Observed behaviour | Capture | Collection |
| --- | --- | --- | --- |
| AnkiDroid not installed | Setup card hidden; shell reports `packageUnavailable` | [Before install](evidence/setup-package-missing.xml) | — |
| Database permission never granted | Setup reports `accessDenied`; nothing changed | [Denied](evidence/setup-access-denied.xml) | — |
| Database permission revoked after a successful run | Setup reports `accessDenied` again | [Revoked](evidence/setup-access-revoked.xml) | — |
| AnkiDroid API switched off / restored | `apiDisabled`, then setup answers again | [Off](evidence/setup-api-disabled.xml), [restored](evidence/setup-api-restored.xml) | — |
| AnkiDroid disabled with `pm disable-user` | `packageUnavailable` | [Disabled](evidence/setup-package-unavailable.xml) | — |
| Inspection on a collection without VoiceQA | Names both missing pieces; read-only | [Needed](evidence/setup-needed-fresh.xml) | [Before](evidence/collection-fresh-before.json) |
| Disclosure shown before the first write | Full-sync wording, **Install VoiceQA** / **Not now** | [Disclosure](evidence/setup-disclosure.xml) | — |
| Disclosure declined | "Setup cancelled. Nothing in your collection was changed." | [Declined](evidence/setup-declined.xml) | [No change](evidence/comparison-declined.json) |
| Fresh install accepted | VoiceQA installed; 4 sample notes added to VoiceQA Demo | [Installed](evidence/setup-installed-fresh.xml) | [1 note type, 1 deck, 4 notes, 4 cards](evidence/comparison-fresh-install.json) |
| Setup run again | Reuse reported; demo content skipped | [Repeat](evidence/setup-repeat-run.xml) | [Nothing changed](evidence/comparison-repeat.json) |
| `VoiceQA` with `Extra` renamed to `Notes` | Named conflict listing both field orders; no write path offered | [Conflict](evidence/setup-field-conflict.xml) | [Nothing changed](evidence/comparison-conflict.json) |
| AV-002 baseline imported through AnkiDroid's own UI | `AV002 Baseline: 1 new / 2 learning / 1 review` | [Imported](evidence/ankidroid-baseline-imported.xml) | [Before](evidence/collection-existing-before.json) |
| Inspection with VoiceQA already present | Only the demo deck is reported missing | [Existing](evidence/setup-existing-notetype.xml) | — |
| Disclosure on the reuse path | Lists only the demo deck, and still discloses the full sync | [Disclosure](evidence/setup-disclosure-reuse.xml) | — |
| Provisioning against the existing note type | Reused; 4 sample notes added to VoiceQA Demo | [Reused](evidence/setup-reused-notetype.xml) | [8 → 12 cards, 11 → 11 reviews](evidence/comparison-existing-notetype.json) |
| Setup run again on that collection | Demo content skipped | [Repeat](evidence/setup-existing-repeat.xml) | [Nothing changed](evidence/comparison-existing-repeat.json) |
| Permission restored, app force-stopped and relaunched | Setup reports complete again | [Final](evidence/setup-final-complete.xml) | — |

Each `comparison-*.json` is derived from the two `collection-*.json` snapshots
around it, which record note types, fields, templates, decks, notes, cards and
`revlog` from a read-only copy of the collection; both are retained.
[`fixture-manifest.json`](evidence/fixture-manifest.json) is the AV-002 baseline
manifest for the synthetic collection this ran against.

The installed note type was compared with the fixture directly from the
collection database: the six ordered fields, the single `Voice recall` template,
and the front, back and CSS stored verbatim. All four sample notes match their
fixture example field for field, carry `ankivoice-demo` plus their example id as
tags, and have exactly one card each in `VoiceQA Demo`.

On the AV-002 baseline the reused note type's stored row was byte-identical
before and after, with an unchanged `mtime_secs`: it was reused, not rewritten.
The eight AV-002 cards stayed in `AV002 Baseline` with identical scheduling, and
the eleven historical reviews were unchanged. **No review was added or altered
in any run.**

### JVM tests

`ProvisioningTest` drives every outcome through the resolver seam with an
in-memory stand-in for the 2.24.1 provider, including the ones the emulator run
does not manufacture.

| Group | Cases |
| --- | --- |
| Placement and threading | Provider calls run on the worker; reports wait for main delivery; inspection never writes |
| Disclosure | Declining writes nothing; an already provisioned collection needs no disclosure |
| Fresh install | Note type, deck and notes written once; template installed unchanged; front hides the answer; deck exists before the note type binds it |
| Read-back | A wrong stored template is incomplete, not successful; a model the provider never returns is incomplete |
| Reuse and repeatability | A matching note type is reused and never rewritten; notes still reach the demo deck; a second run duplicates nothing; a partial state is completed; an existing demo deck skips demo content; a deck created mid-run is treated as provisioned |
| Conflict | Differing fields stop with a named conflict; two note types named `VoiceQA` stop rather than guessing |
| Failing safely | `packageUnavailable`, `apiDisabled`, `accessDenied` before any resolver call; permission revoked mid-run keeps its progress; a null cursor is never success and rechecks a component disabled during the run; an unexpected fault stays unknown; a refused note insert reports how many landed; a sample note that misses the demo deck is not counted |

`VoiceQaNoteTypeTest` (7 cases) checks the loader against the fixture text and
that the resource is on the runtime classpath. `ShellControllerTest` adds 11
cases for the setup action, disclosure, decline, deck re-read and lifecycle.
`tests/test_av039_note_type.py` (6 cases) fails if the derived resource is stale
or escapes a value that a properties parser would not return unchanged.

## What this does not establish

- Emulator only. Physical-device behaviour, and any behaviour of an AnkiDroid
  other than the pinned 2.24.1, remain unverified.
- No sync ran. That adding a note type forces AnkiDroid's next sync to be a
  one-way full sync is Anki's documented schema rule and the reason for the
  disclosure; this run did not perform a sync to observe it, and the AVD was
  never signed in to AnkiWeb.
- A null cursor with no established cause was not manufactured on the device.
  AV-023 recorded one from a real device state; here it is covered by JVM tests.
- The app offers no second write once setup is complete, so the on-device repeat
  is an inspection. A second `provision` call is covered by `ProvisioningTest`.
- The conflict case was produced by renaming one field of an installed VoiceQA.
  A note type with a different field *count* or an unrelated origin follows the
  same ordered-field-name rule but was not separately staged on the device.
- Reuse compares ordered field names only, which is the decided policy: a
  matching note type is reused as-is, so an edited template on a pre-existing
  VoiceQA is not detected or corrected. Read-back applies to a note type this
  run wrote.
- A run that fails partway through adding sample notes leaves the demo deck in
  place, and later runs skip demo content while it exists. The report says so
  and tells the learner to delete that deck and run setup again.

GitHub Actions was not triggered for these changes: the implementation has not
been pushed and no pull request was created. These are local executions of its
commands, not a claim of a remote CI run.
