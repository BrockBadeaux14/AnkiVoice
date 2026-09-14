#!/usr/bin/env python3
"""Validate historical AV-006 evidence and reproduce its tables; no network calls."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import statistics
import sys
import wave

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.av006_providers import load_corpus, CANDIDATES

EVIDENCE = ROOT / "docs/testing/av006/evidence"


def read(name):
    return json.loads((EVIDENCE / name).read_text())


def normalized(text):
    return re.findall(r"[a-z0-9]+", text.lower())


def latency(values):
    return {"n": len(values), "median_ms": statistics.median(values) if values else None,
            "max_ms": max(values) if values else None}


def derive():
    corpus = load_corpus()
    cases = {c["id"]: c for c in corpus["cases"]}
    corpus_hash = hashlib.sha256((ROOT / "fixtures/providers/av006-corpus.json").read_bytes()).hexdigest()
    audio = read("audio-manifest.json")
    assert len(audio) == 16
    for item in audio:
        path = EVIDENCE / "audio" / item["file"]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == item["sha256"]
        with wave.open(str(path)) as wav:
            assert (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) == (24000, 1, 2)
            assert wav.getnframes() / wav.getframerate() == item["seconds"]
            assert 0 < item["seconds"] <= 30
            assert any(wav.readframes(wav.getnframes())), "Silent audio"
    tts = read("native-tts.json")
    prompts = [x for x in tts["attempts"] if x["kind"] == "prompt"]
    assert len(prompts) == 4 and all(x["status"] == "playback_completed" for x in prompts)
    assert read("tts-listening.json")["result"] == "All four are intelligible"
    native_names = ["native-stt.json", "native-stt-online-permitted.json", "native-stt-online-remainder.json"]
    native = [row for name in native_names for row in read(name)["attempts"]]
    native_case_counts = Counter(row["id"] for row in native)
    assert max(native_case_counts.values()) <= 2 and len(native) <= 24
    selected_native = [row for name in native_names[1:] for row in read(name)["attempts"]]
    assert Counter(row["id"] for row in selected_native) == Counter(cases.keys())
    for row in native:
        assert row["expected_text"] == cases[row["id"]]["answer"]
    native_by_case = {row["id"]: row for row in selected_native}
    successes = [row for row in selected_native if row["status"] == "success"]
    summary = {
        "tts": {"prompts": 4, "playback_completed": 4, "human_intelligible": 4,
                "synthesis": latency([x["synthesis_ms"] for x in prompts]),
                "playback": latency([x["playback_ms"] for x in prompts])},
        "native_stt": {"total_attempts_including_offline_preflight": len(native),
                       "online_permitted_attempts": len(selected_native),
                       "transcripts_returned": len(successes),
                       "normalized_exact_matches": sum(normalized(x["transcript"]) == normalized(x["expected_text"]) for x in successes),
                       "errors": dict(Counter(str(x["error_code"]) for x in selected_native if x["status"] == "error")),
                       "finalization_after_audio_eof": latency([x["finalization_after_eof_ms"] for x in successes]),
                       "request_to_final": latency([x["total_ms"] for x in successes])},
        "cloud": {},
    }
    names = ["openrouter-grade-a.json", "openrouter-grade-a-pass2.json", "openrouter-grade-b.json",
             "openrouter-grade-b-pass2.json", "openrouter-stt.json"]
    counts = Counter()
    total_requests = 0
    for name in names:
        run = read(name)
        assert "finished_epoch" in run, f"Unfinished: {name}"
        assert run["corpus_sha256"] == corpus_hash
        assert run["model"] == CANDIDATES[run["kind"]][0]
        assert run["key_before"]["usage"] == run["key_after"]["usage"] == 0
        rows = run["attempts"]
        for row in rows:
            assert row["expected_text"] == cases[row["case_id"]]["answer"]
            assert row["expected_label"] == cases[row["case_id"]]["expected_label"]
            counts[run["kind"], row["case_id"]] += 1
            if "cost" in row.get("usage", {}):
                assert row["usage"]["cost"] == 0
            if row.get("grade"):
                assert row["matches_expected"] == (row["grade"]["label"] == row["expected_label"])
        total_requests += len(rows)
        valid = [r for r in rows if r["status"] == "success"]
        summary["cloud"][name] = {
            "attempts": len(rows), "counts": dict(Counter(r["status"] for r in rows)),
            "matching_labels": sum(r.get("matches_expected", False) for r in rows),
            "valid_response_latency": latency([r["request_to_final_ms"] for r in valid]),
            "prompt_tokens": sum(r.get("usage", {}).get("prompt_tokens", 0) for r in rows),
            "completion_tokens": sum(r.get("usage", {}).get("completion_tokens", 0) for r in rows),
        }
    assert max(counts.values()) <= 2
    assert sum(v for (kind, _), v in counts.items() if kind != "stt") <= 48
    assert len(native) + sum(v for (kind, _), v in counts.items() if kind == "stt") <= 48
    # These are HTTP-completion successes, not usable transcripts; inspect the retained text.
    cloud_stt = read("openrouter-stt.json")["attempts"]
    assert "provide the audio" in cloud_stt[0]["transcript"].lower()
    assert "provide the audio" in cloud_stt[1]["transcript"].lower()
    summary["cloud"]["openrouter-stt.json"]["usable_transcripts"] = 0
    summary["cloud"]["openrouter-stt.json"]["valid_transcript_latency"] = latency([])
    summary["cloud"]["openrouter-stt.json"]["failure_assessment"] = "2 non-transcriptions + 1 provider error; 9 not attempted"
    answer_seconds = sum(x["seconds"] for x in audio if x["file"].startswith("answer-"))
    prompt_seconds = sum(x["seconds"] for x in audio if x["file"].startswith("prompt-"))
    last = summary["cloud"]["openrouter-grade-b-pass2.json"]
    summary["usage"] = {
        "cloud_requests": total_requests, "grading_requests": total_requests - len(cloud_stt),
        "cloud_stt_requests": len(cloud_stt), "total_stt_attempts": len(native) + len(cloud_stt),
        "incremental_cost_usd": 0, "answer_audio_seconds": answer_seconds,
        "30_turn_estimate": {"assumption": "same prompt/answer mix as this small synthetic corpus, one grade per turn",
                             "tts_seconds": prompt_seconds / 4 * 30, "stt_seconds": answer_seconds / 12 * 30,
                             "grading_requests": 30, "grading_prompt_tokens": last["prompt_tokens"] / 12 * 30,
                             "grading_completion_tokens": last["completion_tokens"] / 12 * 30,
                             "cost_usd_at_selected_zero_prices": 0},
    }
    first = {r["case_id"]: r for r in read("openrouter-grade-b.json")["attempts"]}
    second = {r["case_id"]: r for r in read("openrouter-grade-b-pass2.json")["attempts"]}
    table = ["# AV-006 per-case results", "", "Derived from retained JSON; see the decision report for protocol and limitations.", "",
             "| Case | Expected answer | Label | Native STT (online permitted) | LFM pass 1 | LFM pass 2 |",
             "| --- | --- | --- | --- | --- | --- |"]
    for case in corpus["cases"]:
        cid = case["id"]; row = native_by_case[cid]
        transcript = row["transcript"] or f"ERROR_NO_MATCH ({row['error_code']})"
        grades = [r.get("grade", {}).get("label", r["status"]) for r in (first[cid], second[cid])]
        table.append(f"| {cid} | {case['answer']} | {case['expected_label']} | {transcript} | {grades[0]} | {grades[1]} |")
    return summary, "\n".join(table) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Regenerate summary and table after validation")
    args = parser.parse_args()
    summary, table = derive()
    encoded = json.dumps(summary, indent=2) + "\n"
    if args.write:
        (EVIDENCE / "summary.json").write_text(encoded)
        (EVIDENCE.parent / "results.md").write_text(table)
    else:
        assert (EVIDENCE / "summary.json").read_text() == encoded, "Summary drift"
        assert (EVIDENCE.parent / "results.md").read_text() == table, "Results table drift"
    print("AV006 evidence validated: corpus, WAV hashes, attempt bounds, costs, outcomes and derived tables.")
