"""Label/class alignment tests for synthesize.py (no real sprites needed).

Every rank gets a solid, unique colour tile, so the colour under a box centre
proves which card was really pasted there. A skipped (off-canvas) card must
never shift the classes of the cards that follow it.

Run: python tools/training/test_synthesize.py
"""

from __future__ import annotations

import random
import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common as C  # noqa: E402
import synthesize as S  # noqa: E402


def rank_color(index: int) -> tuple[int, int, int]:
    # 15 well separated BGR colours, none of them close to the green felt.
    return (20 + 15 * index, 250 - 14 * index, 40 + 11 * ((index * 7) % 15))


def make_tile(rank_index: int, width: int, height: int, off=(4, 3), glyph=(14, 16)) -> dict:
    image = np.zeros((height, width, 3), np.uint8)
    image[:, :] = rank_color(rank_index)
    return {
        "image": image, "off_x": off[0], "off_y": off[1],
        "glyph_w": glyph[0], "glyph_h": glyph[1],
    }


class StubAssets:
    """Same surface as synthesize.Assets, minus the real sprite files."""

    def __init__(self) -> None:
        self.tiles = {}
        for i, rank in enumerate(C.RANKS):
            self.tiles[("hand", rank)] = [make_tile(i, 56, 44)]
            self.tiles[("outside", rank)] = [make_tile(i, 40, 34, off=(3, 3), glyph=(10, 13))]
            self.tiles[("self", rank)] = [make_tile(i, 60, 46, off=(4, 4), glyph=(15, 19))]
        self.negatives: list[np.ndarray] = []
        self.felt = [np.full((120, 160, 3), (40, 110, 50), np.uint8)]


def colour_class(pixel: np.ndarray) -> int:
    distances = [np.abs(pixel.astype(int) - np.array(rank_color(i))).sum() for i in range(len(C.RANKS))]
    best = int(np.argmin(distances))
    return best if distances[best] <= 6 else -1


def centre_pixel(image: np.ndarray, box) -> np.ndarray:
    cx = int((box[0] + box[2]) / 2)
    cy = int((box[1] + box[3]) / 2)
    return image[cy, cx]


class PasteCardsTest(unittest.TestCase):
    def test_skipped_card_drops_its_own_class(self):
        ranks = [0, 1, 2, 3, 4]
        tiles = [make_tile(i, 40, 34) for i in ranks]
        canvas = np.full((60, 200, 3), (40, 110, 50), np.uint8)
        # first card starts at x=-20: pasted left of the canvas -> skipped.
        placed = S.paste_cards(canvas, tiles, (-20.0, 10.0), 30.0, 1.0)
        indices = [index for _, index in placed]
        self.assertNotIn(0, indices)
        self.assertEqual(indices, [1, 2, 3, 4])
        for box, index in placed:
            self.assertEqual(colour_class(centre_pixel(canvas, box)), ranks[index])

    def test_cards_past_the_right_edge_do_not_mislabel(self):
        tiles = [make_tile(i, 40, 34) for i in range(6)]
        canvas = np.full((60, 100, 3), (40, 110, 50), np.uint8)
        placed = S.paste_cards(canvas, tiles, (0.0, 10.0), 30.0, 1.0)
        for box, index in placed:
            self.assertEqual(colour_class(centre_pixel(canvas, box)), index)


class SceneLabelTest(unittest.TestCase):
    def setUp(self):
        self.assets = StubAssets()
        self._fade = S.FADE_PROB
        S.FADE_PROB = 0.0  # opaque cards: colour at the box centre is exact

    def tearDown(self):
        S.FADE_PROB = self._fade

    def assert_scene(self, image, boxes, classes):
        self.assertEqual(len(boxes), len(classes))
        for box, cls in zip(boxes, classes):
            self.assertGreaterEqual(box[0], 0)
            self.assertGreaterEqual(box[1], 0)
            self.assertLessEqual(box[2], image.shape[1])
            self.assertLessEqual(box[3], image.shape[0])
            self.assertEqual(
                colour_class(centre_pixel(image, box)), cls,
                f"box {box} labelled {C.RANKS[cls]} but another card is drawn there",
            )

    def test_play_scenes_all_zones(self):
        total = 0
        for seed in range(600):
            rng = random.Random(seed)
            image, boxes, classes = S.make_play_scene(self.assets, rng, self_zone=seed % 4 == 0)
            self.assert_scene(image, boxes, classes)
            total += len(boxes)
        self.assertGreater(total, 1000)

    def test_top_zone_wide_stacks_keep_every_label_true(self):
        # TOP is only 82px wide: a long stack used to lose the leftmost joker and
        # then label the following TWO as that joker.
        hit = 0
        for seed in range(2000):
            rng = random.Random(seed)
            image, boxes, classes = S.make_play_scene(self.assets, rng, self_zone=False)
            if image.shape[1] > 100:
                continue
            hit += 1
            self.assert_scene(image, boxes, classes)
        self.assertGreater(hit, 100)

    def test_hand_scenes_full_rows(self):
        total = 0
        for seed in range(150):
            rng = random.Random(1000 + seed)
            strip, boxes, classes = S.make_hand_scene(self.assets, rng)
            self.assert_scene(strip, boxes, classes)
            total += len(boxes)
        self.assertGreater(total, 3000)

    def test_hand_rows_never_start_off_canvas(self):
        # 17-18 card rows used to start at x<0 (plus jitter) and skip a card.
        for seed in range(400):
            rng = random.Random(seed)
            strip, boxes, classes = S.make_hand_scene(self.assets, rng)
            for box in boxes:
                self.assertGreaterEqual(box[0], 0)


class PaddingTest(unittest.TestCase):
    def setUp(self):
        self.assets = StubAssets()

    def test_play_letterbox_padding_stays_114(self):
        for seed in range(60):
            rng = random.Random(seed)
            image, _, _ = S.render(self.assets, rng, kind="outside")
            self.assertEqual(image.shape, (320, 320, 3))
            # every outside crop is wider than tall: the first row is pure padding
            self.assertTrue((image[0] == C.LETTERBOX_PAD).all(), f"seed {seed}: padding changed")

    def test_hand_canvas_padding_stays_0(self):
        for seed in range(60):
            rng = random.Random(seed)
            image, _, _ = S.render(self.assets, rng, kind="hand")
            # rows above the strip (y < 540/870*320 = 198) are pure canvas fill
            self.assertTrue((image[:196] == 0).all(), f"seed {seed}: black padding changed")


if __name__ == "__main__":
    unittest.main(verbosity=2)
