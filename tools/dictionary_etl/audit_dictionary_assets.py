import argparse
import csv
import json
import re
import sqlite3
import sys
from pathlib import Path


def set_max_csv_field_size() -> None:
    limit = sys.maxsize
    while True:
        try:
            csv.field_size_limit(limit)
            return
        except OverflowError:
            limit //= 10


set_max_csv_field_size()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Audit dictionary DB separation, lookup performance, and EN/KO data quality.",
    )
    parser.add_argument(
        "--raw-dir",
        type=Path,
        default=Path("tools/dictionary_etl/raw"),
    )
    parser.add_argument(
        "--seed-meta",
        type=Path,
        default=Path("tools/dictionary_etl/generated/dictionary_seed.meta.json"),
    )
    parser.add_argument(
        "--dictionary-db",
        type=Path,
        default=Path("app/src/main/assets/databases/dictionary.db"),
    )
    parser.add_argument(
        "--dictionary-db-kt",
        type=Path,
        default=Path("app/src/main/kotlin/com/example/bookhelper/data/local/DictionaryDatabase.kt"),
    )
    parser.add_argument(
        "--user-db-kt",
        type=Path,
        default=Path("app/src/main/kotlin/com/example/bookhelper/data/local/UserDatabase.kt"),
    )
    parser.add_argument(
        "--top-n",
        type=int,
        default=10000,
    )
    parser.add_argument(
        "--report-json",
        type=Path,
        default=Path("tools/dictionary_etl/reports/dictionary_audit_report.json"),
    )
    parser.add_argument(
        "--ko-example-required",
        action="store_true",
        help="Treat Korean examples as required quality gate (default: optional).",
    )
    return parser.parse_args()


def extract_db_name_from_kotlin(path: Path) -> str:
    if not path.exists():
        return ""
    text = path.read_text(encoding="utf-8", errors="ignore")
    match = re.search(r'"([a-zA-Z0-9_\-]+\.db)"', text)
    if not match:
        return ""
    return match.group(1)


def analyze_db_separation(dictionary_db_kt: Path, user_db_kt: Path) -> dict:
    dictionary_db_name = extract_db_name_from_kotlin(dictionary_db_kt)
    user_db_name = extract_db_name_from_kotlin(user_db_kt)
    return {
        "dictionaryDbName": dictionary_db_name,
        "userDbName": user_db_name,
        "isSeparated": bool(dictionary_db_name and user_db_name and dictionary_db_name != user_db_name),
    }


def explain_query_plan(connection: sqlite3.Connection, query: str) -> list[str]:
    rows = list(connection.execute(f"EXPLAIN QUERY PLAN {query}"))
    return [str(row[3]) for row in rows]


def analyze_performance(dictionary_db: Path) -> dict:
    if not dictionary_db.exists():
        return {
            "dbExists": False,
            "entryCount": 0,
            "senseCount": 0,
            "ftsRowCount": 0,
            "queryPlans": {},
            "flags": ["dictionary.db not found"],
        }

    conn = sqlite3.connect(str(dictionary_db))
    try:
        entry_count = int(conn.execute("SELECT COUNT(*) FROM dictionary_entries").fetchone()[0])
        sense_count = int(conn.execute("SELECT COUNT(*) FROM dictionary_senses").fetchone()[0])
        fts_row_count = int(conn.execute("SELECT COUNT(*) FROM dictionary_senses_fts").fetchone()[0])

        plans = {
            "exactHeadword": explain_query_plan(
                conn,
                "SELECT * FROM dictionary_entries WHERE headword='run' ORDER BY frequencyRank ASC LIMIT 20",
            ),
            "lemmaIn": explain_query_plan(
                conn,
                "SELECT * FROM dictionary_entries WHERE lemma IN ('run','running') ORDER BY frequencyRank ASC LIMIT 50",
            ),
            "prefixLike": explain_query_plan(
                conn,
                "SELECT * FROM dictionary_entries WHERE headword LIKE 'run%' ORDER BY frequencyRank ASC LIMIT 50",
            ),
            "prefixRange": explain_query_plan(
                conn,
                "SELECT * FROM dictionary_entries WHERE headword >= 'run' AND headword < 'run\uffff' ORDER BY frequencyRank ASC LIMIT 50",
            ),
            "ftsSearch": explain_query_plan(
                conn,
                "SELECT DISTINCT entryId FROM dictionary_senses_fts WHERE dictionary_senses_fts MATCH 'run*' LIMIT 80",
            ),
        }

        flags = []
        if any("SCAN dictionary_entries" in step for step in plans["prefixLike"]):
            flags.append("prefix LIKE query causes full scan")
        if not any("SEARCH dictionary_entries USING INDEX" in step for step in plans["prefixRange"]):
            flags.append("prefix range query is not index-backed")
        if entry_count > 0 and fts_row_count < int(sense_count * 0.95):
            flags.append("fts row count looks lower than senses")

        return {
            "dbExists": True,
            "entryCount": entry_count,
            "senseCount": sense_count,
            "ftsRowCount": fts_row_count,
            "queryPlans": plans,
            "flags": flags,
        }
    finally:
        conn.close()


def normalize_lookup(value: str) -> str:
    text = (value or "").strip().lower()
    text = text.replace("’", "'").replace("‘", "'").replace("＇", "'")
    return re.sub(r"^[^a-z0-9]+|[^a-z0-9']+$", "", text)


def load_top_words(top_n: int) -> list[str]:
    try:
        from wordfreq import top_n_list
    except ImportError:
        return []
    words = [normalize_lookup(word) for word in top_n_list("en", top_n)]
    return [word for word in words if word]


def analyze_data_quality(raw_dir: Path, top_n: int, ko_example_required: bool) -> dict:
    csv_paths = sorted(raw_dir.glob("*.csv"))
    if not csv_paths:
        return {
            "csvFiles": [],
            "rowCount": 0,
            "english": {},
            "englishKorean": {},
            "flags": [f"no csv files found in {raw_dir}"],
        }

    row_count = 0
    missing_definition_en = 0
    missing_definition_ko = 0
    missing_example_ko = 0
    suspicious_ko_ascii_only = 0
    suspicious_en_has_hangul = 0

    headword_set: set[str] = set()
    lemma_set: set[str] = set()
    ko_covered_headwords: set[str] = set()

    duplicate_key_map: dict[tuple[str, str, str], set[str]] = {}

    for csv_path in csv_paths:
        with csv_path.open("r", encoding="utf-8") as handle:
            reader = csv.DictReader(handle)
            for row in reader:
                row_count += 1
                headword = normalize_lookup(row.get("headword") or "")
                lemma = normalize_lookup(row.get("lemma") or "")
                definition_en = (row.get("definitionEn") or "").strip()
                definition_ko = (row.get("definitionKo") or "").strip()
                example_ko = (row.get("exampleKo") or "").strip()

                if headword:
                    headword_set.add(headword)
                if lemma:
                    lemma_set.add(lemma)

                if not definition_en:
                    missing_definition_en += 1
                if not definition_ko:
                    missing_definition_ko += 1
                else:
                    if headword:
                        ko_covered_headwords.add(headword)
                if not example_ko:
                    missing_example_ko += 1

                if definition_ko and re.fullmatch(r"[A-Za-z0-9 ,;:()'\-\.]+", definition_ko):
                    suspicious_ko_ascii_only += 1
                if definition_en and re.search(r"[가-힣]", definition_en):
                    suspicious_en_has_hangul += 1

                if headword and definition_en:
                    key = (headword, (row.get("pos") or "noun").strip().lower(), definition_en.lower())
                    duplicate_key_map.setdefault(key, set()).add(definition_ko.lower())

    conflicting_translation_keys = sum(1 for variants in duplicate_key_map.values() if len(variants) >= 3)

    top_words = load_top_words(top_n)
    top_word_set = set(top_words)
    known_words = headword_set.union(lemma_set)
    missing_top_words = sorted(word for word in top_word_set if word not in known_words)

    english_quality = {
        "uniqueHeadwords": len(headword_set),
        "uniqueLemmas": len(lemma_set),
        "missingDefinitionEnRows": missing_definition_en,
        "definitionEnCompletenessRate": round((1 - (missing_definition_en / max(1, row_count))) * 100, 2),
        "topWordCoverage": {
            "topN": top_n,
            "wordfreqAvailable": len(top_words) > 0,
            "covered": len(top_word_set.intersection(known_words)) if top_words else None,
            "missing": len(missing_top_words) if top_words else None,
            "missingSamples": missing_top_words[:40] if top_words else [],
        },
    }

    english_korean_quality = {
        "koExamplePolicy": "required" if ko_example_required else "optional",
        "missingDefinitionKoRows": missing_definition_ko,
        "definitionKoCompletenessRate": round((1 - (missing_definition_ko / max(1, row_count))) * 100, 2),
        "missingExampleKoRows": missing_example_ko,
        "exampleKoCompletenessRate": round((1 - (missing_example_ko / max(1, row_count))) * 100, 2),
        "headwordKoCoverageRate": round((len(ko_covered_headwords) / max(1, len(headword_set))) * 100, 2),
        "suspiciousKoAsciiOnlyRows": suspicious_ko_ascii_only,
        "suspiciousEnHasHangulRows": suspicious_en_has_hangul,
        "conflictingTranslationKeys": conflicting_translation_keys,
    }

    flags = []
    if english_korean_quality["definitionKoCompletenessRate"] < 90:
        flags.append("korean definition completeness under 90%")
    if english_quality["definitionEnCompletenessRate"] < 95:
        flags.append("english definition completeness under 95%")
    if ko_example_required and english_korean_quality["exampleKoCompletenessRate"] < 70:
        flags.append("korean example completeness under 70% (required policy)")
    if suspicious_ko_ascii_only > 0:
        flags.append("some korean definitions look untranslated (ASCII-only)")
    if conflicting_translation_keys > 0:
        flags.append("some identical english senses map to many different korean definitions")

    return {
        "csvFiles": [str(path) for path in csv_paths],
        "rowCount": row_count,
        "english": english_quality,
        "englishKorean": english_korean_quality,
        "flags": flags,
    }


def load_seed_meta(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


def build_recommendations(report: dict) -> list[str]:
    recommendations: list[str] = []
    perf_flags = report.get("performance", {}).get("flags", [])
    quality_flags = report.get("quality", {}).get("flags", [])

    if any("prefix LIKE query causes full scan" in flag for flag in perf_flags):
        recommendations.append("Use index-friendly prefix range query instead of LIKE prefix for large dictionary tables.")
    if any("korean definition completeness under 90%" in flag for flag in quality_flags):
        recommendations.append("Increase Korean definition augmentation coverage (Kaikki translations + Tatoeba lexical backfill).")
    if any("korean example completeness under 70%" in flag for flag in quality_flags):
        recommendations.append("Increase Korean example augmentation only if your product policy requires examples.")
    if any("some korean definitions look untranslated" in flag for flag in quality_flags):
        recommendations.append("Filter or reprocess rows where definitionKo is ASCII-only to reduce untranslated Korean entries.")
    if any("identical english senses map to many different korean definitions" in flag for flag in quality_flags):
        recommendations.append("Add translation-consistency normalization for duplicated EN senses with divergent KO labels.")

    if not recommendations:
        recommendations.append("Current dictionary pipeline and DB shape are healthy for now; monitor metrics as entries grow.")

    return recommendations


def main() -> int:
    args = parse_args()

    db_separation = analyze_db_separation(args.dictionary_db_kt, args.user_db_kt)
    performance = analyze_performance(args.dictionary_db)
    quality = analyze_data_quality(args.raw_dir, args.top_n, args.ko_example_required)
    seed_meta = load_seed_meta(args.seed_meta)

    report = {
        "dbSeparation": db_separation,
        "performance": performance,
        "quality": quality,
        "seedMeta": seed_meta,
    }
    report["recommendations"] = build_recommendations(report)

    args.report_json.parent.mkdir(parents=True, exist_ok=True)
    args.report_json.write_text(
        json.dumps(report, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(f"Wrote audit report: {args.report_json}")
    print(f"DB separated: {db_separation['isSeparated']}")
    print(f"Performance flags: {len(performance.get('flags', []))}")
    print(f"Quality flags: {len(quality.get('flags', []))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
