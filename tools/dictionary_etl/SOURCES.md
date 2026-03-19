# Dictionary Sources

This ETL can collect entries from the following public sources:

- **Princeton WordNet**
  - Data path in script: `nltk.corpus.wordnet`
  - Website: https://wordnet.princeton.edu/
  - License label used in ETL rows: `WordNet-3.0`

- **Kaikki (Wiktionary extract, English)**
  - Stream URL: `https://kaikki.org/dictionary/English/words/kaikki.org-dictionary-English-words.jsonl.gz`
  - Website: https://kaikki.org/dictionary/
  - License label used in ETL rows: `CC-BY-SA-3.0`

- **Tatoeba (English-Korean sentence pairs for example backfill)**
  - Links URL: `https://downloads.tatoeba.org/exports/per_language/eng/eng-kor_links.tsv.bz2`
  - English sentences URL: `https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2`
  - Korean sentences URL: `https://downloads.tatoeba.org/exports/per_language/kor/kor_sentences.tsv.bz2`
  - Website: https://tatoeba.org/
  - License label used in ETL rows when augmented: `CC-BY-2.0` (combined as needed)
  - ETL usage:
    - Korean example backfill for English senses (`tatoeba-eng-kor`)
    - Conservative lexical translation rows (`tatoeba-lexicon-ko`)

Before shipping, ensure attribution and license obligations are satisfied for each source.
