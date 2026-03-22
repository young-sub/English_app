#!/usr/bin/env python3
from __future__ import annotations

import argparse
import io
import importlib.metadata
import json
import os
import shutil
import subprocess
import tempfile
import threading
import wave
from dataclasses import dataclass
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import numpy as np
import onnx
import sherpa_onnx
from numpy.typing import NDArray


ROOT = Path(__file__).resolve().parents[2]
TOOL_DIR = Path(__file__).resolve().parent
STATIC_DIR = TOOL_DIR / "static"
CACHE_DIR = TOOL_DIR / ".cache"
RUNTIME_DIR = CACHE_DIR / "runtime"
KOKORO_DATA_DIR = ROOT / "modules" / "feature" / "tts" / "models" / "kokoro-en-v0_19" / "espeak-ng-data"
QUOTES_PATH = TOOL_DIR / "quotes.json"
BATCH_SIZE = 10


@dataclass(frozen=True)
class ModelSpec:
    id: str
    label: str
    subtitle: str
    raw_dir: Path
    raw_model_name: str
    raw_json_name: str
    default_speaker_id: int
    single_speaker_label: str | None = None


MODEL_SPECS: dict[str, ModelSpec] = {
    "single": ModelSpec(
        id="single",
        label="단일 모델",
        subtitle="미국 여성",
        raw_dir=ROOT / "modules" / "feature" / "tts" / "models" / "lessac-low",
        raw_model_name="en_US-lessac-low.onnx",
        raw_json_name="en_US-lessac-low.onnx.json",
        default_speaker_id=0,
        single_speaker_label="미국/여성",
    ),
    "multi": ModelSpec(
        id="multi",
        label="다화자 모델",
        subtitle="화자 선택",
        raw_dir=ROOT / "modules" / "feature" / "tts" / "models" / "piper-en_US-libritts_r-medium",
        raw_model_name="en_US-libritts_r-medium.onnx",
        raw_json_name="en_US-libritts_r-medium.onnx.json",
        default_speaker_id=0,
    ),
}

def load_text_presets() -> list[dict[str, str]]:
    quotes = json.loads(QUOTES_PATH.read_text(encoding="utf-8"))
    presets: list[dict[str, str]] = []
    for index, quote in enumerate(quotes, start=1):
        label_source = quote.split(" -- ", maxsplit=1)[-1].strip()
        label = f"{index:02d}. {label_source}"
        presets.append(
            {
                "id": f"quote-{index:02d}",
                "label": label,
                "text": quote,
            }
        )
    return presets


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Developer-only local TTS preset lab")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--provider", choices=["auto", "cpu", "cuda"], default="auto")
    parser.add_argument("--smoke-test", action="store_true")
    return parser.parse_args()


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_tokens(tokens_path: Path, config: dict[str, Any]) -> None:
    with tokens_path.open("wb") as file:
        for symbol, ids in config["phoneme_id_map"].items():
            if symbol == "\n":
                continue
            file.write(f"{symbol} {ids[0]}\n".encode("utf-8"))


def patch_onnx_metadata(model_path: Path, config: dict[str, Any]) -> None:
    model = onnx.load(str(model_path))
    existing = {entry.key: entry for entry in model.metadata_props}
    metadata = {
        "model_type": "vits",
        "comment": "piper",
        "language": config["language"]["name_english"],
        "voice": config["espeak"]["voice"],
        "has_espeak": "1",
        "n_speakers": str(config["num_speakers"]),
        "sample_rate": str(config["audio"]["sample_rate"]),
    }
    for key, value in metadata.items():
        if key in existing:
            existing[key].value = str(value)
        else:
            prop = model.metadata_props.add()
            prop.key = key
            prop.value = str(value)
    onnx.save(model, str(model_path))


def ensure_runtime_dir(spec: ModelSpec) -> Path:
    runtime_dir = RUNTIME_DIR / spec.id
    model_path = runtime_dir / "model.onnx"
    tokens_path = runtime_dir / "tokens.txt"
    data_dir = runtime_dir / "espeak-ng-data"
    source_model = spec.raw_dir / spec.raw_model_name
    source_json = spec.raw_dir / spec.raw_json_name
    metadata_marker = runtime_dir / ".metadata-ready"
    if runtime_dir.exists() and model_path.exists() and tokens_path.exists() and data_dir.exists() and metadata_marker.exists():
        return runtime_dir
    runtime_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source_model, model_path)
    raw_config = load_json(source_json)
    patch_onnx_metadata(model_path, raw_config)
    write_tokens(tokens_path, raw_config)
    if data_dir.exists():
        shutil.rmtree(data_dir)
    shutil.copytree(KOKORO_DATA_DIR, data_dir)
    metadata_marker.write_text("ok", encoding="utf-8")
    return runtime_dir


def curated_speaker_ids(total: int, target_count: int = 24) -> list[int]:
    if total <= 1:
        return [0]
    anchors = [0, 1, 2, 4, 7, 12, 20, 32]
    spread_count = max(0, target_count - len(anchors))
    spread = []
    if spread_count > 0:
        for index in range(spread_count):
            speaker_id = round(index * (total - 1) / max(1, spread_count - 1))
            spread.append(speaker_id)
    combined = []
    for speaker_id in anchors + spread:
        normalized = min(max(int(speaker_id), 0), total - 1)
        if normalized not in combined:
            combined.append(normalized)
    return combined[:target_count]


def build_speaker_deck(total: int, batch_size: int = BATCH_SIZE) -> list[int]:
    if total <= 1:
        return [0]
    bucket_count = min(max(batch_size, 1), total)
    boundaries = [round(index * total / bucket_count) for index in range(bucket_count + 1)]
    buckets: list[list[int]] = []
    for index in range(bucket_count):
        start = boundaries[index]
        end = boundaries[index + 1]
        bucket = list(range(start, end))
        if bucket:
            buckets.append(bucket)

    deck: list[int] = []
    while any(buckets):
        for bucket in buckets:
            if bucket:
                deck.append(bucket.pop(0))
    return deck


def take_next_speakers(deck: list[int], seen_speaker_ids: list[int], count: int = BATCH_SIZE) -> list[int]:
    seen = set(seen_speaker_ids)
    selected: list[int] = []
    for speaker_id in deck:
        if speaker_id in seen:
            continue
        selected.append(speaker_id)
        if len(selected) >= count:
            break
    return selected


def detect_gpu_name() -> str | None:
    try:
        result = subprocess.run(
            ["nvidia-smi", "--query-gpu=name", "--format=csv,noheader"],
            capture_output=True,
            text=True,
            check=True,
        )
    except Exception:
        return None
    first_line = result.stdout.strip().splitlines()
    return first_line[0].strip() if first_line else None


def cuda_wheel_ready() -> bool:
    try:
        version = importlib.metadata.version("sherpa-onnx")
    except importlib.metadata.PackageNotFoundError:
        return False
    return "cuda" in version.lower()


def build_config_payload(default_provider: str) -> dict[str, Any]:
    gpu_name = detect_gpu_name()
    models = []
    for spec in MODEL_SPECS.values():
        raw_config = load_json(spec.raw_dir / spec.raw_json_name)
        total_speakers = int(raw_config.get("num_speakers") or 1)
        curated = []
        for index, speaker_id in enumerate(curated_speaker_ids(total_speakers), start=1):
            curated.append(
                {
                    "speakerId": speaker_id,
                    "label": f"프리셋 {index:02d}",
                    "hint": f"speaker {speaker_id}",
                }
            )
        models.append(
            {
                "id": spec.id,
                "label": spec.label,
                "subtitle": spec.subtitle,
                "singleSpeakerLabel": spec.single_speaker_label,
                "totalSpeakers": total_speakers,
                "defaultSpeakerId": spec.default_speaker_id,
                "curatedSpeakers": curated,
                "speakerDeck": build_speaker_deck(total_speakers, batch_size=BATCH_SIZE),
                "sampleRate": int(raw_config.get("audio", {}).get("sample_rate") or 0),
                "quality": raw_config.get("audio", {}).get("quality") or "unknown",
            }
        )
    return {
        "defaultProvider": default_provider,
        "providers": ["auto", "cuda", "cpu"],
        "gpuName": gpu_name,
        "cudaWheelReady": cuda_wheel_ready(),
        "textPresets": load_text_presets(),
        "models": models,
    }


class TtsRuntimePool:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._cache: dict[tuple[str, str], sherpa_onnx.OfflineTts] = {}

    def synthesize(self, model_id: str, text: str, speaker_id: int, speed: float, provider: str) -> tuple[bytes, str, int]:
        spec = MODEL_SPECS[model_id]
        active_provider, tts = self._resolve_runtime(spec, provider)
        generated = tts.generate(text=text, sid=max(speaker_id, 0), speed=min(max(speed, 0.7), 1.4))
        samples = np.asarray(generated.samples, dtype=np.float32)
        return wav_bytes(samples, int(generated.sample_rate)), active_provider, int(generated.sample_rate)

    def _resolve_runtime(self, spec: ModelSpec, requested_provider: str) -> tuple[str, sherpa_onnx.OfflineTts]:
        if requested_provider == "auto":
            candidates = ["cuda", "cpu"] if cuda_wheel_ready() else ["cpu"]
        elif requested_provider == "cuda" and not cuda_wheel_ready():
            candidates = ["cpu"]
        else:
            candidates = [requested_provider]
        last_error: Exception | None = None
        for provider in candidates:
            try:
                return provider, self._get_or_create(spec, provider)
            except Exception as error:
                last_error = error
        if last_error is None:
            raise RuntimeError("No provider candidates were available")
        raise last_error

    def _get_or_create(self, spec: ModelSpec, provider: str) -> sherpa_onnx.OfflineTts:
        key = (spec.id, provider)
        with self._lock:
            cached = self._cache.get(key)
            if cached is not None:
                return cached
            runtime_dir = ensure_runtime_dir(spec)
            tts = build_tts(runtime_dir, provider)
            self._cache[key] = tts
            return tts


def build_tts(runtime_dir: Path, provider: str) -> sherpa_onnx.OfflineTts:
    vits_config = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=str(runtime_dir / "model.onnx"),
        lexicon="",
        tokens=str(runtime_dir / "tokens.txt"),
        data_dir=str(runtime_dir / "espeak-ng-data"),
    )
    model_config = sherpa_onnx.OfflineTtsModelConfig(
        vits=vits_config,
        num_threads=max(1, min(8, (os.cpu_count() or 4) // 2)),
        debug=False,
        provider=provider,
    )
    tts_config = sherpa_onnx.OfflineTtsConfig(
        model=model_config,
        max_num_sentences=1,
        silence_scale=0.2,
    )
    if not tts_config.validate():
        raise RuntimeError(f"Invalid TTS config for provider={provider}")
    return sherpa_onnx.OfflineTts(tts_config)


def wav_bytes(samples: NDArray[np.float32], sample_rate: int) -> bytes:
    pcm = np.clip(samples, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype(np.int16)
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(pcm.tobytes())
    return buffer.getvalue()


class LabRequestHandler(SimpleHTTPRequestHandler):
    runtime_pool = TtsRuntimePool()
    default_provider = "auto"

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, directory=str(STATIC_DIR), **kwargs)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/config":
            self._send_json(build_config_payload(self.default_provider))
            return
        if parsed.path == "/api/health":
            self._send_json({"ok": True})
            return
        if parsed.path == "/":
            self.path = "/index.html"
        super().do_GET()

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path != "/api/synthesize":
            self.send_error(HTTPStatus.NOT_FOUND, "Unknown API path")
            return
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(content_length).decode("utf-8"))
            model_id = str(payload["modelId"])
            text = str(payload["text"]).strip()
            provider = str(payload.get("provider") or self.default_provider)
            speaker_id = int(payload.get("speakerId") or 0)
            speed = float(payload.get("speed") or 1.0)
            if model_id not in MODEL_SPECS:
                raise ValueError("Unknown modelId")
            if not text:
                raise ValueError("Text is blank")
            wav_payload, active_provider, sample_rate = self.runtime_pool.synthesize(
                model_id=model_id,
                text=text,
                speaker_id=speaker_id,
                speed=speed,
                provider=provider,
            )
        except Exception as error:
            self._send_json({"error": str(error)}, status=HTTPStatus.BAD_REQUEST)
            return

        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Content-Length", str(len(wav_payload)))
        self.send_header("X-Active-Provider", active_provider)
        self.send_header("X-Sample-Rate", str(sample_rate))
        self.end_headers()
        self.wfile.write(wav_payload)

    def log_message(self, format: str, *args: Any) -> None:
        return

    def _send_json(self, payload: dict[str, Any], status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def smoke_test(provider: str) -> int:
    pool = TtsRuntimePool()
    wav_payload, active_provider, sample_rate = pool.synthesize(
        model_id="single",
        text="This is a smoke test for the standalone TTS preset lab.",
        speaker_id=0,
        speed=1.0,
        provider=provider,
    )
    output_path = Path(tempfile.gettempdir()) / "tts_preset_lab_smoke.wav"
    output_path.write_bytes(wav_payload)
    print(f"Smoke test OK -> {output_path}")
    print(f"Provider: {active_provider}, sample_rate={sample_rate}")
    return 0


def main() -> int:
    args = parse_args()
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    if args.smoke_test:
        return smoke_test(args.provider)
    handler = LabRequestHandler
    handler.default_provider = args.provider
    server = ThreadingHTTPServer((args.host, args.port), handler)
    print(f"TTS Preset Lab -> http://{args.host}:{args.port}")
    print(f"GPU: {detect_gpu_name() or 'not detected'}")
    print(f"Default provider: {args.provider}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
