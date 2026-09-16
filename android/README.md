# AnkiVoice for Android

- Issue: [#48 — AV-041: Bootstrap the Android build and port the AV-007 contract types](https://github.com/BrockBadeaux14/AnkiVoice/issues/48).
- Decision: [AV-022: Android implementation](../docs/decisions/0022-android-implementation.md)
  (native Kotlin, one Gradle build, five modules).
- Specification: [AV-007: Integration contracts and review lifecycle](../docs/contracts/av007-session-contracts.md).

This is the Gradle build and the Kotlin copy of the AV-007 contracts that every Android
card builds on. AV-023 (#24) adds the Compose shell, AnkiDroid access preflight and deck
selection, permission onboarding, app-private settings, and a debug sample-session
preview. See the [AV-023 results](../docs/testing/av023/results.md) and
[runbook](../docs/testing/av023/runbook.md). AV-039 (#10) adds VoiceQA note type
provisioning behind an explicit setup action: see the
[AV-039 results](../docs/testing/av039/results.md) and
[runbook](../docs/testing/av039/runbook.md). Real card access, speech, network access and
ReviewSession integration belong to #25, #26, #17 and #14.

## Build

Requirements:

- A JDK 17 or newer to run Gradle. The build emits Java 17 bytecode whatever JDK runs it.
- The Android SDK with `platforms;android-36` and `build-tools;36.0.0`. Point
  `ANDROID_HOME` at it, or put `sdk.dir=…` in `android/local.properties`, which is
  gitignored.

```sh
cd android
./gradlew checkModuleBoundaries :core:test assembleDebug
./gradlew :ankidroid:testDebugUnitTest :app:testDebugUnitTest :app:assembleRelease :app:lintDebug
```

On Windows, run `gradlew.bat` with the same arguments.

| Task | What it checks |
| --- | --- |
| `checkModuleBoundaries` | The module graph follows AV-022 (see [Modules](#modules)). It also runs as part of `check`. |
| `:core:test` | The contract-level JVM tests and the Kotlin half of the drift guard. |
| `assembleDebug` | All five modules compile, and `:app` produces `app/build/outputs/apk/debug/app-debug.apk`. |
| `:ankidroid:testDebugUnitTest :app:testDebugUnitTest` | AV-023's access classification, deck selection, session cancellation and lifecycle regression tests, and AV-039's provisioning outcomes and note type loader. |
| `:app:assembleRelease :app:lintDebug` | Release compilation without fakes; Android lint. |

[`.github/workflows/android.yml`](../.github/workflows/android.yml) runs both commands on
`ubuntu-24.04` with Temurin 17, for every push and pull request. The
[`VoiceQA fixtures`](../.github/workflows/fixtures.yml) workflow runs the Python half of
the drift guard.

## Pins

Every version is pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml),
except the Gradle wrapper, which is pinned in
[`gradle/wrapper/gradle-wrapper.properties`](gradle/wrapper/gradle-wrapper.properties).

<!-- av041:pins:begin -->

| Pin | Value | Source |
| --- | --- | --- |
| Gradle wrapper | `9.3.1`, `all` distribution, SHA-256 `17f277867f6914d61b1aa02efab1ba7bb439ad652ca485cd8ca6842fccec6e43` | AV-022 baseline |
| Android Gradle plugin | `9.1.0` | AV-022 baseline |
| Compile SDK | `36` | AV-022 baseline |
| Target SDK | `35` | AV-022 baseline |
| Minimum SDK | `33` | AV-022 baseline (install floor, not a support claim) |
| Build tools | `36.0.0` | AV-022 baseline |
| Bytecode | Java `17` | AV-022 baseline |
| Kotlin | `2.4.20`: the Kotlin JVM plugin, the Compose compiler plugin and `kotlin-reflect` (tests only) | AV-041 |
| Compose BOM | `2026.06.01`, which resolves Compose `1.11.4` and supplies `material3` | AV-041 |
| AndroidX Activity | `androidx.activity:activity-compose` `1.13.0` | AV-041 |
| JUnit | `6.1.3`, through `org.junit:junit-bom` (Jupiter and the platform launcher) | AV-041, tests only |

<!-- av041:pins:end -->

Why these AV-041 pins:

- **Kotlin 2.4.20** was the newest stable Kotlin when this was pinned. AGP 9's built-in
  Kotlin support compiles the Android modules with the Kotlin Gradle plugin on the root
  classpath, so this one version covers `:core`, the Android modules and the Compose
  compiler.
- **Compose BOM 2026.06.01** is the newest BOM that compiles against SDK 36. BOMs from
  `2026.08.00` onward resolve Compose 1.12, whose AAR metadata requires compile SDK 37.
  AGP 9.1.0 recommends 36 as its maximum, and moving compile SDK is outside AV-022's
  baseline. `checkDebugAarMetadata` fails with the newer BOM.
- **Activity 1.13.0** was the newest stable `activity-compose`, and it compiles against
  SDK 36.

Build JDK: the validation below ran Gradle on Android Studio's JBR 25.0.2, the JDK in
AV-022's baseline. CI runs it on Temurin 17.

## Modules

```mermaid
flowchart TD
  app[":app"] --> core[":core"]
  app --> ankidroid[":ankidroid"]
  app --> speech[":speech"]
  app --> provider[":provider"]
  ankidroid --> core
  speech --> core
  provider --> core
```

| Module | Kind | Contents now | Owner of what comes next |
| --- | --- | --- | --- |
| `:core` | Kotlin/JVM, no Android plugin or dependency | The AV-007 contract port in `org.ankivoice.core.contracts`; the fakes in its `testFixtures` source set | #14, #13, #25, #16/#18, #15, #20 |
| `:ankidroid` | Android library | Access preflight; `decks` reads and verified `selected_deck` updates; AV-039 VoiceQA provisioning with read-back | #25 |
| `:speech` | Android library | A marker object; no platform calls | #26 |
| `:provider` | Android library | A marker object; no platform calls or network access | #17, #18 |
| `:app` | Android application | Single-activity shell, onboarding, the VoiceQA setup action and its full-sync disclosure, private settings, lifecycle delivery and debug sample session | #27, #17 |
| `:core` | Kotlin/JVM, no Android plugin or dependency | The AV-007 contract port in `org.ankivoice.core.contracts`; AV-015's rule-based grading in `org.ankivoice.core.grading`; the fakes in its `testFixtures` source set | #14, #13, #25, #18, #15, #20 |
| `:ankidroid` | Android library | Access preflight; `decks` reads and verified `selected_deck` updates | #25, #10 |
| `:speech` | Android library | A marker object; no platform calls | #26 |
| `:provider` | Android library | AV-020's credential store, free-route guard, durable quota ledger, content-free diagnostics and the one HTTPS seam | #18 |
| `:app` | Android application | Single-activity shell, onboarding, private settings, lifecycle delivery and debug sample session | #27, #17 |

`checkModuleBoundaries` fails the build when:

- a module depends on a project AV-022 does not allow;
- a module other than `:provider` declares `android.permission.INTERNET`, `:provider`
  stops declaring it, or any module declares `android.permission.READ_PHONE_STATE`;
- `:app` stops depending on all four other modules;
- `:core` applies an Android plugin or declares an `android*`, `androidx*` or
  `com.google.android*` dependency;
- a feature variant of `:core`, such as its test fixtures, is added to a configuration
  that a release build can see.

### The fakes

The in-memory fakes live in `core/src/testFixtures`, so they reach JVM tests and debug
builds only:

```kotlin
testImplementation(testFixtures(project(":core")))  // in :core this is automatic
debugImplementation(testFixtures(project(":core")))  // what :app declares
```

`:app` proves both sides. Its `src/debug` composition root reads the demo collection from
the fakes. Its `src/release` counterpart supplies no card provider: study is unavailable
while onboarding and deck selection remain usable. The release APK contains no
`org.ankivoice.core.fakes` classes.

## The contract port

`:core` ports the part of [`tools/av007_contracts.py`](../tools/av007_contracts.py)
before `GuardedReviewWriter`, and all of [`tools/av007_fakes.py`](../tools/av007_fakes.py)
except `FakeReviewWriter`. The Python binding is unchanged and stays the executable
reference.

| Python | Kotlin (`org.ankivoice.core.contracts`) |
| --- | --- |
| `CardIdentity`, `CardState`, `VoiceQAFields`, `ScheduledCard`, `QueueExhausted`, `VOICEQA_MODEL` | Same names. `QueueExhausted` is a `data object`. |
| `Capabilities` | `Capabilities`, with the same defaults. A negative cap throws `IllegalArgumentException`. |
| Five `*Failure` enums, `ALL_FAILURE_MODES`, `Failure` | Five enums under `sealed interface FailureMode`, plus `Contract`, `failureModes(contract)`, `ALL_FAILURE_MODES` and `Failure`. |
| `ScheduledCard \| QueueExhausted \| Failure` and the other unions | The sealed results `CapabilitiesResult`, `NextCardResult` (which includes `ReadCardResult`) and `GradingOutcome`. |
| `Utterance`, `UtterancePurpose`, `GradingContext` and their builders | Same names: `questionUtterance`, `revealUtterance`, `elaborationUtterance`, `gradingContext`, `cardLanguage`. |
| `GradeLabel`, `AUTOMATIC_PROPOSALS`, `GradingResult.proposed_rating` | `GradeLabel.automaticProposal`, an exhaustive `when`, and `GradingResult.proposedRating`. |
| `OperationToken`, `TranscriptKind`, `Confidence`, `CaptureEvent`, `PlaybackResult`, `GradingRequest`, `GradingReply`, `ConfirmationSource`, `RatingConfirmation` | Same names. `CaptureEvent` and `PlaybackResult` are sealed. |
| `ReviewState`, `TERMINAL_REVIEW_STATES`, `ReviewIntent`, `RawAcknowledgement`, `ReviewOutcome` | Same names. `ReviewState.isTerminal`; `RawAcknowledgement` is sealed. |
| `CardProvider`, `SpeechOutput`, `SpeechInput`, `Grader`, `ReviewTransport`, `ReviewWriter` | Interfaces with the same operations. |
| `MonotonicClock` | `fun interface MonotonicClock`, plus `SystemMonotonicClock` backed by `System.nanoTime`. |
| `tools/av007_fakes.py` | `org.ankivoice.core.fakes`: `FakeClock`, `FakeCollection`, `StoredCard`, `RecordedReview`, `FakeCardProvider`, `WriteAnomaly`, `FakeReviewTransport`, `FakeSpeechOutput`, `FakeSpeechInput`, `FakeGrader`, `demoCard` and `demoCollection`. |

Left for later cards: `is_one_review_transition`, `GuardedReviewWriter` and
`FakeReviewWriter` go to #25. `ReviewSession`, `SessionState`, `Halt`, `Event`,
`Interruption`, `transcript()` and the 53 scenarios go to #14.

### Representation choices

These keep the specification's meaning while using Kotlin types:

- **Every operation result is exhaustively matchable.** Where Python returns a union, or a
  value with an optional failure, Kotlin returns a sealed type:
  - `PlaybackResult` is `Completed` or `Failed`.
  - `CaptureEvent` is `Transcript` or `Failed`.
  - `RawAcknowledgement` is `UpdateCount`, `NullResponse` or `ErrorResponse`.

  The specification says a failure takes precedence over any accompanying text. The
  Python binding can express a capture with both text and a failure; in Kotlin a failed
  capture has no text field at all.
- **Specification spelling.** Enum constants keep the Python member names (`ACCESS_DENIED`),
  and each enum's `specName` is the specification's spelling (`accessDenied`,
  `outcome-unknown`, `learner-corrected`). `Failure.toString()` prints
  `CardProvider.accessDenied: detail` where Python prints `CardProvider.access_denied: detail`.
- **No failure becomes a rating.** `Failure` carries a mode, a detail and a cause, and
  nothing else. `FailureTaxonomyTest` scans every compiled `:core` class. It fails if a
  public method takes a failure, or a result that can hold one, and returns an `Int`.
- **Caller ordering errors** throw `IllegalStateException` where Python raises
  `ValueError`, for example `ReviewIntent.correct` after commit.
- **Units.** IDs, `due`, Unix-second times and milliseconds are `Long`. Ratings, counts,
  ordinals and revisions are `Int`. Tuples become `List`.
- **`ReviewIntent` stays mutable**, as in the binding, because #14 and #25 advance it in
  place. Its `state`, `elapsedMs`, `token`, `confirmation` and `interrupted` setters are
  `internal` to `:core`.
- **Operations are synchronous**, like the binding. AV-022 places each contract but fixes
  no Kotlin method signatures. Delivery across threads, whether by coroutines or
  callbacks, belongs to the cards that implement the transports and the session: #14,
  #25 and #26.
- **The fakes are `open`** so a test can override one call, where the Python tests
  replace a bound method. Script entries are typed (`FakeSpeechInput.Say`, `Fail` and
  `Deliver`, and the equivalents for the other fakes). A `null` provider script entry
  falls through to normal behaviour, like Python's `None`. Stand-in scheduling rounds
  ties to even, as Python's `round()` does, so both fakes produce the same card states.

### Specification and binding discrepancies

Two field names found during the port differ between the specification and the
binding. Both were resolved in favour of the specification. The binding and its tests
are unchanged.

| Where | Specification | Python binding | Kotlin |
| --- | --- | --- | --- |
| `ReviewWriter.commit` input | `ReviewIntent(cardSnapshot, …)` | `ReviewIntent.card` | `ReviewIntent.cardSnapshot` |
| `Grader.grade` input | `GradingRequest(operationToken, …)` | `GradingRequest.token` | `GradingRequest.operationToken` |

One non-semantic difference: the specification's CardProvider failure table lists
`nullCursor` fourth, while the binding declares it last. The Kotlin enum follows the
binding's order, so the drift guard can compare lists in order.

## VoiceQA provisioning

`:ankidroid` owns AV-039 (#10). `AnkiDroidProvisioning` installs the VoiceQA note type
when it is missing, reuses it when its ordered field names match the fixture, and stops
with a named conflict when they do not. `:app` supplies the explicit setup action and the
full-sync disclosure; nothing provisions on its own.

| Type | What it is |
| --- | --- |
| `VoiceQaNoteType` | The installable specification: name, ordered fields, CSS, one template, the demo deck and its four sample notes |
| `ProvisioningPlatform` | The resolver seam, extending AV-023's `AccessPlatform` with `models`, `models/<id>/templates`, `decks`, `notes`, `notes/<id>/cards` and the card move |
| `ProvisioningStatus` | `Complete`, `Declined`, `Incomplete(reasons)`, `Conflict(differences)` or `Failed(failure)` |
| `ProvisioningReport` | The status plus how far the run got: a `ProvisioningStep` per item and the number of sample notes added |
| `Provisioner` | `inspect`, which never writes, and `provision(fullSyncAccepted)`, which writes nothing without it |

`AnkiDroidProvisioning` and `AnkiDroidAccess` share one serial worker in `:app`'s
composition root, so a deck read and a collection write never overlap. Provisioning's
only writes are a `models` insert with its template update, a `decks` insert, `notes`
inserts, and a move of the cards that run just created. It reports failures with AV-007's
names: `packageUnavailable`, `apiDisabled`, `accessDenied` and `nullCursor`.

After writing a note type it is read back through `models` and its templates and compared
with the fixture; a mismatch is reported as incomplete, naming what differs. The
[AV-039 results](../docs/testing/av039/results.md) record the confirmed provider route and
the pinned-emulator evidence.
## Rule-based grading

- Issue: [#16 — AV-015: Implement rule-based grading and rating policy](https://github.com/BrockBadeaux14/AnkiVoice/issues/16).

`RuleGrader.grade(context)` in `org.ankivoice.core.grading` is a pure, deterministic
policy over the `GradingContext`. It returns `GradingResult(correct, reason)`, which the
existing mapping turns into a proposed Good, or `null`. A null means there is no
rule-based label and no proposed rating. The caller then asks the AI grader (#18) if one
is available, and otherwise the learner for an explicit self-grade. When the rules match,
no AI grading request is sent for that transcript revision.

The rules never conclude `incorrect`, `partial` or `uncertain`, and never propose Again,
Hard or Easy. Every result is a suggestion that needs the learner's confirmation, which
#14 and #25 enforce. The rules do not read the prompt or RequiredConcepts; concept
coverage belongs to #18.

| Step | Rule |
| --- | --- |
| Normalization | Applied to the transcript, ReferenceAnswer and each AcceptedAnswers line: NFKC, full case folding, punctuation removed, whitespace collapsed and trimmed. There is no stemming, synonym table, stop-word removal or reordering. |
| Exact match | The normalized transcript equals the ReferenceAnswer or a nonblank AcceptedAnswers line. The reason names the reference answer or accepted answer *n*, numbered by its line. |
| Fuzzy match | Tried only without an exact match. The word lists are the same length and differ in one word. That word has at least five letters on both sides, is alphabetic, and is one slip apart: one inserted, deleted or substituted letter, or two adjacent letters swapped. The reason names both words. |
| Protected words | A fuzzy match never touches digits (a fuzzy word must be alphabetic) or a word in `NUMBER_WORDS`, `NEGATIONS` or `UNITS`. Any word ending in `n't` counts as a negation. |
| Anything else | `null`, including an empty or punctuation-only transcript. |

Exact matches win over fuzzy ones. Within each step, the reference answer is tried first,
then the accepted answers in order.

Some punctuation choices are more conservative than plain removal, and the tests cover
each one:

- **Apostrophes.** An apostrophe between two word characters is kept and written as `'`,
  so "isn’t" and "isn't" agree. Any other apostrophe is removed.
- **Numbers.** Punctuation between two digits is kept, so "3.5" never matches "35" or
  "3 5".
- **Unit signs.** `%`, `‰`, `‱` and the prime signs are units, although Unicode classes
  them as punctuation. They are kept, so "50" never matches "50%".
- **Separators.** Every other punctuation mark becomes a space, so "green,blue" and
  "green blue" agree.

Known limitation: one slip can join two different words, such as "round" and "sound".
That is accepted because the result is a labelled suggestion that always needs
confirmation.

`RuleGraderTest` covers normalization, the exact and fuzzy positives, and the near-miss
negatives, including a one-letter slip on every protected word of five or more letters.
`VoiceQAFixtureGradingTest` runs every expected case in
[`fixtures/voiceqa/note-type.json`](../fixtures/voiceqa/note-type.json), read from that
file. The `:core` test task passes the file's path and declares it as an input, so a
fixture edit reruns the test. Two of the nine cases match: "Green, blue, red." and
"Five.". The other seven get no label, and no case marked `incorrect` or `partial` is
ever labelled.

## Provider credentials, usage controls and diagnostics

- Issue: [#17 — AV-020: Add provider credentials, usage controls and useful diagnostics](https://github.com/BrockBadeaux14/AnkiVoice/issues/17).
- Decision: [AV-006: Speech and grading providers](../docs/decisions/0006-speech-and-grading-providers.md).
- Evidence: [AV-020 runbook](../docs/testing/av020/runbook.md) and
  [`tools/av020-qa/validate.py`](../tools/av020-qa/validate.py).

`:provider` implements the free-only grading route. It is the app's **only** network
route: it alone declares `android.permission.INTERNET`, and `checkModuleBoundaries` fails
the build if that moves. No phone-state permission exists anywhere. Nothing here decides
a grade: #18 owns the grading instruction, the reply's content and the label policy, and
`:provider` carries the request and the reply text.

| Part | What it does |
| --- | --- |
| `KeystoreCredentialStore` | The key is entered at runtime, wrapped by a non-exportable Android Keystore AES/GCM key, and held in app-private storage. It is in no `BuildConfig` field, resource, asset or log, and the UI only ever reports *that* a key is saved. It can be replaced or cleared. |
| `FreeRoute` | AV-006's ported guard. `liquid/lfm-2.5-2.6b:free` through `liquid/fp8`, `allow_fallbacks=false`, zero maximum prompt/completion/request prices, temperature 0, JSON object output, a 1,024-token cap, and no tools, plugins, search or router. Before each session it checks the pinned endpoint for zero prices; changed, nonzero or unknown prices refuse the route. A reply served by another model or provider, or without a verified zero cost, is refused too. |
| `HttpsUrlTransport` | HTTPS only, and a 3xx is a refusal: the pinned endpoint cannot be moved by a reply. The key travels in the Authorization header and nowhere else. |
| `QuotaLedger` | Durable, append-only, flushed to the filesystem **before** dispatch, so a timeout or process death still consumes the allowance. At most 30 requests per session and a configurable daily limit, default 50 per UTC day, settable only between 0 and 1,000. A 402, a 429, an unverified cost or a refused route stops grading for the rest of the UTC day. Nothing retries automatically. |
| `Diagnostics` | Timings, failure names, request and reservation counts, and the reported cost. Every detail passes through `CredentialPolicy.redact`. Keeping transcripts and card text for #29's report is an explicit opt-in, off by default and clearable. No `:provider` API accepts audio bytes, which a test enforces. |
| `GradingProvider` | Ties them together. A missing key, an unacknowledged disclosure, a guard refusal, an exhausted allowance, a 401/403, a 402/429, a timeout or a provider error each becomes a #7 `Grader` failure (`quotaExhausted`, `providerError`, `graderTimeout`, `outputTruncated`, `unparsableResponse`) or unavailable grading. None is ever a rating; self-grading always remains. |

`:app` adds the screens to AV-023's shell and its private settings store: credential
entry, the retention disclosure, the allowance with its configurable daily limit, and the
diagnostics card. The disclosure lists what is sent (the answer text, Prompt,
ReferenceAnswer, RequiredConcepts, AcceptedAnswers), what is never sent (audio, Extra,
card or note IDs, collection data) and the limits AV-006 recorded, and grading stays off
until it is acknowledged. Replacing the key re-arms it. The wrapped key and the ledger are
excluded from backup and device transfer by
[`data_extraction_rules.xml`](app/src/main/res/xml/data_extraction_rules.xml), on top of
`allowBackup="false"`.

The tests make no live provider call, in CI or locally: `:provider` drives the whole path
through a fake transport. What a JVM test cannot show — one real request over the live
route — is the runbook's recorded smoke run on the pinned macOS ARM64 AVD, which uses the
owner's own key and is counted in the ledger. That run is **not yet recorded**.

AV-023's evidence check in [`tools/av023-qa/validate.py`](../tools/av023-qa/validate.py)
asserted that no APK carried `INTERNET`. It now says explicitly that it is checking
AV-023's retained evidence build, which predates this card; the rule that replaces it for
current builds is the module-boundary check above. `READ_PHONE_STATE` stays forbidden in
both.

## Drift guard

[`tools/av041_manifest.py`](../tools/av041_manifest.py) derives
[`core/src/test/resources/av007/manifest.txt`](core/src/test/resources/av007/manifest.txt)
from the Python binding. The manifest covers:

- contract names and operations, and the `ReviewTransport` seam;
- the failures under each contract;
- the capability flags and their defaults;
- the review states and which are terminal;
- the grade labels and their proposals.

Each check fails in its own place:

- `tests/test_av041_android.py` fails if the checked-in manifest is stale, or if a
  name is not spelled as the specification spells it.
- `ManifestDriftTest` in `:core` fails if the Kotlin port differs from the manifest.

After an intentional change to `tools/av007_contracts.py`:

```sh
python tools/av041_manifest.py --write
python -m unittest tests.test_av041_android -v
cd android && ./gradlew :core:test
```

AV-039 uses the same shape for the note type itself.
[`tools/av039_note_type.py`](../tools/av039_note_type.py) derives
[`ankidroid/src/main/resources/av039/voiceqa-note-type.properties`](ankidroid/src/main/resources/av039/voiceqa-note-type.properties)
from [`fixtures/voiceqa/note-type.json`](../fixtures/voiceqa/note-type.json). The app
cannot read that JSON directly: `org.json` is stubbed in JVM unit tests and AV-022's pins
add no JSON library, so the derived resource is read with `java.util.Properties`, which
both the host JVM and Android supply. `tests/test_av039_note_type.py` fails if the
checked-in copy is stale or escapes a value a properties parser would not return
unchanged; `VoiceQaNoteTypeTest` fails if the Kotlin loader disagrees with the resource,
or if the resource is not packaged where the app looks for it.

After an intentional change to `fixtures/voiceqa/note-type.json`:

```sh
python tools/av039_note_type.py --write
python -m unittest tests.test_av039_note_type -v
cd android && ./gradlew :ankidroid:testDebugUnitTest
```

## What this does not establish

- AV-041's original validation built the debug APK without launching it. AV-023 now
  records a focused onboarding/deck/session run on the pinned macOS ARM64 AVD in its
  [results](../docs/testing/av023/results.md). Physical-device behavior remains unverified.
- AV-041's original local validation ran on a Windows 11 x86_64 host, not the macOS ARM64 evidence host
  with AV-022's validated pins. A JVM build and its unit tests do not depend on the host.
  Any device or emulator evidence in later cards still needs the pinned host.
- The fakes are not a scheduler. They cannot prove timing, threading or Android lifecycle
  behaviour.
- AV-039's provider route is confirmed on the pinned emulator with AnkiDroid 2.24.1 only.
  No sync was performed, so the full-sync consequence the disclosure states is Anki's
  documented schema rule rather than something this build observed. See the
  [AV-039 limits](../docs/testing/av039/results.md#what-this-does-not-establish).

## AnkiDroid adapter and review safeguards

AV-024 (#25) adds `AnkiDroidCardProvider`, `AnkiDroidReviewTransport` and the
platform-independent `GuardedReviewWriter`. The existing `AndroidAccessPlatform`
owns every resolver call. All production calls run on the composition root's
serial worker; token-tagged read overloads deliver on the main executor.

The provider resolves the selected deck's `maxTaken`, reads per-card answer
buttons from `schedule`, resolves the full card/note/model identity, and splits
VoiceQA fields on the unit separator. Null cursors, missing decks/cards,
unsupported note types and malformed cards remain distinct. Collection changes
are detected when readable IDs no longer agree; no collection-generation token
exists, so a restore with identical IDs cannot be proven absent.

The writer requires explicit confirmation, re-reads both the offered card and its
ID, compares identity/state/content, rechecks ratings and cancellation, writes
once, and verifies the same card. Only an acknowledged consistent one-review
transition confirms. Unknown outcomes cannot replay. Outcomes carry the evidence
needed by #20; nothing here persists a journal. `FakeReviewWriter` is available
in test fixtures. Session orchestration and full UI wiring remain #14/#27.

Both debug and release shell paths now use the real provider. Start reports
Card ready or Queue exhausted for the selected deck; no review writer is exposed
by the shell. This supersedes the historical AV-041/023 fake-preview and release
unavailability descriptions above. See the [AV-024 results](../docs/testing/av024/results.md)
and [runbook](../docs/testing/av024/runbook.md) for validation and limits.
