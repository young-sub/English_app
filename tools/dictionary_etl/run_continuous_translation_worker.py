from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CACHE_DIR = ROOT / "tools" / "dictionary_etl" / "cache" / "ko_regen"
WORKER_LOG_DIR = CACHE_DIR / "worker_logs"
OPENCODE_CMD = Path.home() / "AppData" / "Roaming" / "npm" / "opencode.cmd"


@dataclass(frozen=True)
class Claim:
    mode: str
    slice_file: Path
    output_file: Path
    claim_file: Path
    count: int
    start: int
    end: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Continuously process dictionary translation slices.")
    parser.add_argument("--model", default="openai/gpt-5.4")
    parser.add_argument("--claim-timeout", type=int, default=120)
    parser.add_argument("--codex-timeout", type=int, default=900)
    parser.add_argument("--apply-timeout", type=int, default=900)
    parser.add_argument("--retry-count", type=int, default=2)
    parser.add_argument("--idle-sleep", type=float, default=3.0)
    return parser.parse_args()


def run_command(command: list[str], timeout_seconds: int, cwd: Path = ROOT) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=cwd,
        text=True,
        capture_output=True,
        timeout=timeout_seconds,
        check=False,
    )


def parse_json_output(stdout: str) -> dict[str, object]:
    text = stdout.strip()
    if not text:
        raise RuntimeError("Command produced empty JSON output")
    return json.loads(text)


def claim_next_slice(mode: str, timeout_seconds: int) -> Claim | None:
    result = run_command(
        [sys.executable, "tools/dictionary_etl/next_translation_task.py", "--mode", mode, "--claim"],
        timeout_seconds,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or f"Claim failed for {mode}")
    payload = parse_json_output(result.stdout)
    if payload.get("done"):
        return None
    return Claim(
        mode=str(payload["mode"]),
        slice_file=Path(str(payload["slice_file"])),
        output_file=Path(str(payload["output_file"])),
        claim_file=Path(str(payload["claim_file"])),
        count=int(payload["count"]),
        start=int(payload["start"]),
        end=int(payload["end"]),
    )


def load_jsonl(path: Path) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if not stripped:
                continue
            rows.append(json.loads(stripped))
    return rows


def validate_output_file(slice_file: Path, output_file: Path, expected_count: int) -> None:
    source_rows = load_jsonl(slice_file)
    translated_rows = load_jsonl(output_file)
    if len(source_rows) != expected_count:
        raise RuntimeError(f"Slice count mismatch: expected {expected_count}, found {len(source_rows)}")
    if len(translated_rows) != expected_count:
        raise RuntimeError(f"Output count mismatch: expected {expected_count}, found {len(translated_rows)}")

    for index, (source_row, translated_row) in enumerate(zip(source_rows, translated_rows, strict=True), start=1):
        keys = list(translated_row.keys())
        if set(keys) != {"field", "source_hash", "translated_text"}:
            raise RuntimeError(f"Unexpected keys on line {index}: {keys}")
        if str(translated_row["field"]) != str(source_row["field"]):
            raise RuntimeError(f"Field mismatch on line {index}")
        if str(translated_row["source_hash"]) != str(source_row["source_hash"]):
            raise RuntimeError(f"source_hash mismatch on line {index}")
        if not str(translated_row["translated_text"]).strip():
            raise RuntimeError(f"Empty translated_text on line {index}")


def build_codex_prompt(claim: Claim, repair_only: bool) -> str:
    repair_line = (
        "If the current output file is malformed or incomplete, rewrite only that file from the slice and fix every bad line."
        if repair_only
        else "Translate the entire slice from scratch."
    )
    return f"""In this repository, process exactly one claimed dictionary translation slice.

Work in `{ROOT}`.

You are assigned this slice:
- mode: `{claim.mode}`
- slice_file: `{claim.slice_file}`
- output_file: `{claim.output_file}`
- count: `{claim.count}`

Do this exactly:
1. Read `{claim.slice_file}`.
2. Write valid JSONL to `{claim.output_file}`.
3. {repair_line}
4. Preserve record order and handle all `{claim.count}` records with no omissions.
5. Use exactly these JSON keys in each line: `field`, `source_hash`, `translated_text`.
6. For `field=definition`, write natural Korean dictionary-style glosses.
7. For `field=example`, write natural Korean example sentences.
8. Escape JSON strings correctly.
9. Do not edit any file except `{claim.output_file}`.
10. Do not run apply-cache.
11. Print only `{claim.output_file}` and `{claim.count}`.
"""


def codex_translate_slice(claim: Claim, model: str, timeout_seconds: int, repair_only: bool) -> None:
    WORKER_LOG_DIR.mkdir(parents=True, exist_ok=True)
    log_stem = f"continuous-{claim.mode}-{claim.start:04d}-{claim.end:04d}"
    stdout_path = WORKER_LOG_DIR / f"{log_stem}.out.log"
    stderr_path = WORKER_LOG_DIR / f"{log_stem}.err.log"
    prompt = build_codex_prompt(claim, repair_only)
    command = [str(OPENCODE_CMD), "run", "--dir", str(ROOT), "--model", model, prompt]
    with stdout_path.open("w", encoding="utf-8") as stdout_handle, stderr_path.open("w", encoding="utf-8") as stderr_handle:
        result = subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            stdout=stdout_handle,
            stderr=stderr_handle,
            timeout=timeout_seconds,
            check=False,
        )
    if result.returncode != 0:
        raise RuntimeError(f"Codex translation failed for {claim.output_file}")


def auto_translate_slice(claim: Claim, timeout_seconds: int) -> None:
    result = run_command(
        [
            sys.executable,
            "tools/dictionary_etl/translate_missing_texts.py",
            "--backend",
            "deep-google",
            "--input-jsonl",
            str(claim.slice_file),
            "--output-jsonl",
            str(claim.output_file),
            "--max-items",
            "80",
            "--batch-size",
            "1",
            "--sleep-seconds",
            "0.02",
            "--retry-count",
            "2",
        ],
        timeout_seconds,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or f"Auto translation failed for {claim.output_file}")


def is_json_parse_error(stderr: str, stdout: str) -> bool:
    text = f"{stdout}\n{stderr}"
    return "JSONDecodeError" in text or "json.decoder.JSONDecodeError" in text


def apply_cache(output_file: Path, timeout_seconds: int) -> subprocess.CompletedProcess[str]:
    return run_command(
        [
            sys.executable,
            "tools/dictionary_etl/regenerate_korean_from_english.py",
            "apply-cache",
            "--input-jsonl",
            str(output_file),
            "--overwrite-existing-ko",
        ],
        timeout_seconds,
    )


def process_claim(claim: Claim, args: argparse.Namespace) -> None:
    attempts = 0
    while True:
        attempts += 1
        repair_only = attempts > 1
        if claim.output_file.exists() and repair_only:
            claim.output_file.unlink()

        try:
            codex_translate_slice(claim, args.model, args.codex_timeout, repair_only=repair_only)
            validate_output_file(claim.slice_file, claim.output_file, claim.count)
        except Exception:
            if attempts > args.retry_count:
                auto_translate_slice(claim, args.codex_timeout)
                validate_output_file(claim.slice_file, claim.output_file, claim.count)
                break
            continue

        result = apply_cache(claim.output_file, args.apply_timeout)
        if result.returncode == 0:
            break
        if is_json_parse_error(result.stderr, result.stdout):
            if attempts > args.retry_count:
                auto_translate_slice(claim, args.codex_timeout)
                validate_output_file(claim.slice_file, claim.output_file, claim.count)
                retry_result = apply_cache(claim.output_file, args.apply_timeout)
                if retry_result.returncode == 0:
                    break
                raise RuntimeError(retry_result.stderr.strip() or retry_result.stdout.strip() or "apply-cache retry failed")
            continue
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or f"apply-cache failed for {claim.output_file}")


def claim_next_available(args: argparse.Namespace) -> Claim | None:
    for mode in ("codex", "auto"):
        claim = claim_next_slice(mode, args.claim_timeout)
        if claim is not None:
            return claim
    return None


def main() -> int:
    args = parse_args()
    if not OPENCODE_CMD.exists():
        raise SystemExit(f"Missing opencode command: {OPENCODE_CMD}")

    while True:
        claim = claim_next_available(args)
        if claim is None:
            time.sleep(args.idle_sleep)
            claim = claim_next_available(args)
            if claim is None:
                return 0

        process_claim(claim, args)
        print(f"{claim.output_file} {claim.count}", flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
