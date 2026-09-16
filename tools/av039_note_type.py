#!/usr/bin/env python3
"""Derive the VoiceQA provisioning resource that :ankidroid installs and verifies.

fixtures/voiceqa/note-type.json is the specification. The Android app cannot parse
it: org.json is stubbed in JVM unit tests, and AV-022's pins add no JSON library.
This tool restates the installable part of the fixture -- the note type name, its
ordered fields, the single template, the CSS, the demo deck and the four sample
notes -- as a java.util.Properties resource that both the app and its JVM tests
read with the JDK's own parser.

tests/test_av039_note_type.py fails if the checked-in copy is stale;
VoiceQaNoteTypeTest in :ankidroid fails if the loader disagrees with it.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "fixtures" / "voiceqa" / "note-type.json"
RESOURCE = (ROOT / "android" / "ankidroid" / "src" / "main" / "resources"
            / "av039" / "voiceqa-note-type.properties")

# The deck AV-039 creates for the sample notes, and the tags that keep each sample
# note traceable to its fixture example id. The fixture has no field for that id.
DEMO_DECK = "VoiceQA Demo"
DEMO_TAG = "ankivoice-demo"

HEADER = (
    "# The installable VoiceQA note type and demo content, for AV-039 (#10).\n"
    "# Generated from fixtures/voiceqa/note-type.json by tools/av039_note_type.py;\n"
    "# do not edit. Regenerate with: python tools/av039_note_type.py --write\n"
)


def escape(value: str) -> str:
    """Escape a value the way java.util.Properties reads it back unchanged."""
    out = []
    for index, character in enumerate(value):
        if character == "\\":
            out.append("\\\\")
        elif character == "\n":
            out.append("\\n")
        elif character == "\r":
            out.append("\\r")
        elif character == "\t":
            out.append("\\t")
        elif character == " " and index == 0:
            out.append("\\ ")
        elif character in "=:#!":
            out.append("\\" + character)
        elif not character.isprintable() or ord(character) > 126:
            out.append(f"\\u{ord(character):04x}")
        else:
            out.append(character)
    return "".join(out)


def render() -> str:
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    fields = [field["name"] for field in fixture["fields"]]
    template = fixture["templates"][0]
    if len(fixture["templates"]) != 1 or fixture["card_count_per_note"] != 1:
        raise SystemExit("AV-039 installs exactly one template and one card per note")

    lines = [
        f"notetype.name={escape(fixture['name'])}",
        f"notetype.fieldCount={len(fields)}",
    ]
    lines += [f"notetype.field.{index}={escape(name)}" for index, name in enumerate(fields)]
    lines += [
        f"notetype.css={escape(fixture['css'])}",
        f"template.name={escape(template['name'])}",
        f"template.front={escape(template['front'])}",
        f"template.back={escape(template['back'])}",
        f"demo.deck={escape(DEMO_DECK)}",
        f"demo.count={len(fixture['examples'])}",
    ]
    for index, example in enumerate(fixture["examples"]):
        lines.append(f"demo.note.{index}.tags={escape(DEMO_TAG + ' ' + example['id'])}")
        for position, name in enumerate(fields):
            value = example["fields"].get(name, "")
            lines.append(f"demo.note.{index}.field.{position}={escape(value)}")
    return HEADER + "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true", help=f"Write {RESOURCE.relative_to(ROOT)}")
    mode.add_argument("--check", action="store_true",
                      help="Exit 1 if the checked-in resource is stale")
    args = parser.parse_args()
    text = render()
    if args.write:
        RESOURCE.parent.mkdir(parents=True, exist_ok=True)
        RESOURCE.write_text(text, encoding="utf-8", newline="\n")
        return 0
    if args.check:
        current = RESOURCE.read_text(encoding="utf-8") if RESOURCE.exists() else ""
        if current != text:
            print(f"{RESOURCE.relative_to(ROOT)} is stale; run "
                  "python tools/av039_note_type.py --write", file=sys.stderr)
            return 1
        return 0
    sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
