"""AV-043 drift guard: free first, paid only within the cap, and nothing reaches a writer.

These checks read the sources and the documents; no Gradle run, no emulator, no network.
They fail if the route order changes, if the free-route guard grows a second hard-coded
provider name, if the paid pin drifts from a paid model with decimal prices, if the
default cap or the budget stop disappears, if a paid request could leave the device before
its reservation, or if the disclosure and the decision record stop describing the route
the build ships.
"""
from decimal import Decimal
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
PROVIDER = ROOT / "android" / "provider" / "src" / "main" / "kotlin" / "org" / "ankivoice" / "provider"
PROVIDER_TEST = ROOT / "android" / "provider" / "src" / "test" / "kotlin" / "org" / "ankivoice" / "provider"
APP = ROOT / "android" / "app" / "src" / "main"
DOCS = ROOT / "docs"


def code(path):
    """A Kotlin source with its comments removed, so prose about a symbol is not a use of it."""
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class RouteOrder(unittest.TestCase):
    def test_free_is_tried_before_paid(self):
        routes = code(PROVIDER / "GradingRoute.kt")
        self.assertIn("val ORDER: List<GradingRoute> = listOf(FREE, PAID)", routes)
        grader = code(PROVIDER / "SemanticGrader.kt")
        self.assertIn("for (route in provider.routes)", grader)
        self.assertIn("if (route == GradingRoute.FREE) freeFailure = failure", grader)
        provider = code(PROVIDER / "GradingProvider.kt")
        self.assertIn("GradingRoute.ORDER.filter { it in enabledRoutes }", provider)

    def test_the_paid_route_keeps_the_deadline_and_the_single_retry(self):
        grader = code(PROVIDER / "SemanticGrader.kt")
        self.assertIn("const val DEADLINE_MS: Int = 20_000", grader)
        self.assertIn("const val ATTEMPTS: Int = 2", grader)
        self.assertIn("provider.request(sessionId, system, user, DEADLINE_MS, route)", grader)
        paid = code(PROVIDER / "PaidRoute.kt")
        self.assertIn("const val MAX_TOKENS: Int = FreeRoute.MAX_TOKENS", paid)
        self.assertIn("const val TEMPERATURE: Int = FreeRoute.TEMPERATURE", paid)


class FreeRouteGuard(unittest.TestCase):
    def test_the_provider_name_comes_from_the_listing_not_a_second_constant(self):
        text = code(PROVIDER / "FreeRoute.kt")
        self.assertNotIn('"Liquid"', text, "the provider name is read from the endpoints listing")
        self.assertIn('child("provider_name")', text)
        self.assertIn("provider !in accepted", text)

    def test_the_regression_test_reads_the_recorded_evidence(self):
        text = code(PROVIDER_TEST / "FreeRouteRegressionTest.kt")
        self.assertIn("ankivoice.av043.av017Transcript", text)
        self.assertIn("ankivoice.av043.av006GradeB", text)
        build = (ROOT / "android" / "provider" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("docs/testing/av017/evidence/ai-20260916/transcript.jsonl", build)
        self.assertIn("docs/testing/av006/evidence/openrouter-grade-b.json", build)


class PaidPin(unittest.TestCase):
    def setUp(self):
        text = (PROVIDER / "PaidRoute.kt").read_text(encoding="utf-8")
        block = re.search(r"val PINNED: PaidRoutePin = PaidRoutePin\.of\((.*?)\n    \)", text, re.S)
        self.assertIsNotNone(block, "PaidRoute.PINNED is declared with PaidRoutePin.of(...)")
        self.pin = dict(re.findall(r'(\w+) = "([^"]*)"', block.group(1)))
        self.text = text

    def test_the_pin_names_one_paid_model_and_one_endpoint_tag(self):
        self.assertTrue(self.pin["model"] and not self.pin["model"].endswith(":free"))
        self.assertTrue(self.pin["provider"])
        for name in ("promptUsdPerToken", "completionUsdPerToken"):
            self.assertGreaterEqual(Decimal(self.pin[name]), 0, name)

    def test_the_ceiling_is_an_upper_bound_at_the_request_caps(self):
        self.assertIn("const val PROMPT_TOKEN_CAP: Int = 4_096", self.text)
        self.assertIn("promptUsdPerToken * PaidRoute.PROMPT_TOKEN_CAP.toBigDecimal()", self.text)
        self.assertIn("completionUsdPerToken * PaidRoute.MAX_TOKENS.toBigDecimal()", self.text)

    def test_the_results_page_records_the_same_pin(self):
        results = (DOCS / "testing" / "av043" / "results.md").read_text(encoding="utf-8")
        self.assertIn(self.pin["model"], results)
        self.assertIn(self.pin["provider"], results)


class Budget(unittest.TestCase):
    def test_the_default_cap_is_one_dollar_and_zero_disables(self):
        ledger = code(PROVIDER / "QuotaLedger.kt")
        self.assertIn('val DEFAULT_DAILY_CAP_USD: BigDecimal = BigDecimal("1.00")', ledger)
        self.assertIn("BUDGET_EXHAUSTED(", ledger)
        self.assertIn("val enabled: Boolean get() = capUsd.signum() > 0", ledger)
        self.assertIn("fun reservePaid(", ledger)
        self.assertIn("fun charge(", ledger)

    def test_a_paid_request_is_reserved_before_it_leaves_the_device(self):
        provider = code(PROVIDER / "GradingProvider.kt")
        self.assertLess(provider.index("ledger.reservePaid("), provider.index("transport.post("))
        # A reply that reports no cost is charged the ceiling, never waved through.
        self.assertIn("check.reportedCostUsd ?: paidPin.ceilingUsd", provider)
        # One key serves both routes, so a rejected key turns both off.
        self.assertIn("for (each in routes) blocked[each] = GradingUnavailable.KEY_REJECTED", provider)

    def test_the_settings_store_and_screen_carry_the_cap(self):
        self.assertIn('"daily_cap_usd"', (APP / "kotlin" / "org" / "ankivoice" / "app" / "ShellApplication.kt").read_text(encoding="utf-8"))
        screens = (APP / "kotlin" / "org" / "ankivoice" / "app" / "ProviderScreens.kt").read_text(encoding="utf-8")
        self.assertIn("DISCLOSURE_COST", screens)
        self.assertIn("never rates a card", screens)
        self.assertIn("Daily paid budget (USD)", screens)

    def test_the_ledger_stays_excluded_from_backup_and_transfer(self):
        rules = (APP / "res" / "xml" / "data_extraction_rules.xml").read_text(encoding="utf-8")
        self.assertEqual(2, rules.count("av020-quota-ledger.jsonl"))


class NothingReachesAWriter(unittest.TestCase):
    FORBIDDEN = ("GuardedReviewWriter", "ReviewTransport", "JournaledReviewWriter", "ReviewWriter", "ReviewIntent", "submitReview")

    def test_no_provider_source_names_a_writer(self):
        for path in sorted(PROVIDER.glob("*.kt")):
            text = code(path)
            for name in self.FORBIDDEN:
                self.assertNotIn(name, text, f"{path.name} names {name}")


class Evaluation(unittest.TestCase):
    def test_the_spike_is_tuning_only_and_paid_only(self):
        harness = code(PROVIDER_TEST / "GradingEvaluationTest.kt")
        self.assertIn("check(split == CorpusAnswer.TUNING)", harness)
        self.assertIn("enabledRoutes = if (spike != null) listOf(GradingRoute.PAID) else GradingRoute.ORDER", harness)

    def test_the_frozen_configuration_covers_the_paid_route(self):
        configuration = (ROOT / "tools" / "av017-qa" / "configuration.py").read_text(encoding="utf-8")
        self.assertIn("PaidRoute.kt", configuration)
        self.assertIn("GradingRoute.kt", configuration)
        self.assertIn('"paid_route": run.get("paid_route")', configuration)


class Documents(unittest.TestCase):
    def test_the_decision_record_carries_a_dated_addendum_and_keeps_its_evidence(self):
        decision = (DOCS / "decisions" / "0006-speech-and-grading-providers.md").read_text(encoding="utf-8")
        self.assertIn("## Addendum", decision)
        self.assertIn("AV-043", decision)
        self.assertIn("September 16, 2026", decision)
        self.assertIn("free models only", decision, "the accepted evidence is not rewritten")

    def test_the_readme_and_runbooks_describe_the_shipped_route(self):
        readme = (ROOT / "android" / "README.md").read_text(encoding="utf-8")
        self.assertIn("## Paid grading fallback", readme)
        runbook = (DOCS / "testing" / "av017" / "runbook.md").read_text(encoding="utf-8")
        self.assertIn("av017.dailyCapUsd", runbook)
        self.assertNotIn("never enable a paid fallback", runbook)
        self.assertTrue((DOCS / "testing" / "av043" / "runbook.md").is_file())

    def test_ci_runs_the_evidence_guard(self):
        workflow = (ROOT / ".github" / "workflows" / "fixtures.yml").read_text(encoding="utf-8")
        self.assertIn("tools/av043-qa/validate.py", workflow)


if __name__ == "__main__":
    unittest.main()
