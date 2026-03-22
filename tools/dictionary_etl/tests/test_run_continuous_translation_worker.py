from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.dictionary_etl.run_continuous_translation_worker import Claim
from tools.dictionary_etl.run_continuous_translation_worker import is_json_parse_error
from tools.dictionary_etl.run_continuous_translation_worker import validate_output_file


class ContinuousTranslationWorkerTests(unittest.TestCase):
    def write_jsonl(self, path: Path, rows: list[dict[str, str]]) -> None:
        with path.open("w", encoding="utf-8") as handle:
            for row in rows:
                handle.write(json.dumps(row, ensure_ascii=False) + "\n")

    def test_validate_output_file_accepts_matching_rows(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_path = Path(tmp_dir)
            slice_file = tmp_path / "slice.jsonl"
            output_file = tmp_path / "output.jsonl"
            self.write_jsonl(
                slice_file,
                [
                    {"field": "definition", "source_text": "alpha", "source_hash": "hash-1"},
                    {"field": "example", "source_text": "beta", "source_hash": "hash-2"},
                ],
            )
            self.write_jsonl(
                output_file,
                [
                    {"field": "definition", "source_hash": "hash-1", "translated_text": "alpha 뜻"},
                    {"field": "example", "source_hash": "hash-2", "translated_text": "beta 예문"},
                ],
            )

            validate_output_file(slice_file, output_file, 2)

    def test_validate_output_file_rejects_mismatched_hash(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_path = Path(tmp_dir)
            slice_file = tmp_path / "slice.jsonl"
            output_file = tmp_path / "output.jsonl"
            self.write_jsonl(
                slice_file,
                [{"field": "definition", "source_text": "alpha", "source_hash": "hash-1"}],
            )
            self.write_jsonl(
                output_file,
                [{"field": "definition", "source_hash": "wrong", "translated_text": "alpha 뜻"}],
            )

            with self.assertRaisesRegex(RuntimeError, "source_hash mismatch"):
                validate_output_file(slice_file, output_file, 1)

    def test_is_json_parse_error_detects_decoder_trace(self) -> None:
        self.assertTrue(is_json_parse_error("json.decoder.JSONDecodeError: bad", ""))
        self.assertTrue(is_json_parse_error("", "JSONDecodeError: bad"))
        self.assertFalse(is_json_parse_error("ValueError: bad", ""))

    def test_claim_dataclass_keeps_paths(self) -> None:
        claim = Claim(
            mode="codex",
            slice_file=Path("slice.jsonl"),
            output_file=Path("output.jsonl"),
            claim_file=Path("claim.claim"),
            count=1,
            start=1,
            end=1,
        )
        self.assertEqual(claim.mode, "codex")
        self.assertEqual(claim.output_file.name, "output.jsonl")


if __name__ == "__main__":
    unittest.main()
