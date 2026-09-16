# Current scope: human voice input

The owner narrowed #51 during this task to getting live human voice input working.
The earlier comprehensive criteria, interruption gate, and no-go stop rule are deferred
for this MVP. The exact instruction and operator replies are in
[evidence/scope-and-operator-notes.json](evidence/scope-and-operator-notes.json).

The two-turn [initial ledger](evidence/ledger.json) and its no-go integrity check are
historical diagnostics. They remain unchanged. They do not define acceptance of the
new scope. Subsequent live input trials have cumulative IDs starting at 3 and will be
reported separately. The old failures are not converted into successes.

Current acceptance: a human speaks into the configured microphone, the app receives
that audio and displays the corresponding transcript. Confirm this on the actual
operator route. A generated answer, typed transcript, service availability, callback
simulation, or unconfirmed recording alone is insufficient.

Keep all work on codex/av042-live-speech-blockers. No PR, merge or deployment.
