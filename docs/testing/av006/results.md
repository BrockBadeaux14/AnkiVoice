# AV-006 per-case results

Derived from retained JSON; see the decision report for protocol and limitations.

| Case | Expected answer | Label | Native STT (online permitted) | LFM pass 1 | LFM pass 2 |
| --- | --- | --- | --- | --- | --- |
| demo-reversal-1 | Green, blue, red. | correct | green blue red | correct | correct |
| demo-reversal-2 | Blue, green, red. | incorrect | blue green red | incorrect | incorrect |
| demo-reversal-3 | Start with green, then blue, and finish with red. | correct | start with green then blue and finish with red | correct | correct |
| demo-rules-1 | No, round does not satisfy square. | correct | no round does not satisfy Square | correct | correct |
| demo-rules-2 | Yes, because it is blue. | incorrect | yes because it is blue | incorrect | incorrect |
| demo-rules-3 | No. | partial | no | correct | partial |
| demo-rules-4 | It is not valid because it is not square. | correct | it is not valid because it is not Square | correct | correct |
| demo-arithmetic-1 | Five. | correct | ERROR_NO_MATCH (7) | correct | correct |
| demo-arithmetic-2 | Six. | incorrect | ERROR_NO_MATCH (7) | correct | incorrect |
| demo-explanation-1 | It puts two first, then five, then seven. | correct | it puts you first then five then seven | correct | correct |
| demo-explanation-2 | It gives seven, five, two. | incorrect | it gives seven five two | correct | incorrect |
| demo-explanation-3 | It puts two first. | partial | it puts two first | correct | incomplete_or_empty |
