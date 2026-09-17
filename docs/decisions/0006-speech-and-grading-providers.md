# AV-006: Speech and grading providers

- Issue: [#6 — Evaluate and choose speech and grading providers](https://github.com/BrockBadeaux14/AnkiVoice/issues/6).
- Date: September 14, 2026. Status: ready for review; acceptance pending.
- Dependencies: #1 and #2 were closed in the issue and GitHub dependency records. #6 had no discussion comments. #5 is a parallel investigation, not a prerequisite.
- User clarification: OpenRouter, **free models only**, paid fallbacks disabled. The user supplied a local credential and confirmed all four prompt recordings were intelligible.

## Decision

**Constrained go for provider integration in the personal English demo:** use
Google's installed Android TTS engine/local English voice, its native recognition
service with online recognition permitted, and OpenRouter's
`liquid/lfm-2.5-2.6b:free` through `liquid/fp8` as an **advisory** semantic grader
with the second-pass instruction. This combination was accessible with zero
incremental API cost. Explicit transcript correction, rating confirmation, and
self-grading/manual fallback are required parts of it.

**No-go for unattended rating or a guaranteed voice-only session.** Native STT
missed two one-word answers and changed a number in another answer. The first
grading instruction produced four false-correct labels. The revision matched
11 labels but hit its output limit on the twelfth. Display the transcript for
correction and require an explicit user rating/confirmation. #19 owns held-out
evaluation and any future automation threshold; none is approved here.

There is **no verified cloud STT backup**. NVIDIA's free route advertised audio
input but returned requests for an audio file and then a provider error. Gemma's
free route returned HTTP 429 in both bounded attempts. Neither is selected.
Expanding the shortlist requires a subsequent decision, not an implicit paid
fallback or a third candidate in this experiment.

The bounded provider investigation is complete. Production Android integration,
foreground microphone/lifecycle proof, and release acceptance remain downstream.
If transcript checks and explicit self-grading/manual controls are unacceptable,
that capability requirement blocks this recommendation: improve and re-evaluate
speech/grading before proceeding. No purchase is needed for the constrained route.

## Candidates and environment

All linked official documentation and endpoint snapshots were accessed September
14, 2026. API aliases and provider endpoint names do not pin immutable weights
or guarantee future availability.

| Function | Pin/configuration | English, account, availability |
| --- | --- | --- |
| Native TTS | `com.google.android.tts`, version `googletts.google-speech-apk_20241125.02_p2.702443970`, code `210526444`; voice `en-US-language`; rate/pitch 1.0 | Installed local voice reports no network requirement; no API key or signed-in account. Four prompts played. [Android TTS](https://developer.android.com/reference/android/speech/tts/TextToSpeech) |
| Native STT | Same package/version; `com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService`; `en-US`, free-form, `EXTRA_PREFER_OFFLINE=false` | Signed-out AVD; no separate metered cloud credential. Online-permitted path returned 10/12 transcripts. [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) |
| Cloud STT | `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free`, provider `nvidia`; endpoint name dated `20260428` | OpenRouter key; English/audio advertised by [NVIDIA](https://build.nvidia.com/nvidia/nemotron-3-nano-omni-30b-a3b-reasoning/modelcard). No usable transcript. [Prices/endpoint](https://openrouter.ai/api/v1/models/nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free/endpoints) |
| Grader A | `google/gemma-4-26b-a4b-it:free`, `google-ai-studio`; endpoint name dated `20260403` | OpenRouter key; English instruction model per [Gemma model card](https://ai.google.dev/gemma/docs/core/model_card_4). Two 429s; quality unmeasured. [Prices/endpoint](https://openrouter.ai/api/v1/models/google/gemma-4-26b-a4b-it:free/endpoints) |
| Grader B — selected | `liquid/lfm-2.5-2.6b:free`, `liquid/fp8`; endpoint name dated `20260811`, FP8 | OpenRouter key; compact English-capable model per [Liquid model card](https://huggingface.co/LiquidAI/LFM2.5-2.6B). Both 12-case passes ran. [Prices/endpoint](https://openrouter.ai/api/v1/models/liquid/lfm-2.5-2.6b:free/endpoints) |

Native speech avoids an additional account and cloud TTS charges. The two text
models offer JSON output and free inference; the single cloud audio candidate
tests whether its advertised modality actually works through OpenRouter.

The separate `AnkiVoice_AV006` AVD used Android 16/API 36, ARM64 Google Play
image revision 7 and emulator 37.1.11.0. Exact fingerprint, time zone, source
commit and build tools are in [environment.json](../testing/av006/evidence/environment.json).
The AVD was signed out and contained no Anki collection. AV004 was preserved.

## Protocol and measurements

The [12-answer corpus](../../fixtures/providers/av006-corpus.json) was frozen
before comparison. It preserves all nine AV002 labeled answers and adds a
reversal paraphrase, explicit negation, and incomplete number-list answer. It
embeds all four prompts/rubrics and hashes its AV002 source. Expected labels are
evaluation metadata and are never sent to graders.

Native TTS generated the same 12 mono 24 kHz PCM16 WAVs for both STT candidates.
[Hashes/durations](../testing/av006/evidence/audio-manifest.json) identify them.
Native STT consumed PCM through `EXTRA_AUDIO_SOURCE` in 20 ms chunks; host
microphone input was disabled. Segmented recognition ended on audio-pipe EOF.
These are synthetic file-fed measurements, not human microphone, natural
endpointing, ambient noise or echo-cancellation tests.
[Android's audio-source contract](https://developer.android.com/reference/android/speech/RecognizerIntent).

Cloud STT received base64 WAV as `input_audio` with a transcribe-only instruction,
no prompt or answer key, following [OpenRouter's audio contract](https://openrouter.ai/docs/guides/overview/multimodal/audio).
Both graders received identical **frozen expected transcripts**, isolating grader
quality from recognition error. Host HTTPS and emulator virtual Wi-Fi used the
ordinary host network; no bandwidth shaping or RTT measurement was performed.
Android cloud integration and combined STT-to-grading error were not measured.

| Measurement | Outcome | Successful-result latency: median / maximum |
| --- | --- | --- |
| Native TTS, four prompts | 4 synthesis successes, 4 playback completions, all intelligible to user | Synthesis 131.5 / 170 ms; playback 5,834.5 / 7,492 ms including speech |
| Native STT, offline-preferred preflight | First case failed with code 13 (`ERROR_LANGUAGE_UNAVAILABLE`); other cases not attempted in this mode | No successful transcript |
| Native STT, online permitted | 12 attempted: 10 transcripts, 9 normalized exact matches; two code-7 `ERROR_NO_MATCH` results | After audio EOF: 135 / 445 ms (n=10); total including feed: 2,399 / 3,956 ms |
| NVIDIA cloud STT | 3 attempted: 2 non-transcription replies, 1 provider error; 9 not attempted after failure | No usable-transcript latency; invalid replies took 3,327.381 and 4,148.621 ms |
| Gemma, both passes | First case returned 429 in each pass; other 11 cases not attempted per pass | No successful grading latency |
| Liquid, pass 1 | 12 valid grades, 8 matching labels, 4 false-correct labels | 2,289.585 / 5,793.129 ms (n=12) |
| Liquid, pass 2 | 11 valid grades, all matching; 1 truncated response, no usable grade | 3,259.846 / 12,253.046 ms (n=11; truncation excluded) |

The [per-case table](../testing/av006/results.md) shows all expected text, observed
native transcripts, and Liquid labels. Native STT missed “Five” and “Six” and
changed “two” to “you” in the longer sorting answer. Negation cases were preserved.

Pass 1 used [this instruction](../testing/av006/evidence/grading-prompt-v1.txt) and
the field `transcript`. It confused reference content with learner claims. Pass
2 explicitly separates `learner_answer` from the answer key. Its exact instruction
is retained in [the result](../testing/av006/evidence/openrouter-grade-b-pass2.json)
and [runner](../../tools/av006_providers.py). Both passes used temperature 0, JSON
output and a 1,024-token cap. The final incomplete sorting answer hit that cap
in pass 2. No other malformed/uncertain outputs occurred. A correct label can
still have a flawed rationale, so explanations are also advisory.

**11/12 is in-sample comparison evidence, not validated accuracy.** The revision
was informed by these examples. #19 needs new held-out answers.

There were 26 grading requests and 16 STT attempts (13 native, 3 cloud), within
the 48-per-function limits. No candidate/case exceeded two attempts. No automatic
retries occurred. A recognition-support query returned code 14
(`ERROR_CANNOT_CHECK_SUPPORT`); a model-download request had no completion within
55 seconds. Those capability calls are separate from answer attempts. Native
online testing resumed at case index 8 after the initial no-match stop, without
rerunning successful cases. Raw STT cloud `status=success` means a nonempty HTTP
completion; the assessment correctly counts both audio-request replies as unusable.

## Cost and usage controls

**Incremental API cost: $0.** All 26 successful OpenRouter completions reported
zero cost, including the two unusable audio replies; three other requests failed.
The dedicated key's usage remained 0 before/after each run. The
[account snapshot](../testing/av006/evidence/openrouter-credits.json) reported $10
total credits and $1.093991918 historical usage, leaving $8.906008082 at that
instant. The key's $20 remaining spending **cap** is not a $20 account balance.
The user authorized no paid-model use of either balance.

Each request pins one `:free` model/provider, sets `allow_fallbacks=false`, and
maximum prompt/completion/request prices of zero. No tools, plugins, search or
automatic router were requested. The runner refuses changed/nonzero prices and
stops on unknown/nonzero response cost. [Provider routing and price filtering](https://openrouter.ai/docs/guides/routing/provider-selection).

OpenRouter documents 20 requests/minute and 50/day for free variants below $10
of lifetime credit purchases, or 1,000/day at that threshold. Limits are shared;
upstream capacity can be lower. This account reports `is_free_tier=false` and
$10 total credits, but its exact remaining daily request count was not exposed.
Dollar usage is not a request count. [Official limits](https://openrouter.ai/docs/api/reference/limits),
[official support article](https://openrouter.zendesk.com/hc/en-us/articles/39501163636379-OpenRouter-Rate-Limits-What-You-Need-to-Know).

The experiment reserved 29 cloud requests durably before dispatch, at least four
seconds apart. Its conservative local ceiling is 48/day: 19 local reservations
remained on the run's UTC date, not necessarily 19 account requests. Preserve
`build/av006/cloud-ledger.jsonl`; deleting it is not permission to exceed the
two-pass comparison budget. Both grading candidates have exhausted that budget.

For 30 turns with the same short-answer mix and one grading call per turn:

| Resource | Calculation |
| --- | --- |
| Prompt speech | 21.56 seconds / 4 × 30 = **161.7 seconds** |
| Answer audio | 22.395 seconds / 12 × 30 = **55.9875 seconds** |
| Grading input | 3,538 pass-2 prompt tokens / 12 × 30 = **8,845 tokens** |
| Grading output | 6,882 pass-2 completion tokens / 12 × 30 = **17,205 tokens**, including truncation |
| Incremental cost | Native speech has no separate API bill; `(8,845 × $0) + (17,205 × $0) = $0` at the selected free endpoint |

These use reported prompt/completion totals; do not add reasoning tokens again.
Some cloud-audio detail counters were inconsistent, so they cannot support a paid
audio estimate. This is a synthetic workload estimate, not an integrated demo:
30 answers at the capture limit would instead total 900 seconds.

#17 must reserve at most **30 grading requests per 30-turn session**, persist
account/session counts, and permit explicit retries only within remaining
allowance. Check allowance before a session. Stop on 402/429, exhausted local
quota, unknown/nonzero price or unknown cost. Other account activity may still
consume quota; server rejection must pause safely. Never add funds, raise a cap,
switch to paid inference or loop retries automatically.

## Data flow and credential handoff

| Recipient | Data received | Retention boundary |
| --- | --- | --- |
| Local native TTS | Prompt for question speech; synthetic answer text to construct this corpus. Product reveal sends ReferenceAnswer; optional post-answer speech may send Extra. | Voice reports no network requirement; no packet-level telemetry audit. |
| Native STT | Answer PCM and English locale; no prompt, reference, rubric, card IDs or credentials | Online processing permitted. Signed out does not prove zero retention; exact SDK-service retention was not exposed. |
| OpenRouter → NVIDIA | Synthetic WAV and transcription-only instruction | Upstream audio delivery unproven: model requested an audio file. No answer key sent. |
| OpenRouter → Liquid/Gemma | Learner text, Prompt, ReferenceAnswer, RequiredConcepts, AcceptedAnswers, evaluation instruction | No audio, Extra, card IDs, personal collection data or expected labels. |

OpenRouter says its own prompt/output storage is opt-in and it retains request
metadata. Account privacy/observability settings were not inspected or changed.
The synthetic experiment used `data_collection=allow` and did not request ZDR.
Upstream policies and free-model settings are separate.
[OpenRouter data collection](https://openrouter.ai/docs/guides/privacy/data-collection),
[provider logging](https://openrouter.ai/docs/guides/privacy/provider-logging).

Liquid's policy permits training/improvement using inputs/outputs and promises
no fixed short retention period. NVIDIA's general policy does not establish a
special zero-retention API agreement. Google account audio-history controls do
not establish the signed-out recognition SDK's retention. #17 must disclose
these limits before real study content is sent. Restrictive routing can remove
a free endpoint and requires another availability check.
[Liquid policy](https://www.liquid.ai/privacy-policy),
[NVIDIA policy](https://www.nvidia.com/en-us/about-nvidia/privacy-policy/),
[Google audio controls](https://support.google.com/websearch/answer/6030020).

The host credential is in a user-created file outside Git with owner-only
permissions. It is never embedded in code/APK assets, printed or passed as a shell
argument. The runner also accepts the case-sensitive environment variable
`ankivoice_oai`. An explicit `--key-file` takes precedence, followed by the
environment variable, then the default key file. Empty/invalid selected sources
fail without switching credentials or echoing the value. Environment support was
added after these measurements and validated locally without new provider calls.
The runner sends an HTTPS Authorization header to OpenRouter and refuses redirects.
The native probe has neither Internet nor Anki permission.

#17 owns runtime Android key entry and private storage protected with Android
Keystore facilities, backup exclusion, and diagnostics. Never compile the host
key into BuildConfig/resources or an APK. A distributed pilot needs a separate
credential architecture decision; it is outside this personal pilot. This is a
handoff contract, not implemented Android credential storage.

Local logging is independent: this experiment retains synthetic text/WAVs,
sanitized usage and results for review. Product diagnostics should default to
content-free records, with explicit consent for content capture, implemented in #17.

## Defaults and fallback contract

| Setting | Selected default / evidence |
| --- | --- |
| TTS | Pinned local English voice, rate/pitch 1.0; completion before capture. Provisional 15-second initialization and 30-second utterance limits; longest prompt playback 7.492 seconds. |
| STT | Pinned service, English, online permitted; 30-second capture cap, provisional 5-second finalization deadline after input ends (observed maximum 445 ms). Human endpointing remains #5/#13. |
| Grader | Fixed Liquid free route, pass-2 instruction, temperature 0, JSON output, 1,024-token cap. Provisional 20-second deadline versus 12.253-second observed maximum. No automatic retries or model fallback. |
| Output | Accept only correct/partial/incorrect/uncertain plus a textual reason. Empty/malformed/truncated output is not a grade. Labels do not directly map to Anki ratings. |
| Automation | Disabled; transcript correction and explicit user rating/confirmation required. |

Timeouts are engineering defaults, not tail-latency guarantees. The measurements
used a 30-second HTTP timeout and 45-second native attempt watchdog.

- Grader uncertainty/failure while speech works: reveal the reference as appropriate and ask for an explicit spoken self-grade.
- No-match or unavailable STT/TTS: pause with no rating; offer explicit retry/manual controls. Spoken self-grading requires working STT.
- Exhausted quota/unknown cost: pause with no rating; manual continuation requires an explicit choice. Never silently route elsewhere.
- Transcript correction invalidates any previous suggestion. Never turn failure into Again or silently accept a proposed grade.

## Downstream gates and verification

The existing dependent issues remain gated by open #6 and their other
prerequisites. Acceptance of this investigation must carry these constraints:

| Issue | Handoff |
| --- | --- |
| #13 | Short-number no-matches, two/you substitution, transcript correction, bounded capture; injected EOF is not human endpointing proof. |
| #17 | Free-route enforcement, runtime credentials, durable quotas, data/retention disclosures, content-free diagnostics. |
| #18 | Explicit learner-answer boundary, truncation rejection, advisory proposals and self-grading fallback. |
| #19 | Retain first-pass failures as regression context; use new held-out answers for automation thresholds. |
| #23 | Reconcile #5 with this image/service pin; no physical phone, Bluetooth, phone-call, microphone or background support claim. |
| #26 | Local TTS completion/listening evidence; online-permitted recognition and explicit retry/manual recovery. |

The [runbook](../testing/av006/runbook.md) reproduces the experiments. The
[validator](../../tools/av006-probe/validate_evidence.py) checks corpus/audio hashes,
coverage, attempt caps, reported costs and derived tables without network access.
Unit tests exercise cost refusal, request limits and invalid responses.

Evidence does **not** verify Android cloud integration, real microphone input,
combined STT/grading, 30-turn study, provider tail latency or generalization to
human answers. Those limitations remain explicit downstream review gates.

## Addendum — September 16, 2026 (AV-043): a paid grading fallback within a daily cap

- Issue: [#66 — AV-043: Fix the free-route reply check and add a budgeted paid grading fallback](https://github.com/BrockBadeaux14/AnkiVoice/issues/66).
- Evidence: [AV-043 results](../testing/av043/results.md) and [runbook](../testing/av043/runbook.md);
  [AV-017 results](../testing/av017/results.md#what-the-ai-path-did-and-the-finding-against-the-route-guard)
  for the finding that prompted the correction.

This addendum records a change of decision for **grading only**. Nothing above is
rewritten: its measurements stand as the evidence for the free route, and the
speech decisions are untouched.

**What changed.** On September 16, 2026 the owner accepted a paid OpenRouter fallback for
advisory grading, behind a hard daily USD cap. The order is now: (1) AV-015's rules on
device; (2) the pinned free route above, at a verified zero cost; (3) the pinned paid
route, only when the free route is refused by its guard, unavailable, past AV-016's
20-second deadline, or failed. A paid request is never sent while the free route would
have been tried. This supersedes the clarification above — OpenRouter, free models only,
paid fallbacks disabled — and the September 14, 2026 statement that no paid-provider
fallback is authorized, for grading only. Rejected: a paid primary route, and an
owner-selectable order in settings.

**Budget.** An owner-set daily cap in USD, default **$1.00**, stored in AV-020's private
settings beside the daily request limit; `$0` disables the paid route. Each paid request
holds its ceiling — every prompt token up to 4,096 and every completion token up to 1,024
at the listed prices — in the durable quota ledger before dispatch, and the reply's own
`usage.cost` replaces the hold. A paid reply that reports no cost is refused as a label
**and** charged the ceiling, because the money may have been spent. When a request's
ceiling would take the UTC day past the cap, the paid route stops for the day
(`BUDGET_EXHAUSTED`) and grading falls back to explicit self-grading, exactly as a
request-quota stop does. The cap is a ceiling, not a target. Rejected: a lifetime pilot
ceiling, and two simultaneous limits.

**Pinned paid model.** `openai/gpt-4.1-nano` through the OpenRouter endpoint tag `openai`,
listed on September 16, 2026 at $0.10 per million prompt tokens and $0.40 per million
completion tokens, which makes the per-request ceiling $0.0008192. It is subject to the
same instruction, two-key schema, 20-second deadline and single retry as the free route,
and to the same 30-request session cap and daily request limit. The pin was chosen from
the public endpoints listing; the bounded spike the card requires — two or three
candidates on the AV-017 tuning 20 only — confirms or replaces it, and its result is
recorded in the AV-043 results page.

**Also corrected.** The shipped reply check compared the reply's provider *name*
(`Liquid`) with the pinned endpoint *tag* (`liquid/fp8`) and refused every live reply of
the free route, so AI grading was inert. Both accepted identities are now read from the
endpoints listing at session start; the zero-cost verification is unchanged.

**Unchanged.** Explicit confirmation of every rating and no automatic acceptance; native
speech stays free on the pinned route; the credential is the same Keystore-wrapped
OpenRouter key; diagnostics stay content-free; the app never adds funds or raises a cap or
budget on its own.
