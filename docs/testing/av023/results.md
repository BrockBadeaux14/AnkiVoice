# AV-023: Mobile shell and permission onboarding

Issue [#24 — Create the mobile shell and permission onboarding](https://github.com/BrockBadeaux14/AnkiVoice/issues/24).
Implementation and validation completed September 15, 2026; ready for review.
Branch: `codex/av023-mobile-shell-onboarding`.

## Delivered

- A single-activity Compose shell with failure-specific onboarding and Android
  runtime permission requests, deck selection with a notice before its first
  non-review write, session controls, and private selected-deck/language settings.
- `:ankidroid` performs package, component, permission and deck checks on a serial
  worker; it delivers results on the main executor. Its resolver seam distinguishes
  missing/disabled package, disabled API, denied database access, denied microphone,
  unknown/null provider result, a valid empty result and a missing selected deck.
- Only `:ankidroid` names the AnkiDroid authority, including its merged manifest
  query. Its only provider operations are `decks` queries and `selected_deck`
  update/readback. It rechecks existence and verifies selection before saving it.
- The process-owned composition root retains the shell through rotation and wires
  AV-041's `FakeCardProvider` only in debug. Release supplies no card provider and
  shows **Study unavailable**; onboarding and deck selection still work.
- A pure-Kotlin `ForegroundEventPort` receives Activity resume/pause/stop, excluding
  pause/stop during configuration changes. The shell invalidates pending results
  on Stop or backgrounding. Resume checks access but does not restart a paused or
  stopped session. Rotation during a pending Start or selection is covered by JVM
  regression tests.

This is the shell preview. Real card reads/review writes (#25), speech (#26),
credentials/network (#17), ReviewSession (#14) and the integrated study screen (#27)
remain with their existing owners. The shell performs no speech, grading or review.

## Dependency and environment verification

#7, #23 and #48 were closed; PRs #46, #47 and #49 were merged and their merge commits
were ancestors of the starting checkout. The native blocked-by links agree with
those dependencies. #24 had no discussion comments. The starting working tree was
clean and on `main`; the work uses a dedicated branch.

The [environment capture](evidence/environment.json) matches AV-022's validated
macOS 26.6.2 (25G83) ARM64 host, revision-7 API-36 Play Store ARM64 image, build
`BE2A.250530.026.D1/13818094`, medium_phone 1080×2400 at 420 dpi, emulator 37.1.11.0,
adb 37.0.1 and AnkiDroid 2.24.1 (322401300). A **new** `AnkiVoice_AV023` AVD on
`emulator-5582` was created; previous AVDs were untouched. It had no Google accounts
and was never signed in to AnkiWeb. The [fresh AV-002 manifest](evidence/fixture-manifest.json)
records the synthetic baseline imported through AnkiDroid's normal UI.

## Validation

| Check | Result / evidence |
| --- | --- |
| Android workflow commands, run locally | **Pass**: module boundaries, 55 existing core tests, 17 access tests, 18 shell tests, debug assembly and release assembly. [Gradle log](evidence/gradle-checks.txt), [90 JVM cases](evidence/jvm-tests.json) |
| Lint | **0 errors, 14 warnings**. Version/target warnings concern preserved pins; remaining warnings concern existing manifest defaults and optional Kotlin extension style. [Report](evidence/lint.txt) |
| VoiceQA fixtures workflow commands, run locally | **182 tests pass**, no broken Python dependencies, AV-006 evidence validator passes. [Tests](evidence/python-tests.txt), [dependencies](evidence/pip-check.txt), [provider evidence](evidence/provider-evidence-check.txt) |
| Final APK inspection | Both target 35, have one activity and required permission/visibility declarations, and have no Internet or phone-state permission. Debug contains fakes; release does not. [APK verification](evidence/apk-verification.json), [debug manifest](evidence/debug-manifest.xml), [release manifest](evidence/release-manifest.xml) |
| AnkiVoice crashes | No AnkiVoice fatal crash in the dedicated emulator's [crash buffer](evidence/crash-buffer.txt). |
| Collection integrity | Exact before/after card, note identity/tag and review rows match: **8 cards, 11 historical reviews; no reviews added or altered**. [Comparison](evidence/database-verification.json), [before](evidence/database-before.json), [after](evidence/database-after.json) |

GitHub Actions was not triggered for these changes: the implementation has not been
pushed and no PR was created. These are local executions of its commands, not a claim of a remote CI run.

### Emulator matrix

Every row below is a real UI capture from the pinned AVD. Each XML has a matching PNG.

| Scenario | Observed behavior | Capture |
| --- | --- | --- |
| AnkiDroid not installed | `packageUnavailable`; Start disabled | [Missing package](evidence/package-missing.xml) |
| AnkiDroid disabled via `pm disable-user` | `packageUnavailable`; app-info action opens | [Disabled package](evidence/package-disabled.xml), [app info](evidence/enable-app-settings.xml) |
| Package restored | Access rechecked successfully | [Restored](evidence/package-reenabled.xml) |
| Database never granted, denied, granted, revoked and re-granted | Native permission request; `accessDenied` when denied/revoked; recovery after grant | [Never granted](evidence/database-never-granted.xml), [dialog](evidence/database-permission-dialog.xml), [denied](evidence/database-denied.xml), [revoked](evidence/database-revoked.xml) |
| Provider before AnkiDroid's first-run setup | `nullCursor`, recovery guidance and disabled Start | [Unknown provider result](evidence/provider-before-setup.xml) |
| Microphone never granted, denied, granted, revoked and re-granted | Native permission request; `permissionDenied` when denied/revoked; recovery after grant | [Never granted](evidence/microphone-never-granted.xml), [dialog](evidence/microphone-permission-dialog.xml), [denied](evidence/microphone-denied.xml), [revoked](evidence/microphone-revoked.xml) |
| Native API setting off / restored | `apiDisabled`; then successful access | [Native switch off](evidence/native-api-off.xml), [failure](evidence/api-disabled-fixed.xml), [restored](evidence/api-restored.xml) |
| First deck selection | Notice visible before selection; confirmed selected deck and saved ID | [Choices](evidence/deck-choices-final.xml), [selected](evidence/deck-selected.xml) |
| Start / Stop | `Card ready`, then `Stopped` | [Card ready](evidence/card-ready.xml), [stopped](evidence/stopped.xml) |
| Home / return | `Paused`; requires explicit Resume | [Paused](evidence/home-return-paused.xml) |
| Stop / Home / return | Remains `Stopped` | [Stopped after resume](evidence/stopped-after-resume.xml) |
| Rotation | Remains `Card ready` in landscape; no lifecycle interruption | [Landscape session](evidence/rotation-session.xml) |
| Saved empty deck deleted in AnkiDroid | `deckMissing`; remaining deck list enables recovery | [Temporary selection](evidence/temporary-deck-selected.xml), [missing deck](evidence/deck-missing.xml) |
| Language and deck after process restart | Saved `en-GB` and selected deck restored; language subsequently reset to `en-US` | [Restart](evidence/settings-restored-verified.xml), [saved settings](evidence/settings-en-gb.xml), [final settings](evidence/settings-final.xml) |
| Release smoke test | Onboarding succeeds and selected deck is retained; Start reports `Study unavailable` | [Release setup](evidence/release-onboarding-ready.xml), [unavailable](evidence/release-unavailable.xml) |

The valid-empty result, fake queue exhaustion, fake card/capability failures, late
success/failure callbacks, mid-query revocation and deletion, and in-flight rotation
cases were verified through controlled JVM seams. They are not claimed as live
AnkiDroid queue or timing measurements. Ordinary rotation, Home and Stop were also
verified on the emulator.

### Findings resolved during the run

The first API-disabled attempt produced `nullCursor` because resolving the authority
did not expose the disabled component. The fixed implementation enumerates the
package's providers with `GET_PROVIDERS | MATCH_DISABLED_COMPONENTS`, then reads the
component's explicit enabled-state override. The native switch's implementation is
pinned in [AnkiDroid 2.24.1](https://github.com/ankidroid/Anki-Android/blob/v2.24.1/AnkiDroid/src/main/java/com/ichi2/anki/preferences/AdvancedSettingsFragment.kt#L121);
Android documents the override via
[PackageManager.getComponentEnabledSetting](https://developer.android.com/reference/android/content/pm/PackageManager#getComponentEnabledSetting(android.content.ComponentName)).
[Before-fix capture](evidence/api-disabled-before-fix.xml) is retained separately;
[after-fix capture](evidence/api-disabled-fixed.xml) verifies the correction.

An initial automated settings attempt force-stopped the process immediately after
injecting Save, before its UI handler ran. The completed test first observed
**Stopped**, the changed language and the private settings file, then force-stopped
and relaunched; the saved values survived. It required no settings code change.
Early captures precede the status-bar contrast and idle-failure wording cleanup;
the final APK hashes identify the reviewed build.

## Limits and review handoff

- Physical devices, Bluetooth, phone calls, screen-off/audio-focus monitoring and
  actual speech are not established by this shell run.
- The sideloaded AnkiDroid app's app-info screen on this image does not offer Enable
  after shell-only `pm disable-user`. The shell opens that screen as designed; the
  test restored the package with `pm enable`, following AV-004's runbook.
- `nullCursor` also represents an unknown provider exception. It does not claim to
  diagnose the underlying cause or prove a particular null-cursor mechanism on the
  fresh-install path. A literal null cursor and a valid empty result are distinct
  in the JVM tests.
- Release has deliberately unavailable study until #25. Debug previews synthetic
  cards, regardless of the selected real deck. This is explained in the UI.

The [runbook](runbook.md) reproduces the checks. `python3 tools/av023-qa/validate.py`
checks retained evidence and its hashes without a device. The dedicated AVD is
preserved for review; no personal AVD or collection was changed. No PR, merge,
deployment or next task is started by this work.
