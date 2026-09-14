"""Acceptance tests against the real pinned Anki backend; no app/provider mocks."""

import contextlib
import io
import json
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

from anki.collection import Collection
from anki.scheduler.v3 import CardAnswer

from tools import voiceqa_fixtures as fixtures


class VoiceQAFixturesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory()
        cls.root = Path(cls.workspace.name)
        cls.bundle = cls.root / "fixtures"
        with contextlib.redirect_stdout(io.StringIO()):
            fixtures.build(cls.bundle)
        cls.spec, cls.scenarios = fixtures.read_sources()

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(dir=self.root)
        self.addCleanup(self.scratch.cleanup)
        self.directory = Path(self.scratch.name)

    def open_copy(self, name):
        target = self.directory / name
        fixtures.restore(self.bundle / name / "collection.colpkg", target)
        col = Collection(str(target / "collection.anki2"))
        self.addCleanup(col.close)
        return col

    @staticmethod
    def cards_by_fixture(col):
        return {next(t.removeprefix("fixture::") for t in col.get_card(cid).note().tags
                     if t.startswith("fixture::")): col.get_card(cid)
                for cid in col.find_cards("tag:av002")}

    @staticmethod
    def answer_next(col, rating):
        queued = col.sched.get_queued_cards().cards[0]
        card = col.get_card(queued.card.id)
        card.start_timer()
        answer = col.sched.build_answer(card=card, states=queued.states, rating=rating)
        answer.milliseconds_taken = 2500
        col.sched.answer_card(answer)
        return card.id

    def test_content_schema_templates_and_integrity(self):
        field_names = ["Prompt", "ReferenceAnswer", "RequiredConcepts",
                       "AcceptedAnswers", "Language", "Extra"]
        self.assertEqual([f["name"] for f in self.spec["fields"] if f["required"]],
                         ["Prompt", "ReferenceAnswer"])
        for name, scenario in self.scenarios["profiles"].items():
            with self.subTest(profile=name):
                col = self.open_copy(name)
                self.assertEqual(col.db.scalar("pragma integrity_check"), "ok")
                self.assertEqual(col.db.scalar("select count(*) from notes"), len(scenario["cards"]))
                self.assertEqual(col.db.scalar("select count(*) from cards"), len(scenario["cards"]))
                model = col.models.by_name("VoiceQA")
                self.assertEqual([f["name"] for f in model["flds"]], field_names)
                self.assertEqual(len(model["tmpls"]), 1)
                self.assertEqual(model["tmpls"][0]["qfmt"], self.spec["templates"][0]["front"])
                self.assertEqual(model["tmpls"][0]["afmt"], self.spec["templates"][0]["back"])
                self.assertEqual(model["css"], self.spec["css"])
                for card in self.cards_by_fixture(col).values():
                    note = card.note()
                    if note.note_type()["name"] == "VoiceQA":
                        self.assertIn(note["Prompt"], card.question())
                        self.assertNotIn("REFERENCE ANSWER", card.question())
                        self.assertNotIn("KEY CONCEPTS", card.question())
                        self.assertTrue(note["RequiredConcepts"])
                        self.assertTrue(note["AcceptedAnswers"])
                    elif name != "rejection":
                        self.fail("Incompatible cards escaped the rejection profile")

    def test_front_does_not_render_answer_or_grading_fields(self):
        col = self.open_copy("baseline")
        note = self.cards_by_fixture(col)["new"].note()
        for field in ("ReferenceAnswer", "RequiredConcepts", "AcceptedAnswers", "Extra"):
            note[field] = f"SECRET_{field}"
        col.update_note(note)
        front = col.get_card(note.card_ids()[0]).question()
        self.assertNotIn("SECRET_", front)

    def test_requested_states_history_and_due_units(self):
        col = self.open_copy("baseline")
        cards = self.cards_by_fixture(col)
        expected = {"new": (0, 0), "learning": (1, 1), "relearning": (3, 1),
                    "mature": (2, 2), "suspended": (0, -1),
                    "buried-manual": (0, -3), "buried-sibling": (0, -2), "future": (2, 2)}
        self.assertEqual(set(cards), set(expected))
        for key, state in expected.items():
            with self.subTest(fixture=key):
                card = cards[key]
                self.assertEqual((card.type, card.queue), state)
                history = col.db.all("select id, ease, ivl, type from revlog where cid=? order by id", card.id)
                self.assertEqual(len(history), card.reps)
                self.assertTrue(all(row[0] / 1000 < time.time() for row in history))
                if key in ("learning", "relearning"):
                    self.assertLess(card.due, time.time())
                    self.assertGreater(card.due, col.sched.day_cutoff - 2 * fixtures.DAY)
                    self.assertEqual(history[-1][1], 1)
                    self.assertLess(history[-1][2], 0)
        self.assertEqual(cards["mature"].due, col.sched.today)
        self.assertGreaterEqual(cards["mature"].ivl, 21)
        self.assertEqual(cards["future"].due, col.sched.today + 7)
        self.assertEqual(cards["relearning"].lapses, 1)
        self.assertEqual(col.sched.counts(), (1, 2, 1))
        queued = col.sched.get_queued_cards(fetch_limit=100)
        self.assertEqual({entry.card.id for entry in queued.cards},
                         {cards[key].id for key in ("new", "learning", "relearning", "mature")})

    def test_learning_and_relearning_can_be_reviewed(self):
        col = self.open_copy("baseline")
        cards = self.cards_by_fixture(col)
        expected = {cards[key].id for key in ("learning", "relearning")}
        reviewed = set()
        for _ in range(2):
            queued = col.sched.get_queued_cards().cards[0]
            self.assertIn(queued.card.id, expected)
            self.assertEqual(len(col.sched.describe_next_states(queued.states)), 4)
            before = col.get_card(queued.card.id)
            cid = self.answer_next(col, CardAnswer.EASY)
            after = col.get_card(cid)
            self.assertEqual((after.type, after.queue), (2, 2))
            self.assertEqual(after.reps, before.reps + 1)
            self.assertEqual(col.db.scalar("select count(*) from revlog where cid=?", cid), after.reps)
            reviewed.add(cid)
        self.assertEqual(reviewed, expected)

    def test_limits_stop_queue_and_survive_reopen(self):
        col = self.open_copy("limits")
        self.assertEqual(col.sched.counts(), (1, 0, 2))
        settings = col.decks.get_deck_configs_for_update(col.decks.get_current_id())
        self.assertFalse(settings.fsrs)
        self.assertTrue(settings.new_cards_ignore_review_limit)
        reviewed = []
        previous_history = col.db.scalar("select count(*) from revlog")
        for _ in range(3):
            entry = col.sched.get_queued_cards().cards[0]
            reviewed.append(entry.card.ctype)
            self.answer_next(col, CardAnswer.EASY)
        self.assertCountEqual(reviewed, [0, 2, 2])
        self.assertEqual(col.sched.counts(), (0, 0, 0))
        self.assertEqual(len(col.sched.get_queued_cards().cards), 0)
        self.assertEqual(col.db.scalar("select count(*) from cards where queue=0"), 2)
        self.assertEqual(col.db.scalar("select count(*) from cards where queue=2 and due<=?", col.sched.today), 1)
        self.assertEqual(col.db.scalar("select count(*) from revlog"), previous_history + 3)
        col.close()
        reopened = Collection(str(self.directory / "limits" / "collection.anki2"))
        self.addCleanup(reopened.close)
        self.assertEqual(reopened.sched.counts(), (0, 0, 0))

    def test_rejection_cases_are_isolated_and_labelled(self):
        col = self.open_copy("rejection")
        cards = self.cards_by_fixture(col)
        self.assertEqual(cards["basic"].note().note_type()["name"], "Basic")
        self.assertEqual(cards["cloze"].note().note_type()["name"], "Cloze")
        self.assertEqual(cards["missing-reference"].note()["ReferenceAnswer"], "")
        self.assertTrue(cards["valid-control"].note()["ReferenceAnswer"])
        metadata = col.get_config(fixtures.CONFIG_KEY)
        self.assertEqual(metadata["expected_rejections"], {
            "basic": "unsupported_note_type", "cloze": "unsupported_note_type",
            "missing-reference": "missing_reference_answer",
        })
        # Anki itself accepts these cards. Product rejection/no-write behavior is
        # a future integration test, not a classifier implemented by this fixture.
        self.assertEqual(col.sched.counts(), (4, 0, 0))

    def test_backup_restore_preserves_edits_reviews_media_and_baseline(self):
        col = self.open_copy("baseline")
        original = fixtures.snapshot(col)
        self.answer_next(col, CardAnswer.GOOD)
        note = self.cards_by_fixture(col)["mature"].note()
        note["Extra"] = "Synthetic backup round-trip edit."
        col.update_note(note)
        media = self.directory / "baseline" / "collection.media" / "fixture.txt"
        media.write_text("synthetic media round trip")
        expected = fixtures.snapshot(col)
        col.close()
        package = self.directory / "after-review.colpkg"
        fixtures.backup(self.directory / "baseline", package)
        fixtures.restore(package, self.directory / "restored")
        restored = Collection(str(self.directory / "restored" / "collection.anki2"))
        self.addCleanup(restored.close)
        self.assertEqual(fixtures.snapshot(restored), expected)
        self.assertEqual((self.directory / "restored" / "collection.media" / "fixture.txt").read_text(),
                         "synthetic media round trip")
        # Reset from the untouched seed into another disposable directory.
        fixtures.restore(self.bundle / "baseline" / "collection.colpkg", self.directory / "reset")
        reset = Collection(str(self.directory / "reset" / "collection.anki2"))
        self.addCleanup(reset.close)
        self.assertEqual(fixtures.snapshot(reset), original)
        self.assertEqual(reset.sched.counts(), (1, 2, 1))

    def test_manifest_matches_imported_collection(self):
        for name in self.scenarios["profiles"]:
            with self.subTest(profile=name):
                manifest = json.loads((self.bundle / name / fixtures.MARKER).read_text())
                actual = fixtures.snapshot(self.open_copy(name))
                # JSON encodes backend tuples as arrays.
                self.assertEqual(json.loads(json.dumps(actual)),
                                 {key: manifest[key] for key in actual})

    def test_rebuild_reproduces_content_and_relative_scheduling(self):
        second = self.directory / "second-build"
        with contextlib.redirect_stdout(io.StringIO()):
            fixtures.build(second)

        def normalized(manifest):
            built_at = int(fixtures.datetime.fromisoformat(manifest["built_at"]).timestamp())
            cards = []
            for card in manifest["cards"]:
                card = dict(card)
                del card["card_id"], card["note_id"]
                if card["queue"] == 1:
                    card["due"] -= built_at
                elif card["type"] == 2:
                    card["due"] -= manifest["scheduler_today"]
                card["review_history"] = [row[3:] for row in card["review_history"]]
                cards.append(card)
            return {"cards": cards, "counts": manifest["initial_counts_new_learning_review"],
                    "new_limit": manifest["preset"]["new"]["perDay"],
                    "review_limit": manifest["preset"]["rev"]["perDay"],
                    "source_hashes": manifest["source_sha256"]}

        for name in self.scenarios["profiles"]:
            with self.subTest(profile=name):
                first_manifest = json.loads((self.bundle / name / fixtures.MARKER).read_text())
                second_manifest = json.loads((second / name / fixtures.MARKER).read_text())
                self.assertEqual(normalized(first_manifest), normalized(second_manifest))

    def test_existing_outputs_and_unmarked_profiles_are_refused(self):
        sentinel = self.directory / "existing"
        sentinel.mkdir()
        (sentinel / "keep.txt").write_text("user data")
        package = self.bundle / "baseline" / "collection.colpkg"
        with self.assertRaises(FileExistsError):
            fixtures.build(sentinel)
        with self.assertRaises(FileExistsError):
            fixtures.restore(package, sentinel)
        existing_package = self.directory / "existing.colpkg"
        existing_package.write_bytes(b"existing backup")
        with self.assertRaises(FileExistsError):
            fixtures.backup(self.bundle / "baseline", existing_package)
        with self.assertRaises(ValueError):
            fixtures.backup(sentinel, self.directory / "new.colpkg")
        self.assertEqual((sentinel / "keep.txt").read_text(), "user data")
        self.assertEqual(existing_package.read_bytes(), b"existing backup")
        self.assertFalse((sentinel / "collection.anki2").exists())

    def test_cli_missing_package_reports_actionable_error(self):
        result = subprocess.run([
            sys.executable, str(Path(fixtures.__file__)), "restore", "--package",
            str(self.directory / "missing.colpkg"), "--output", str(self.directory / "restore"),
        ], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("Package does not exist", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_cli_invalid_package_reports_error_without_touching_source(self):
        package = self.directory / "invalid.colpkg"
        package.write_bytes(b"not an Anki package")
        result = subprocess.run([
            sys.executable, str(Path(fixtures.__file__)), "restore", "--package",
            str(package), "--output", str(self.directory / "restore"),
        ], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("error:", result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assertEqual(package.read_bytes(), b"not an Anki package")


if __name__ == "__main__":
    unittest.main()
