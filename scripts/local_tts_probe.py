#!/usr/bin/env python3
"""Standalone launcher for local TTS playback probe instrumentation test.

This script runs an Android instrumentation test that directly uses
LocalModelTtsEngine (without app UI integration) and checks synthesis+playback.
"""

from __future__ import annotations

import argparse
import platform
import subprocess
import sys
from pathlib import Path


TEST_CLASS = "com.example.bookhelper.tts.LocalTtsStandaloneProbeInstrumentedTest"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run standalone local TTS probe on connected Android device")
    parser.add_argument(
        "--text",
        default="This is a standalone local TTS probe.",
        help="Sentence to synthesize and play",
    )
    parser.add_argument(
        "--speed",
        type=float,
        default=1.0,
        help="Speech speed (0.85 ~ 1.15 recommended)",
    )
    parser.add_argument(
        "--speaker-id",
        type=int,
        default=None,
        help="Optional speaker id for Kokoro model",
    )
    parser.add_argument(
        "--timeout-ms",
        type=int,
        default=120000,
        help="Timeout for playback detection in milliseconds",
    )
    parser.add_argument(
        "--skip-adb-check",
        action="store_true",
        help="Skip adb devices check",
    )
    return parser.parse_args()


def project_root() -> Path:
    return Path(__file__).resolve().parents[1]


def gradle_executable(root: Path) -> str:
    if platform.system().lower().startswith("win"):
        return str(root / "gradlew.bat")
    return str(root / "gradlew")


def gradle_command(root: Path, gradle_args: list[str]) -> list[str]:
    executable = gradle_executable(root)
    if platform.system().lower().startswith("win"):
        return ["cmd", "/c", executable, *gradle_args]
    return [executable, *gradle_args]


def run(command: list[str], cwd: Path) -> int:
    try:
        process = subprocess.run(command, cwd=cwd)
    except FileNotFoundError as error:
        print(f"[local_tts_probe] command not found: {error}", file=sys.stderr)
        return 127
    return int(process.returncode)


def main() -> int:
    args = parse_args()
    root = project_root()

    if not args.skip_adb_check:
        adb_code = run(["adb", "devices"], cwd=root)
        if adb_code != 0:
            print("[local_tts_probe] adb devices failed. Make sure adb is installed and device is connected.", file=sys.stderr)
            return adb_code

    speed = min(max(args.speed, 0.85), 1.15)
    timeout_ms = max(args.timeout_ms, 1000)

    gradle_args = [
        ":app:connectedDebugAndroidTest",
        f"-Pandroid.testInstrumentationRunnerArguments.class={TEST_CLASS}",
        f"-Pandroid.testInstrumentationRunnerArguments.localTtsText={args.text}",
        f"-Pandroid.testInstrumentationRunnerArguments.localTtsSpeed={speed}",
        f"-Pandroid.testInstrumentationRunnerArguments.localTtsTimeoutMs={timeout_ms}",
    ]
    if args.speaker_id is not None:
        gradle_args.append(f"-Pandroid.testInstrumentationRunnerArguments.localTtsSpeakerId={max(args.speaker_id, 0)}")
    command = gradle_command(root, gradle_args)

    print("[local_tts_probe] Running standalone local TTS probe test...")
    print("[local_tts_probe] Command:", " ".join(command))
    return run(command, cwd=root)


if __name__ == "__main__":
    raise SystemExit(main())
