from __future__ import annotations

import argparse
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Shard a JSONL translation queue into multiple batch files.")
    parser.add_argument("--input-jsonl", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--shard-size", type=int, default=250)
    parser.add_argument("--prefix", default="batch")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    with args.input_jsonl.open("r", encoding="utf-8") as handle:
        lines = handle.readlines()

    shard_count = 0
    for start in range(0, len(lines), args.shard_size):
        shard_lines = lines[start : start + args.shard_size]
        output_path = args.output_dir / f"{args.prefix}-{shard_count:04d}.jsonl"
        with output_path.open("w", encoding="utf-8") as handle:
            handle.writelines(shard_lines)
        shard_count += 1

    print({"input_lines": len(lines), "shard_size": args.shard_size, "shard_count": shard_count, "output_dir": str(args.output_dir)})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
