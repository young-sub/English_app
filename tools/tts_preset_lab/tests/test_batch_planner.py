from __future__ import annotations

import unittest

from tools.tts_preset_lab.server import build_speaker_deck, take_next_speakers


class BatchPlannerTest(unittest.TestCase):
    def test_speaker_deck_covers_all_without_duplicates(self) -> None:
        deck = build_speaker_deck(23, batch_size=10)

        self.assertEqual(len(deck), 23)
        self.assertEqual(len(set(deck)), 23)
        self.assertEqual(set(deck), set(range(23)))

    def test_next_batch_skips_previously_seen_speakers(self) -> None:
        deck = build_speaker_deck(50, batch_size=10)
        first_batch = take_next_speakers(deck, seen_speaker_ids=[], count=10)
        second_batch = take_next_speakers(deck, seen_speaker_ids=first_batch, count=10)

        self.assertEqual(len(first_batch), 10)
        self.assertEqual(len(second_batch), 10)
        self.assertTrue(set(first_batch).isdisjoint(second_batch))

    def test_batch_order_spreads_first_batch_across_range(self) -> None:
        deck = build_speaker_deck(100, batch_size=10)

        self.assertEqual(len(deck[:10]), 10)
        self.assertLess(deck[0], 10)
        self.assertGreater(deck[1], 9)
        self.assertGreater(deck[-1], 80)


if __name__ == "__main__":
    unittest.main()
