import csv
import hashlib
import json
from pathlib import Path


ALLOWED_LICENSES = {
    "cc0-1.0",
    "cc-by-2.0",
    "cc-by-sa-3.0",
    "public-domain",
    "mit",
    "bsd-3-clause",
    "apache-2.0",
    "wordnet-3.0",
    "internal-dev",
}


def is_allowed_license(license_name: str) -> bool:
    normalized_parts = [part.strip().lower() for part in license_name.split("+") if part.strip()]
    if not normalized_parts:
        return False
    return all(part in ALLOWED_LICENSES for part in normalized_parts)


def build(raw_dir: Path, output_json: Path) -> None:
    grouped = {}
    input_csvs = sorted(raw_dir.glob("*.csv"))
    if not input_csvs:
        raise FileNotFoundError(f"No CSV files found in {raw_dir}")

    for input_csv in input_csvs:
        with input_csv.open("r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                license_name = (row.get("license") or "internal-dev").strip() or "internal-dev"
                if not is_allowed_license(license_name):
                    continue

                key = (row["headword"].strip(), row["lemma"].strip(), row["pos"].strip())
                if key not in grouped:
                    grouped[key] = {
                        "headword": row["headword"].strip(),
                        "lemma": row["lemma"].strip(),
                        "pos": row["pos"].strip(),
                        "ipa": row["ipa"].strip(),
                        "frequencyRank": int((row.get("frequencyRank") or "0") or 0),
                        "source": (row.get("source") or "seed-csv").strip() or "seed-csv",
                        "license": license_name,
                        "senses": [],
                    }

                grouped[key]["senses"].append(
                    {
                        "definitionEn": row["definitionEn"].strip(),
                        "definitionKo": row["definitionKo"].strip(),
                        "exampleEn": row["exampleEn"].strip(),
                        "exampleKo": row["exampleKo"].strip(),
                    }
                )

    normalized_payload = []
    for entry in grouped.values():
        unique_senses = []
        seen = set()
        for sense in entry["senses"]:
            key = (sense["definitionEn"], sense["definitionKo"], sense["exampleEn"], sense["exampleKo"])
            if key in seen:
                continue
            seen.add(key)
            unique_senses.append(sense)

        if not unique_senses:
            continue

        entry["frequencyRank"] = entry["frequencyRank"] or 20000
        entry["senses"] = unique_senses
        normalized_payload.append(entry)

    normalized_payload.sort(key=lambda item: (item.get("frequencyRank") or 20000, item["headword"]))

    output_json.parent.mkdir(parents=True, exist_ok=True)
    with output_json.open("w", encoding="utf-8") as f:
        json.dump(normalized_payload, f, ensure_ascii=False, indent=2)

    payload_bytes = json.dumps(normalized_payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    seed_version = hashlib.sha256(payload_bytes).hexdigest()[:16]
    sense_count = sum(len(item.get("senses", [])) for item in normalized_payload)
    source_counts = {}
    for item in normalized_payload:
        source = (item.get("source") or "unknown").strip() or "unknown"
        source_counts[source] = source_counts.get(source, 0) + 1

    meta = {
        "seedVersion": seed_version,
        "entryCount": len(normalized_payload),
        "senseCount": sense_count,
        "sourceCounts": source_counts,
    }
    meta_path = output_json.with_name("dictionary_seed.meta.json")
    with meta_path.open("w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    repo_root = Path(__file__).resolve().parents[2]
    raw_dir = repo_root / "tools" / "dictionary_etl" / "raw"
    output_json = repo_root / "app" / "src" / "main" / "assets" / "dictionary_seed.json"
    build(raw_dir, output_json)
    print(f"Wrote {output_json}")
