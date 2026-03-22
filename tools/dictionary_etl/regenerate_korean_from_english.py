from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sqlite3
import sys
import time
import tempfile
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / "tools" / "dictionary_etl" / "raw"
CACHE_DIR = ROOT / "tools" / "dictionary_etl" / "cache" / "ko_regen"
DB_PATH = CACHE_DIR / "translation_cache.sqlite3"


def set_max_csv_field_size() -> None:
    limit = sys.maxsize
    while True:
        try:
            csv.field_size_limit(limit)
            return
        except OverflowError:
            limit //= 10


set_max_csv_field_size()


@dataclass(frozen=True)
class TextRecord:
    field: str
    text: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Regenerate definitionKo/exampleKo from English source text.")
    parser.add_argument(
        "mode",
        choices=["init-cache", "export-missing", "apply-cache", "stats"],
        help="Pipeline stage to run.",
    )
    parser.add_argument(
        "--raw-dir",
        type=Path,
        default=RAW_DIR,
    )
    parser.add_argument(
        "--cache-db",
        type=Path,
        default=DB_PATH,
    )
    parser.add_argument(
        "--output-jsonl",
        type=Path,
        default=CACHE_DIR / "missing_texts.jsonl",
    )
    parser.add_argument(
        "--input-jsonl",
        type=Path,
        default=CACHE_DIR / "translated_texts.jsonl",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="Optional limit when exporting missing texts.",
    )
    parser.add_argument(
        "--bucket-count",
        type=int,
        default=0,
        help="Optional number of hash buckets for evenly partitioned export.",
    )
    parser.add_argument(
        "--bucket-index",
        type=int,
        default=0,
        help="Optional bucket index to export when bucket-count is set.",
    )
    parser.add_argument(
        "--overwrite-existing-ko",
        action="store_true",
        help="Replace existing Korean data instead of filling only missing rows.",
    )
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
    connection.execute(
        "CREATE INDEX IF NOT EXISTS index_translations_field_hash ON translations(field, source_hash)"
    )


def text_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def iter_raw_rows(raw_dir: Path) -> Iterable[tuple[Path, list[dict[str, str]]]]:
    for csv_path in sorted(raw_dir.glob("*.csv")):
        with csv_path.open("r", encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle))
        yield csv_path, rows


def iter_unique_english_texts(raw_dir: Path) -> Iterable[TextRecord]:
    seen: set[tuple[str, str]] = set()
    for _path, rows in iter_raw_rows(raw_dir):
        for row in rows:
            definition_en = (row.get("definitionEn") or "").strip()
            example_en = (row.get("exampleEn") or "").strip()
            if definition_en:
                key = ("definition", definition_en)
                if key not in seen:
                    seen.add(key)
                    yield TextRecord(field="definition", text=definition_en)
            if example_en:
                key = ("example", example_en)
                if key not in seen:
                    seen.add(key)
                    yield TextRecord(field="example", text=example_en)


def init_cache(raw_dir: Path, cache_db: Path) -> None:
    cache_db.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(cache_db) as connection:
        ensure_cache_schema(connection)
        existing = {
            (field, source_hash)
            for field, source_hash in connection.execute("SELECT field, source_hash FROM translations")
        }
        inserts: list[tuple[str, str, str, str]] = []
        for record in iter_unique_english_texts(raw_dir):
            digest = text_hash(record.text)
            key = (record.field, digest)
            if key in existing:
                continue
            inserts.append((record.field, record.text, digest, ""))
            existing.add(key)
        connection.executemany(
            "INSERT OR IGNORE INTO translations(field, source_text, source_hash, translated_text) VALUES (?, ?, ?, ?)",
            inserts,
        )
        print(json.dumps({"inserted": len(inserts), "cache_db": str(cache_db)}, ensure_ascii=False))


def matches_bucket(source_hash: str, bucket_count: int, bucket_index: int) -> bool:
    if bucket_count <= 0:
        return True
    return int(source_hash, 16) % bucket_count == bucket_index


def export_missing(cache_db: Path, output_jsonl: Path, limit: int, bucket_count: int, bucket_index: int) -> None:
    output_jsonl.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(cache_db) as connection, output_jsonl.open("w", encoding="utf-8") as handle:
        ensure_cache_schema(connection)
        query = "SELECT field, source_text, source_hash FROM translations WHERE translated_text = '' ORDER BY field, source_hash"
        count = 0
        for field, source_text, source_hash in connection.execute(query):
            if not matches_bucket(source_hash, bucket_count, bucket_index):
                continue
            handle.write(
                json.dumps(
                    {
                        "field": field,
                        "source_text": source_text,
                        "source_hash": source_hash,
                    },
                    ensure_ascii=False,
                )
                + "\n"
            )
            count += 1
            if limit > 0 and count >= limit:
                break
    print(json.dumps({"exported": count, "output": str(output_jsonl)}, ensure_ascii=False))


def load_translations_jsonl(path: Path) -> list[tuple[str, str, str]]:
    rows: list[tuple[str, str, str]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            payload = json.loads(line)
            field = str(payload["field"])
            source_hash = str(payload["source_hash"])
            translated_text = str(payload["translated_text"]).strip()
            if not translated_text:
                continue
            rows.append((field, source_hash, translated_text))
    return rows


def merge_translations(cache_db: Path, input_jsonl: Path) -> int:
    updates = load_translations_jsonl(input_jsonl)
    with sqlite3.connect(cache_db) as connection:
        ensure_cache_schema(connection)
        connection.executemany(
            "UPDATE translations SET translated_text = ? WHERE field = ? AND source_hash = ?",
            [(translated_text, field, source_hash) for field, source_hash, translated_text in updates],
        )
    return len(updates)


def load_cache_map(cache_db: Path) -> dict[tuple[str, str], str]:
    with sqlite3.connect(cache_db) as connection:
        ensure_cache_schema(connection)
        rows = connection.execute(
            "SELECT field, source_hash, translated_text FROM translations WHERE translated_text != ''"
        ).fetchall()
    return {(field, source_hash): translated_text for field, source_hash, translated_text in rows}


def apply_cache(raw_dir: Path, cache_db: Path, overwrite_existing_ko: bool, input_jsonl: Path) -> None:
    if input_jsonl.exists():
        merged = merge_translations(cache_db, input_jsonl)
        print(json.dumps({"merged_from_jsonl": merged, "input": str(input_jsonl)}, ensure_ascii=False))

    cache_map = load_cache_map(cache_db)
    file_updates: dict[str, int] = {}

    for csv_path, rows in iter_raw_rows(raw_dir):
        if not rows:
            continue
        fieldnames = list(rows[0].keys())
        updated = 0
        for row in rows:
            definition_en = (row.get("definitionEn") or "").strip()
            example_en = (row.get("exampleEn") or "").strip()
            if definition_en and (overwrite_existing_ko or not (row.get("definitionKo") or "").strip()):
                translated = cache_map.get(("definition", text_hash(definition_en)))
                if translated:
                    row["definitionKo"] = translated
                    updated += 1
            if example_en and (overwrite_existing_ko or not (row.get("exampleKo") or "").strip()):
                translated = cache_map.get(("example", text_hash(example_en)))
                if translated:
                    row["exampleKo"] = translated
                    updated += 1
        temp_handle = tempfile.NamedTemporaryFile(
            "w",
            encoding="utf-8",
            newline="",
            delete=False,
            dir=csv_path.parent,
            suffix=".tmp",
        )
        temp_path = Path(temp_handle.name)
        with temp_handle as handle:
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)
        attempts = 0
        while True:
            try:
                temp_path.replace(csv_path)
                break
            except PermissionError:
                attempts += 1
                if attempts >= 5:
                    temp_path.unlink(missing_ok=True)
                    raise
                time.sleep(0.2 * attempts)
        file_updates[str(csv_path)] = updated

    print(json.dumps({"applied": file_updates}, ensure_ascii=False, indent=2))


def stats(cache_db: Path) -> None:
    with sqlite3.connect(cache_db) as connection:
        ensure_cache_schema(connection)
        total = connection.execute("SELECT COUNT(*) FROM translations").fetchone()[0]
        done = connection.execute("SELECT COUNT(*) FROM translations WHERE translated_text != ''").fetchone()[0]
        definitions = connection.execute(
            "SELECT COUNT(*) FROM translations WHERE field='definition'"
        ).fetchone()[0]
        examples = connection.execute(
            "SELECT COUNT(*) FROM translations WHERE field='example'"
        ).fetchone()[0]
    print(
        json.dumps(
            {
                "total": total,
                "translated": done,
                "remaining": total - done,
                "definition_rows": definitions,
                "example_rows": examples,
                "cache_db": str(cache_db),
            },
            ensure_ascii=False,
            indent=2,
        )
    )


def main() -> int:
    args = parse_args()
    if args.mode == "init-cache":
        init_cache(args.raw_dir, args.cache_db)
        return 0
    if args.mode == "export-missing":
        if args.bucket_count > 0 and (args.bucket_index < 0 or args.bucket_index >= args.bucket_count):
            raise ValueError("bucket-index must be between 0 and bucket-count - 1")
        export_missing(args.cache_db, args.output_jsonl, args.limit, args.bucket_count, args.bucket_index)
        return 0
    if args.mode == "apply-cache":
        apply_cache(args.raw_dir, args.cache_db, args.overwrite_existing_ko, args.input_jsonl)
        return 0
    if args.mode == "stats":
        stats(args.cache_db)
        return 0
    raise ValueError(f"Unsupported mode: {args.mode}")


if __name__ == "__main__":
    raise SystemExit(main())
