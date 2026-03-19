import argparse
import csv
import gzip
import json
import re
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate supplemental CSV rows for missing top-N English words from Kaikki cache.",
    )
    parser.add_argument(
        "--top-n",
        type=int,
        default=10000,
    )
    parser.add_argument(
        "--cache-gz",
        type=Path,
        default=Path("tools/dictionary_etl/cache/kaikki-english-words.jsonl.gz"),
    )
    parser.add_argument(
        "--raw-dir",
        type=Path,
        default=Path("tools/dictionary_etl/raw"),
    )
    parser.add_argument(
        "--output-csv",
        type=Path,
        default=Path("tools/dictionary_etl/raw/top_coverage_supplement.csv"),
    )
    return parser.parse_args()


def normalize_lookup(value: str) -> str:
    text = (value or "").strip().lower()
    text = text.replace("’", "'").replace("‘", "'").replace("＇", "'")
    return re.sub(r"^[^a-z0-9]+|[^a-z0-9']+$", "", text)


def normalize_word(value: str) -> str:
    lowered = (value or "").strip().lower().replace("_", " ")
    lowered = re.sub(r"\s+", " ", lowered)
    return lowered


def normalize_pos(value: str) -> str:
    pos = (value or "").strip().lower()
    if pos in {"n", "noun"}:
        return "noun"
    if pos in {"v", "verb"}:
        return "verb"
    if pos in {"a", "adj", "adjective", "s"}:
        return "adjective"
    if pos in {"r", "adv", "adverb"}:
        return "adverb"
    return pos or "noun"


def clean_text(value: str) -> str:
    text = (value or "").strip()
    return re.sub(r"\s+", " ", text)


def choose_gloss(sense: object) -> str:
    if not isinstance(sense, dict):
        return ""
    for key in ("glosses", "raw_glosses"):
        values = sense.get(key)
        if isinstance(values, list):
            for item in values:
                if isinstance(item, str):
                    text = clean_text(item)
                    if text:
                        return text[:320]
    return ""


def choose_example(sense: object) -> str:
    if not isinstance(sense, dict):
        return ""
    examples = sense.get("examples")
    if isinstance(examples, list):
        for item in examples:
            if isinstance(item, dict):
                text = clean_text(item.get("text") or "")
            elif isinstance(item, str):
                text = clean_text(item)
            else:
                text = ""
            if text:
                return text[:220]
    return ""


def extract_ko_words(translations: object) -> list[str]:
    if not isinstance(translations, list):
        return []
    result: list[str] = []
    seen: set[str] = set()
    for item in translations:
        if not isinstance(item, dict):
            continue
        code = clean_text((item.get("code") or item.get("lang_code") or ""))
        if code != "ko":
            continue
        word = clean_text(item.get("word") or "")
        if not word:
            continue
        key = word.lower()
        if key in seen:
            continue
        seen.add(key)
        result.append(word)
    return result


def load_top_words(top_n: int) -> list[str]:
    try:
        from wordfreq import top_n_list
    except ImportError as exc:
        raise RuntimeError(
            "wordfreq is required for top coverage supplement. Install with: pip install --user wordfreq"
        ) from exc

    words = [normalize_lookup(word) for word in top_n_list("en", top_n)]
    return [word for word in words if word]


def load_existing_words(raw_dir: Path, exclude_path: Path | None = None) -> set[str]:
    existing: set[str] = set()
    for csv_path in sorted(raw_dir.glob("*.csv")):
        if exclude_path is not None and csv_path.resolve() == exclude_path.resolve():
            continue
        with csv_path.open("r", encoding="utf-8") as handle:
            reader = csv.DictReader(handle)
            for row in reader:
                existing.add(normalize_lookup(row.get("headword") or ""))
                existing.add(normalize_lookup(row.get("lemma") or ""))
    existing.discard("")
    return existing


def load_existing_rows_by_headword(raw_dir: Path, exclude_path: Path | None = None) -> dict[str, list[dict[str, str]]]:
    rows_by_headword: dict[str, list[dict[str, str]]] = {}
    for csv_path in sorted(raw_dir.glob("*.csv")):
        if exclude_path is not None and csv_path.resolve() == exclude_path.resolve():
            continue
        with csv_path.open("r", encoding="utf-8") as handle:
            reader = csv.DictReader(handle)
            for row in reader:
                headword = normalize_lookup(row.get("headword") or "")
                if not headword:
                    continue
                rows_by_headword.setdefault(headword, []).append(
                    {
                        "headword": row.get("headword") or "",
                        "lemma": row.get("lemma") or "",
                        "pos": row.get("pos") or "noun",
                        "ipa": row.get("ipa") or "",
                        "frequencyRank": row.get("frequencyRank") or "20000",
                        "definitionEn": row.get("definitionEn") or "",
                        "definitionKo": row.get("definitionKo") or "",
                        "exampleEn": row.get("exampleEn") or "",
                        "exampleKo": row.get("exampleKo") or "",
                        "source": row.get("source") or "seed-csv",
                        "license": row.get("license") or "unknown",
                    }
                )
    return rows_by_headword


def generate_rows(cache_gz: Path, missing_words: set[str]) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    seen_word_pos: set[tuple[str, str]] = set()
    pattern = re.compile(r"^[a-z0-9][a-z0-9\- '\\.]*$")

    with gzip.open(cache_gz, "rt", encoding="utf-8", errors="ignore") as handle:
        for line in handle:
            try:
                item = json.loads(line)
            except Exception:
                continue

            if item.get("lang_code") != "en":
                continue

            word = normalize_word(str(item.get("word") or ""))
            word_key = normalize_lookup(word)
            if word_key not in missing_words:
                continue
            if not pattern.match(word):
                continue
            if is_numeric_token(word_key) or is_ordinal_token(word_key):
                continue

            pos = normalize_pos(item.get("pos"))
            key = (word_key, pos)
            if key in seen_word_pos:
                continue

            senses = item.get("senses")
            if not isinstance(senses, list) or not senses:
                continue

            ko_top = extract_ko_words(item.get("translations"))
            used = 0
            for sense in senses:
                definition_en = choose_gloss(sense)
                if not definition_en:
                    continue

                translations_sense = extract_ko_words(sense.get("translations") if isinstance(sense, dict) else None)
                ko_words = translations_sense or ko_top

                rows.append(
                    {
                        "headword": word,
                        "lemma": word,
                        "pos": pos,
                        "ipa": "",
                        "frequencyRank": "15000",
                        "definitionEn": definition_en,
                        "definitionKo": ", ".join(ko_words[:3]),
                        "exampleEn": choose_example(sense),
                        "exampleKo": "",
                        "source": "kaikki-top-coverage",
                        "license": "CC-BY-SA-3.0",
                    }
                )
                used += 1
                if used >= 2:
                    break

            if used > 0:
                seen_word_pos.add(key)

    return rows


def append_source_tag(source: str, extra: str) -> str:
    parts = [segment.strip() for segment in source.split("+") if segment.strip()]
    if extra not in parts:
        parts.append(extra)
    return "+".join(parts)


def infer_base_candidates(word: str) -> list[str]:
    candidates: list[str] = []

    def add(value: str) -> None:
        normalized = normalize_lookup(value)
        if normalized and normalized not in candidates:
            candidates.append(normalized)

    if word in blocked_possessive_derivations:
        return []

    if word.endswith("'s") and len(word) > 2:
        add(word[:-2])
    if word.endswith("s'") and len(word) > 2:
        add(word[:-1])
        add(word[:-2])
    if word.endswith("'") and len(word) > 1:
        add(word[:-1])

    numeric_aliases = {
        "0": "zero",
        "1": "one",
        "2": "two",
        "3": "three",
        "4": "four",
        "5": "five",
        "6": "six",
        "7": "seven",
        "8": "eight",
        "9": "nine",
        "10": "ten",
        "1st": "first",
        "2nd": "second",
        "3rd": "third",
        "4th": "fourth",
        "5th": "fifth",
        "6th": "sixth",
        "7th": "seventh",
        "8th": "eighth",
        "9th": "ninth",
        "10th": "tenth",
    }
    alias = numeric_aliases.get(word)
    if alias:
        add(alias)

    return candidates


blocked_possessive_derivations = {
    "it's",
    "that's",
    "there's",
    "what's",
    "who's",
    "here's",
    "where's",
    "when's",
    "why's",
    "how's",
    "let's",
    "he's",
    "she's",
    "we're",
    "they're",
}


def generate_derived_rows(
    missing_words: set[str],
    rows_by_headword: dict[str, list[dict[str, str]]],
) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for missing_word in sorted(missing_words):
        base_candidates = infer_base_candidates(missing_word)
        if not base_candidates:
            continue

        for base in base_candidates:
            base_rows = rows_by_headword.get(base)
            if not base_rows:
                continue

            filtered_rows = base_rows
            if is_ordinal_token(missing_word):
                filtered_rows = [row for row in base_rows if normalize_pos(row.get("pos") or "noun") == "adjective"]
            elif is_numeric_token(missing_word):
                preferred = [row for row in base_rows if normalize_pos(row.get("pos") or "noun") == "adjective"]
                fallback = [row for row in base_rows if normalize_pos(row.get("pos") or "noun") == "noun"]
                filtered_rows = preferred + fallback

            if not filtered_rows:
                continue

            used = 0
            for base_row in filtered_rows:
                definition_en = clean_text(base_row.get("definitionEn") or "")
                definition_ko = clean_text(base_row.get("definitionKo") or "")
                if not definition_en and not definition_ko:
                    continue

                rows.append(
                    {
                        "headword": missing_word,
                        "lemma": base,
                        "pos": normalize_pos(base_row.get("pos") or "noun"),
                        "ipa": base_row.get("ipa") or "",
                        "frequencyRank": base_row.get("frequencyRank") or "15000",
                        "definitionEn": definition_en,
                        "definitionKo": definition_ko,
                        "exampleEn": clean_text(base_row.get("exampleEn") or ""),
                        "exampleKo": clean_text(base_row.get("exampleKo") or ""),
                        "source": append_source_tag(base_row.get("source") or "seed-csv", "top-derived"),
                        "license": base_row.get("license") or "unknown",
                    }
                )
                used += 1
                if used >= 2:
                    break

            if used > 0:
                break

    return rows


def dedupe_rows(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    seen: set[tuple[str, str, str, str, str]] = set()
    unique: list[dict[str, str]] = []
    for row in rows:
        key = (
            normalize_lookup(row.get("headword") or ""),
            normalize_pos(row.get("pos") or "noun"),
            clean_text(row.get("definitionEn") or "").lower(),
            clean_text(row.get("definitionKo") or "").lower(),
            row.get("source") or "",
        )
        if not key[0]:
            continue
        if key in seen:
            continue
        seen.add(key)
        unique.append(row)
    return unique


def index_rows_by_headword(rows: list[dict[str, str]]) -> dict[str, list[dict[str, str]]]:
    index: dict[str, list[dict[str, str]]] = {}
    for row in rows:
        headword = normalize_lookup(row.get("headword") or "")
        if not headword:
            continue
        index.setdefault(headword, []).append(row)
    return index


def is_numeric_token(token: str) -> bool:
    return bool(re.fullmatch(r"[0-9]+", token))


def is_ordinal_token(token: str) -> bool:
    return bool(re.fullmatch(r"[0-9]+(st|nd|rd|th)", token))


def write_rows(output_csv: Path, rows: list[dict[str, str]]) -> None:
    fieldnames = [
        "headword",
        "lemma",
        "pos",
        "ipa",
        "frequencyRank",
        "definitionEn",
        "definitionKo",
        "exampleEn",
        "exampleKo",
        "source",
        "license",
    ]

    output_csv.parent.mkdir(parents=True, exist_ok=True)
    with output_csv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    args = parse_args()

    if not args.cache_gz.exists():
        raise FileNotFoundError(f"Kaikki cache not found: {args.cache_gz}")

    top_words = load_top_words(args.top_n)
    existing = load_existing_words(args.raw_dir, exclude_path=args.output_csv)
    rows_by_headword = load_existing_rows_by_headword(args.raw_dir, exclude_path=args.output_csv)
    missing = {word for word in top_words if word not in existing}

    kaikki_rows = generate_rows(args.cache_gz, missing)
    covered_by_kaikki = {normalize_lookup(row.get("headword") or "") for row in kaikki_rows}
    remaining = {word for word in missing if word not in covered_by_kaikki}
    rows_by_headword.update(index_rows_by_headword(kaikki_rows))
    derived_rows = generate_derived_rows(remaining, rows_by_headword)
    rows = dedupe_rows(kaikki_rows + derived_rows)
    write_rows(args.output_csv, rows)

    print(f"Top words: {len(top_words)}")
    print(f"Missing before supplement: {len(missing)}")
    print(f"Kaikki supplement rows: {len(kaikki_rows)}")
    print(f"Derived supplement rows: {len(derived_rows)}")
    print(f"Supplement rows: {len(rows)}")
    print(f"Wrote: {args.output_csv}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
