# AV-020 runbook: the recorded live smoke request

- Issue: [#17 — AV-020: Add provider credentials, usage controls and useful diagnostics](https://github.com/BrockBadeaux14/AnkiVoice/issues/17).
- Host: the pinned **macOS ARM64** evidence host and AVD (AV-022's validated pins), not a
  Windows or x86_64 machine. A JVM test does not depend on the host; this run does.
- What it proves: one real request over the pinned free route, refused or served at a
  verified zero cost, counted in the durable ledger.

Everything else in AV-020 is covered by JVM tests that make no network call. This is the
one step that needs your own OpenRouter key and a live endpoint.

> **Never put the key in a file, a shell argument, a screenshot or an evidence JSON.**
> Type it into the app's settings field. `validate.py` fails if anything key-shaped
> appears in the evidence.

## 1. Build and install

```sh
cd android
./gradlew --console=plain checkModuleBoundaries :core:test :provider:testDebugUnitTest \
  :app:testDebugUnitTest assembleDebug :app:assembleRelease :app:lintDebug | tee ../docs/testing/av020/evidence/gradle-checks.txt
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Record the merged manifests and the APK check:

```sh
aapt2 dump xmltree --file AndroidManifest.xml app/build/outputs/apk/debug/app-debug.apk \
  > ../docs/testing/av020/evidence/debug-manifest.txt
aapt2 dump xmltree --file AndroidManifest.xml app/build/outputs/apk/release/app-release-unsigned.apk \
  > ../docs/testing/av020/evidence/release-manifest.txt
```

`apk-verification.json` records, per variant: `internet_permission` (true),
`phone_state_permission` (false), and `key_strings_present` (false — grep the APK for
`sk-or-` and confirm nothing matches).

## 2. Enter the credential

1. Open AnkiVoice, scroll to **AI grading**.
2. Type your OpenRouter key into the field and press **Save key**. The field clears, and
   the screen says only that a key is saved.
3. Capture `credential-saved.png` and the UI dump `credential-saved.xml`. Confirm no part
   of the key is on screen.
4. The disclosure card appears because saving a key always re-arms it. Read it, capture
   `disclosure.png`, then press **I understand — turn on AI grading**.

## 3. Check the route, then send one request

1. Press **Check the free route**. This is the pre-session zero-price check: it sends no
   grading request and reserves nothing. Capture `route-check.png` and copy the reported
   endpoint name into `smoke-request.json`.
2. Copy the ledger before the request:

   ```sh
   adb shell run-as org.ankivoice cat files/av020-quota-ledger.jsonl \
     > docs/testing/av020/evidence/ledger-before.jsonl
   ```

3. Press **Send one test request**. It sends a fixed sample — no card, transcript or
   collection data — and consumes exactly one reservation.
4. Capture `smoke-result.png`, then copy the ledger again to `ledger-after.jsonl`.
5. Write `smoke-request.json` with the observed values:

   ```json
   {
     "utc": "2026-09-16T12:00:00Z",
     "model": "liquid/lfm-2.5-2.6b:free",
     "provider": "liquid/fp8",
     "endpoint_name": "Liquid | fp8",
     "http_status": 200,
     "reported_cost": "0",
     "outcome": "content",
     "message": "the on-screen result line, verbatim",
     "reservations_before": 0,
     "reservations_after": 1
   }
   ```

   A refusal is a valid result: record `"outcome": "failed"` with the failure shown, for
   example `Grader.quotaExhausted`. Do not retry beyond what the allowance permits, and
   never raise the cap to make a run succeed.

## 4. Durability and diagnostics

1. Kill the app (`adb shell am force-stop org.ankivoice`), reopen it, and confirm the
   allowance line still counts the request. Capture `allowance-after-restart.png`.
2. Capture the **Diagnostics** card as `diagnostics.png` and confirm it shows timings,
   names and counts only — no key, card text or transcript.
3. Leave **Keep transcripts and card text for the pilot report** off, which is its
   default.

## 5. Environment and hashes

Record `environment.json` in the shape AV-023 used: host, arch, image properties,
fingerprint, accounts and the source commit. Then:

```sh
python tools/av020-qa/validate.py
```

It writes nothing; it fails loudly if the evidence is inconsistent, if a permission is
wrong, if the ledger did not advance by exactly one reservation, or if anything
key-shaped appears anywhere in the evidence.

## What this does not establish

- Physical-device behaviour, Bluetooth or phone-call interruptions.
- Anything about grading quality: #18 owns the instruction and reply validation, and #19
  owns held-out evaluation. This run only proves the route, the guard and the ledger.
- Long-run quota behaviour. One request is one data point; the free tier's own limits are
  the provider's, not this app's.
