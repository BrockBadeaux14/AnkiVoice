# AV-040 per-turn results

[Report](../av040-live-microphone.md) · [Machine-readable derivation](evidence/measured-results.json)

Derived from the four unmodified human evidence files below. B01–B12 are baseline positions; 1–28 are persistent follow-up attempt IDs. A dash means absent/not applicable, not zero. “Success” is raw final text only. Owner corrections in [operator-attestation.json](evidence/operator-attestation.json) qualify the echo cases.

- [baseline-human.json](evidence/baseline-human.json)
- [candidate1-human.json](evidence/candidate1-human.json)
- [candidate2-human.json](evidence/candidate2-human.json)
- [macbook-extension-human.json](evidence/macbook-extension-human.json)

## Outcomes

| ID | Scenario | Expected answer | Raw text | Outcome / detail | Attestation |
| --- | --- | --- | --- | --- | --- |
| B01 | loop | Green, blue, red. | — | error / ERROR_NO_MATCH | interrupted |
| B02 | loop | No, round does not satisfy square. | — | error / empty_results_bundle | spoke_answer |
| B03 | loop | Five. | — | error / empty_results_bundle | spoke_answer |
| B04 | loop | It puts two first, then five, then seven. | — | error / ERROR_NO_MATCH | interrupted |
| B05 | loop | Blue, green, red. | — | error / ERROR_NO_MATCH | interrupted |
| B06 | loop | Yes, because it is blue. | — | error / ERROR_NO_MATCH | interrupted |
| B07 | loop | Six. | — | error / ERROR_NO_MATCH | interrupted |
| B08 | loop | It gives seven, five, two. | — | error / empty_results_bundle | spoke_answer |
| B09 | loop | Green, blue, red. | — | error / ERROR_NO_MATCH | interrupted |
| B10 | loop | No. | — | error / ERROR_NO_MATCH | interrupted |
| B11 | loop | Five. | — | error / ERROR_NO_MATCH | interrupted |
| B12 | loop | It puts two first, then five, then seven. | — | error / ERROR_NO_MATCH | interrupted |
| 1 | av040_pause2 | Green, blue, red. | — | error / ERROR_NO_MATCH | interrupted |
| 2 | av040_pause2 | Five. | — | error / ERROR_NO_MATCH | interrupted |
| 3 | av040_pause2 | Green, blue, red. | — | error / ERROR_NO_MATCH | interrupted |
| 4 | av040_pause2 | Five. | — | error / ERROR_NO_MATCH | interrupted |
| 5 | av040_pause2 | Green, blue, red. | — | error / ERROR_NO_MATCH | spoke_answer |
| 6 | av040_pause2 | Five. | — | error / ERROR_NO_MATCH | spoke_answer |
| 7 | av040_pause5 | No, round does not satisfy square. | — | error / ERROR_NO_MATCH | spoke_answer |
| 8 | av040_pause5 | It puts two first, then five, then seven. | it inputs two first five and then seven | success | spoke_answer |
| 9 | av040_pause5 | No, round does not satisfy square. | — | halted / left_foreground | interrupted |
| 10 | av040_pause5 | It puts two first, then five, then seven. | — | error / ERROR_NO_MATCH | spoke_answer |
| 11 | av040_pause5 | No, round does not satisfy square. | — | error / ERROR_NO_MATCH | spoke_answer |
| 12 | av040_pause5 | It puts two first, then five, then seven. | — | error / ERROR_NO_MATCH | spoke_answer |
| 13 | av040_call_playback | No, round does not satisfy square. | — | halted / external_audio_mode_1 | interrupted |
| 14 | av040_call_playback | It puts two first, then five, then seven. | — | halted / external_audio_mode_1 | interrupted |
| 15 | av040_call_capture | No, round does not satisfy square. | no Brown does not satisfy Square | success | spoke_answer |
| 16 | av040_call_capture | It puts two first, then five, then seven. | — | error / ERROR_NO_MATCH | interrupted |
| 17 | av040_home_playback | It puts two first, then five, then seven. | — | halted / left_foreground | interrupted |
| 18 | av040_home_capture | It puts two first, then five, then seven. | — | halted / left_foreground | interrupted |
| 19 | av040_lock_playback | It puts two first, then five, then seven. | — | halted / left_foreground | interrupted |
| 20 | av040_lock_capture | It puts two first, then five, then seven. | — | halted / left_foreground | interrupted |
| 21 | av040_cancel_playback | It puts two first, then five, then seven. | — | cancelled / operator_cancel | interrupted |
| 22 | av040_cancel_capture | It puts two first, then five, then seven. | — | cancelled / operator_cancel | interrupted |
| 23 | av040_echo | Green, blue, red. | how many | success | stayed_silent |
| 24 | av040_echo | No, round does not satisfy square. | — | error / ERROR_NO_MATCH | stayed_silent |
| 25 | av040_pause2 | Green, blue, red. | green blue red | success | spoke_answer |
| 26 | av040_pause2 | Five. | — | error / ERROR_NO_MATCH | spoke_answer |
| 27 | av040_echo | Green, blue, red. | — | error / ERROR_NO_MATCH | spoke_answer |
| 28 | av040_echo | No, round does not satisfy square. | — | error / ERROR_NO_MATCH | spoke_answer |

## Callback timing (milliseconds)

Thinking is measured after the playback settle delay and before Start answer. Partial and final-segment offsets are from the capture request, as is the terminal offset. A final segment can precede session completion; partials never become an accepted answer. The capture duration includes any wait for a terminal callback after Done. A halted playback has no capture duration. Monotonic times are per scenario, not comparable across Activity instances.

| ID | Thinking | Capture | First nonempty partial | First final segment | Terminal after capture request | Done → terminal |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| B01 | — | 4549 | — | — | 4549 | — |
| B02 | — | 6495 | 1996 | — | 6495 | — |
| B03 | — | 3725 | 2242 | — | 3725 | — |
| B04 | — | 1143 | — | — | 1143 | — |
| B05 | — | 1136 | — | — | 1136 | — |
| B06 | — | 1151 | — | — | 1151 | — |
| B07 | — | 1165 | — | — | 1165 | — |
| B08 | — | 3924 | 2315 | — | 3924 | — |
| B09 | — | 1518 | — | — | 1518 | — |
| B10 | — | 1235 | — | — | 1235 | — |
| B11 | — | 1191 | — | — | 1191 | — |
| B12 | — | 1261 | — | — | 1261 | — |
| 1 | 7206 | 1710 | — | — | 1710 | — |
| 2 | 3873 | 1281 | — | — | 1281 | — |
| 3 | 4555 | 1414 | — | — | 1414 | — |
| 4 | 4510 | 1348 | — | — | 1348 | — |
| 5 | 4081 | 3202 | — | — | 3202 | 151 |
| 6 | 3972 | 4562 | — | — | 4562 | 117 |
| 7 | 10866 | 7205 | — | — | 7205 | 256 |
| 8 | 7065 | 10495 | 4869 | 8235 | 10495 | 145 |
| 9 | — | — | — | — | — | — |
| 10 | 8461 | 8238 | — | — | 8238 | 146 |
| 11 | 15826 | 12529 | — | — | 12529 | 147 |
| 12 | 6749 | 7995 | — | — | 7995 | 128 |
| 13 | — | — | — | — | — | — |
| 14 | — | — | — | — | — | — |
| 15 | 2783 | 7881 | 3902 | 5353 | 7881 | 156 |
| 16 | 2307 | 8125 | — | — | 8125 | 126 |
| 17 | — | — | — | — | — | — |
| 18 | 1285 | 622 | — | — | 622 | — |
| 19 | — | — | — | — | — | — |
| 20 | 2642 | 1428 | — | — | 1428 | — |
| 21 | — | — | — | — | — | — |
| 22 | 900 | 422 | — | — | 422 | — |
| 23 | — | 7644 | — | 7643 | 7644 | 389 |
| 24 | — | 14289 | — | — | 14289 | 132 |
| 25 | 5035 | 5364 | 3254 | 4213 | 5364 | 148 |
| 26 | 3035 | 3399 | — | — | 3399 | 116 |
| 27 | — | 6940 | — | — | 6940 | 192 |
| 28 | — | 7767 | — | — | 7767 | 207 |

## Interpretation limits

- Attempts 3/4 and 9–12 are extra repeated trials, not same-turn retries. Attempt 9 was halted by the investigator’s selection intent.
- Attempts 15/16 are confirmed missed capture calls regardless of transcript/error status.
- Attempts 23/24: owner silent, prompts inaudible; the text in 23 has unknown origin.
- Attempts 27/28: owner spoke, prompts audible, Done used. Neither pair establishes silent audible echo behavior.
- Baseline “Six” was B07 and was attested Interrupted. No completed spoken Six trial is claimed.
- No grade/rating, successful automatic recovery, calibrated audio latency or statistically representative recognition accuracy is inferred.
