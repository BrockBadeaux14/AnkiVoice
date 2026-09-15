"""Consistency checks for the AV-022 Android implementation decision.

The decision is prose, so these tests hold it to the repository: the scores must
add up, the pinned baseline must match the retained AV-004/005/006 evidence, and
the contract and breakdown tables must cover what AV-007 and the issue require.
Nothing here needs an emulator, the network or credentials.
"""

import ast
import json
import re
import unittest
from pathlib import Path

from tools import av007_contracts

ROOT = Path(__file__).resolve().parents[1]
DECISION = ROOT / "docs" / "decisions" / "0022-android-implementation.md"
EVIDENCE = ROOT / "docs" / "testing"


def evidence(relative):
    return json.loads((EVIDENCE / relative).read_text(encoding="utf-8"))


def block(text, name):
    match = re.search(rf"<!-- av022:{name}:begin -->(.*?)<!-- av022:{name}:end -->", text, re.S)
    if match is None:
        raise AssertionError(f"missing av022:{name} block")
    return match.group(1)


def section(text, heading):
    match = re.search(rf"^#+ {re.escape(heading)}\n(.*?)(?=^#+ |\Z)", text, re.S | re.M)
    if match is None:
        raise AssertionError(f"missing section {heading!r}")
    return match.group(1)


def table_rows(markdown):
    rows = []
    for line in markdown.splitlines():
        if not line.startswith("|") or set(line) <= set("|-: "):
            continue
        rows.append([cell.strip() for cell in line.strip().strip("|").split("|")])
    return rows[1:]  # drop the header


class ScoreTest(unittest.TestCase):
    """The rubric arithmetic and the stated winner must agree."""

    @classmethod
    def setUpClass(cls):
        cls.text = DECISION.read_text(encoding="utf-8")
        rows = table_rows(block(cls.text, "scores"))
        cls.criteria = [row for row in rows if row[0].isdigit()]
        cls.totals = next(row for row in rows if "total" in row[1].lower())
        cls.weights = [int(row[2]) for row in cls.criteria]
        cls.kotlin = [int(row[3]) for row in cls.criteria]
        cls.flutter = [int(row[4]) for row in cls.criteria]

    @staticmethod
    def weighted(weights, scores):
        return sum(w * s for w, s in zip(weights, scores))

    def test_five_criteria_in_rubric_order(self):
        self.assertEqual([int(row[0]) for row in self.criteria], [1, 2, 3, 4, 5])
        self.assertIn("Native reach", self.criteria[0][1])
        self.assertIn("Deferred-platform", self.criteria[4][1])

    def test_scores_use_the_documented_scale(self):
        for score in self.kotlin + self.flutter:
            self.assertIn(score, range(1, 6))

    def test_deferred_platforms_carry_the_lowest_weight(self):
        self.assertEqual(self.weights[4], min(self.weights))
        self.assertTrue(all(w > self.weights[4] for w in self.weights[:4]))

    def test_stated_totals_match_the_arithmetic(self):
        maximum = 5 * sum(self.weights)
        self.assertIn(f"maximum {maximum}", self.totals[1])
        kotlin = self.weighted(self.weights, self.kotlin)
        flutter = self.weighted(self.weights, self.flutter)
        self.assertEqual(self.totals[3], f"**{kotlin}**")
        self.assertEqual(self.totals[4], f"**{flutter}**")
        self.assertIn(f"**{kotlin}/{maximum}**", self.text)
        self.assertIn(f"**{flutter}/{maximum}**", self.text)

    def test_stated_winner_has_the_higher_total(self):
        decision = section(self.text, "Decision")
        self.assertIn("**Kotlin, native Android.**", decision)
        self.assertGreater(self.weighted(self.weights, self.kotlin),
                           self.weighted(self.weights, self.flutter))

    def test_sensitivity_claims(self):
        unweighted = (sum(self.kotlin), sum(self.flutter))
        self.assertIn(f"Kotlin {unweighted[0]}, Flutter {unweighted[1]}", self.text)
        self.assertGreater(*unweighted)

        generous = self.flutter[:3] + [5, 5]
        best_case = self.weighted(self.weights, generous)
        self.assertIn(f"Flutter reaches {best_case} weighted", self.text)
        self.assertLess(best_case, self.weighted(self.weights, self.kotlin))

        # Criterion 5 alone cannot decide: drop it, or hand each side the other's score.
        for kotlin_five, flutter_five in ((0, 0), (self.flutter[4], self.kotlin[4])):
            kotlin = self.kotlin[:4] + [kotlin_five]
            flutter = self.flutter[:4] + [flutter_five]
            self.assertGreater(self.weighted(self.weights, kotlin),
                               self.weighted(self.weights, flutter))


class BaselineTest(unittest.TestCase):
    """Every validated pin must be the value the evidence actually recorded."""

    @classmethod
    def setUpClass(cls):
        cls.text = DECISION.read_text(encoding="utf-8")
        cls.baseline = block(cls.text, "baseline")
        cls.av004 = evidence("av004/evidence/environment.json")
        cls.av005 = evidence("av005/evidence/environment.json")
        cls.av006 = evidence("av006/evidence/environment.json")
        cls.tts = evidence("av006/evidence/native-tts.json")
        cls.stt = evidence("av006/evidence/native-stt-online-permitted.json")

    def pinned(self, value):
        self.assertIn(f"`{value}`", self.baseline)

    def test_android_image_and_build(self):
        for environment in (self.av004, self.av005):
            self.pinned(environment["system_image"])
        self.assertTrue(self.av006["system_image"].startswith(self.av004["system_image"]))
        self.pinned(str(self.av004["image_revision"]))
        self.assertIn("revision 7", self.av006["system_image"])
        self.pinned(self.av004["android_fingerprint"])
        self.assertEqual(self.av006["android_fingerprint"], self.av004["android_fingerprint"])
        self.pinned(self.av004["emulator"].split()[0])
        self.assertIn(self.av004["emulator"].split()[0], self.av005["emulator_version"])

    def test_ankidroid_release(self):
        self.pinned(self.av004["ankidroid"])
        self.pinned(self.av004["apk"])
        self.pinned(str(self.av004["version_code"]))
        self.pinned(self.av004["apk_sha256"])
        self.pinned(self.av004["upstream_commit"])
        self.assertIn("com.ichi2.anki.flashcards", self.av004["api_route"])
        self.pinned("com.ichi2.anki.flashcards")

    def test_x86_64_version_code_follows_the_abi_scheme(self):
        # AnkiDroid's pinned build script: ABI prefix * 10^8 + base version code,
        # with arm64-v8a = 3 and x86_64 = 4.
        base = self.av004["version_code"] - 3 * 100_000_000
        self.assertEqual(base, 22401300)
        self.assertIn(f"`{4 * 100_000_000 + base}`", self.text)
        self.assertIn("variant-abi-AnkiDroid-2.24.1-x86_64.apk", self.text)

    def test_sdk_levels(self):
        compile_sdk = self.av004["compile_sdk"]
        target_sdk = self.av004["probe_target_sdk"]
        self.assertEqual((compile_sdk, target_sdk),
                         (self.av005["compile_sdk"], self.av005["target_sdk"]))
        self.pinned(str(compile_sdk))
        self.pinned(str(target_sdk))
        self.pinned(str(self.av005["min_sdk"]))
        self.assertLessEqual(self.av005["min_sdk"], target_sdk)
        self.assertLessEqual(target_sdk, compile_sdk)
        manifest = (ROOT / "tools" / "av006-probe" / "AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertIn(f'minSdkVersion="{self.av005["min_sdk"]}"', manifest)
        self.assertIn(f'targetSdkVersion="{target_sdk}"', manifest)

    def test_build_toolchain(self):
        self.pinned(self.av005["android_gradle_plugin"])
        self.pinned(self.av005["gradle"])
        self.pinned(self.av004["build_tools"])
        self.assertEqual(self.av006["build_tools"], self.av004["build_tools"])

    def test_speech_configuration(self):
        for record in (self.tts, self.stt):
            self.pinned(record["engine"])
            self.pinned(record["engine_version"])
            self.pinned(str(record["engine_version_code"]))
            self.pinned(record["recognition_service"])
        self.pinned(self.tts["selected_voice"])
        self.assertFalse(self.stt["prefer_offline"])
        self.pinned("EXTRA_PREFER_OFFLINE=false")

    def test_selected_grader_route(self):
        # Parse rather than import: the runner uses POSIX-only modules.
        source = (ROOT / "tools" / "av006_providers.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        candidates = next(
            ast.literal_eval(node.value) for node in tree.body
            if isinstance(node, ast.Assign)
            and any(getattr(t, "id", None) == "CANDIDATES" for t in node.targets)
        )
        model, provider = candidates["grade-b"]
        self.pinned(model)
        self.pinned(provider)
        self.assertIn('"allow_fallbacks": False', source)
        self.pinned("allow_fallbacks=false")

    def test_unvalidated_pins_are_not_called_validated(self):
        rows = {row[0]: row for row in table_rows(self.baseline)}
        self.assertIn("Not validated", rows["Recognition"][3])
        self.assertIn("not a support claim", rows["Minimum SDK"][3])
        self.assertIn("intended and unvalidated", self.text)


class OwnershipTest(unittest.TestCase):
    """The ownership map must place every #7 contract and every required card."""

    @classmethod
    def setUpClass(cls):
        cls.text = DECISION.read_text(encoding="utf-8")
        cls.contracts = av007_contracts

    def test_every_contract_is_placed(self):
        rows = table_rows(section(self.text, "The five #7 contracts"))
        placed = {row[0].strip("`") for row in rows}
        protocols = {"CardProvider", "SpeechOutput", "SpeechInput", "Grader", "ReviewWriter"}
        for name in protocols:
            self.assertTrue(hasattr(self.contracts, name), name)
        self.assertEqual(placed, protocols)

    def test_failure_count_matches_the_binding(self):
        count = len(self.contracts.ALL_FAILURE_MODES)
        self.assertRegex(self.text, rf"\b{count}\s+enumerated failures")

    def test_required_responsibilities_have_one_owner(self):
        rows = table_rows(section(self.text, "Responsibilities"))
        owners = {row[0]: row[1] for row in rows}
        self.assertEqual(set(owners), {
            "AnkiDroid ContentResolver calls",
            "Speech/TTS instances and cancellation",
            "Credential storage",
            "Provider requests",
            "Session state",
            "Review writing",
        })
        modules = {":app", ":core", ":ankidroid", ":speech", ":provider"}
        for responsibility, owner in owners.items():
            self.assertTrue(any(f"`{m}" in owner for m in modules), responsibility)

    def test_breakdown_covers_the_required_cards(self):
        rows = table_rows(section(self.text, "Implementation breakdown"))
        cards = {row[0].split()[0] for row in rows}
        self.assertEqual(cards, {"#24", "#25", "#26", "#13", "#14", "#17", "#27"})

    def test_no_python_loop_is_claimed(self):
        self.assertIn("**No Python voice loop exists to port.**", self.text)


class LinkTest(unittest.TestCase):
    def test_relative_links_resolve(self):
        text = DECISION.read_text(encoding="utf-8")
        targets = re.findall(r"\]\(([^)\s]+)\)", text)
        relative = [t for t in targets if not re.match(r"[a-z]+:", t) and not t.startswith("#")]
        self.assertTrue(relative)
        for target in relative:
            path = (DECISION.parent / target.split("#")[0]).resolve()
            self.assertTrue(path.exists(), target)


if __name__ == "__main__":
    unittest.main()
