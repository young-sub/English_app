import argparse
import subprocess
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Collect open dictionary data and build generated seed data plus app DB asset."
    )
    parser.add_argument("--max-wordnet-words", type=int, default=0)
    parser.add_argument("--max-kaikki-words", type=int, default=0)
    parser.add_argument("--max-kaikki-senses-per-word", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument(
        "--enable-tatoeba-augment",
        action="store_true",
        help="Enable Korean example augmentation from Tatoeba (default: disabled).",
    )
    parser.add_argument(
        "--disable-tatoeba-augment",
        action="store_true",
        help="Deprecated compatibility flag; keeps Tatoeba example augmentation disabled.",
    )
    parser.add_argument("--disable-tatoeba-lexicon", action="store_true")
    parser.add_argument("--tatoeba-max-pairs-per-token", type=int, default=3)
    parser.add_argument("--tatoeba-lexicon-max-rows", type=int, default=30000)
    parser.add_argument("--exclude-kaikki-without-ko", action="store_true")
    parser.add_argument("--exclude-inflected-forms", action="store_true")
    parser.add_argument(
        "--output-csv",
        type=Path,
        default=Path("tools/dictionary_etl/raw/open_web_dictionary.csv"),
    )
    parser.add_argument(
        "--skip-audit",
        action="store_true",
        help="Skip post-build dictionary audit report generation.",
    )
    parser.add_argument(
        "--audit-top-n",
        type=int,
        default=10000,
        help="Top-N English coverage target for audit report.",
    )
    return parser.parse_args()


def run(command: list[str], cwd: Path) -> None:
    subprocess.run(command, cwd=cwd, check=True)


def main() -> int:
    args = parse_args()
    repo_root = Path(__file__).resolve().parents[2]
    collector = repo_root / "tools" / "dictionary_etl" / "collect_open_dictionary_data.py"
    builder = repo_root / "tools" / "dictionary_etl" / "build_dictionary_assets.py"
    prebuilt_builder = repo_root / "tools" / "dictionary_etl" / "build_prebuilt_room_db.py"
    auditor = repo_root / "tools" / "dictionary_etl" / "audit_dictionary_assets.py"

    command = [
        sys.executable,
        str(collector),
        "--output-csv",
        str(args.output_csv),
        "--max-wordnet-words",
        str(args.max_wordnet_words),
        "--max-kaikki-words",
        str(args.max_kaikki_words),
        "--max-kaikki-senses-per-word",
        str(args.max_kaikki_senses_per_word),
        "--timeout",
        str(args.timeout),
    ]
    if args.exclude_kaikki_without_ko:
        command.append("--exclude-kaikki-without-ko")
    if args.exclude_inflected_forms:
        command.append("--exclude-inflected-forms")
    enable_tatoeba_augment = args.enable_tatoeba_augment and not args.disable_tatoeba_augment
    if enable_tatoeba_augment:
        command.append("--augment-tatoeba-example-ko")
        command.extend(["--tatoeba-max-pairs-per-token", str(args.tatoeba_max_pairs_per_token)])
    if not args.disable_tatoeba_lexicon:
        command.append("--augment-tatoeba-lexicon-ko")
        command.extend(["--tatoeba-lexicon-max-rows", str(args.tatoeba_lexicon_max_rows)])

    run(command, cwd=repo_root)
    run([sys.executable, str(builder)], cwd=repo_root)
    run([sys.executable, str(prebuilt_builder)], cwd=repo_root)
    if not args.skip_audit:
        run(
            [
                sys.executable,
                str(auditor),
                "--top-n",
                str(args.audit_top_n),
            ],
            cwd=repo_root,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
