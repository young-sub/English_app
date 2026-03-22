from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sqlite3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / "tools" / "dictionary_etl" / "raw"
DEFAULT_CACHE_DB = ROOT / "tools" / "dictionary_etl" / "cache" / "ko_regen" / "translation_cache.sqlite3"
DEFAULT_OUTPUT_DIR = ROOT / "tools" / "dictionary_etl" / "cache" / "ko_regen" / "engine_split"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Split translation workload by popularity between Codex and auto translation.")
    parser.add_argument("--raw-dir", type=Path, default=RAW_DIR)
    parser.add_argument("--cache-db", type=Path, default=DEFAULT_CACHE_DB)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--codex-ratio", type=float, default=0.5)
    parser.add_argument("--include-translated", action="store_true")
    return parser.parse_args()


def ensure_cache_schema(connection: sqlite3.Connection) -> None:
    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS translations (
            field TEXT NOT NULL,
            source_text TEXT NOT NULL,
            source_hash TEXT NOT NULL,
            translated_text TEXT NOT NULL,
            PRIMARY KEY(field, source_hash)
        )
        """
    )


def text_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def build_rank_index(raw_dir: Path) -> dict[tuple[str, str], dict[str, int | str]]:
    ranking: dict[tuple[str, str], dict[str, int | str]] = {}
    for csv_path in sorted(raw_dir.glob("*.csv")):
        with csv_path.open("r", encoding="utf-8", newline="") as handle:
            for row in csv.DictReader(handle):
                freq = int((row.get("frequencyRank") or "0").strip() or "0")
                for field_name, english_field in (("definition", "definitionEn"), ("example", "exampleEn")):
                    text = (row.get(english_field) or "").strip()
                    if not text:
                        continue
                    key = (field_name, text_hash(text))
                    bucket = ranking.setdefault(
                        key,
                        {
                            "field": field_name,
                            "source_text": text,
                            "source_hash": text_hash(text),
                            "best_frequency_rank": freq,
                            "occurrences": 0,
                        },
                    )
                    bucket["occurrences"] = int(bucket["occurrences"]) + 1
                    if freq > 0 and (int(bucket["best_frequency_rank"]) == 0 or freq < int(bucket["best_frequency_rank"])):
                        bucket["best_frequency_rank"] = freq
    return ranking


def load_translation_state(cache_db: Path) -> dict[tuple[str, str], bool]:
    with sqlite3.connect(cache_db) as connection:
        ensure_cache_schema(connection)
        rows = connection.execute("SELECT field, source_hash, translated_text FROM translations").fetchall()
    return {(field, source_hash): bool(translated_text) for field, source_hash, translated_text in rows}


def write_jsonl(path: Path, rows: list[dict[str, int | str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def main() -> int:
    args = parse_args()
    rank_index = build_rank_index(args.raw_dir)
    translated_state = load_translation_state(args.cache_db)

    pending_rows: list[dict[str, int | str]] = []
    for key, row in rank_index.items():
        already_done = translated_state.get(key, False)
        if already_done and not args.include_translated:
            continue
        pending_rows.append(row)

    pending_rows.sort(
        key=lambda row: (
            int(row["best_frequency_rank"]) if int(row["best_frequency_rank"]) > 0 else 10**9,
            -int(row["occurrences"]),
            str(row["field"]),
            str(row["source_hash"]),
        )
    )

    codex_count = int(len(pending_rows) * args.codex_ratio)
    codex_rows = pending_rows[:codex_count]
    auto_rows = pending_rows[codex_count:]

    args.output_dir.mkdir(parents=True, exist_ok=True)
    write_jsonl(args.output_dir / "codex.jsonl", codex_rows)
    write_jsonl(args.output_dir / "auto.jsonl", auto_rows)

    summary = {
        "pending_total": len(pending_rows),
        "codex_count": len(codex_rows),
        "auto_count": len(auto_rows),
        "codex_first_rank": codex_rows[0]["best_frequency_rank"] if codex_rows else None,
        "codex_last_rank": codex_rows[-1]["best_frequency_rank"] if codex_rows else None,
        "auto_first_rank": auto_rows[0]["best_frequency_rank"] if auto_rows else None,
        "auto_last_rank": auto_rows[-1]["best_frequency_rank"] if auto_rows else None,
        "output_dir": str(args.output_dir),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
