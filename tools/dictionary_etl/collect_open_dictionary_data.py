import argparse
import bz2
import csv
import gzip
import importlib
import json
import re
import sys
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Set, Tuple, TypedDict


KAIKKI_ENGLISH_JSONL_URL = "https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl"
KAIKKI_ENGLISH_GZ_URL = "https://kaikki.org/dictionary/English/words/kaikki.org-dictionary-English-words.jsonl.gz"
TATOEBA_ENG_SENTENCES_URL = "https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2"
TATOEBA_KOR_SENTENCES_URL = "https://downloads.tatoeba.org/exports/per_language/kor/kor_sentences.tsv.bz2"
TATOEBA_ENG_KOR_LINKS_URL = "https://downloads.tatoeba.org/exports/per_language/eng/eng-kor_links.tsv.bz2"
CACHE_DIR = Path("tools/dictionary_etl/cache")
KAIKKI_CACHE_GZ = CACHE_DIR / "kaikki-english-words.jsonl.gz"


@dataclass(frozen=True)
class CsvRow:
    headword: str
    lemma: str
    pos: str
    ipa: str
    frequency_rank: int
    definition_en: str
    definition_ko: str
    example_en: str
    example_ko: str
    source: str
    license_name: str


class WordnetBucket(TypedDict):
    best_count: int
    senses: List[Tuple[str, str]]


def is_limit_reached(limit: int, count: int) -> bool:
    return limit > 0 and count >= limit


def ensure_kaikki_gz_cached(timeout: int, attempts: int) -> Path:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    final_path = KAIKKI_CACHE_GZ
    part_path = final_path.with_suffix(final_path.suffix + ".part")

    existing_valid = final_path.exists() and final_path.stat().st_size > 100_000_000
    if existing_valid:
        return final_path

    if final_path.exists():
        final_path.unlink()

    last_error: Optional[Exception] = None
    expected_total: Optional[int] = None

    for _ in range(attempts):
        downloaded = part_path.stat().st_size if part_path.exists() else 0
        headers = {"User-Agent": "english-app-dictionary-etl/1.0"}
        if downloaded > 0:
            headers["Range"] = f"bytes={downloaded}-"

        request = urllib.request.Request(KAIKKI_ENGLISH_GZ_URL, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                content_range = response.getheader("Content-Range")
                content_length = response.getheader("Content-Length")

                if content_range and "/" in content_range:
                    expected_total = int(content_range.split("/")[-1])
                elif content_length and expected_total is None:
                    expected_total = int(content_length) + downloaded

                mode = "ab" if downloaded > 0 and response.status == 206 else "wb"
                if mode == "wb":
                    downloaded = 0

                with part_path.open(mode) as output:
                    while True:
                        chunk = response.read(1024 * 1024)
                        if not chunk:
                            break
                        output.write(chunk)
        except Exception as exc:
            last_error = exc
            continue

        current_size = part_path.stat().st_size if part_path.exists() else 0
        if expected_total is not None and current_size < expected_total:
            continue

        part_path.replace(final_path)
        return final_path

    if last_error is not None:
        raise last_error
    raise RuntimeError("Failed to download Kaikki cache")


def normalize_word(value: str) -> str:
    lowered = value.strip().lower()
    lowered = lowered.replace("_", " ")
    lowered = re.sub(r"\s+", " ", lowered)
    return lowered


def normalize_pos(value: Optional[str]) -> str:
    if not value:
        return "noun"
    pos = value.strip().lower()
    if pos in {"n", "noun"}:
        return "noun"
    if pos in {"v", "verb"}:
        return "verb"
    if pos in {"a", "adj", "adjective", "s"}:
        return "adjective"
    if pos in {"r", "adv", "adverb"}:
        return "adverb"
    return pos


def clean_text(value: Optional[str]) -> str:
    if not value:
        return ""
    text = value.strip()
    text = re.sub(r"\s+", " ", text)
    return text


def truncate_text(value: str, max_chars: int) -> str:
    if len(value) <= max_chars:
        return value
    return value[: max_chars - 3].rstrip() + "..."


def choose_ipa(sounds: object) -> str:
    if not isinstance(sounds, list):
        return ""
    for sound in sounds:
        if isinstance(sound, dict):
            ipa = clean_text(sound.get("ipa"))
            if ipa:
                return ipa
    return ""


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
                        return truncate_text(text, 320)
    return ""


def choose_example(sense: object) -> str:
    if not isinstance(sense, dict):
        return ""
    examples = sense.get("examples")
    if isinstance(examples, list):
        for example in examples:
            if isinstance(example, dict):
                text = clean_text(example.get("text"))
                if text:
                    return truncate_text(text, 220)
            elif isinstance(example, str):
                text = clean_text(example)
                if text:
                    return truncate_text(text, 220)
    return ""


def extract_ko_words(translations: object) -> List[str]:
    if not isinstance(translations, list):
        return []

    result: List[str] = []
    seen: Set[str] = set()
    for item in translations:
        if not isinstance(item, dict):
            continue
        code = clean_text(item.get("code") or item.get("lang_code"))
        if code != "ko":
            continue
        word = clean_text(item.get("word"))
        if not word:
            continue
        key = word.lower()
        if key in seen:
            continue
        seen.add(key)
        result.append(word)
    return result


def extract_form_words(forms: object) -> List[str]:
    if not isinstance(forms, list):
        return []

    allowed_tags = {
        "plural",
        "simple past",
        "past participle",
        "present participle",
        "third-person singular",
        "comparative",
        "superlative",
    }
    result: List[str] = []
    seen: Set[str] = set()
    for item in forms:
        if not isinstance(item, dict):
            continue
        tags_raw = item.get("tags")
        tags = set()
        if isinstance(tags_raw, list):
            tags = {clean_text(str(tag)).lower() for tag in tags_raw if clean_text(str(tag))}
        if tags and tags.isdisjoint(allowed_tags):
            continue
        form = normalize_word(clean_text(item.get("form")))
        if not form or not re.match(r"^[a-z][a-z\- ']*$", form):
            continue
        if form in seen:
            continue
        seen.add(form)
        result.append(form)
    return result


def download_bz2_lines(url: str, timeout: int) -> List[str]:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "english-app-dictionary-etl/1.0"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = response.read()
    text = bz2.decompress(payload).decode("utf-8", errors="ignore")
    return text.splitlines()


def tokenize_english(text: str) -> List[str]:
    return [token for token in re.findall(r"[a-z']+", text.lower()) if len(token) >= 3]


def english_overlap_ratio(a: str, b: str) -> float:
    stopwords = {
        "the",
        "and",
        "for",
        "with",
        "that",
        "this",
        "from",
        "have",
        "has",
        "had",
        "was",
        "were",
        "are",
        "you",
        "your",
        "his",
        "her",
        "its",
        "our",
        "their",
        "they",
        "them",
        "she",
        "him",
        "who",
        "what",
        "when",
        "where",
        "why",
        "how",
        "into",
        "over",
        "under",
        "than",
    }
    left = {token for token in tokenize_english(a) if token not in stopwords}
    right = {token for token in tokenize_english(b) if token not in stopwords}
    if not left or not right:
        return 0.0
    intersection = left.intersection(right)
    return len(intersection) / max(1, min(len(left), len(right)))


def append_source_tag(source: str, extra: str) -> str:
    parts = [segment.strip() for segment in source.split("+") if segment.strip()]
    if extra not in parts:
        parts.append(extra)
    return "+".join(parts)


def merge_license(existing: str, extra: str) -> str:
    parts = [segment.strip() for segment in existing.split("+") if segment.strip()]
    if extra not in parts:
        parts.append(extra)
    return "+".join(parts)


def load_tatoeba_pair_maps(timeout: int) -> Tuple[Dict[str, List[str]], Dict[str, str], Dict[str, str]]:
    link_lines = download_bz2_lines(TATOEBA_ENG_KOR_LINKS_URL, timeout=timeout)
    eng_to_kor_ids: Dict[str, List[str]] = {}
    eng_ids: Set[str] = set()
    kor_ids: Set[str] = set()

    for line in link_lines:
        columns = line.split("\t")
        if len(columns) < 2:
            continue
        eng_id = columns[0].strip()
        kor_id = columns[1].strip()
        if not eng_id or not kor_id:
            continue
        eng_to_kor_ids.setdefault(eng_id, []).append(kor_id)
        eng_ids.add(eng_id)
        kor_ids.add(kor_id)

    eng_by_id: Dict[str, str] = {}
    for line in download_bz2_lines(TATOEBA_ENG_SENTENCES_URL, timeout=timeout):
        columns = line.split("\t", 2)
        if len(columns) < 3:
            continue
        sentence_id = columns[0].strip()
        if sentence_id not in eng_ids:
            continue
        sentence = truncate_text(clean_text(columns[2]), 220)
        if sentence:
            eng_by_id[sentence_id] = sentence

    kor_by_id: Dict[str, str] = {}
    for line in download_bz2_lines(TATOEBA_KOR_SENTENCES_URL, timeout=timeout):
        columns = line.split("\t", 2)
        if len(columns) < 3:
            continue
        sentence_id = columns[0].strip()
        if sentence_id not in kor_ids:
            continue
        sentence = truncate_text(clean_text(columns[2]), 220)
        if sentence:
            kor_by_id[sentence_id] = sentence

    return eng_to_kor_ids, eng_by_id, kor_by_id


def load_tatoeba_index(timeout: int, max_pairs_per_token: int) -> Dict[str, List[Tuple[str, str]]]:
    eng_to_kor_ids, eng_by_id, kor_by_id = load_tatoeba_pair_maps(timeout=timeout)

    index: Dict[str, List[Tuple[str, str]]] = {}
    for eng_id, ko_ids in eng_to_kor_ids.items():
        eng_sentence = eng_by_id.get(eng_id)
        if not eng_sentence:
            continue
        for ko_id in ko_ids:
            ko_sentence = kor_by_id.get(ko_id)
            if not ko_sentence:
                continue
            pair = (eng_sentence, ko_sentence)
            for token in set(tokenize_english(eng_sentence)):
                bucket = index.setdefault(token, [])
                if pair in bucket:
                    continue
                if len(bucket) >= max_pairs_per_token:
                    continue
                bucket.append(pair)

    return index


def collect_tatoeba_lexicon_rows(timeout: int, max_rows: int) -> List[CsvRow]:
    eng_to_kor_ids, eng_by_id, kor_by_id = load_tatoeba_pair_maps(timeout=timeout)

    ko_candidates: Dict[str, Dict[str, int]] = {}
    for eng_id, ko_ids in eng_to_kor_ids.items():
        eng_sentence = eng_by_id.get(eng_id)
        if not eng_sentence:
            continue

        tokens = tokenize_english(eng_sentence)
        if len(tokens) != 1:
            continue
        token = tokens[0]
        if not re.match(r"^[a-z][a-z\-']*$", token):
            continue

        for ko_id in ko_ids:
            ko_sentence = kor_by_id.get(ko_id)
            if not ko_sentence:
                continue
            if len(ko_sentence) > 32:
                continue
            if ko_sentence.count(" ") > 2:
                continue
            bucket = ko_candidates.setdefault(token, {})
            bucket[ko_sentence] = bucket.get(ko_sentence, 0) + 1

    rows: List[CsvRow] = []
    for word, candidates in sorted(ko_candidates.items()):
        if not candidates:
            continue
        best_ko, count = max(candidates.items(), key=lambda item: item[1])
        if count < 2:
            continue

        rows.append(
            CsvRow(
                headword=word,
                lemma=word,
                pos="unknown",
                ipa="",
                frequency_rank=70000,
                definition_en="bilingual lexical translation",
                definition_ko=best_ko,
                example_en="",
                example_ko="",
                source="tatoeba-lexicon-ko",
                license_name="CC-BY-2.0",
            )
        )
        if is_limit_reached(max_rows, len(rows)):
            break

    return rows


def augment_rows_with_tatoeba_examples(
    rows: List[CsvRow],
    timeout: int,
    max_pairs_per_token: int,
) -> Tuple[List[CsvRow], int]:
    try:
        tatoeba_index = load_tatoeba_index(timeout=timeout, max_pairs_per_token=max_pairs_per_token)
    except Exception:
        return rows, 0

    updated_rows: List[CsvRow] = []
    filled_count = 0

    for row in rows:
        if row.example_ko:
            updated_rows.append(row)
            continue

        token_candidates = []
        for candidate in (row.lemma, row.headword):
            token = normalize_word(candidate)
            if token and token not in token_candidates:
                token_candidates.append(token)

        pair: Optional[Tuple[str, str]] = None
        for token in token_candidates:
            options = tatoeba_index.get(token)
            if options:
                if row.example_en:
                    best_pair = max(options, key=lambda candidate: english_overlap_ratio(row.example_en, candidate[0]))
                    if english_overlap_ratio(row.example_en, best_pair[0]) < 0.45:
                        continue
                    pair = best_pair
                else:
                    pair = options[0]
                break

        if not pair:
            updated_rows.append(row)
            continue

        example_en = pair[0]
        example_ko = pair[1]
        updated_rows.append(
            CsvRow(
                headword=row.headword,
                lemma=row.lemma,
                pos=row.pos,
                ipa=row.ipa,
                frequency_rank=row.frequency_rank,
                definition_en=row.definition_en,
                definition_ko=row.definition_ko,
                example_en=example_en,
                example_ko=example_ko,
                source=append_source_tag(row.source, "tatoeba-eng-kor"),
                license_name=merge_license(row.license_name, "CC-BY-2.0"),
            )
        )
        filled_count += 1

    return updated_rows, filled_count


def _collect_kaikki_rows_once(
    max_words: int,
    max_senses_per_word: int,
    timeout: int,
    include_entries_without_ko: bool,
    include_inflected_forms: bool,
    seen_word_pos_initial: Optional[Set[Tuple[str, str]]] = None,
) -> Tuple[List[CsvRow], Set[Tuple[str, str]], bool]:
    rows: List[CsvRow] = []
    seen_word_pos: Set[Tuple[str, str]] = set(seen_word_pos_initial or set())
    completed_stream = True

    cache_path = ensure_kaikki_gz_cached(timeout=timeout, attempts=4)
    with gzip.open(cache_path, "rb") as line_stream:
        while True:
            try:
                line = line_stream.readline()
            except EOFError:
                completed_stream = False
                break

            if not line:
                break

            item = json.loads(line)

            if item.get("lang_code") != "en":
                continue
            word = normalize_word(str(item.get("word") or ""))
            if not word or not re.match(r"^[a-z][a-z\- '\\.]*$", word):
                continue

            pos = normalize_pos(item.get("pos"))
            key = (word, pos)
            if key in seen_word_pos:
                continue

            translations_top = extract_ko_words(item.get("translations"))

            senses = item.get("senses")
            if not isinstance(senses, list) or not senses:
                continue

            ipa = choose_ipa(item.get("sounds"))
            used_senses = 0
            for sense in senses:
                definition_en = choose_gloss(sense)
                if not definition_en:
                    continue
                example_en = choose_example(sense)
                translations_sense = extract_ko_words(
                    sense.get("translations") if isinstance(sense, dict) else None
                )
                ko_words = translations_sense or translations_top
                if not include_entries_without_ko and not ko_words:
                    continue
                definition_ko = ", ".join(ko_words[:3])
                rows.append(
                    CsvRow(
                        headword=word,
                        lemma=word,
                        pos=pos,
                        ipa=ipa,
                        frequency_rank=0,
                        definition_en=definition_en,
                        definition_ko=definition_ko,
                        example_en=example_en,
                        example_ko="",
                        source="kaikki-wiktionary-en",
                        license_name="CC-BY-SA-3.0",
                    )
                )
                used_senses += 1
                if used_senses >= max_senses_per_word:
                    break

            if include_inflected_forms and used_senses > 0:
                form_words = extract_form_words(item.get("forms"))
                if form_words:
                    first_sense = next(
                        (r for r in rows if r.lemma == word and r.pos == pos and r.source == "kaikki-wiktionary-en"),
                        None,
                    )
                    if first_sense is not None:
                        for form_word in form_words[:6]:
                            if form_word == word:
                                continue
                            rows.append(
                                CsvRow(
                                    headword=form_word,
                                    lemma=word,
                                    pos=pos,
                                    ipa=ipa,
                                    frequency_rank=0,
                                    definition_en=first_sense.definition_en,
                                    definition_ko=first_sense.definition_ko,
                                    example_en=first_sense.example_en,
                                    example_ko="",
                                    source="kaikki-wiktionary-en",
                                    license_name="CC-BY-SA-3.0",
                                )
                            )

            if used_senses == 0 and translations_top:
                rows.append(
                    CsvRow(
                        headword=word,
                        lemma=word,
                        pos=pos,
                        ipa=ipa,
                        frequency_rank=50000,
                        definition_en="translation entry",
                        definition_ko=", ".join(translations_top[:3]),
                        example_en="",
                        example_ko="",
                        source="kaikki-wiktionary-en-ko-only",
                        license_name="CC-BY-SA-3.0",
                    )
                )
                used_senses = 1

            if used_senses == 0:
                continue

            seen_word_pos.add(key)
            if is_limit_reached(max_words, len(seen_word_pos)):
                return rows, seen_word_pos, completed_stream
    return rows, seen_word_pos, completed_stream


def collect_kaikki_rows(
    max_words: int,
    max_senses_per_word: int,
    timeout: int,
    include_entries_without_ko: bool,
    include_inflected_forms: bool,
) -> List[CsvRow]:
    rows, _, _ = _collect_kaikki_rows_once(
        max_words=max_words,
        max_senses_per_word=max_senses_per_word,
        timeout=timeout,
        include_entries_without_ko=include_entries_without_ko,
        include_inflected_forms=include_inflected_forms,
        seen_word_pos_initial=None,
    )
    return rows


def collect_kaikki_rows_with_retry(
    max_words: int,
    max_senses_per_word: int,
    timeout: int,
    include_entries_without_ko: bool,
    include_inflected_forms: bool,
    attempts: int = 3,
) -> List[CsvRow]:
    combined_rows: List[CsvRow] = []
    seen_word_pos: Set[Tuple[str, str]] = set()
    last_error: Optional[Exception] = None

    for attempt in range(1, attempts + 1):
        try:
            rows, seen_once, completed = _collect_kaikki_rows_once(
                max_words=max_words,
                max_senses_per_word=max_senses_per_word,
                timeout=timeout,
                include_entries_without_ko=include_entries_without_ko,
                include_inflected_forms=include_inflected_forms,
                seen_word_pos_initial=seen_word_pos,
            )
        except (EOFError, json.JSONDecodeError, OSError) as exc:
            last_error = exc
            if KAIKKI_CACHE_GZ.exists():
                KAIKKI_CACHE_GZ.unlink()
            part_path = KAIKKI_CACHE_GZ.with_suffix(KAIKKI_CACHE_GZ.suffix + ".part")
            if part_path.exists():
                part_path.unlink()
            print(f"Kaikki parse failed (attempt {attempt}/{attempts}), refreshing cache...", flush=True)
            continue

        combined_rows.extend(rows)
        seen_word_pos = seen_once

        if completed or is_limit_reached(max_words, len(seen_word_pos)):
            break

        print(f"Kaikki stream interrupted (attempt {attempt}/{attempts}), retrying...", flush=True)

    if not combined_rows and last_error is not None:
        raise last_error

    return combined_rows


def collect_wordnet_rows(max_words: int) -> List[CsvRow]:
    try:
        nltk = importlib.import_module("nltk")
        corpus = importlib.import_module("nltk.corpus")
        wn = getattr(corpus, "wordnet")
    except ImportError as exc:
        raise RuntimeError(
            "nltk is required for WordNet collection. Install with: pip install nltk"
        ) from exc

    try:
        wn.ensure_loaded()
    except LookupError:
        nltk.download("wordnet", quiet=True)
        wn.ensure_loaded()

    lemmas_by_entry: Dict[Tuple[str, str], WordnetBucket] = {}
    for synset in wn.all_synsets():
        pos = normalize_pos(synset.pos())
        definition = clean_text(synset.definition())
        if not definition:
            continue
        definition = truncate_text(definition, 320)

        example_en = ""
        examples = synset.examples()
        if examples:
            example_en = truncate_text(clean_text(examples[0]), 220)

        for lemma in synset.lemmas():
            word = normalize_word(lemma.name())
            if not word or not re.match(r"^[a-z][a-z\- ']*$", word):
                continue
            key = (word, pos)
            bucket = lemmas_by_entry.setdefault(
                key,
                {
                    "best_count": 0,
                    "senses": [],
                },
            )
            lemma_count = int(lemma.count())
            if lemma_count > bucket["best_count"]:
                bucket["best_count"] = lemma_count
            senses = bucket["senses"]
            senses.append((definition, example_en))

    ranked_items = sorted(
        lemmas_by_entry.items(),
        key=lambda item: item[1]["best_count"],
        reverse=True,
    )
    if max_words > 0:
        ranked_items = ranked_items[:max_words]

    rows: List[CsvRow] = []
    for rank_index, ((word, pos), payload) in enumerate(ranked_items, start=1):
        senses_raw = payload["senses"]

        seen_sense: Set[str] = set()
        for definition, example_en in senses_raw[:3]:
            sense_key = definition.lower()
            if sense_key in seen_sense:
                continue
            seen_sense.add(sense_key)
            rows.append(
                CsvRow(
                    headword=word,
                    lemma=word,
                    pos=pos,
                    ipa="",
                    frequency_rank=rank_index,
                    definition_en=definition,
                    definition_ko="",
                    example_en=example_en,
                    example_ko="",
                    source="princeton-wordnet",
                    license_name="WordNet-3.0",
                )
            )

    return rows


def merge_rows(
    wordnet_rows: Iterable[CsvRow],
    kaikkis_rows: Iterable[CsvRow],
    extra_rows: Iterable[CsvRow] = (),
) -> List[CsvRow]:
    combined: List[CsvRow] = []
    seen: Set[Tuple[str, str, str, str, str]] = set()

    def add_row(row: CsvRow) -> None:
        key = (
            row.headword,
            row.pos,
            row.definition_en.lower(),
            row.definition_ko.lower(),
            row.source,
        )
        if key in seen:
            return
        seen.add(key)
        combined.append(row)

    for row in wordnet_rows:
        add_row(row)
    for row in kaikkis_rows:
        add_row(row)
    for row in extra_rows:
        add_row(row)

    # Backfill Korean definitions for WordNet rows from Kaikki rows with same headword+pos.
    kaikki_by_word_pos: Dict[Tuple[str, str], List[CsvRow]] = {}
    for row in combined:
        if row.source.startswith("kaikki-wiktionary-en") and row.definition_ko:
            key = (row.headword, row.pos)
            kaikki_by_word_pos.setdefault(key, []).append(row)

    backfilled: List[CsvRow] = []
    for row in combined:
        if row.definition_ko:
            backfilled.append(row)
            continue
        ko = ""
        candidates = kaikki_by_word_pos.get((row.headword, row.pos), [])
        if candidates:
            best = max(candidates, key=lambda candidate: english_overlap_ratio(row.definition_en, candidate.definition_en))
            if english_overlap_ratio(row.definition_en, best.definition_en) >= 0.5:
                ko = best.definition_ko
            else:
                ko_freq: Dict[str, int] = {}
                for candidate in candidates:
                    if not candidate.definition_ko:
                        continue
                    ko_freq[candidate.definition_ko] = ko_freq.get(candidate.definition_ko, 0) + 1
                if ko_freq:
                    ko = max(ko_freq.items(), key=lambda item: item[1])[0]

        if ko:
            backfilled.append(
                CsvRow(
                    headword=row.headword,
                    lemma=row.lemma,
                    pos=row.pos,
                    ipa=row.ipa,
                    frequency_rank=row.frequency_rank,
                    definition_en=row.definition_en,
                    definition_ko=ko,
                    example_en=row.example_en,
                    example_ko=row.example_ko,
                    source=row.source,
                    license_name=row.license_name,
                )
            )
        else:
            backfilled.append(row)

    return sorted(backfilled, key=lambda r: (r.frequency_rank or 999999, r.headword, r.pos))


def write_csv(rows: Iterable[CsvRow], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[
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
            ],
        )
        writer.writeheader()
        for row in rows:
            writer.writerow(
                {
                    "headword": row.headword,
                    "lemma": row.lemma,
                    "pos": row.pos,
                    "ipa": row.ipa,
                    "frequencyRank": row.frequency_rank,
                    "definitionEn": row.definition_en,
                    "definitionKo": row.definition_ko,
                    "exampleEn": row.example_en,
                    "exampleKo": row.example_ko,
                    "source": row.source,
                    "license": row.license_name,
                }
            )


def parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Collect open web dictionary data (WordNet + Kaikki) and output ETL-ready CSV."
    )
    parser.add_argument(
        "--output-csv",
        type=Path,
        required=True,
        help="Output CSV path in app ETL format.",
    )
    parser.add_argument(
        "--max-wordnet-words",
        type=int,
        default=0,
        help="Maximum WordNet word+pos entries to collect (0 means no limit).",
    )
    parser.add_argument(
        "--max-kaikki-words",
        type=int,
        default=0,
        help="Maximum Kaikki word+pos entries to collect (0 means no limit).",
    )
    parser.add_argument(
        "--max-kaikki-senses-per-word",
        type=int,
        default=2,
        help="Maximum Kaikki senses per word+pos row set.",
    )
    parser.add_argument(
        "--exclude-kaikki-without-ko",
        action="store_true",
        help="If set, skip Kaikki entries that do not have Korean translations.",
    )
    parser.add_argument(
        "--exclude-inflected-forms",
        action="store_true",
        help="If set, do not synthesize rows for inflected forms from Kaikki forms data.",
    )
    parser.add_argument(
        "--augment-tatoeba-example-ko",
        action="store_true",
        help="Augment missing Korean examples from Tatoeba English-Korean sentence pairs.",
    )
    parser.add_argument(
        "--augment-tatoeba-lexicon-ko",
        action="store_true",
        help="Add conservative English->Korean lexical rows from Tatoeba sentence pairs.",
    )
    parser.add_argument(
        "--tatoeba-lexicon-max-rows",
        type=int,
        default=30000,
        help="Maximum lexical rows generated from Tatoeba (0 means no limit).",
    )
    parser.add_argument(
        "--tatoeba-max-pairs-per-token",
        type=int,
        default=3,
        help="Maximum Tatoeba sentence pairs stored per token for example backfill.",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=120,
        help="Network timeout seconds for Kaikki download stream.",
    )
    parser.add_argument(
        "--kaikki-attempts",
        type=int,
        default=6,
        help="Number of retry attempts for Kaikki cache download/parse.",
    )
    return parser.parse_args(argv)


def main(argv: List[str]) -> int:
    args = parse_args(argv)

    print("Collecting WordNet entries...", flush=True)
    wordnet_rows = collect_wordnet_rows(max_words=args.max_wordnet_words)
    print(f"WordNet rows: {len(wordnet_rows)}", flush=True)

    print("Collecting Kaikki (Wiktionary extract) entries...", flush=True)
    kaikki_rows = collect_kaikki_rows_with_retry(
        max_words=args.max_kaikki_words,
        max_senses_per_word=args.max_kaikki_senses_per_word,
        timeout=args.timeout,
        include_entries_without_ko=not args.exclude_kaikki_without_ko,
        include_inflected_forms=not args.exclude_inflected_forms,
        attempts=args.kaikki_attempts,
    )
    print(f"Kaikki rows: {len(kaikki_rows)}", flush=True)

    extra_rows: List[CsvRow] = []
    if args.augment_tatoeba_lexicon_ko:
        print("Collecting Tatoeba lexical Korean rows...", flush=True)
        tatoeba_lex_rows = collect_tatoeba_lexicon_rows(
            timeout=args.timeout,
            max_rows=args.tatoeba_lexicon_max_rows,
        )
        extra_rows.extend(tatoeba_lex_rows)
        print(f"Tatoeba lexical rows: {len(tatoeba_lex_rows)}", flush=True)

    merged_rows = merge_rows(
        wordnet_rows=wordnet_rows,
        kaikkis_rows=kaikki_rows,
        extra_rows=extra_rows,
    )

    if args.augment_tatoeba_example_ko:
        print("Augmenting Korean examples from Tatoeba...", flush=True)
        merged_rows, filled = augment_rows_with_tatoeba_examples(
            rows=merged_rows,
            timeout=args.timeout,
            max_pairs_per_token=args.tatoeba_max_pairs_per_token,
        )
        print(f"Tatoeba-augmented rows: {filled}", flush=True)

    write_csv(merged_rows, args.output_csv)
    print(f"Wrote merged CSV: {args.output_csv}")
    print(f"Total rows: {len(merged_rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
