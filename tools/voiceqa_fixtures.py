"""AV-002 disposable fixtures. Never opens a user's Anki profile automatically."""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import sys
import time
from datetime import datetime, timezone
from importlib.metadata import version
from pathlib import Path

from anki.collection import Collection
from anki._backend import RustBackend
from anki.config import Config
from anki.consts import (
    CARD_TYPE_LRN, CARD_TYPE_RELEARNING, CARD_TYPE_REV,
    QUEUE_TYPE_LRN, QUEUE_TYPE_REV,
)
from anki.decks import UpdateDeckConfigs
from anki.errors import AnkiException
from anki.media import media_paths_from_col_path

ANKI_VERSION = "25.9.2"
FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "voiceqa"
MARKER = "av002-manifest.json"
CONFIG_KEY = "ankivoice_av002"
DAY = 86400


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def read_sources() -> tuple[dict, dict]:
    return tuple(json.loads((FIXTURES / name).read_text()) for name in
                 ("note-type.json", "scenarios.json"))


def check_version() -> None:
    if version("anki") != ANKI_VERSION:
        raise ValueError(f"Use anki=={ANKI_VERSION}; install requirements-fixtures.txt.")


def create_model(col: Collection, spec: dict) -> dict:
    model = col.models.new(spec["name"])
    for field in spec["fields"]:
        col.models.add_field(model, col.models.new_field(field["name"]))
    for source in spec["templates"]:
        template = col.models.new_template(source["name"])
        template.update(qfmt=source["front"], afmt=source["back"])
        col.models.add_template(model, template)
    model["css"] = spec["css"]
    col.models.add(model)
    return model


def seed_state(col: Collection, card, state: str, now: int, index: int) -> None:
    """Seed synthetic history/dates in a fresh collection, not product review code.

    Revlog intervals are days when positive and seconds when negative. History
    has three graduated reviews (1 -> 7 -> 30 days), then a lapse if relearning.
    """
    history = []  # (seconds ago, rating, interval, previous interval, review kind)
    if state == "learning":
        card.type, card.queue = CARD_TYPE_LRN, QUEUE_TYPE_LRN
        card.due, card.left, card.reps = now - 60, 2002, 1
        history = [(120, 1, -60, 0, 0)]
    elif state in {"mature", "future", "relearning"}:
        card.type, card.queue = CARD_TYPE_REV, QUEUE_TYPE_REV
        card.ivl, card.factor, card.reps = 30, 2500, 3
        offset = 7 if state == "future" else 0
        card.due = col.sched.today + offset
        history = [((38-offset)*DAY, 3, 1, 0, 0),
                   ((37-offset)*DAY, 3, 7, 1, 1),
                   ((30-offset)*DAY, 3, 30, 7, 1)]
        if state == "relearning":
            card.type, card.queue = CARD_TYPE_RELEARNING, QUEUE_TYPE_LRN
            card.due, card.left, card.ivl = now - 60, 1001, 1
            card.reps, card.lapses, card.factor = 4, 1, 2300
            history.append((660, 1, -600, 30, 1))
    elif state not in {"new", "suspended", "buried-manual", "buried-sibling"}:
        raise ValueError(f"Unknown fixture state: {state}")
    col.update_card(card)
    # Direct SQL is confined to synthetic history in this newly created database.
    for ago, rating, interval, previous, kind in history:
        col.db.execute(
            "insert into revlog values (?, ?, -1, ?, ?, ?, ?, 2500, ?)",
            (now - ago) * 1000 + index, card.id, rating, interval, previous,
            2300 if state == "relearning" and ago == 660 else 2500, kind,
        )
    if state == "suspended":
        col.sched.suspend_cards([card.id])
    elif state.startswith("buried-"):
        col.sched.bury_cards([card.id], manual=state == "buried-manual")


def snapshot(col: Collection) -> dict:
    """Capture actual persisted content, state, and history for inspection."""
    cards = []
    for cid in sorted(col.find_cards("tag:av002")):
        card = col.get_card(cid)
        note = card.note()
        cards.append({
            "fixture_id": next(t.removeprefix("fixture::") for t in note.tags
                               if t.startswith("fixture::")),
            "card_id": cid, "note_id": note.id,
            "note_type": note.note_type()["name"], "fields": dict(note.items()),
            "tags": sorted(note.tags), "type": card.type, "queue": card.queue,
            "due": card.due, "interval_days": card.ivl, "remaining_steps": card.left,
            "reps": card.reps, "lapses": card.lapses, "ease_factor": card.factor,
            "review_history": col.db.all("select * from revlog where cid=? order by id", cid),
        })
    return {"cards": cards, "deck": col.decks.current(),
            "preset": col.decks.config_dict_for_deck_id(col.decks.get_current_id())}


def export_package(col: Collection, destination: Path) -> None:
    # Exclusive reservation prevents overwriting an existing backup.
    with destination.open("xb"):
        pass
    try:
        col.export_collection_package(str(destination.resolve()), include_media=True, legacy=False)
    except BaseException:
        destination.unlink(missing_ok=True)
        raise


def build_profile(directory: Path, name: str, scenario: dict, spec: dict) -> dict:
    directory.mkdir(parents=True, exist_ok=False)
    now = int(time.time())
    col = Collection(str((directory / "collection.anki2").resolve()))
    try:
        # Give synthetic history a 90-day collection age; due dates use Anki's day.
        col.crt = now - 90 * DAY
        col.set_v3_scheduler(True)
        prefs = col.get_preferences()
        prefs.scheduling.rollover = 4
        prefs.scheduling.learn_ahead_secs = 0
        col.set_preferences(prefs)
        col.set_config_bool(Config.Bool.LOAD_BALANCER_ENABLED, False)
        did = col.decks.add_normal_deck_with_name(scenario["deck"]).id
        config = col.decks.get_deck_configs_for_update(did).defaults
        config.id = 0
        config.name = f"AV002 {name}"
        config.config.new_per_day = scenario["new_per_day"]
        config.config.reviews_per_day = scenario["reviews_per_day"]
        config.config.learn_steps[:] = [1, 10]
        config.config.relearn_steps[:] = [10]
        col.decks.update_deck_configs(UpdateDeckConfigs(
            target_deck_id=did, configs=[config], fsrs=False,
            new_cards_ignore_review_limit=True,
        ))
        col.decks.set_current(did)
        model = create_model(col, spec)
        examples = {example["id"]: example for example in spec["examples"]}
        for index, fixture in enumerate(scenario["cards"], 1):
            fields = dict(examples[fixture["example"]]["fields"]) if "example" in fixture else {}
            fields.update(fixture.get("fields", {}))
            note_model = col.models.by_name(fixture["note_type"]) if "note_type" in fixture else model
            note = col.new_note(note_model)
            for key, value in fields.items():
                note[key] = value
            note.tags = ["av002", f"profile::{name}", f"fixture::{fixture['id']}",
                         f"state::{fixture['state']}"]
            if "expected_rejection" in fixture:
                note.tags.append(f"reject::{fixture['expected_rejection']}")
            col.add_note(note, did)
            cards = note.cards()
            if len(cards) != 1:
                raise ValueError(f"Fixture {fixture['id']} must produce exactly one card")
            seed_state(col, cards[0], fixture["state"], now, index)
        # Initialize today's queue so buried cards remain buried on same-day reopen.
        counts = col.sched.counts()
        metadata = {
            "schema_version": 1, "issue": 2, "profile": name,
            "anki_version": ANKI_VERSION, "python_version": platform.python_version(),
            "built_at": datetime.fromtimestamp(now, timezone.utc).isoformat(),
            "local_timezone": str(datetime.now().astimezone().tzinfo),
            "scheduler_today": col.sched.today, "next_day_at": col.sched.day_cutoff,
            "scheduler": "v3 / SM-2; FSRS disabled", "synthetic_history": True,
            "source_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                              for p in sorted(FIXTURES.glob("*.json"))},
            "expected_rejections": {f["id"]: f["expected_rejection"]
                                    for f in scenario["cards"] if "expected_rejection" in f},
        }
        col.set_config(CONFIG_KEY, metadata)
        manifest = {**metadata, "initial_counts_new_learning_review": counts, **snapshot(col)}
        write_json(directory / MARKER, manifest)
        export_package(col, directory / "collection.colpkg")
        return manifest
    finally:
        col.close()


def build(output: Path) -> None:
    check_version()
    spec, scenarios = read_sources()
    output.mkdir(parents=True, exist_ok=False)
    for name, scenario in scenarios["profiles"].items():
        manifest = build_profile(output / name, name, scenario, spec)
        print(f"{name}: {len(manifest['cards'])} cards; new/learning/review "
              f"{manifest['initial_counts_new_learning_review']}")


def backup(profile: Path, output: Path) -> None:
    check_version()
    if output.exists() or output.is_symlink():
        raise FileExistsError(f"Refusing to overwrite {output}")
    if not (profile / MARKER).is_file() or not (profile / "collection.anki2").is_file():
        raise ValueError("Expected a generated AV002 profile directory; close its client first.")
    col = Collection(str((profile / "collection.anki2").resolve()))
    try:
        if not col.get_config(CONFIG_KEY):
            raise ValueError("This collection is not an AV002 fixture profile.")
        export_package(col, output)
    finally:
        col.close()


def restore(package: Path, output: Path) -> None:
    check_version()
    if not package.is_file():
        raise ValueError(f"Package does not exist: {package}")
    output.mkdir(parents=True, exist_ok=False)
    col_path = str((output / "collection.anki2").resolve())
    media, media_db = media_paths_from_col_path(col_path)
    RustBackend().import_collection_package(
        col_path=col_path, backup_path=str(package.resolve()), media_folder=media, media_db=media_db,
    )
    col = Collection(col_path)
    try:
        metadata = col.get_config(CONFIG_KEY)
        if not metadata:
            raise ValueError("Package is not an AV002 fixture collection.")
        write_json(output / MARKER, {**metadata, "restored_from": str(package.resolve()),
                                    **snapshot(col)})
    finally:
        col.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("build", "backup", "restore"):
        command = commands.add_parser(name)
        command.add_argument("--output", type=Path, required=True,
                             help="New destination; existing paths are refused")
        if name == "backup":
            command.add_argument("--profile", type=Path, required=True)
        elif name == "restore":
            command.add_argument("--package", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "build":
            build(args.output)
        elif args.command == "backup":
            backup(args.profile, args.output)
        else:
            restore(args.package, args.output)
    except (OSError, ValueError, AnkiException) as error:
        parser.exit(1, f"error: {error}\n")
    print(f"Created {args.output.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
