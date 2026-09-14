# AV-004: AnkiDroid review access

- Issue: [#4 — Investigate AnkiDroid review access on an Android emulator](https://github.com/BrockBadeaux14/AnkiVoice/issues/4).
- Result: **constrained go** for AnkiDroid 2.24.1 on the environment below.
- Status: ready for review; this report does not accept the issue or advance dependent work.
- Investigated September 14, 2026. Dependencies #1 and #2 were closed; the issue had no discussion comments or other blocking dependencies.

The released ContentProvider selected the scheduled VoiceQA cards, exposed their
content and scheduling state, and saved seven intended test reviews across the
baseline and limits collections. Separate native-reviewer runs matched those
cards, their order, rating intervals and resulting scheduling. This supports the
Android path **only with the verification and pause behavior below**.

**A return value of `1` is not proof of a saved review.** In an observed stale-card
case, the provider returned `1`, card state stayed unchanged, and no review was
added. The backend logged `BackendInvalidInputException: card was modified`.
This limitation must inform #7's contracts, #23's implementation decision, and
#25/#20's submission handling. Do not treat this spike as permission to retry
ambiguous writes automatically.

## Pinned environment

| Component | Tested value |
| --- | --- |
| Host | macOS 26.6.2 (25G83), ARM64 |
| Disposable AVD | `AnkiVoice_AV004`, serial `emulator-5584`, `medium_phone`, 1080×2400 at 420 dpi, 2 GiB RAM |
| Android image | API 36 / Android 16, `google_apis_playstore;arm64-v8a`, revision 7 |
| Android build | `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094:user/release-keys` |
| Emulator / adb | 37.1.11.0 (15917651) / 37.0.1 (15733141) |
| AnkiDroid | Released `AnkiDroid-2.24.1-arm64-v8a.apk`, version code `322401300` |
| APK SHA-256 | `3012692ca67b856b287430715f99ca6150e471588326f6c8c45f27bbe895afbf` |
| API source | `v2.24.1`, commit `9f579c10bb151146728220729c510acbbd8faba7`; direct ContentResolver, no wrapper library |
| Probe build | Android build tools 36.0.0, compile SDK 36, target SDK 35, JBR 25.0.2 with Java 8 output |
| Fixtures | AV-002, Anki Python backend 25.9.2; generated `2026-09-14T19:28:03Z` in America/Chicago |
| Scheduler | v3, SM-2, FSRS off; 04:00 local rollover; learn-ahead 0; learning 1/10 minutes; relearning 10 minutes |

The AVD was newly created, used only synthetic collections, and remained signed
out of AnkiWeb. Android reported zero accounts. The existing `Medium_Phone` AVD
was not used. Cloud speech, physical devices and synchronization were not involved.

Official references, accessed September 14, 2026:
[release and APK](https://github.com/ankidroid/Anki-Android/releases/tag/v2.24.1),
[API guide](https://github.com/ankidroid/Anki-Android/wiki/AnkiDroid-API),
[pinned contract](https://github.com/ankidroid/Anki-Android/blob/9f579c10bb151146728220729c510acbbd8faba7/api/src/main/java/com/ichi2/anki/FlashCardsContract.kt),
[pinned provider implementation](https://github.com/ankidroid/Anki-Android/blob/9f579c10bb151146728220729c510acbbd8faba7/AnkiDroid/src/main/java/com/ichi2/anki/provider/CardContentProvider.kt).

## Results against the acceptance criteria

| Criterion | Observed result / evidence |
| --- | --- |
| Reproducible setup, import and reset | Both modern `.colpkg` packages imported through the native collection importer. Re-import restored original IDs, counts, cards and history. Environment, hashes and manifests are retained. See the [runbook](av004/runbook.md). |
| Deny, grant, revoke, disabled and unavailable API | Native permission denial and adb revocation produced `SecurityException` on `/decks`. Grant enabled access. Switching off “Enable AnkiDroid API” returned a null cursor; disabling the AnkiDroid package did likewise. All failed-access attempts preserved the original 11 history rows and scheduling. The probe stopped at its read preflight; these are not claims that it attempted a denied write. Package disablement simulated unavailability; uninstall/reinstall was not tested. |
| Scheduled identity and VoiceQA content | `selected_deck` update returned `1`; `schedule` returned note ID, ordinal `0`, four buttons, four interval strings and empty media. `cards` exposed card ID, note ID, deck ID, rendered question/answer and scheduling fields. `notes` exposed all six unit-separator-delimited fields; `models` exposed their ordered names. IDs matched the generated manifests. Fronts contained the prompt and excluded the reference answer. |
| Native queue comparison | Baseline API/native order: learning → relearning → mature → new; exhaustion followed. Suspended, manually buried, sibling-buried and future cards remained unchanged. Limits API/native order: review-1 → new-1 → review-2; two unseen cards and one due review remained withheld. Native rating intervals and resulting scheduling matched the API run. |
| Rating and verification | Each of the seven core submissions used Easy (`4`) and `time_taken=12345` ms. Each added exactly one intended review with that rating/time; no other card changed. The baseline has intermediate database captures after each submission; limits has API state captures per submission and database captures around the full run. |
| Capability matrix | Findings and explicit fallbacks below cover time, verification, stale state, invalid ratings, ambiguity, skip and native undo. |
| Decision and reusable evidence | Constrained go, with raw JSON, native UI trees/screenshots, a source-linked runbook, and a runnable evidence validator included as repository artifacts for review. |

### Baseline transitions

IDs are from this run's manifest; never reuse them in a freshly generated collection.
All four submissions used ordinal `0`, rating `4`, and 12,345 ms.

| Fixture | Note/card ID (equal in these fixtures) | Type → type | Reps | Due → due | Interval days | History rows |
| --- | --- | --- | --- | --- | --- | --- |
| learning | 1789414083107 | 1 → 2 | 1 → 2 | 1789414023 → 93 | 0 → 3 | 1 → 2 |
| relearning | 1789414083108 | 3 → 2 | 4 → 5 | 1789414023 → 92 | 1 → 2 | 4 → 5 |
| mature | 1789414083109 | 2 → 2 | 3 → 4 | 90 → 187 | 30 → 97 | 3 → 4 |
| new | 1789414083106 | 0 → 2 | 0 → 1 | 1 → 93 | 0 → 3 | 0 → 1 |

The learning/relearning due inputs are Unix seconds; review due values are
collection-relative scheduler days. The new input is a position. These units
cannot be interchanged. All four final queues were review (`2`). Native review
times differed because they measured the UI interaction; scheduling matched.

## Capability matrix and downstream contract

| Capability | Evidence and limitation | Required fallback/contract |
| --- | --- | --- |
| Review time | 12,345 ms persisted exactly. An additional 98,765 ms input persisted as 60,000 ms, matching this deck's `maxTaken=60` seconds. | Measure elapsed time with a monotonic clock, submit nonnegative milliseconds, and account for the configured cap. Do not infer a different unit from capped data. |
| Result verification available to an ordinary app | After successful updates, direct card queries showed the same card/note/ordinal, `reps + 1`, a populated `last_review_time_secs`, and the expected type/queue/due/interval transition. These fields are readable without root or filesystem access. | Capture identity and state before a write; require a fresh scheduled identity; submit once; read the same card afterward. For this constrained single-client run, require a consistent one-review transition. Unexpected deltas, null/error responses or unavailable post-state are unconfirmed outcomes and must pause. |
| Limits of verification | The reviewed contract exposes neither a full revlog endpoint nor a transaction/idempotency key or atomic compare-and-write. Reps/time cannot attribute a competing native/sync write to this caller. | Single active reviewer only. Never automatically replay an unconfirmed request. App switching, process loss, possible concurrent modification or sync requires stopping the session and reconciliation. #20 owns recovery; #28 must test sync handoff and stale-session behavior. |
| Stale card | Force-stopping/reopening AnkiDroid after the first limits answer rebuilt the queue: the previously offered new-1 was replaced by review-2. Raw submission of new-1 returned `1`, but did not change state/history. | A previous “next card” is not a reservation. Re-query immediately before commit and compare note/card identity, ordinal, deck and stored state. Even then a race remains; retain post-write verification and the pause rule. The ordinary probe path checks fresh identity; `raw-answer` deliberately bypasses it for synthetic negative experiments. |
| Stale collection / missing deck | Pinned source returns an empty schedule for an unknown deck and provides no stable collection-generation token. Collection replacement with active pending answers was not separately exercised. | Validate the selected deck and current VoiceQA note/card/model/field identity; a missing deck is not evidence of normal exhaustion. Stop on any app handoff, replacement or sync. Re-establish the collection and discard pending answers; #28 must validate this boundary. |
| Invalid ratings | Raw `0` and `5` each returned `0`; no history or scheduling change. The valid four options were exposed for every tested state. | Accept only the offered rating range and verify the result. Do not convert rejection into Again. |
| Access unavailable | Denied/revoked permission threw; API disabled and package disabled returned null cursors. Re-enabling restored reads. No review was written in these cases. | Pause with explicit retry/manual controls after access is restored. Null cursor is distinct from an empty, valid queue. |
| Ambiguous response | Observed `update_count=1` without a saved review. No transport-kill timing experiment was needed to establish that acknowledgement alone is insufficient. | No automatic retry, next-card advance, or success announcement without consistent post-state. |
| Skip | No non-mutating skip operation in the reviewed contract. Bury and suspend exist, but change scheduling. They were not exercised here. | Pause/exit without a write. Do not emulate skip by rating, burying, or sorting another queue. A product skip policy belongs in #7/#25. |
| Native undo | AnkiDroid's native toolbar Undo reversed an API-submitted relearning review in the same process: the original scheduling/history returned, as did the offered card. No external undo endpoint was found in the pinned contract. Persistence across process death was not tested. | Prefer correction before commit. A user may use native Undo as an explicit handoff; the app must stop and reload afterward. Do not promise programmatic or durable undo. |

The filesystem copies used for test assertions are **offline evidence only**.
They are not the application's verification route and do not justify giving the
future app storage access to AnkiDroid's database.

## Validation and evidence

The included [evidence directory](av004/evidence/) contains the original fixture
manifests, per-call responses, offline card/note/revlog snapshots, native UI trees,
two screenshots, and the stale-submission error excerpt. The
[environment record](av004/evidence/environment.json) pins source/APK/package
hashes; full APKs, databases and temporary output remain under ignored
`build/av004/` on the test machine.

```sh
python3 tools/av004-probe/validate_evidence.py
# PASS: 161 evidence assertions (captured run; not a new emulator execution)
bash tools/av004-probe/build.sh
# APK compiled, aligned, signed, verified, installed and exercised on the AVD
.venv/bin/python -m unittest discover -s tests -v
# Ran 12 tests: OK
.venv/bin/python -m pip check
# No broken requirements found
git diff --check
```

The validator checks identities, history deltas, no-write cases, rating/time
values, excluded cards, native parity, queue exhaustion after restart, capped
time, and undo. It rechecks captured evidence; it does not claim to rerun the
emulator. SQLite copies passed integrity checks using a case-insensitive collation
for these ASCII synthetic fixtures.

The optional capability gaps above are explicit fallbacks within this spike's
scope. Physical hardware, multiple clients, synchronization, transport/process
failure recovery, other AnkiDroid/Android versions and production adapter behavior
remain unverified. No product adapter, custom scheduler, provider integration,
pull request, merge or deployment is part of this result.
