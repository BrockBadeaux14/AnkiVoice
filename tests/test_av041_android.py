"""AV-041 checks for the Android build that need no Gradle run.

The drift guard: the manifest derived from tools/av007_contracts.py must be current;
android/:core's ManifestDriftTest fails when the Kotlin port differs from it. The pins:
android/README.md must list every pinned version, and the AV-022 baseline pins must be
the values the AV-004/AV-005 probes recorded. No emulator, network or Gradle run.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib
import unittest

from tools import av007_contracts as contracts
from tools import av041_manifest as manifest

ROOT = Path(__file__).resolve().parents[1]
SPECIFICATION = ROOT / "docs" / "contracts" / "av007-session-contracts.md"
ANDROID = ROOT / "android"
CATALOG = ANDROID / "gradle" / "libs.versions.toml"
WRAPPER = ANDROID / "gradle" / "wrapper" / "gradle-wrapper.properties"
README = ANDROID / "README.md"
EVIDENCE = ROOT / "docs" / "testing"


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
                         "Regenerate with python tools/av041_manifest.py --write")

    def test_it_covers_the_whole_binding(self):
        contract_names = [record[0] for record in records(self.text, "contract")]
        self.assertEqual(contract_names,
                         ["CardProvider", "SpeechOutput", "SpeechInput", "Grader", "ReviewWriter"])
        self.assertEqual([record[0] for record in records(self.text, "seam")], ["ReviewTransport"])
        self.assertEqual(len(records(self.text, "failure")), len(contracts.ALL_FAILURE_MODES))
        self.assertEqual(len(records(self.text, "failure")), 34)
        self.assertEqual(len(records(self.text, "capability")), 9)
        self.assertEqual([r[0] for r in records(self.text, "reviewState")],
                         [state.value for state in contracts.ReviewState])
        self.assertEqual([r[0] for r in records(self.text, "gradeLabel")],
                         [label.value for label in contracts.GradeLabel])

    def test_failures_are_grouped_under_their_contract(self):
        for name, mode in records(self.text, "failure"):
            group = getattr(contracts, f"{name}Failure")
            self.assertIn(mode, {manifest.spec_name(member.value) for member in group}, mode)

    def test_every_name_is_spelled_as_the_specification_spells_it(self):
        spec = SPECIFICATION.read_text(encoding="utf-8")
        # Contract names are headings; every other name is quoted as code. The seam's
        # operation is a binding detail the specification does not name.
        headings = [record[0] for kind in ("contract", "seam") for record in records(self.text, kind)]
        quoted = [record[0] for kind in ("capability", "reviewState", "gradeLabel")
                  for record in records(self.text, kind)]
        quoted += [record[1] for record in records(self.text, "failure")]
        quoted += [operation for record in records(self.text, "contract") for operation in record[1:]]
        for name in headings:
            self.assertIsNotNone(re.search(rf"\b{re.escape(name)}\b", spec), name)
        for name in quoted:
            self.assertIsNotNone(re.search(rf"`{re.escape(name)}`", spec), name)

    def test_spec_name_conversion(self):
        self.assertEqual(manifest.spec_name("access_denied"), "accessDenied")
        self.assertEqual(manifest.spec_name("supports_atomic_compare_and_write"),
                         "supportsAtomicCompareAndWrite")
        self.assertEqual(manifest.spec_name("commit"), "commit")

    def test_check_mode_runs_without_pythonpath(self):
        env = dict(os.environ)
        env.pop("PYTHONPATH", None)
        result = subprocess.run([sys.executable, "tools/av041_manifest.py", "--check"],
                                cwd=ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)


class PinsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.versions = tomllib.loads(CATALOG.read_text(encoding="utf-8"))["versions"]
        cls.wrapper = dict(line.split("=", 1) for line in WRAPPER.read_text(encoding="utf-8").splitlines()
                           if "=" in line and not line.startswith("#"))
        match = re.search(r"<!-- av041:pins:begin -->(.*?)<!-- av041:pins:end -->",
                          README.read_text(encoding="utf-8"), re.S)
        if match is None:
            raise AssertionError("missing av041:pins block in android/README.md")
        cls.pins = match.group(1)

    def wrapper_version(self):
        match = re.search(r"gradle-(\d+\.\d+\.\d+)-(bin|all)\.zip$", self.wrapper["distributionUrl"])
        self.assertIsNotNone(match, self.wrapper["distributionUrl"])
        return match.group(1), match.group(2)

    def test_the_readme_lists_every_pin(self):
        for name, version in self.versions.items():
            self.assertIn(f"`{version}`", self.pins, name)
        version, distribution = self.wrapper_version()
        self.assertIn(f"`{version}`, `{distribution}` distribution", self.pins)
        self.assertIn(f"`{self.wrapper['distributionSha256Sum']}`", self.pins)

    def test_the_av022_baseline_pins_are_the_probe_measurements(self):
        av004 = json.loads((EVIDENCE / "av004/evidence/environment.json").read_text(encoding="utf-8"))
        av005 = json.loads((EVIDENCE / "av005/evidence/environment.json").read_text(encoding="utf-8"))
        self.assertEqual(self.wrapper_version()[0], av005["gradle"])
        self.assertEqual(self.versions["agp"], av005["android_gradle_plugin"])
        self.assertEqual(int(self.versions["compileSdk"]), av005["compile_sdk"])
        self.assertEqual(int(self.versions["compileSdk"]), av004["compile_sdk"])
        self.assertEqual(int(self.versions["targetSdk"]), av005["target_sdk"])
        self.assertEqual(int(self.versions["minSdk"]), av005["min_sdk"])
        self.assertEqual(self.versions["buildTools"], av004["build_tools"])
        self.assertEqual(self.versions["java"], "17")

    def test_the_ci_workflow_runs_the_documented_command(self):
        workflow = (ROOT / ".github" / "workflows" / "android.yml").read_text(encoding="utf-8")
        self.assertIn("./gradlew --console=plain checkModuleBoundaries :core:test assembleDebug", workflow)
        self.assertIn(f'"platforms;android-{self.versions["compileSdk"]}"', workflow)
        self.assertIn(f'"build-tools;{self.versions["buildTools"]}"', workflow)
        self.assertIn(f"java-version: '{self.versions['java']}'", workflow)


if __name__ == "__main__":
    unittest.main()
