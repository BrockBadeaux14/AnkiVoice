#!/usr/bin/env python3
"""Derive the AV-005 turn corpus from the merged AV-002 VoiceQA fixtures.

The probe speaks only the ``Prompt`` field and expects the matching learner answer
from ``expected_cases``. Keeping the corpus derived means the spike reuses the
fixture text instead of restating it.
"""
from __future__ import annotations

import argparse
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "fixtures" / "voiceqa" / "note-type.json"
ROUNDS = 3


def build() -> dict:
    note_type = json.loads(FIXTURE.read_text(encoding="utf-8"))
    examples = note_type["examples"]
    turns = []
    for round_number in range(1, ROUNDS + 1):
        for example in examples:
            cases = example["expected_cases"]
            case = cases[(round_number - 1) % len(cases)]
            turns.append(
                {
                    "index": len(turns),
                    "round": round_number,
                    "example_id": example["id"],
                    "prompt": example["fields"]["Prompt"],
                    "expected_answer": case["answer"],
                    "expected_label": case["result"],
                    "language": example["fields"]["Language"],
                }
            )
    return {
        "schema_version": 1,
        "source": "fixtures/voiceqa/note-type.json",
        "rounds": ROUNDS,
        "turns": turns,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=pathlib.Path)
    arguments = parser.parse_args()
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(build(), indent=2) + "\n", encoding="utf-8")
    print(arguments.output)


if __name__ == "__main__":
    main()
