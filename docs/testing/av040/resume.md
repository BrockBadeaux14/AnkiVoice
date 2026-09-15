# AV-040 review checkpoint

September 15, 2026. The human investigation has ended. The earlier pause/resume
instructions are superseded by this checkpoint; they do not authorize more trials.

- Issue: [#45 — AV-040: Validate live microphone capture and foreground interruptions](https://github.com/BrockBadeaux14/AnkiVoice/issues/45).
- Branch: `codex/av040-live-microphone-interruptions`.
- Base: `96caf9467a8e2f3259d0f3e9ee6af76e62ae3822`.
- Implementation and evidence are on the branch above. Inspect `git status` and
  preserve any later user changes before editing. The user subsequently authorized
  a PR and one follow-up issue on September 15; merge and deployment remain separate.
- Final result: **no-go**. See the [report](../av040-live-microphone.md),
  [per-turn ledger](results.md), [runbook](runbook.md) and
  [architecture addendum](../../decisions/0022-android-implementation.md#av-040-follow-up-september-15-2026).
- **40 actual turns:** baseline 12 once, original 24 follow-up attempts plus the
  owner's explicit four-turn extension. IDs 1–28 are all used. Two capture
  policies and one interruption rule; no counter reset and no authorized trials
  remaining. The 24/28 cap checks contain zero turns.
- Four raw transcripts, three human-attested. Both capture calls were missed;
  neither arithmetic answer was recognized. Silent audible echo is unverified:
  original prompts were inaudible; during the audible extension the owner spoke.
  #13/#26 remain blocked on the live-speech capability requirement.
- Probe debug build passed; 182 Python tests passed; canonical evidence passed
  538 assertions. The original AV-005 matrix still passed 334 assertions.
  [Verification](evidence/verification.json) separates checks from capability.
- Test AVD `AnkiVoice_AV005` / `emulator-5588` is stopped and its microphone
  forwarding disabled. Probe/logcat sessions ended. MacBook Pro microphone and
  speakers remain the host defaults; separate `Medium_Phone` / `emulator-5556`
  remains running. See [teardown](evidence/teardown.json).
- The original pre-PR review state is recorded in
  [review handoff](evidence/review-handoff.json). Keep the card **In review** until
  the user reviews and accepts it. Do not mark Done, merge, deploy or start the
  follow-up investigation automatically.

The four canonical human evidence files are cumulative snapshots from separate
Activity instances. Do not merge earlier interim saves, rewrite their raw
observations or reuse their attempts to claim additional coverage. Latest echo
confirmation is preserved verbatim in [operator attestations](evidence/operator-attestation.json).
