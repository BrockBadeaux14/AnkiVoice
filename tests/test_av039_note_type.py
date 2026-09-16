"""AV-039 checks for the derived VoiceQA provisioning resource. No Gradle or emulator.

The resource :ankidroid installs from must stay a faithful restatement of
fixtures/voiceqa/note-type.json, and must stay readable by java.util.Properties.
VoiceQaNoteTypeTest in :ankidroid fails when the Kotlin loader disagrees with it.
"""
import json
from pathlib import Path
import unittest

from tools import av039_note_type as generator

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = json.loads(generator.FIXTURE.read_text(encoding="utf-8"))
FIELDS = [field["name"] for field in FIXTURE["fields"]]


def parse(text):
    """java.util.Properties semantics for the subset this resource uses."""
    escapes = {"n": "\n", "r": "\r", "t": "\t", "\\": "\\", "=": "=", ":": ":",
               "#": "#", "!": "!", " ": " "}
    values = {}
    for line in text.splitlines():
        if not line or line.lstrip().startswith(("#", "!")):
            continue
        key, out, index, in_key = "", [], 0, True
        while index < len(line):
            character = line[index]
            if character == "\\":
                index += 1
                following = line[index]
                if following == "u":
                    out.append(chr(int(line[index + 1:index + 5], 16)))
                    index += 4
                else:
                    out.append(escapes[following])
            elif in_key and character in "=:":
                key, out, in_key = "".join(out), [], False
            else:
                out.append(character)
            index += 1
        assert not in_key, line
        values[key] = "".join(out)
    return values


class ResourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = generator.render()
        cls.values = parse(cls.text)

    def test_checked_in_resource_is_current(self):
        self.assertTrue(generator.RESOURCE.exists(), generator.RESOURCE)
        self.assertEqual(generator.RESOURCE.read_text(encoding="utf-8"), self.text,
                         "Regenerate with python tools/av039_note_type.py --write")

    def test_escaping_round_trips_every_value(self):
        # A properties parser must return the fixture's own strings, not an escaped form.
        self.assertEqual(self.values["notetype.name"], FIXTURE["name"])
        self.assertEqual(self.values["notetype.css"], FIXTURE["css"])
        self.assertEqual(self.values["template.name"], FIXTURE["templates"][0]["name"])
        self.assertEqual(self.values["template.front"], FIXTURE["templates"][0]["front"])
        self.assertEqual(self.values["template.back"], FIXTURE["templates"][0]["back"])

    def test_it_restates_the_note_type_the_fixture_defines(self):
        self.assertEqual(int(self.values["notetype.fieldCount"]), len(FIELDS))
        self.assertEqual([self.values[f"notetype.field.{index}"]
                          for index in range(len(FIELDS))], FIELDS)
        self.assertEqual(FIELDS[:2], ["Prompt", "ReferenceAnswer"])
        self.assertEqual(FIXTURE["card_count_per_note"], 1)
        self.assertEqual(len(FIXTURE["templates"]), 1)

    def test_the_front_template_reveals_no_grading_criteria(self):
        front = self.values["template.front"]
        for field in FIELDS[1:]:
            self.assertNotIn(field, front, field)
        self.assertIn("{{Prompt}}", front)

    def test_it_restates_every_sample_note(self):
        examples = FIXTURE["examples"]
        self.assertEqual(int(self.values["demo.count"]), len(examples))
        self.assertEqual(self.values["demo.deck"], "VoiceQA Demo")
        for index, example in enumerate(examples):
            tags = self.values[f"demo.note.{index}.tags"].split()
            self.assertEqual(tags, [generator.DEMO_TAG, example["id"]])
            for position, name in enumerate(FIELDS):
                self.assertEqual(self.values[f"demo.note.{index}.field.{position}"],
                                 example["fields"].get(name, ""), (example["id"], name))

    def test_the_demo_deck_is_not_an_av002_deck(self):
        # AV-002's generated decks are test tooling and are never written to.
        scenarios = json.loads((ROOT / "fixtures" / "voiceqa" / "scenarios.json")
                               .read_text(encoding="utf-8"))
        self.assertNotIn(self.values["demo.deck"], json.dumps(scenarios))
        self.assertFalse(self.values["demo.deck"].startswith("AV002"))


if __name__ == "__main__":
    unittest.main()
