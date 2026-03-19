# Dictionary ETL

This folder contains a minimal ETL pipeline for generating offline dictionary assets.

## Input

- `raw/*.csv` (all CSV files are merged)
- Required columns:
  - `headword, lemma, pos, ipa, frequencyRank, definitionEn, definitionKo, exampleEn, exampleKo, source, license`
- Only allowlisted licenses are included during build:
  - `CC0-1.0`, `CC-BY-2.0`, `CC-BY-SA-3.0`, `Public-Domain`, `MIT`, `BSD-3-Clause`, `Apache-2.0`, `WordNet-3.0`, `internal-dev`
  - Mixed licenses can be represented with `+` (example: `WordNet-3.0+CC-BY-2.0`)

## Output

- `app/src/main/assets/databases/dictionary.db` (prebuilt dictionary Room DB)

## Run

```bash
# One-shot (collect from web + build app asset)
# Note: EN-KO policy defaults to Korean definitions required, Korean examples optional.
python tools/dictionary_etl/build_open_dictionary_assets.py \
  --max-wordnet-words 0 \
  --max-kaikki-words 0

# Optional: enable Korean example augmentation from Tatoeba
python tools/dictionary_etl/build_open_dictionary_assets.py \
  --max-wordnet-words 0 \
  --max-kaikki-words 0 \
  --enable-tatoeba-augment

# Optional: skip the post-build audit report
python tools/dictionary_etl/build_open_dictionary_assets.py \
  --max-wordnet-words 0 \
  --max-kaikki-words 0 \
  --skip-audit

# Or run in two steps:
# 1) Collect open web dictionary sources (WordNet + Kaikki/Wiktionary extract)
python tools/dictionary_etl/collect_open_dictionary_data.py \
  --output-csv tools/dictionary_etl/raw/open_web_dictionary.csv \
  --max-wordnet-words 0 \
  --max-kaikki-words 0 \
  --augment-tatoeba-example-ko \
  --augment-tatoeba-lexicon-ko

# 1.5) Optional: supplement missing top-frequency English words from Kaikki cache
# (requires: pip install --user wordfreq)
python tools/dictionary_etl/generate_top_coverage_supplement.py \
  --top-n 10000 \
  --cache-gz tools/dictionary_etl/cache/kaikki-english-words.jsonl.gz \
  --output-csv tools/dictionary_etl/raw/top_coverage_supplement.csv

# 2) Build app asset JSON from raw CSV files
python tools/dictionary_etl/build_dictionary_assets.py

# 3) Run quality/performance/separation audit report
python tools/dictionary_etl/audit_dictionary_assets.py \
  --top-n 10000

# Optional: treat Korean examples as required in quality gates
python tools/dictionary_etl/audit_dictionary_assets.py \
  --top-n 10000 \
  --ko-example-required
```

The prebuilt SQLite DB is used by `DictionaryDatabase` as the only runtime dictionary source.
Runtime reseeding from JSON is intentionally removed to keep startup predictable.
The audit report is written to `tools/dictionary_etl/reports/dictionary_audit_report.json`.

`build_dictionary_assets.py` merges every CSV in `tools/dictionary_etl/raw/`.

## Notes for Commercial Use

- Keep source + license metadata for every row.
- Do not import proprietary dictionary data without redistribution rights.
- Include attribution assets when a source license requires it.
