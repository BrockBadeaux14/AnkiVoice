"""AV-017's graded configuration: what must be frozen before the held-out 40 is scored.

The configuration is the corpus plus the shipped code that decides a label. Its hash
covers the rule policy (#16), the label-to-rating mapping, the suggestion binding, the
pinned instruction, the reply validation, the route envelope and #18's deadline and
retry. If any of those change, the hash changes and the previous held-out run no longer
describes this build.

Nothing here grades anything or contacts a network.
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "fixtures" / "grading" / "av017-corpus.json"
EVIDENCE = ROOT / "docs" / "testing" / "av017" / "evidence"

# The shipped code this evaluation measures. It changes none of it.
GRADER_SOURCES = (
    "android/core/src/main/kotlin/org/ankivoice/core/grading/RuleGrader.kt",
    "android/core/src/main/kotlin/org/ankivoice/core/grading/Suggestions.kt",
    "android/core/src/main/kotlin/org/ankivoice/core/contracts/Grading.kt",
    "android/provider/src/main/kotlin/org/ankivoice/provider/GradingInstruction.kt",
    "android/provider/src/main/kotlin/org/ankivoice/provider/SemanticGrader.kt",
    "android/provider/src/main/kotlin/org/ankivoice/provider/FreeRoute.kt",
    # AV-043: the paid fallback and the route order are part of the graded configuration.
    "android/provider/src/main/kotlin/org/ankivoice/provider/PaidRoute.kt",
    "android/provider/src/main/kotlin/org/ankivoice/provider/GradingRoute.kt",
)

CONFIGURATION_FILE = "configuration.json"
HELD_OUT_LEDGER = "held-out-runs.jsonl"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def describe(run):
    """The graded configuration for `run`, with its hash.

    `run` is the harness's own output, so the pinned route values come from the build
    that produced the measurements rather than from this script's idea of them.
    """
    components = {
        "corpus_sha256": sha256(CORPUS),
        "sources": {name: sha256(ROOT / name) for name in GRADER_SOURCES},
        "pinned_route": run["pinned_route"],
        # AV-043: absent from runs recorded before the paid route existed.
        "routes": run.get("routes"),
        "paid_route": run.get("paid_route"),
        "permitted_ratings": [1, 2, 3, 4],
        "rubric_version": 1,
    }
    canonical = json.dumps(components, sort_keys=True, separators=(",", ":"))
    return {
        "components": components,
        "hash": hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
    }


def load_frozen(evidence):
    path = Path(evidence) / CONFIGURATION_FILE
    if not path.is_file():
        return None
    return json.loads(path.read_text())


def freeze(evidence, run):
    """Write the frozen configuration. Refuses to overwrite a different one."""
    path = Path(evidence) / CONFIGURATION_FILE
    current = describe(run)
    existing = load_frozen(evidence)
    if existing is not None and existing["hash"] != current["hash"]:
        raise SystemExit(
            f"{path} already freezes {existing['hash'][:12]}, and this build is "
            f"{current['hash'][:12]}.\nA frozen configuration is not edited in place: start a new "
            "evidence directory, record a new live pass and score the held-out 40 once against it."
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(current, indent=2) + "\n")
    return current
