#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path
from typing import Any

PIPER_BUNDLED_MODEL_DIRS = {
    "lessac-low": "piper-en_US-lessac-low",
    "piper-en_US-libritts_r-medium": "piper-en_US-libritts_r-medium",
}
PIPER_PRESET_COUNT = 10


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--dest", required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    source_root = Path(args.source)
    dest_root = Path(args.dest)

    if dest_root.exists():
        shutil.rmtree(dest_root)
    dest_root.mkdir(parents=True, exist_ok=True)

    espeak_source = source_root / "kokoro-en-v0_19" / "espeak-ng-data"

    for child in sorted(source_root.iterdir()):
        if not child.is_dir():
            continue
        output_dir_name = PIPER_BUNDLED_MODEL_DIRS.get(child.name)
        if output_dir_name is None:
            continue
        prepare_piper_dir(child, dest_root / output_dir_name, espeak_source)


def prepare_piper_dir(source: Path, dest: Path, espeak_source: Path) -> None:
    try:
        import onnx
    except ImportError as exc:
        raise SystemExit(
            "Missing Python dependency 'onnx'. Install it locally with `python -m pip install --user onnx==1.17.0`."
        ) from exc

    raw_model = next(source.glob("*.onnx"), None)
    raw_json = next(source.glob("*.onnx.json"), None)
    if raw_model is None or raw_json is None:
        raise SystemExit(f"Raw Piper model is incomplete in {source}")

    config = json.loads(raw_json.read_text(encoding="utf-8"))
    dest.mkdir(parents=True, exist_ok=True)

    output_model = dest / "model.onnx"
    shutil.copy2(raw_model, output_model)
    patch_onnx_metadata(onnx, output_model, config)

    write_tokens(dest / "tokens.txt", config)

    espeak_dest = dest / "espeak-ng-data"
    if espeak_dest.exists():
        shutil.rmtree(espeak_dest)
    shutil.copytree(espeak_source, espeak_dest)

    write_speaker_manifest(dest / "speaker-manifest.tsv", config)


def patch_onnx_metadata(onnx_module, model_path: Path, config: dict[str, Any]) -> None:
    model = onnx_module.load(str(model_path))
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
    onnx_module.save(model, str(model_path))


def write_tokens(tokens_path: Path, config: dict[str, Any]) -> None:
    with tokens_path.open("wb") as file:
        for symbol, ids in config["phoneme_id_map"].items():
            if symbol == "\n":
                continue
            file.write(f"{symbol} {ids[0]}\n".encode("utf-8"))


def write_speaker_manifest(manifest_path: Path, config: dict[str, Any]) -> None:
    speaker_items = sorted((config.get("speaker_id_map") or {}).items(), key=lambda item: item[1])
    preset_count = min(len(speaker_items), PIPER_PRESET_COUNT)
    if preset_count == 0:
        return
    selected_items = []
    if len(speaker_items) <= preset_count:
        selected_items = speaker_items
    else:
        for index in range(preset_count):
            mapped_index = round(index * (len(speaker_items) - 1) / (preset_count - 1))
            selected_items.append(speaker_items[mapped_index])
    with manifest_path.open("wb") as file:
        file.write("speaker_id\tcode\tdisplay_label\taccent_label\tgender\n".encode("utf-8"))
        for index, (code, speaker_id) in enumerate(selected_items):
            batch = index // 5 + 1
            file.write(f"{speaker_id}\t{code}\tSpeaker {speaker_id}\tPreset {batch}\tUNKNOWN\n".encode("utf-8"))


if __name__ == "__main__":
    main()
