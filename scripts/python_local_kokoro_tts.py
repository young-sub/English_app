#!/usr/bin/env python3
"""Pure Python local Kokoro TTS test (independent from Android app flow)."""

from __future__ import annotations

import argparse
import platform
import tempfile
from pathlib import Path

import numpy as np
import sherpa_onnx
from scipy.io import wavfile


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run local Kokoro TTS directly from Python")
    parser.add_argument("--text", required=True, help="English sentence to synthesize")
    parser.add_argument(
        "--model-dir",
        default=str(Path("modules/feature/tts/models/kokoro-en-v0_19")),
        help="Directory containing model.onnx, voices.bin, tokens.txt, espeak-ng-data",
    )
    parser.add_argument("--speaker-id", type=int, default=0, help="Kokoro speaker id")
    parser.add_argument("--speed", type=float, default=1.0, help="Speech speed")
    parser.add_argument("--output", default="", help="Output WAV file path")
    parser.add_argument("--no-play", action="store_true", help="Do not play audio after synthesis")
    return parser.parse_args()


def resolve_model_paths(model_dir: Path) -> tuple[Path, Path, Path, Path]:
    model = model_dir / "model.onnx"
    voices = model_dir / "voices.bin"
    tokens = model_dir / "tokens.txt"
    data_dir = model_dir / "espeak-ng-data"
    missing = [p.name for p in [model, voices, tokens] if not p.exists()]
    if not data_dir.exists() or not data_dir.is_dir():
        missing.append("espeak-ng-data")
    if missing:
        raise FileNotFoundError(f"Missing required Kokoro files in {model_dir}: {', '.join(missing)}")
    return model, voices, tokens, data_dir


def build_tts(model: Path, voices: Path, tokens: Path, data_dir: Path) -> sherpa_onnx.OfflineTts:
    kokoro_config = sherpa_onnx.OfflineTtsKokoroModelConfig(
        model=str(model),
        voices=str(voices),
        tokens=str(tokens),
        data_dir=str(data_dir),
    )
    model_config = sherpa_onnx.OfflineTtsModelConfig(
        kokoro=kokoro_config,
        num_threads=2,
        debug=True,
        provider="cpu",
    )
    tts_config = sherpa_onnx.OfflineTtsConfig(
        model=model_config,
        max_num_sentences=1,
        silence_scale=0.2,
    )
    return sherpa_onnx.OfflineTts(tts_config)


def save_wav(samples: np.ndarray, sample_rate: int, output_path: Path) -> None:
    pcm = np.clip(samples, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype(np.int16)
    wavfile.write(str(output_path), sample_rate, pcm)


def play_wav(output_path: Path) -> None:
    system = platform.system().lower()
    if system.startswith("win"):
        import winsound

        winsound.PlaySound(str(output_path), winsound.SND_FILENAME)
        return
    raise RuntimeError("Auto playback is currently implemented for Windows only. Use --no-play on other OS.")


def main() -> int:
    args = parse_args()
    model_dir = Path(args.model_dir).resolve()
    model, voices, tokens, data_dir = resolve_model_paths(model_dir)

    tts = build_tts(model, voices, tokens, data_dir)
    speed = min(max(float(args.speed), 0.85), 1.15)
    speaker_id = max(int(args.speaker_id), 0)
    generated = tts.generate(text=args.text, sid=speaker_id, speed=speed)

    samples = np.asarray(generated.samples, dtype=np.float32)
    sample_rate = int(generated.sample_rate)
    if samples.size == 0 or sample_rate <= 0:
        raise RuntimeError("Generated audio is empty or sample rate is invalid")

    if args.output:
        output_path = Path(args.output).resolve()
    else:
        output_path = Path(tempfile.gettempdir()) / "python_local_kokoro_tts.wav"

    save_wav(samples, sample_rate, output_path)
    duration_sec = samples.size / float(sample_rate)
    print(f"Generated WAV: {output_path}")
    print(f"Sample rate: {sample_rate} Hz, samples: {samples.size}, duration: {duration_sec:.2f}s")

    if not args.no_play:
        print("Playing audio...")
        play_wav(output_path)
        print("Playback finished.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
