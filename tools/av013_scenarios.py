#!/usr/bin/env python3
"""Derive the AV-013 conformance manifest from the Python scenario suite.

The manifest lists the 53 scenarios tools/av007_scenarios.py runs — the named ones by
name and the failure sweep by contract and failure — plus the binding's session states
and interruption kinds, all spelled as the specification spells them.

tests/test_av013_session.py fails if the checked-in copy is stale; the Kotlin
ScenarioDriftTest fails if android/:core's ported suite differs from it. Together they
keep the port in step with its source, in the manner of the AV-041 manifest guard.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

# Support the documented direct-file command as well as module execution.
if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools import av007_contracts as contracts
from tools import av007_scenarios as scenarios
from tools.av041_manifest import spec_name

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "android" / "core" / "src" / "test" / "resources" / "av007" / "scenarios.txt"

HEADER = (
    "# AV-007 conformance scenarios for the Kotlin session port (AV-013),\n"
    "# in specification spelling. Generated from tools/av007_scenarios.py by\n"
    "# tools/av013_scenarios.py; do not edit.\n"
    "# Regenerate with: python tools/av013_scenarios.py --write\n"
)


def named() -> list[str]:
    """The named scenarios, in suite order, by the name each Run declares."""
    return [scenario().name for scenario in scenarios.NAMED_SCENARIOS]


def render() -> str:
    lines = [f"named {name}" for name in named()]
    if len(lines) != len(set(lines)):
        raise ValueError("two named scenarios share a name")
    lines += [f"failure {mode.contract} {spec_name(mode.value)}"
              for mode in contracts.ALL_FAILURE_MODES]
    lines += [f"sessionState {state.value}" for state in contracts.SessionState]
    lines += [f"interruption {kind.value}" for kind in contracts.Interruption]
    return HEADER + "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true", help=f"Write {MANIFEST.relative_to(ROOT)}")
    mode.add_argument("--check", action="store_true",
                      help="Exit 1 if the checked-in manifest is stale")
    args = parser.parse_args()
    text = render()
    if args.write:
        MANIFEST.parent.mkdir(parents=True, exist_ok=True)
        MANIFEST.write_text(text, encoding="utf-8", newline="\n")
        return 0
    if args.check:
        current = MANIFEST.read_text(encoding="utf-8") if MANIFEST.exists() else ""
        if current != text:
            print(f"{MANIFEST.relative_to(ROOT)} is stale; run "
                  "python tools/av013_scenarios.py --write", file=sys.stderr)
            return 1
        return 0
    sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
