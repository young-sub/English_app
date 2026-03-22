from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
LOG_DIR = ROOT / "tools" / "dictionary_etl" / "cache" / "ko_regen" / "worker_logs"
OPENCODE_CMD = Path.home() / "AppData" / "Roaming" / "npm" / "opencode.cmd"


PROMPT = """In this repository, continue the English-to-Korean dictionary regeneration workflow.

Work in `C:\\Users\\YS\\Desktop\\python\\english_app`.

Follow these steps exactly:
1. Run `python \"tools/dictionary_etl/next_translation_task.py\" --mode codex --claim`.
2. Read the JSON output and get `slice_file`, `output_file`, and `count`.
3. Translate every record in `slice_file` into Korean and write valid JSONL to `output_file`.
   - Keep record order.
   - Keep keys exactly: `field`, `source_hash`, `translated_text`.
   - `field=definition`: Korean dictionary-style gloss.
   - `field=example`: Natural Korean example sentence.
4. Do NOT run apply-cache.
5. Do NOT edit any file other than the chosen `output_file`.
6. At the end, print only the processed `output_file` path and `count`.
"""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Launch background Codex translation workers.")
    parser.add_argument("--count", type=int, default=1)
    parser.add_argument("--model", default="openai/gpt-5.4")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    launches: list[dict[str, str | int]] = []

    for index in range(args.count):
        stdout_path = LOG_DIR / f"codex-worker-{index:02d}.out.log"
        stderr_path = LOG_DIR / f"codex-worker-{index:02d}.err.log"
        cmd = [
            str(OPENCODE_CMD),
            "run",
            "--dir",
            str(ROOT),
            "--model",
            args.model,
            PROMPT,
        ]
        process = subprocess.Popen(
            cmd,
            cwd=ROOT,
            stdout=stdout_path.open("w", encoding="utf-8"),
            stderr=stderr_path.open("w", encoding="utf-8"),
            text=True,
            shell=False,
        )
        launches.append(
            {
                "pid": process.pid,
                "stdout": str(stdout_path),
                "stderr": str(stderr_path),
            }
        )

    print(json.dumps({"launched": launches}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
