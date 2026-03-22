from __future__ import annotations

import argparse
import importlib
import json
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class MissingText:
    field: str
    source_text: str
    source_hash: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Translate missing EN dictionary texts to Korean.")
    parser.add_argument("--input-jsonl", type=Path, required=True)
    parser.add_argument("--output-jsonl", type=Path, required=True)
    parser.add_argument(
        "--backend",
        choices=["marian", "ct2-opus", "deep-google"],
        default="ct2-opus",
    )
    parser.add_argument(
        "--model-name",
        default="Helsinki-NLP/opus-mt-en-ko",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=24,
    )
    parser.add_argument(
        "--model-dir",
        type=Path,
        default=Path("tools/dictionary_etl/cache/ko_regen/models/opus_en_ko_ct2"),
    )
    parser.add_argument(
        "--source-spm",
        type=Path,
        default=Path("tools/dictionary_etl/cache/ko_regen/models/opus_marian_en_ko/source.spm"),
    )
    parser.add_argument(
        "--target-spm",
        type=Path,
        default=Path("tools/dictionary_etl/cache/ko_regen/models/opus_marian_en_ko/target.spm"),
    )
    parser.add_argument(
        "--max-items",
        type=int,
        default=0,
        help="Optional cap for this execution.",
    )
    parser.add_argument(
        "--sleep-seconds",
        type=float,
        default=0.2,
        help="Throttle delay between requests for web-backed translators.",
    )
    parser.add_argument(
        "--retry-count",
        type=int,
        default=3,
    )
    return parser.parse_args()


def load_missing(path: Path, max_items: int) -> list[MissingText]:
    rows: list[MissingText] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            payload = json.loads(line)
            rows.append(
                MissingText(
                    field=str(payload["field"]),
                    source_text=str(payload["source_text"]),
                    source_hash=str(payload["source_hash"]),
                )
            )
            if max_items > 0 and len(rows) >= max_items:
                break
    return rows


def batched(items: list[MissingText], batch_size: int) -> Iterable[list[MissingText]]:
    for start in range(0, len(items), batch_size):
        yield items[start : start + batch_size]


def translate_with_marian(texts: list[str], model_name: str) -> list[str]:
    try:
        transformers = importlib.import_module("transformers")
    except ImportError as exc:
        raise RuntimeError(
            "transformers and sentencepiece are required for Marian translation backend"
        ) from exc

    tokenizer = transformers.MarianTokenizer.from_pretrained(model_name)
    model = transformers.MarianMTModel.from_pretrained(model_name)
    encoded = tokenizer(texts, return_tensors="pt", padding=True, truncation=True)
    generated = model.generate(**encoded, max_new_tokens=256)
    return tokenizer.batch_decode(generated, skip_special_tokens=True)


def translate_with_ct2_opus(
    texts: list[str],
    model_dir: Path,
    source_spm: Path,
    target_spm: Path,
) -> list[str]:
    try:
        ctranslate2 = importlib.import_module("ctranslate2")
        sentencepiece = importlib.import_module("sentencepiece")
    except ImportError as exc:
        raise RuntimeError("ctranslate2 and sentencepiece are required for ct2-opus backend") from exc

    source_processor = sentencepiece.SentencePieceProcessor()
    target_processor = sentencepiece.SentencePieceProcessor()
    if not source_processor.load(str(source_spm)):
        raise RuntimeError(f"Failed to load source sentencepiece model: {source_spm}")
    if not target_processor.load(str(target_spm)):
        raise RuntimeError(f"Failed to load target sentencepiece model: {target_spm}")

    translator = ctranslate2.Translator(str(model_dir), device="cpu", compute_type="int8")
    tokenized = [source_processor.encode(text, out_type=str) for text in texts]
    results = translator.translate_batch(tokenized, beam_size=1, max_decoding_length=256)
    decoded: list[str] = []
    for result in results:
        hypothesis = result.hypotheses[0]
        decoded.append(target_processor.decode(hypothesis).strip())
    return decoded


def translate_with_deep_google(texts: list[str], sleep_seconds: float, retry_count: int) -> list[str]:
    try:
        deep_translator = importlib.import_module("deep_translator")
    except ImportError as exc:
        raise RuntimeError("deep-translator is required for deep-google backend") from exc

    translator = deep_translator.GoogleTranslator(source="en", target="ko")
    fallback = deep_translator.MyMemoryTranslator(source="en-GB", target="ko-KR")
    outputs: list[str] = []
    for text in texts:
        last_error: Exception | None = None
        for attempt in range(retry_count):
            try:
                translated = translator.translate(text)
                outputs.append((translated or "").strip())
                break
            except Exception as exc:  # noqa: BLE001
                last_error = exc
                time.sleep(sleep_seconds * (attempt + 1))
        else:
            try:
                translated = fallback.translate(text)
                outputs.append((translated or "").strip())
            except Exception as fallback_exc:  # noqa: BLE001
                last_error = fallback_exc
                outputs.append("")
        if sleep_seconds > 0:
            time.sleep(sleep_seconds)
    return outputs


def translate_rows(
    rows: list[MissingText],
    backend: str,
    model_name: str,
    batch_size: int,
    model_dir: Path,
    source_spm: Path,
    target_spm: Path,
    sleep_seconds: float,
    retry_count: int,
) -> list[dict[str, str]]:
    translated_rows: list[dict[str, str]] = []
    for chunk in batched(rows, batch_size):
        texts = [item.source_text for item in chunk]
        if backend == "marian":
            outputs = translate_with_marian(texts, model_name)
        elif backend == "ct2-opus":
            outputs = translate_with_ct2_opus(texts, model_dir, source_spm, target_spm)
        elif backend == "deep-google":
            outputs = translate_with_deep_google(texts, sleep_seconds, retry_count)
        else:
            raise ValueError(f"Unsupported backend: {backend}")
        for item, translated_text in zip(chunk, outputs, strict=True):
            translated_rows.append(
                {
                    "field": item.field,
                    "source_hash": item.source_hash,
                    "translated_text": translated_text.strip(),
                }
            )
    return translated_rows


def write_jsonl(path: Path, rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def main() -> int:
    args = parse_args()
    rows = load_missing(args.input_jsonl, args.max_items)
    translated_rows = translate_rows(
        rows,
        args.backend,
        args.model_name,
        args.batch_size,
        args.model_dir,
        args.source_spm,
        args.target_spm,
        args.sleep_seconds,
        args.retry_count,
    )
    write_jsonl(args.output_jsonl, translated_rows)
    print(
        json.dumps(
            {
                "translated": len(translated_rows),
                "output": str(args.output_jsonl),
                "backend": args.backend,
                "model": args.model_name,
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
