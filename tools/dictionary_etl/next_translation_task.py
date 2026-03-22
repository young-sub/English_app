from __future__ import annotations

import argparse
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "tools" / "dictionary_etl" / "cache" / "ko_regen"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Pick the next unprocessed translation slice automatically.")
    parser.add_argument("--mode", choices=["codex", "auto"], required=True)
    parser.add_argument("--claim", action="store_true")
    parser.add_argument("--chunk-size", type=int, default=0)
    return parser.parse_args()


def count_lines(path: Path) -> int:
    if not path.exists():
        return 0
    with path.open("r", encoding="utf-8") as handle:
        return sum(1 for _ in handle)


def iter_jsonl(path: Path) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
    return rows


def translated_hashes(translated_dir: Path, stem: str) -> set[str]:
    hashes: set[str] = set()
    for path in sorted(translated_dir.glob(f"{stem}-*.jsonl")):
        for payload in iter_jsonl(path):
            source_hash = str(payload.get("source_hash", "")).strip()
            if source_hash:
                hashes.add(source_hash)
    return hashes


def claimed_hashes(claim_dir: Path) -> set[str]:
    hashes: set[str] = set()
    for path in sorted(claim_dir.glob("*.claim")):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        for source_hash in payload.get("source_hashes", []):
            value = str(source_hash).strip()
            if value:
                hashes.add(value)
    return hashes


def load_shard_records(input_path: Path) -> list[tuple[int, str, str]]:
    records: list[tuple[int, str, str]] = []
    with input_path.open("r", encoding="utf-8") as handle:
        for idx, line in enumerate(handle, start=1):
            payload = json.loads(line)
            source_hash = str(payload.get("source_hash", "")).strip()
            records.append((idx, source_hash, line))
    return records


def choose_slice(
    records: list[tuple[int, str, str]],
    translated: set[str],
    claimed: set[str],
    chunk_size: int,
) -> tuple[int, int, list[str], list[str]] | None:
    selected_lines: list[str] = []
    selected_hashes: list[str] = []
    start_line = 0
    end_line = 0

    for line_no, source_hash, line in records:
        if source_hash in translated or source_hash in claimed:
            continue
        if not start_line:
            start_line = line_no
        selected_lines.append(line)
        selected_hashes.append(source_hash)
        end_line = line_no
        if len(selected_lines) >= chunk_size:
            break

    if not selected_lines:
        return None
    return start_line, end_line, selected_lines, selected_hashes


def main() -> int:
    args = parse_args()
    chunk_size = args.chunk_size or (40 if args.mode == "codex" else 80)

    shard_dir = BASE / "engine_split" / f"{args.mode}_shards"
    translated_dir = BASE / "translated"
    work_dir = BASE / "work"
    claim_dir = BASE / "claims" / args.mode
    work_dir.mkdir(parents=True, exist_ok=True)
    claim_dir.mkdir(parents=True, exist_ok=True)

    for shard_path in sorted(shard_dir.glob(f"{args.mode}-*.jsonl")):
        stem = shard_path.stem
        total_lines = count_lines(shard_path)
        translated = translated_hashes(translated_dir, stem)
        if len(translated) >= total_lines:
            continue

        active_claimed = claimed_hashes(claim_dir)
        records = load_shard_records(shard_path)
        picked = choose_slice(records, translated, active_claimed, chunk_size)
        if not picked:
            continue

        start, end, selected_lines, selected_hashes = picked

        range_tag = f"{start:04d}-{end:04d}"
        output_file = translated_dir / f"{stem}-part-{range_tag}.jsonl"
        claim_file = claim_dir / f"{stem}-part-{range_tag}.claim"
        if output_file.exists():
            continue

        slice_file = work_dir / f"{stem}-slice-{range_tag}.jsonl"
        with slice_file.open("w", encoding="utf-8") as handle:
            handle.writelines(selected_lines)

        if args.claim:
            claim_payload = json.dumps(
                {
                    "mode": args.mode,
                    "source_shard": str(shard_path),
                    "slice_file": str(slice_file),
                    "output_file": str(output_file),
                    "start": start,
                    "end": end,
                    "source_hashes": selected_hashes,
                },
                ensure_ascii=False,
                indent=2,
            )
            try:
                with claim_file.open("x", encoding="utf-8") as handle:
                    handle.write(claim_payload)
            except FileExistsError:
                slice_file.unlink(missing_ok=True)
                continue

        print(
            json.dumps(
                {
                    "mode": args.mode,
                    "source_shard": str(shard_path),
                    "slice_file": str(slice_file),
                    "output_file": str(output_file),
                    "claim_file": str(claim_file) if args.claim else "",
                    "start": start,
                    "end": end,
                    "count": len(selected_lines),
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0

    print(json.dumps({"mode": args.mode, "done": True}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
