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


import extract_sprites as E  # noqa: E402

HAND_OFF, HAND_GLYPH = (4, 3), (14, 30)


def pip_hand_tile(row1: bool = True) -> np.ndarray:
    """White hand corner: dark 14x30 rank glyph at (4,3); row 1 also has a 12x18 suit pip 6px below it."""

    tile = np.full((E.HAND_ROW1_H if row1 else 44, 56, 3), 250, np.uint8)
    tile[3:33, 4:18] = 30
    if row1:
        tile[39:57, 5:17] = 30
    return tile


class PipSpriteTest(unittest.TestCase):
    def card(self, scale: str, big: bool = False):
        return E.compose_play_card(pip_hand_tile(), HAND_OFF, HAND_GLYPH, E.PLAY_CARD[scale], None, big)

    def test_hand_row1_tile_reaches_the_suit_pip(self):
        strip = np.full((140, 870, 3), 250, np.uint8)
        glyph = [100, 10, 114, 40]
        tall = E.cut_tile(strip, glyph, "hand", tall=True)[0]
        short = E.cut_tile(strip, glyph, "hand", tall=False)[0]
        self.assertEqual(tall.shape[0], E.HAND_ROW1_H)
        self.assertEqual(short.shape[0], E.TILES["hand"]["h"])

    def test_pip_is_found_below_the_glyph_and_only_on_row1_tiles(self):
        box = E.find_pip(pip_hand_tile(), HAND_OFF, HAND_GLYPH)
        self.assertIsNotNone(box)
        self.assertGreaterEqual(box[1], HAND_OFF[1] + HAND_GLYPH[1])
        self.assertIsNone(E.find_pip(pip_hand_tile(False), HAND_OFF, HAND_GLYPH))

    def test_tribute_badge_is_not_taken_for_a_suit_pip(self):
        tile = pip_hand_tile()
        yy, xx = np.mgrid[0:tile.shape[0], 0:tile.shape[1]]
        dist = (yy - 46) ** 2 + (xx - 12) ** 2
        tile[39:57, 5:17] = 250
        tile[dist <= 10 ** 2] = (30, 0, 120)  # dark red disc
        tile[dist <= 6 ** 2] = 250  # white character in the middle
        self.assertIsNone(E.find_pip(tile, HAND_OFF, HAND_GLYPH))
        self.assertIsNotNone(E.find_pip(pip_hand_tile(), HAND_OFF, HAND_GLYPH))

    def test_play_card_has_pip_pixels_below_a_label_box_that_stays_on_the_glyph(self):
        for scale in ("outside", "self"):
            card, (gx, gy), (gw, gh) = self.card(scale)
            dark = card.min(axis=2) < 200
            cols = slice(gx + gw // 4, gx + gw - gw // 4)
            self.assertGreater(dark[gy + gh + 6:gy + gh + 10, cols].mean(), 0.6, scale)
            self.assertLess(dark[gy + gh + 1:gy + gh + 2, cols].mean(), 0.5, scale)

    def test_only_top_card_has_the_big_centre_pip(self):
        for scale in ("outside", "self"):
            plain, (_, gy), (_, gh) = self.card(scale, big=False)
            top = self.card(scale, big=True)[0]
            self.assertEqual(int((plain[gy + 2 * gh:].min(axis=2) < 200).sum()), 0, scale)
            self.assertGreater(int((top[gy + 2 * gh:].min(axis=2) < 200).sum()), 20, scale)

    def test_cut_tile_keeps_no_pixels_of_its_own_half_pip(self):
        # A row-2 hand tile is cut inside its own suit pip; only the grafted pip may show.
        clean = pip_hand_tile(False)
        cut = clean.copy()
        cut[39:44, 5:17] = (30, 0, 200)  # top rows of the card's real (red) pip
        graft = np.full((18, 12, 3), 30, np.uint8)
        self.assertIsNone(E.find_pip(cut, HAND_OFF, HAND_GLYPH))
        for scale in ("outside", "self"):
            for big in (False, True):
                spec = E.PLAY_CARD[scale]
                with_cut = E.compose_play_card(cut, HAND_OFF, HAND_GLYPH, spec, graft, big)[0]
                without = E.compose_play_card(clean, HAND_OFF, HAND_GLYPH, spec, graft, big)[0]
                self.assertTrue(
                    np.array_equal(with_cut.min(axis=2) < 200, without.min(axis=2) < 200),
                    f"{scale} big={big}: half pip of the cut tile leaked into the card",
                )


class PipSceneTest(unittest.TestCase):
    def setUp(self):
        self.assets = StubAssets()
        for rank in C.RANKS:
            for scale in ("outside", "self"):
                for suffix, big in (("", False), ("_top", True)):
                    card, off, glyph = E.compose_play_card(
                        pip_hand_tile(), HAND_OFF, HAND_GLYPH, E.PLAY_CARD[scale], None, big)
                    self.assets.tiles[(scale + suffix, rank)] = [{
                        "image": card, "off_x": off[0], "off_y": off[1],
                        "glyph_w": glyph[0], "glyph_h": glyph[1],
                    }]
        self._fade = S.FADE_PROB
        S.FADE_PROB = 0.0

    def tearDown(self):
        S.FADE_PROB = self._fade

    def test_every_play_box_has_pip_pixels_under_it_and_none_inside_the_gap(self):
        checked = 0
        for seed in range(400):
            rng = random.Random(seed)
            image, boxes, _ = S.make_play_scene(self.assets, rng, self_zone=seed % 3 == 0)
            dark = image.min(axis=2) < 200
            for box in boxes:
                x0, x1, y1 = int(box[0]), int(box[2]), int(round(box[3]))
                width = x1 - x0
                cols = slice(x0 + width // 4, x1 - width // 4)
                below = dark[y1 + 6:y1 + 10, cols]
                if below.shape[0] < 4:
                    continue
                checked += 1
                self.assertGreater(below.mean(), 0.5, f"seed {seed}: no suit pip under box {box}")
                # a clean row separates the labelled glyph from the pip: the box stops above the pip
                gap = dark[y1 - 1:y1 + 3, cols].mean(axis=1).min()
                self.assertLess(gap, 0.2, f"seed {seed}: box {box} reaches the pip")
        self.assertGreater(checked, 500)


if __name__ == "__main__":
    unittest.main(verbosity=2)
