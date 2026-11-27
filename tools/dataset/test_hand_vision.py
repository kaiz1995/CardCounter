"""Unit tests for HandAutoLock parity in tools/dataset/hand_vision.py."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hand_vision as hv  # noqa: E402


def _reading(cards: dict[str, int], pos: int | None, structure_valid: bool) -> hv.HandReading:
    return hv.HandReading.from_dict(cards, pos, structure_valid)


class HandAutoLockPythonTest(unittest.TestCase):
    def test_clears_qualified_when_new_rank_appears_even_if_total_mismatches_position(self) -> None:
        lock = hv.HandAutoLock()
        pre = _reading({"TWO": 4, "QUEEN": 4, "JACK": 4}, 12, True)
        for _ in range(hv.HAND_LOCK_STABLE_FRAMES):
            lock.observe(pre)
        post = _reading({"TWO": 4, "QUEEN": 3, "JACK": 4, "NINE": 1}, 13, False)
        for _ in range(hv.HAND_LOCK_STABLE_FRAMES):
            lock.observe(post)
        self.assertIsNone(lock.lock())

    def test_clears_qualified_when_position_changes_without_self_card(self) -> None:
        lock = hv.HandAutoLock()
        pre = _reading({"TWO": 4, "QUEEN": 4, "JACK": 4}, 12, True)
        for _ in range(hv.HAND_LOCK_STABLE_FRAMES):
            lock.observe(pre)
        post = _reading({"TWO": 4, "QUEEN": 3, "JACK": 4}, 13, False)
        for _ in range(hv.HAND_LOCK_STABLE_FRAMES):
            lock.observe(post, self_card_visible=False)
        self.assertIsNone(lock.lock())

    def test_post_tribute_return_replaces_without_spurious_invalidation(self) -> None:
        lock = hv.HandAutoLock()
        with_tribute = _reading({"TWO": 4, "QUEEN": 4, "JACK": 4, "SIX": 1}, 13, True)
        for _ in range(hv.HAND_LOCK_STABLE_FRAMES):
            lock.observe(with_tribute)
        transient = _reading({"TWO": 4, "QUEEN": 4, "JACK": 4}, 12, False)
        for _ in range(hv.HAND_LOCK_STABLE_FRAMES - 1):
            lock.observe(transient, self_card_visible=False)
        returned = _reading({"TWO": 4, "QUEEN": 4, "JACK": 4}, 12, True)
        for _ in range(hv.HAND_LOCK_STABLE_FRAMES):
            lock.observe(returned, self_card_visible=False)
        locked = lock.lock()
        self.assertIsNotNone(locked)
        self.assertEqual(locked[1], 12)
        self.assertEqual({k: v for k, v in locked[0].items() if v > 0}, {"TWO": 4, "QUEEN": 4, "JACK": 4})


if __name__ == "__main__":
    unittest.main(verbosity=2)
