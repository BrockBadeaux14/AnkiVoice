"""AV-013 drift guard for the Kotlin session port, with no Gradle run.

The manifest tools/av013_scenarios.py derives from tools/av007_scenarios.py must be
current, and the ported Kotlin suite must declare one scenario per entry. android/:core's
ScenarioDriftTest enforces the same correspondence from the Kotlin side, where it can see
the compiled enums; this test catches the drift that would otherwise need an Android SDK
to notice. No emulator, network or Gradle run.
"""
from pathlib import Path
import re
import unittest

from tools import av007_contracts as contracts
from tools import av007_scenarios as scenarios
from tools import av013_scenarios as manifest

ROOT = Path(__file__).resolve().parents[1]
SESSION = ROOT / "android" / "core" / "src" / "main" / "kotlin" / "org" / "ankivoice" / "core" / "session"
SUITE = ROOT / "android" / "core" / "src" / "test" / "kotlin" / "org" / "ankivoice" / "core" / "session"


def records(text, kind):
    return [line.split()[1:] for line in text.splitlines()
            if line and not line.startswith("#") and line.split()[0] == kind]


class ManifestTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = manifest.render()

    def test_checked_in_manifest_is_current(self):
        self.assertTrue(manifest.MANIFEST.exists(), manifest.MANIFEST)
        self.assertEqual(manifest.MANIFEST.read_text(encoding="utf-8"), self.text,
                         "Regenerate with python tools/av013_scenarios.py --write")

    def test_it_lists_all_fifty_three_scenarios(self):
        named = records(self.text, "named")
        failures = records(self.text, "failure")
        self.assertEqual(len(named), len(scenarios.NAMED_SCENARIOS))
        self.assertEqual(len(named), 19)
        self.assertEqual(len(failures), len(contracts.ALL_FAILURE_MODES))
        self.assertEqual(len(failures), 34)
        self.assertEqual(len(named) + len(failures), 53)

    def test_named_entries_match_the_runs_the_binding_produces(self):
        self.assertEqual([record[0] for record in records(self.text, "named")],
                         [scenario().name for scenario in scenarios.NAMED_SCENARIOS])

    def test_failures_are_grouped_under_their_contract(self):
        for name, mode in records(self.text, "failure"):
            group = getattr(contracts, f"{name}Failure")
            self.assertIn(mode, {manifest.spec_name(member.value) for member in group}, mode)

    def test_session_states_and_interruptions_come_from_the_binding(self):
        self.assertEqual([record[0] for record in records(self.text, "sessionState")],
                         [state.value for state in contracts.SessionState])
        self.assertEqual([record[0] for record in records(self.text, "interruption")],
                         [kind.value for kind in contracts.Interruption])


class PortTest(unittest.TestCase):
    """The Kotlin sources exist and cover the manifest. Behavior is :core's own suite."""

    @classmethod
    def setUpClass(cls):
        cls.named = [record[0] for record in records(manifest.render(), "named")]
        cls.suite = (SUITE / "NamedScenariosTest.kt").read_text(encoding="utf-8")

    def test_the_session_port_lives_in_core(self):
        for name in ("ReviewSession.kt", "SessionStates.kt"):
            self.assertTrue((SESSION / name).is_file(), SESSION / name)
        for name in ("ScenarioHarness.kt", "NamedScenariosTest.kt", "FailureSweepTest.kt",
                     "ScenarioDriftTest.kt", "SessionGuardsTest.kt"):
            self.assertTrue((SUITE / name).is_file(), SUITE / name)

    def test_every_named_scenario_is_ported(self):
        declared = set(re.findall(r'@Scenario\("([^"]+)"\)', self.suite))
        self.assertEqual(set(self.named), declared,
                         "the Kotlin named suite and the manifest disagree")

    def test_the_failure_sweep_is_driven_from_the_taxonomy(self):
        sweep = (SUITE / "FailureSweepTest.kt").read_text(encoding="utf-8")
        # Enumerating 34 literals would drift silently; the sweep reads ALL_FAILURE_MODES.
        self.assertIn("ALL_FAILURE_MODES", sweep)

    def test_the_port_declares_no_android_dependency(self):
        for source in SESSION.glob("*.kt"):
            text = source.read_text(encoding="utf-8")
            self.assertNotIn("import android", text, source)
            self.assertNotIn("import androidx", text, source)


if __name__ == "__main__":
    unittest.main()
