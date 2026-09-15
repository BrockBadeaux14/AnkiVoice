#!/usr/bin/env python3
"""Derive the AV-007 vocabulary manifest that the Kotlin port is checked against.

The manifest lists the contract names and operations, the failures per contract,
the capability flags with their defaults, the review states and the grade labels,
all read from tools/av007_contracts.py and spelled as the specification spells them.
tests/test_av041_android.py fails if the checked-in copy is stale; the Kotlin
ManifestDriftTest fails if android/:core differs from it.
"""

from __future__ import annotations

import argparse
from dataclasses import MISSING, fields
import inspect
from pathlib import Path
import sys

# Support the documented direct-file command as well as module execution.
if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools import av007_contracts as contracts

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "android" / "core" / "src" / "test" / "resources" / "av007" / "manifest.txt"

HEADER = (
    "# AV-007 vocabulary for the Kotlin port (AV-041), in specification spelling.\n"
    "# Generated from tools/av007_contracts.py by tools/av041_manifest.py; do not edit.\n"
    "# Regenerate with: python tools/av041_manifest.py --write\n"
)


def spec_name(snake: str) -> str:
    """The specification's spelling of a binding name: access_denied -> accessDenied."""
    head, *rest = snake.split("_")
    return head + "".join(part.capitalize() for part in rest)


def protocols() -> list[type]:
    """The binding's Protocol classes, in declaration order."""
    return [value for value in vars(contracts).values()
            if inspect.isclass(value) and value.__module__ == contracts.__name__
            and getattr(value, "_is_protocol", False)]


def failure_groups() -> list[type]:
    return [value for value in vars(contracts).values()
            if inspect.isclass(value) and value is not contracts.ContractFailure
            and issubclass(value, contracts.ContractFailure)]


def operations(protocol: type) -> list[str]:
    return [spec_name(name) for name, value in vars(protocol).items()
            if inspect.isfunction(value) and not name.startswith("_")]


def default_text(value: object) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, tuple):
        return ",".join(str(item) for item in value) or "none"
    return str(value)


def render() -> str:
    groups = {group.__name__.removesuffix("Failure"): group for group in failure_groups()}
    lines = []
    for protocol in protocols():
        if protocol.__name__ in groups:
            lines.append(" ".join(["contract", protocol.__name__, *operations(protocol)]))
    missing = set(groups) - {protocol.__name__ for protocol in protocols()}
    if missing:
        raise ValueError(f"failure groups without a contract: {sorted(missing)}")
    for name, group in groups.items():
        lines.extend(f"failure {name} {spec_name(mode.value)}" for mode in group)
    for protocol in protocols():
        if protocol.__name__ not in groups:
            lines.append(" ".join(["seam", protocol.__name__, *operations(protocol)]))
    for field in fields(contracts.Capabilities):
        if field.default is MISSING:
            raise ValueError(f"capability {field.name} has no default")
        lines.append(f"capability {spec_name(field.name)} {default_text(field.default)}")
    for state in contracts.ReviewState:
        terminal = "terminal" if state in contracts.TERMINAL_REVIEW_STATES else "nonterminal"
        lines.append(f"reviewState {state.value} {terminal}")
    for label in contracts.GradeLabel:
        proposal = contracts.AUTOMATIC_PROPOSALS[label]
        lines.append(f"gradeLabel {label.value} {'none' if proposal is None else proposal}")
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
                  "python tools/av041_manifest.py --write", file=sys.stderr)
            return 1
        return 0
    sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
