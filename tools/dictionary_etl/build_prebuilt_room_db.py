import argparse
import json
import re
import sqlite3
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build prepackaged Room SQLite DB from dictionary_seed.json"
    )
    parser.add_argument(
        "--input-json",
        type=Path,
        default=Path("app/src/main/assets/dictionary_seed.json"),
    )
    parser.add_argument(
        "--output-db",
        type=Path,
        default=Path("app/src/main/assets/databases/dictionary.db"),
    )
    parser.add_argument(
        "--room-version",
        type=int,
        default=1,
    )
    parser.add_argument(
        "--room-identity-hash",
        type=str,
        default="5f7d0506c80df6f66e208e74cfe29c85",
        help="Room identity hash for dictionary schema.",
    )
    return parser.parse_args()


def create_schema(connection: sqlite3.Connection) -> None:
    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS dictionary_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            headword TEXT NOT NULL,
            lemma TEXT NOT NULL,
            pos TEXT NOT NULL,
            ipa TEXT,
            frequencyRank INTEGER,
            source TEXT NOT NULL,
            license TEXT NOT NULL
        )
        """
    )
    connection.execute(
        "CREATE INDEX IF NOT EXISTS index_dictionary_entries_headword ON dictionary_entries(headword)"
    )
    connection.execute(
        "CREATE INDEX IF NOT EXISTS index_dictionary_entries_lemma ON dictionary_entries(lemma)"
    )

    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS dictionary_senses (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            entryId INTEGER NOT NULL,
            senseIndex INTEGER NOT NULL,
            definitionEn TEXT NOT NULL,
            definitionKo TEXT NOT NULL,
            exampleEn TEXT,
            exampleKo TEXT,
            FOREIGN KEY(entryId) REFERENCES dictionary_entries(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """
    )
    connection.execute(
        "CREATE INDEX IF NOT EXISTS index_dictionary_senses_entryId ON dictionary_senses(entryId)"
    )

    connection.execute(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS dictionary_senses_fts USING FTS4(
            entryId INTEGER NOT NULL,
            headword TEXT NOT NULL,
            lemma TEXT NOT NULL,
            pos TEXT NOT NULL,
            definitionEn TEXT NOT NULL,
            definitionKo TEXT NOT NULL
        )
        """
    )

    connection.execute(
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
    )


def infer_room_identity_hash(default_hash: str) -> str:
    impl_path = Path(
        "app/build/generated/ksp/debug/java/com/example/bookhelper/data/local/DictionaryDatabase_Impl.java"
    )
    if not impl_path.exists():
        return default_hash

    content = impl_path.read_text(encoding="utf-8", errors="ignore")
    match = re.search(r"INSERT OR REPLACE INTO room_master_table \(id,identity_hash\) VALUES\(42, '([0-9a-f]+)'\)", content)
    if match:
        return match.group(1)
    return default_hash


def build_prebuilt_db(
    input_json: Path,
    output_db: Path,
    room_version: int,
    room_identity_hash: str,
) -> tuple[int, int]:
    payload = json.loads(input_json.read_text(encoding="utf-8"))

    output_db.parent.mkdir(parents=True, exist_ok=True)
    if output_db.exists():
        output_db.unlink()

    connection = sqlite3.connect(str(output_db))
    try:
        connection.execute("PRAGMA journal_mode=OFF")
        connection.execute("PRAGMA synchronous=OFF")
        connection.execute("PRAGMA temp_store=MEMORY")
        connection.execute("PRAGMA foreign_keys=OFF")
        connection.execute(f"PRAGMA user_version={room_version}")
        create_schema(connection)

        entry_count = 0
        sense_count = 0
        fts_row_id = 1

        with connection:
            for entry in payload:
                cursor = connection.execute(
                    """
                    INSERT INTO dictionary_entries(headword, lemma, pos, ipa, frequencyRank, source, license)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        entry.get("headword", ""),
                        entry.get("lemma", ""),
                        entry.get("pos", "noun"),
                        entry.get("ipa"),
                        entry.get("frequencyRank"),
                        entry.get("source", "seed"),
                        entry.get("license", "unknown"),
                    ),
                )
                if cursor.lastrowid is None:
                    raise RuntimeError("Failed to retrieve inserted entry row id")
                entry_id = int(cursor.lastrowid)
                entry_count += 1

                for sense_index, sense in enumerate(entry.get("senses", [])):
                    definition_en = sense.get("definitionEn", "")
                    definition_ko = sense.get("definitionKo", "")
                    example_en = sense.get("exampleEn")
                    example_ko = sense.get("exampleKo")

                    connection.execute(
                        """
                        INSERT INTO dictionary_senses(entryId, senseIndex, definitionEn, definitionKo, exampleEn, exampleKo)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        (
                            entry_id,
                            sense_index,
                            definition_en,
                            definition_ko,
                            example_en,
                            example_ko,
                        ),
                    )
                    connection.execute(
                        """
                        INSERT INTO dictionary_senses_fts(rowid, entryId, headword, lemma, pos, definitionEn, definitionKo)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            fts_row_id,
                            entry_id,
                            entry.get("headword", ""),
                            entry.get("lemma", ""),
                            entry.get("pos", "noun"),
                            definition_en,
                            definition_ko,
                        ),
                    )
                    fts_row_id += 1
                    sense_count += 1

            connection.execute(
                "INSERT OR REPLACE INTO room_master_table(id, identity_hash) VALUES(42, ?)",
                (room_identity_hash,),
            )

        connection.execute("PRAGMA optimize")
        return entry_count, sense_count
    finally:
        connection.close()


def main() -> int:
    args = parse_args()
    identity_hash = infer_room_identity_hash(args.room_identity_hash)
    entries, senses = build_prebuilt_db(
        args.input_json,
        args.output_db,
        args.room_version,
        identity_hash,
    )
    print(f"Wrote prebuilt DB: {args.output_db}")
    print(f"Entries: {entries}")
    print(f"Senses: {senses}")
    print(f"Room identity hash: {identity_hash}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
