"""Small host helper for the AV004 synthetic emulator experiment (stdlib only)."""
import argparse
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ADB = Path(os.environ.get("ANDROID_SDK_ROOT", Path.home() / "Library/Android/sdk")) / "platform-tools/adb"
SERIAL = "emulator-5584"
OUT = ROOT / "build/av004/evidence"
PACKAGE = "org.ankivoice.av004"


def adb(*args, check=True):
    return subprocess.run([str(ADB), "-s", SERIAL, *map(str, args)], check=check, capture_output=True).stdout


def guard():
    if adb("emu", "avd", "name").decode().splitlines()[0] != "AnkiVoice_AV004":
        raise RuntimeError("Only the dedicated AnkiVoice_AV004 emulator is supported")


def ui(label="ui"):
    raw = adb("exec-out", "uiautomator", "dump", "/dev/tty").decode()
    xml = raw[raw.index("<?xml"):raw.index("</hierarchy>") + len("</hierarchy>")]
    (OUT / f"{label}.xml").write_text(xml)
    return ET.fromstring(xml)


def tap(target):
    tree = ui()
    nodes = [n for n in tree.iter("node") if target in
             (n.get("text"), n.get("content-desc"), n.get("resource-id"))]
    if len(nodes) != 1:
        raise RuntimeError(f"Expected one visible {target!r}; got {len(nodes)}")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", nodes[0].get("bounds")))
    adb("shell", "input", "tap", (x1 + x2) // 2, (y1 + y2) // 2)


def probe(label, profile="baseline", op="snapshot", fixture=None, ease=4, elapsed=12345):
    if (OUT / f"{label}.json").exists():
        raise FileExistsError(f"Evidence label already exists: {label}")
    manifest = json.loads((ROOT / f"build/av004/fixtures/{profile}/av002-manifest.json").read_text())
    args = ["shell", "am", "start", "-S", "-n", PACKAGE + "/.ProbeActivity", "--es", "op", op,
            "--el", "deck", str(manifest["deck"]["id"])]
    if fixture:
        card = next(c for c in manifest["cards"] if c["fixture_id"] == fixture)
        args += ["--el", "note", str(card["note_id"]), "--ei", "ord", "0",
                 "--ei", "ease", str(ease), "--el", "elapsed", str(elapsed),
                 "--es", "confirm", "AV004_SYNTHETIC_ONLY"]
    adb("shell", "run-as", PACKAGE, "rm", "-f", "files/result.json")
    adb(*args)
    for _ in range(100):
        raw = adb("exec-out", "run-as", PACKAGE, "cat", "files/result.json", check=False)
        try:
            result = json.loads(raw)
            (OUT / f"{label}.json").write_text(json.dumps(result, indent=2) + "\n")
            return result
        except (ValueError, UnicodeDecodeError):
            time.sleep(0.1)
    raise TimeoutError("Probe did not complete; inspect the permission dialog or crash log")


def database(label):
    """Stop the client, copy DB and WAL, and inspect offline; never write its DB."""
    adb("shell", "am", "force-stop", "com.ichi2.anki")
    folder = OUT / label
    folder.mkdir(exist_ok=False)
    adb("pull", "/sdcard/AnkiDroid/collection.anki2", folder / "collection.anki2")
    adb("pull", "/sdcard/AnkiDroid/collection.anki2-wal", folder / "collection.anki2-wal", check=False)
    with sqlite3.connect(f"file:{folder / 'collection.anki2'}?mode=ro", uri=True) as db:
        db.create_collation("unicase", lambda a, b: (a.casefold() > b.casefold()) - (a.casefold() < b.casefold()))
        db.row_factory = sqlite3.Row
        result = {"integrity": db.execute("pragma integrity_check").fetchone()[0]}
        for table in ("cards", "notes", "revlog"):
            result[table] = [dict(row) for row in db.execute(f"select * from {table} order by id")]
    (OUT / f"{label}.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def reset(profile):
    # This destructive reset is only for this task's named, synthetic-only AVD.
    state = database(f"before-reset-{time.time_ns()}")
    if any(" av002 " not in n["tags"] for n in state["notes"]):
        raise RuntimeError("Refusing to replace a non-AV002 collection")
    adb("shell", "am", "start", "-n", "com.ichi2.anki/.IntentHandler")
    tap("More options")
    tap("Import")
    tap("Collection package (.colpkg)")
    tap(f"av004-{profile}.colpkg")
    tap("Replace")
    manifest = json.loads((ROOT / f"build/av004/fixtures/{profile}/av002-manifest.json").read_text())
    expected = dict(zip(("deck_new", "deck_learn", "deck_review"),
                        map(str, manifest["initial_counts_new_learning_review"])))
    for _ in range(5):
        tree = ui("reset-result")
        texts = {n.get("text") for n in tree.iter("node")}
        counts = {n.get("resource-id").split("/")[-1]: n.get("text") for n in tree.iter("node")}
        if manifest["deck"]["name"] in texts and all(counts.get(k) == v for k, v in expected.items()):
            print("Imported", profile, "with expected native counts")
            return
    raise TimeoutError("Import did not return to the expected deck/counts; inspect the emulator")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["ui", "tap", "probe", "database", "reset"])
    parser.add_argument("label")
    parser.add_argument("--profile", choices=["baseline", "limits"], default="baseline")
    parser.add_argument("--op", choices=["snapshot", "answer", "raw-answer", "permission"], default="snapshot")
    parser.add_argument("--fixture")
    parser.add_argument("--ease", type=int, default=4)
    parser.add_argument("--elapsed", type=int, default=12345)
    args = parser.parse_args()
    if args.command != "tap" and not re.fullmatch(r"[A-Za-z0-9_-]+", args.label):
        parser.error("Evidence labels must contain only letters, digits, underscores or hyphens")
    guard()
    OUT.mkdir(parents=True, exist_ok=True)
    if args.command == "ui":
        for node in ui(args.label).iter("node"):
            if node.get("text") or node.get("content-desc"):
                print(node.get("text"), node.get("content-desc"), node.get("bounds"))
    elif args.command == "tap":
        tap(args.label)
    elif args.command == "reset":
        reset(args.profile)
    elif args.command == "database":
        result = database(args.label)
        print({"integrity": result["integrity"], "cards": len(result["cards"]), "revlog": len(result["revlog"])})
    else:
        result = probe(args.label, args.profile, args.op, args.fixture, args.ease, args.elapsed)
        print(json.dumps({k: v for k, v in result.items() if k not in ("cards_before", "cards_after", "notes", "decks", "models")}, indent=2))
