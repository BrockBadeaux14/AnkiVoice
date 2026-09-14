# AnkiVoice

An Android-first college project for voice-based Anki study using a dedicated
VoiceQA note type, with cloud speech and AI grading allowed and the app kept in
the foreground.

The repository contains planning documentation and repeatable synthetic Anki
fixtures. Implementation and runtime validation are tracked on the
[project board](https://github.com/users/BrockBadeaux14/projects/2).

See [AV-001: Platform and pilot constraints](docs/decisions/0001-platform-and-pilot.md)
for the selected platform, test environment, deadline, pilot constraints, and
Android-first dependency plan.

See [AV-002: VoiceQA test collection](docs/testing/voiceqa-fixtures.md) to generate
disposable collections, run the fixture checks, and reset or back up a test run.

See [AV-004: AnkiDroid review access](docs/testing/av004-ankidroid-review-access.md)
for the pinned emulator investigation, captured evidence, reproducible probe, and
constraints on submission verification.

See [AV-005: Foreground speech](docs/testing/av005-foreground-speech.md) for the
constrained-go result on emulator speech, the full sixteen-scenario matrix and its
measurements, and the limits of that evidence. The
[runbook](docs/testing/av005/runbook.md) explains how to run the twelve-turn loop
with your own voice, which is the one thing the recorded matrix could not supply.
