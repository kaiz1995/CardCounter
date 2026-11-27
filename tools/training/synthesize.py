"""Synthesize 320x320 training tiles with exact rank-glyph boxes.

Every scene reproduces the app's real crops (see common.py):

* outside play zones: 1-12 cards of one rank stacked at the measured 15px
  pitch, occasionally joker/2 attachments;
* own play zone (SELF): 68x90 cards at 22px pitch;
* hand zone: two dense rows inside the 140px strip, then the 870x870 canvas
  and 320x320 resize the app performs (the rest of the canvas is black);
* pure negatives: felt, overlay boards, buttons, borders, avatars.

Suit pips (small, and the big one on the top card of a stack) are drawn on the
play cards and in suit-only scenes but are never labelled: they are background
the model must learn to ignore.
Labels are the rank glyph boxes only (one box per visible card), written in
YOLO txt format with class id == Rank enum order.

Usage:
  python tools/training/synthesize.py --train 5000 --val 800
"""

from __future__ import annotations

import argparse
import random
import shutil
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common as C  # noqa: E402

HAND_STRIP_W = C.HAND_RIGHT - C.HAND_LEFT      # 870
HAND_STRIP_H = C.HAND_BOTTOM - C.HAND_TOP      # 140
HAND_ROW_TOPS = (42, 104)                       # measured inside the strip
OUTSIDE_PITCH = 15.0
HAND_ROW1_H = 60  # row-1 hand tiles include the small suit pip (see extract_sprites)
SELF_PITCH = 22.0
FADE_PROB = 0.15  # share of play scenes drawn as a semi-transparent fade-in frame


class Assets:
    def __init__(self) -> None:
        index = C.load_json(C.SPRITE_DIR / "index.json")
        if not index:
            raise SystemExit("run extract_sprites.py first (sprites/index.json missing)")
        self.tiles: dict[tuple[str, str], list[dict]] = {}
        for entry in index["entries"]:
            image = cv2.imread(str(C.DATA_ROOT / entry["path"]))
            if image is None:
                continue
            entry = dict(entry)
            entry["image"] = image
            self.tiles.setdefault((entry["scale"], entry["rank"]), []).append(entry)
        neg_paths = sorted((C.SPRITE_DIR / "_neg").glob("*.png"))
        self.negatives = []
        self.badges = []
        for path in neg_paths:
            image = cv2.imread(str(path))
            if image is None:
                continue
            self.negatives.append(image)
            if "_badge_" in path.name:
                self.badges.append(image)
        self.pipneg = [
            image
            for image in (cv2.imread(str(path)) for path in sorted((C.SPRITE_DIR / "_pipneg").glob("*.png")))
            if image is not None
        ]
        self.felt = [image for image in self.negatives if _is_felt(image)]
        if not self.felt:
            self.felt = self.negatives
        missing = [rank for rank in C.RANKS if not self.tiles.get(("hand", rank))]
        if missing:
            raise SystemExit(f"missing hand sprites for {missing}")


def _is_felt(image: np.ndarray) -> bool:
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    green = ((hsv[:, :, 0] > 35) & (hsv[:, :, 0] < 95) & (hsv[:, :, 1] > 60)).mean()
    return green > 0.5


def felt_background(width: int, height: int, assets: Assets, rng: random.Random) -> np.ndarray:
    """Green table fill: a real felt patch, resized/repeated to the crop."""

    patch = rng.choice(assets.felt)
    scale = max(width / patch.shape[1], height / patch.shape[0])
    resized = cv2.resize(
        patch,
        (max(width, int(np.ceil(patch.shape[1] * scale))), max(height, int(np.ceil(patch.shape[0] * scale)))),
        interpolation=cv2.INTER_LINEAR,
    )
    x = rng.randrange(0, max(1, resized.shape[1] - width + 1))
    y = rng.randrange(0, max(1, resized.shape[0] - height + 1))
    return resized[y:y + height, x:x + width].copy()


def zone_background(zone: str, w: int, h: int, assets: Assets, rng: random.Random) -> np.ndarray:
    """Half of the scenes start from a real (empty) play-zone crop."""

    if assets.negatives and rng.random() < 0.5:
        patch = rng.choice(assets.negatives)
        if abs(patch.shape[1] / patch.shape[0] - w / h) < 0.6:
            return cv2.resize(patch, (w, h), interpolation=cv2.INTER_LINEAR)
    return felt_background(w, h, assets, rng)


def paste_cards(
    canvas: np.ndarray,
    tiles: list[dict],
    start: tuple[float, float],
    pitch: float,
    card_scale: float,
    alpha: float = 1.0,
    body_h: int = 0,
) -> list[tuple[list[float], int]]:
    """Paste cards left to right, later cards on top.

    Returns ``(glyph_box, card_index)`` for every card that really landed on the
    canvas with its rank glyph visible. ``card_index`` is the position in
    ``tiles``: a card that is skipped (off-canvas) must also drop its class, so
    callers pair labels by index, never by list prefix.
    """

    placed: list[tuple[list[float], int]] = []
    x0, y0 = start
    for index, tile in enumerate(tiles):
        image = tile["image"]
        if card_scale != 1.0:
            image = cv2.resize(
                image, None, fx=card_scale, fy=card_scale, interpolation=cv2.INTER_AREA
            )
        x = int(round(x0 + index * pitch))
        y = int(round(y0))
        height = min(image.shape[0], canvas.shape[0] - y)
        width = min(image.shape[1], canvas.shape[1] - x)
        if height <= 0 or width <= 0 or x < 0 or y < 0:
            continue
        patch = image[:height, :width]
        if alpha < 1.0:
            roi = canvas[y:y + height, x:x + width].astype(np.float32)
            canvas[y:y + height, x:x + width] = (
                roi * (1 - alpha) + patch.astype(np.float32) * alpha
            ).astype(np.uint8)
        else:
            canvas[y:y + height, x:x + width] = patch
        if body_h > image.shape[0]:
            # extend the card face down to its real height (the corner tile is
            # only the top 44px of a ~74px hand card)
            body_bottom = min(canvas.shape[0], y + body_h)
            if body_bottom > y + height:
                canvas[y + height:body_bottom, x:x + width] = (250, 250, 248)
        raw_x0 = x + tile["off_x"] * card_scale
        raw_y0 = y + tile["off_y"] * card_scale
        full_w = tile["glyph_w"] * card_scale
        full_h = tile["glyph_h"] * card_scale
        # labels must stay inside the canvas on every side
        gx0 = max(0.0, raw_x0)
        gy0 = max(0.0, raw_y0)
        gx1 = min(raw_x0 + full_w, canvas.shape[1])
        gy1 = min(raw_y0 + full_h, canvas.shape[0])
        visible = max(0.0, gx1 - gx0) * max(0.0, gy1 - gy0)
        # A glyph clipped by the canvas edge (hand row 2 is cut by the 140px
        # strip) is still a valid sample when most of it is visible.
        if gx1 - gx0 >= 2 and gy1 - gy0 >= 3 and visible >= 0.85 * full_w * full_h:
            placed.append(([gx0, gy0, gx1, gy1], index))
    return placed


def _paste_circle_badge(canvas: np.ndarray, badge: np.ndarray, cx: float, top_y: int, scale: float = 1.0) -> None:
    """Paste a real circular '出牌' badge using an elliptical mask so green felt corners are ignored."""
    bh = max(12, int(round(badge.shape[0] * scale)))
    bw = max(12, int(round(badge.shape[1] * scale)))
    resized = cv2.resize(badge, (bw, bh), interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_LINEAR)
    x0 = int(round(cx - bw / 2.0))
    y0 = int(round(top_y))
    yy, xx = np.mgrid[0:bh, 0:bw]
    mask = ((xx - (bw - 1) / 2.0) / max(1.0, bw * 0.48)) ** 2 + ((yy - (bh - 1) / 2.0) / max(1.0, bh * 0.48)) ** 2 <= 1.0
    dx0, dy0 = max(0, x0), max(0, y0)
    dx1, dy1 = min(canvas.shape[1], x0 + bw), min(canvas.shape[0], y0 + bh)
    if dx1 <= dx0 or dy1 <= dy0:
        return
    m = mask[dy0 - y0:dy1 - y0, dx0 - x0:dx1 - x0]
    src = resized[dy0 - y0:dy1 - y0, dx0 - x0:dx1 - x0]
    canvas[dy0:dy1, dx0:dx1][m] = src[m]


def _draw_synthetic_turn_badge(canvas: np.ndarray, cx: float, top_y: int, rng: random.Random) -> None:
    """Draw a synthetic white-bordered red circular '出牌' badge with yellow strokes."""
    radius = rng.randint(15, 22)
    cy = int(round(top_y + radius))
    ix = int(round(cx))
    cv2.circle(canvas, (ix, cy), radius, (244, 244, 244), -1, lineType=cv2.LINE_AA)
    cv2.circle(canvas, (ix, cy), max(6, radius - 2), (38, 36, 208), -1, lineType=cv2.LINE_AA)
    # Yellow inner horizontal/vertical strokes mimicking "出牌" text
    y_mid = cy
    span = max(4, radius - 6)
    cv2.line(canvas, (ix - span + 2, y_mid - 3), (ix + span - 2, y_mid - 3), (45, 215, 245), 2)
    cv2.line(canvas, (ix - span + 1, y_mid + 2), (ix + span - 1, y_mid + 2), (45, 215, 245), 2)
    cv2.line(canvas, (ix - 3, y_mid - 6), (ix - 3, y_mid + 6), (45, 215, 245), 2)
    cv2.line(canvas, (ix + 4, y_mid - 6), (ix + 4, y_mid + 6), (45, 215, 245), 2)


def _draw_count_effect_badge(canvas: np.ndarray, x0: int, y0: int, x1: int, y1: int, rng: random.Random) -> None:
    """Draw green/blue '5张/6张' or yellow play-effect occlusion without labels."""
    h, w = canvas.shape[:2]
    x0, y0 = max(0, x0), max(0, y0)
    x1, y1 = min(w, x1), min(h, y1)
    if x1 - x0 < 6 or y1 - y0 < 6:
        return
    style = rng.choice(["green_pill", "blue_pill", "yellow_banner"])
    if style == "yellow_banner":
        cv2.rectangle(canvas, (x0, y0), (x1, y1), (48, 212, 242), -1)
        cv2.rectangle(canvas, (x0, y0), (x1, y1), (242, 242, 242), 1)
        cv2.line(canvas, (x0 + 3, (y0 + y1) // 2), (x1 - 3, (y0 + y1) // 2), (35, 35, 35), 2)
    else:
        fill = (65, 178, 72) if style == "green_pill" else (210, 140, 48)
        cv2.rectangle(canvas, (x0, y0), (x1, y1), fill, -1)
        cv2.rectangle(canvas, (x0, y0), (x1, y1), (242, 242, 242), 1)
        digit = rng.choice(["5", "6", "7", "8"])
        cv2.putText(canvas, digit, (x0 + 2, min(h - 2, y1 - 3)), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (245, 245, 245), 1, cv2.LINE_AA)


def _make_rightmost_big_pip_tile(plain_tile: dict, assets: Assets, rank: str, rng: random.Random) -> dict | None:
    """Build an outside_top variant with wider face & varied big-pip placement (never labelled)."""
    pipneg = getattr(assets, "pipneg", [])
    if not pipneg:
        return None
    card = plain_tile["image"].copy()
    extra_w = rng.randint(0, 7)
    if extra_w > 0:
        ext = np.full((card.shape[0], card.shape[1] + extra_w, 3), (252, 252, 250), np.uint8)
        ext[:, :card.shape[1] - 1] = card[:, :-1]
        ext[:, -1] = (214, 214, 214)
        ext[0, :] = (214, 214, 214)
        ext[-1, :] = (214, 214, 214)
        card = ext
    # Pick a large pip patch (prefer non-blank patches with real suit ink)
    inked = [p for p in pipneg if (p.min(axis=2) < 180).mean() > 0.12] or pipneg
    pip = rng.choice(inked)
    zoom = 0.50 * 2.0 * rng.uniform(0.85, 1.22)
    big = cv2.resize(
        pip, (max(4, round(pip.shape[1] * zoom)), max(4, round(pip.shape[0] * zoom))),
        interpolation=cv2.INTER_AREA if zoom < 1 else cv2.INTER_LINEAR,
    )
    bx = round(card.shape[1] * rng.uniform(0.50, 0.64) - big.shape[1] / 2)
    by = plain_tile["off_y"] + round(rng.uniform(18.0, 24.5))
    x0, y0 = max(0, bx), max(0, by)
    x1, y1 = min(card.shape[1], bx + big.shape[1]), min(card.shape[0], by + big.shape[0])
    if x1 > x0 and y1 > y0:
        roi = card[y0:y1, x0:x1]
        np.minimum(roi, big[y0 - by:y1 - by, x0 - bx:x1 - bx], out=roi)
    return {
        "image": card,
        "off_x": plain_tile["off_x"],
        "off_y": plain_tile["off_y"],
        "glyph_w": plain_tile["glyph_w"],
        "glyph_h": plain_tile["glyph_h"],
    }


def make_play_scene(assets: Assets, rng: random.Random, self_zone: bool, occlude_joker: bool = False):
    zone = "SELF" if self_zone else rng.choice(
        ["TOP", "LEFT_TOP", "LEFT_BOTTOM", "RIGHT_TOP", "RIGHT_BOTTOM"]
    )
    _, _, w, h = C.zone_crop_rect(zone)
    scale_name = "self" if self_zone else "outside"
    pitch = SELF_PITCH if self_zone else OUTSIDE_PITCH
    card_w = 68 if self_zone else 44

    count = rng.choices(
        [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12],
        weights=[18, 22, 14, 12, 9, 8, 5, 4, 3, 2, 2, 1],
    )[0]
    joker_prob = 0.28 if not self_zone else 0.14
    if rng.random() < joker_prob and count >= 2:
        # 挂王炸弹真实牌局包括纯同点（大王+J*4）和带 2 混搭（大王+2+9*4）
        joker = "BIG_JOKER" if rng.random() < 0.6 else "SMALL_JOKER"
        if count >= 3 and rng.random() < 0.45:
            n_two = rng.randint(1, min(2, count - 2))
            base_rank = rng.choice(C.RANKS[3:])
            ranks = [joker] + ["TWO"] * n_two + [base_rank] * (count - 1 - n_two)
        else:
            base_rank = "TWO" if rng.random() < 0.25 else rng.choice(C.RANKS[3:])
            ranks = [joker] + [base_rank] * (count - 1)
    elif count >= 2 and rng.random() < 0.18:
        # 常见带 2 出牌（2+9*4）或混点数序列，防止模型靠相邻牌猜测当前牌
        if rng.random() < 0.75:
            n_two = rng.randint(1, min(2, count - 1))
            base_rank = rng.choice(C.RANKS[3:])
            ranks = ["TWO"] * n_two + [base_rank] * (count - n_two)
        else:
            ranks = [rng.choice(C.RANKS[2:]) for _ in range(count)]
    elif count == 1 and not self_zone and rng.random() < 0.08:
        ranks = ["BIG_JOKER" if rng.random() < 0.6 else "SMALL_JOKER"]
    else:
        ranks = [rng.choice(C.RANKS)] * count

    tiles = []
    for rank in ranks:
        pool = assets.tiles.get((scale_name, rank)) or assets.tiles[("hand", rank)]
        tiles.append(rng.choice(pool))

    background = zone_background(zone, w, h, assets, rng)
    card_scale = rng.uniform(0.94, 1.06)
    step = pitch * card_scale

    def last_glyph_right(n: int) -> float:
        tile_n = tiles[n - 1]
        return (n - 1) * step + (tile_n["off_x"] + tile_n["glyph_w"]) * card_scale

    # A stack wider than the crop (TOP is only 82px wide) keeps the cards whose
    # glyph fits and drops the rest, so no half-visible unlabeled glyph remains.
    while count > 1 and last_glyph_right(count) > w - 2:
        count -= 1
    ranks, tiles = ranks[:count], tiles[:count]
    # Only the top card of a played stack shows the big centre pip.
    if not self_zone and ranks[-1] not in ("BIG_JOKER", "SMALL_JOKER") and rng.random() < 0.45:
        varied = _make_rightmost_big_pip_tile(tiles[-1], assets, ranks[-1], rng)
        if varied is not None:
            tiles[-1] = varied
        else:
            top_pool = assets.tiles.get((scale_name + "_top", ranks[-1]))
            if top_pool:
                tiles[-1] = rng.choice(top_pool)
    else:
        top_pool = assets.tiles.get((scale_name + "_top", ranks[-1]))
        if top_pool:
            tiles[-1] = rng.choice(top_pool)
    stacked_width = (count - 1) * step + tiles[-1]["image"].shape[1] * card_scale
    x0 = (w - stacked_width) / 2 + rng.uniform(-3, 3)
    x0 = min(max(x0, 0.0), max(0.0, w - last_glyph_right(count) - 1))
    glyph_mid = h * (0.45 if self_zone else rng.uniform(0.54, 0.60))
    tile = tiles[0]
    y0 = glyph_mid - (tile["off_y"] + tile["glyph_h"] / 2) * card_scale + rng.uniform(-2, 2)
    alpha = 1.0
    if rng.random() < FADE_PROB:
        alpha = rng.uniform(0.55, 0.9)  # fade-in frame
    placed = paste_cards(background, tiles, (x0, y0), step, card_scale, alpha)

    # 5+张外家出牌在右侧偶现“5张/6张”数字角标或黄色横幅特效（不打标签，教模型当背景忽略）
    if not self_zone and occlude_joker and count >= 5 and placed and rng.random() < 0.35:
        last_box = placed[-1][0]
        bx0 = int(round(last_box[2] + rng.uniform(2, 10)))
        by0 = max(0, int(round(last_box[1] - rng.uniform(0, 4))))
        if bx0 + 18 < w and by0 + 16 < h:
            _draw_count_effect_badge(background, bx0, by0, bx0 + rng.randint(16, 24), by0 + rng.randint(14, 18), rng)

    # 模拟外家牌区左下/底部被“出牌”圆标、印章或“5张/6张”绿色特效遮住下半截的场景，保留王牌真值框
    if (
        occlude_joker
        and not self_zone
        and placed
        and ranks[0] in ("BIG_JOKER", "SMALL_JOKER")
        and alpha >= 0.85
        and rng.random() < 0.55
    ):
        gbox = placed[0][0]
        gw = gbox[2] - gbox[0]
        gh = gbox[3] - gbox[1]
        occ_y0 = max(0, min(h - 1, int(round(gbox[1] + gh * rng.uniform(0.42, 0.62)))))
        occ_cx = gbox[0] + gw * rng.uniform(0.25, 1.15)
        roll_occ = rng.random()
        if roll_occ < 0.55:
            badges = getattr(assets, "badges", [])
            if badges and rng.random() < 0.7:
                _paste_circle_badge(background, rng.choice(badges), occ_cx, occ_y0, rng.uniform(0.85, 1.15))
            else:
                _draw_synthetic_turn_badge(background, occ_cx, occ_y0, rng)
        elif roll_occ < 0.80:
            occ_x0 = max(0, int(round(gbox[0] - rng.uniform(2, 8))))
            occ_x1 = min(w, int(round(gbox[0] + gw * rng.uniform(1.1, 2.2))))
            _draw_count_effect_badge(background, occ_x0, occ_y0, occ_x1, h, rng)
        else:
            occ_x0 = max(0, int(round(gbox[0] - rng.uniform(2, 8))))
            occ_x1 = min(w, int(round(gbox[0] + gw * rng.uniform(1.05, 1.8))))
            if occ_y0 < h and occ_x1 > occ_x0:
                if assets.negatives and rng.random() < 0.5:
                    patch = rng.choice(assets.negatives)
                    background[occ_y0:h, occ_x0:occ_x1] = cv2.resize(patch, (occ_x1 - occ_x0, h - occ_y0))
                else:
                    color = rng.choice([(45, 55, 220), (50, 210, 240), (70, 175, 70)])
                    background[occ_y0:h, occ_x0:occ_x1] = color
    elif (
        occlude_joker
        and not self_zone
        and placed
        and ranks[0] not in ("BIG_JOKER", "SMALL_JOKER")
        and rng.random() < 0.12
    ):
        gbox = placed[0][0]
        badges = getattr(assets, "badges", [])
        top_y = int(round(gbox[3] + rng.uniform(1, 7)))
        if top_y < h - 4:
            if badges and rng.random() < 0.7:
                _paste_circle_badge(background, rng.choice(badges), gbox[0] + 4, top_y, rng.uniform(0.85, 1.10))
            else:
                _draw_synthetic_turn_badge(background, gbox[0] + 4, top_y, rng)

    boxes = [box for box, _ in placed]
    classes = [C.RANK_TO_ID[ranks[index]] for _, index in placed]
    return background, boxes, classes


def make_hand_scene(assets: Assets, rng: random.Random):
    strip = felt_background(HAND_STRIP_W, HAND_STRIP_H, assets, rng)
    if rng.random() < 0.25 and assets.negatives:
        # the overlay count board or a button sits over the hand zone
        patch = rng.choice(assets.negatives)
        ph = min(HAND_STRIP_H, patch.shape[0])
        pw = min(HAND_STRIP_W, patch.shape[1])
        px = rng.randrange(0, HAND_STRIP_W - pw + 1)
        py = rng.randrange(0, HAND_STRIP_H - ph + 1)
        strip[py:py + ph, px:px + pw] = cv2.resize(patch, (pw, ph))

    boxes: list[list[float]] = []
    classes: list[int] = []
    for row, row_top in enumerate(HAND_ROW_TOPS):
        count = rng.randint(12, 20)
        ranks = [rng.choice(C.RANKS) for _ in range(count)]
        if rng.random() < 0.6:
            ranks.sort(key=C.RANK_TO_ID.__getitem__)
        pitch = (HAND_STRIP_W - 52) / max(1, count - 1)
        pitch = max(38.0, min(pitch, 52.0))
        row_width = (count - 1) * pitch + 52
        start_x = (HAND_STRIP_W - row_width) / 2 + rng.uniform(-3, 3)
        # 17-18 cards fill the strip edge to edge: keep the whole row inside it.
        start_x = min(max(start_x, 0.0), max(0.0, HAND_STRIP_W - row_width))
        tiles = []
        for rank in ranks:
            pool = assets.tiles[("hand", rank)]
            if row == 0:  # row 1 shows the small suit pip: prefer the tall tiles
                pool = [t for t in pool if t["image"].shape[0] >= HAND_ROW1_H - 4] or pool
            tiles.append(rng.choice(pool))
        placed = paste_cards(
            strip, tiles, (start_x, row_top - 2), pitch, 1.0, 1.0, body_h=70
        )
        boxes.extend(box for box, _ in placed)
        classes.extend(C.RANK_TO_ID[ranks[index]] for _, index in placed)

    # Boxes are in strip pixels; the caller augments the strip and then parks it
    # on the 870x870 zero canvas (C.hand_canvas), so the black padding stays 0.
    return strip, boxes, classes


def make_negative_scene(assets: Assets, rng: random.Random):
    if rng.random() < 0.5:
        zone = rng.choice(["TOP", "LEFT_TOP", "LEFT_BOTTOM", "RIGHT_TOP", "RIGHT_BOTTOM"])
        _, _, w, h = C.zone_crop_rect(zone)
        return zone_background(zone, w, h, assets, rng), [], []
    patch = rng.choice(assets.negatives)
    size = rng.choice([w for w in (82, 152, 181, 141, 161, 320)])
    return cv2.resize(patch, (size, size), interpolation=cv2.INTER_LINEAR), [], []


def make_pip_scene(assets: Assets, rng: random.Random):
    """Suit-only scene: 1-3 pips on a white card face, no rank glyph.

    No boxes and no labels: the model only learns that a suit pip is background
    (a small club under a 3 used to be read as THREE, and large red diamond/heart tips
    near the bottom edge of LEFT_TOP used to be read as ACE).
    """

    pipneg = getattr(assets, "pipneg", [])
    if not pipneg:
        return make_negative_scene(assets, rng)
    zone = rng.choice(["SELF", "TOP", "LEFT_TOP", "LEFT_BOTTOM", "RIGHT_TOP", "RIGHT_BOTTOM"])
    _, _, w, h = C.zone_crop_rect(zone)
    canvas = zone_background(zone, w, h, assets, rng)
    if rng.random() < 0.65:
        # 铺一张半截白色牌面到底部，模拟外家最右大牌露出白色牌身和大号花色
        face_x0 = rng.randrange(0, max(1, w // 3))
        face_x1 = rng.randrange(min(w, face_x0 + max(30, w // 3)), w + 1)
        face_y0 = rng.randrange(max(1, h // 5), max(2, h * 2 // 3))
        canvas[face_y0:h, face_x0:face_x1] = (250, 250, 248)
        cv2.rectangle(canvas, (face_x0, face_y0), (face_x1 - 1, h), (214, 214, 214), 1)
    for idx in range(rng.randint(1, 3)):
        patch = rng.choice(pipneg)
        zoom = rng.uniform(0.85, 2.0)
        patch = cv2.resize(
            patch, None, fx=zoom, fy=zoom,
            interpolation=cv2.INTER_AREA if zoom < 1 else cv2.INTER_LINEAR,
        )
        ph, pw = patch.shape[:2]
        if ph >= h or pw >= w:
            continue
        x = rng.randrange(0, max(1, w - pw // 3))
        # 一半概率贴近或越过底边（y > h - ph），专门覆盖被底边裁成红三角的大方块/红桃
        if idx == 0 and rng.random() < 0.6:
            y_min = max(0, h - ph)
            y_max = max(y_min + 1, h - max(5, ph // 3))
            y = rng.randrange(y_min, y_max)
        else:
            y = rng.randrange(0, max(1, h - max(4, ph // 2)))
        dy = min(ph, h - y)
        dx = min(pw, w - x)
        if dy > 0 and dx > 0:
            roi = canvas[y:y + dy, x:x + dx]
            np.minimum(roi, patch[:dy, :dx], out=roi)
    return canvas, [], []


def augment(image: np.ndarray, rng: random.Random) -> np.ndarray:
    """Photometric augmentation of real content only (never of padding)."""

    noise_rng = np.random.default_rng(rng.getrandbits(32))  # seeded: reproducible sets
    if rng.random() < 0.7:
        gain = rng.uniform(0.86, 1.14)
        bias = rng.uniform(-14, 14)
        image = np.clip(image.astype(np.float32) * gain + bias, 0, 255).astype(np.uint8)
    if rng.random() < 0.25:
        image = cv2.GaussianBlur(image, (3, 3), 0)
    if rng.random() < 0.5:
        quality = rng.randint(55, 95)
        ok, encoded = cv2.imencode(".jpg", image, [int(cv2.IMWRITE_JPEG_QUALITY), quality])
        if ok:
            image = cv2.imdecode(encoded, cv2.IMREAD_COLOR)
    if rng.random() < 0.3:
        noise = rng.uniform(0.5, 3.0)
        image = np.clip(
            image.astype(np.float32) + noise_rng.normal(0, noise, image.shape), 0, 255
        ).astype(np.uint8)
    return image


def render(assets: Assets, rng: random.Random, kind: str | None = None):
    """One 320x320 tile + boxes + classes.

    ``kind`` (outside | self | hand | neg) forces the scene type; None draws it
    at random. Augmentation runs on the content (play crop / hand strip) before
    letterbox or canvas padding, so padding stays exactly 114 (play zones) and 0
    (hand canvas), like the phone's.
    """

    if kind is None:
        roll = rng.random()
        kind = (
            "outside" if roll < 0.42 else "self" if roll < 0.54 else "hand" if roll < 0.80
            else "neg" if roll < 0.85 else "pip"
        )
    if kind == "hand":
        strip, boxes, classes = make_hand_scene(assets, rng)
        strip = augment(strip, rng)
        canvas = C.hand_canvas(strip)  # 870x870, zeros around the strip
        image = cv2.resize(canvas, (C.MODEL_INPUT, C.MODEL_INPUT), interpolation=cv2.INTER_LINEAR)
        scale = C.MODEL_INPUT / HAND_STRIP_W
        boxes = [[x * scale, (y + C.HAND_PAD_TOP) * scale, x2 * scale, (y2 + C.HAND_PAD_TOP) * scale]
                 for x, y, x2, y2 in boxes]
        return image, boxes, classes
    if kind == "outside":
        raw, boxes, classes = make_play_scene(assets, rng, self_zone=False, occlude_joker=True)
    elif kind == "self":
        raw, boxes, classes = make_play_scene(assets, rng, self_zone=True)
    elif kind == "pip":
        raw, boxes, classes = make_pip_scene(assets, rng)
    else:
        raw, boxes, classes = make_negative_scene(assets, rng)

    raw = augment(raw, rng)
    image, scale, dx, dy = C.letterbox(raw)
    boxes = C.boxes_to_letterbox(boxes, scale, dx, dy).tolist() if boxes else []
    return image, boxes, classes


def build_dataset(n_train: int, n_val: int, seed: int = 20261124, clean: bool = True) -> Path:
    assets = Assets()
    root = C.SYNTH_DIR / "dataset"
    for split in ("train", "val"):
        images = root / "images" / split
        labels = root / "labels" / split
        if clean:
            shutil.rmtree(images, ignore_errors=True)
            shutil.rmtree(labels, ignore_errors=True)
        images.mkdir(parents=True, exist_ok=True)
        labels.mkdir(parents=True, exist_ok=True)

    rng = random.Random(seed)
    stats = {}
    for split, count in (("train", n_train), ("val", n_val)):
        boxes_total = 0
        per_class = np.zeros(len(C.RANKS), np.int64)
        for index in range(count):
            image, boxes, classes = render(assets, rng)
            name = f"{split}_{index:06d}"
            cv2.imwrite(str(root / "images" / split / f"{name}.png"), image)
            lines = C.boxes_to_yolo_labels(boxes, classes)
            (root / "labels" / split / f"{name}.txt").write_text(
                "\n".join(lines) + ("\n" if lines else ""), encoding="utf-8"
            )
            for cls in classes:
                per_class[cls] += 1
            boxes_total += len(lines)
        stats[split] = {"images": count, "boxes": boxes_total,
                        "per_class": {C.RANKS[i]: int(v) for i, v in enumerate(per_class)}}

    yaml_path = root / "data.yaml"
    yaml_path.write_text(
        "\n".join(
            [
                f"path: {root.as_posix()}",
                "train: images/train",
                "val: images/val",
                f"nc: {len(C.RANKS)}",
                "names:",
            ]
            + [f"  {i}: {name}" for i, name in enumerate(C.RANKS)]
        )
        + "\n",
        encoding="utf-8",
    )
    C.save_json(root / "stats.json", stats)
    return yaml_path


def build_align_dataset(real_repeat: int = 10) -> Path:
    """Stage B list file: synthetic train tiles + over-sampled real hand frames.

    A list file avoids duplicating the synthetic images on disk and passes check_isolation.py.
    """

    root = C.SYNTH_DIR / "dataset"
    out = C.SYNTH_DIR / "dataset_align"
    shutil.rmtree(out / "images", ignore_errors=True)
    shutil.rmtree(out / "labels", ignore_errors=True)
    out.mkdir(parents=True, exist_ok=True)
    lines = [str(path).replace("\\", "/") for path in sorted((root / "images" / "train").glob("*.png"))]
    real = C.SYNTH_DIR / "real_hand" / "images"
    real_images = sorted(real.glob("*.png"))
    for _ in range(real_repeat):
        lines.extend(str(path).replace("\\", "/") for path in real_images)
    list_path = out / "train_list.txt"
    list_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    yaml_path = out / "data.yaml"
    yaml_path.write_text(
        "\n".join(
            [
                f"path: {out.as_posix()}",
                f"train: {list_path.as_posix()}",
                f"val: {(root / 'images' / 'val').as_posix()}",
                f"nc: {len(C.RANKS)}",
                "names:",
            ]
            + [f"  {i}: {name}" for i, name in enumerate(C.RANKS)]
        )
        + "\n",
        encoding="utf-8",
    )
    C.save_json(out / "stats.json", {"images": len(lines), "real_frames": len(real_images),
                                    "real_repeat": real_repeat})
    return yaml_path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--train", type=int, default=5000)
    parser.add_argument("--val", type=int, default=800)
    parser.add_argument("--seed", type=int, default=20261124)
    parser.add_argument("--align", action="store_true", help="build the stage-B list file")
    parser.add_argument("--real-repeat", type=int, default=10)
    args = parser.parse_args()
    if args.align:
        path = build_align_dataset(args.real_repeat)
        print(f"align dataset: {path}")
        print(C.load_json(path.parent / "stats.json"))
        return 0
    path = build_dataset(args.train, args.val, args.seed)
    stats = C.load_json(path.parent / "stats.json")
    print(f"dataset: {path}")
    print("train:", stats["train"]["images"], "val:", stats["val"]["images"],
          "boxes:", stats["train"]["boxes"], stats["val"]["boxes"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
