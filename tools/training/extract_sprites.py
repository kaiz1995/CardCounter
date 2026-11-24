"""Extract real rank-corner sprites, negative patches and labelled real frames.

Sources (whitelist enforced by common.ensure_allowed):

* hand zone:  9局 clips whose opening hand is fully dealt. The ground truth in
  g2-analysis/rank-ground-truth.md is a per-rank count vector and the game
  sorts the hand by rank, so card k in reading order is known exactly. The
  script only has to find the glyph positions.
* play zones: card geometry is measured (positions only) with the shipped
  28-class MNN model, fed exactly like the phone (letterbox + 114 grey) on
  ``common.PLAY_GEOMETRY_VIDEOS``. Played cards are rendered at a smaller scale
  than hand cards, which is the whole point of a second sprite set; the play
  sprites themselves are resampled from truth-labelled hand corners.
* negatives: card-free patches (felt, watermark, avatars, buttons, borders,
  overlays) sampled from frames that are not used as sprite sources.

Outputs go to D:\\CardCounterData\\sprites (never into git):
  hand|outside|self/<RANK>/*.png, _neg/*.png, index.json, _mosaic/*.png

Usage: python tools/training/extract_sprites.py [--skip-play] [--skip-neg]
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from collections import Counter, defaultdict
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common as C  # noqa: E402

# Tile geometry: the tile is cut around the glyph, so every sprite in a scale
# shares the same glyph origin inside the tile.
TILES = {
    "hand": {"w": 56, "h": 44, "off": (4, 3)},
    "outside": {"w": 40, "h": 34, "off": (3, 3)},
    "self": {"w": 60, "h": 46, "off": (4, 4)},
}
# play-scale cards come in a plain and a ``_top`` (big centre pip) flavour
ALL_SCALES = ("hand", "outside", "self", "outside_top", "self_top")
HAND_ROW_PITCH = 63  # measured: row 2 top - row 1 top inside the 140px strip

# Row 1 of the hand shows the small suit pip under the rank glyph. Measured on
# 77 clean row-1 glyphs of the 6 training clips: glyph 30px high, 6px of felt-white
# gap, pip 18px high, pip bottom 50..56px (median 54) below the glyph top. The
# row-1 tile therefore reaches 3 (glyph offset) + 56 + 1 = 60px. Row 2 is cut by
# the 140px strip, so its (shorter) tiles stay exactly as they were.
HAND_ROW1_H = 60
PIP_GAP = 6          # glyph bottom -> pip top, hand px (median of the same sample)
PIP_MIN_H, PIP_MAX_H = 10, 26
JOKERS = ("BIG_JOKER", "SMALL_JOKER")
FACE = (252, 252, 250)  # BGR of the synthetic play-card face


def white_mask(bgr: np.ndarray) -> np.ndarray:
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    return ((hsv[:, :, 2] > 170) & (hsv[:, :, 1] < 45)).astype(np.uint8)


def ink_mask(bgr: np.ndarray) -> np.ndarray:
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    hue, sat, val = hsv[:, :, 0].astype(int), hsv[:, :, 1].astype(int), hsv[:, :, 2].astype(int)
    red = ((hue < 12) | (hue > 168)) & (sat > 90) & (val > 50)
    dark = val < 120
    ink = (red | dark).astype(np.uint8)
    # Only ink painted on a card face: the felt is dark green and must not count.
    card = cv2.dilate(white_mask(bgr), np.ones((3, 3), np.uint8))
    return (ink & card).astype(np.uint8)


def clusters_from_mask(mask: np.ndarray, min_area: int = 12, min_h: int = 8) -> list[list[int]]:
    count, _, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    boxes = [stats[i] for i in range(1, count) if stats[i, 4] >= min_area and stats[i, 3] >= min_h]
    boxes.sort(key=lambda b: b[0])
    groups: list[list[int]] = []
    for x, y, w, h, _ in boxes:
        if groups and x <= groups[-1][2] + 6:  # x overlap (digit + suit pip, split strokes)
            group = groups[-1]
            group[0] = min(group[0], int(x))
            group[1] = min(group[1], int(y))
            group[2] = max(group[2], int(x + w))
            group[3] = max(group[3], int(y + h))
        else:
            groups.append([int(x), int(y), int(x + w), int(y + h)])
    return [g for g in groups if g[2] - g[0] >= 6 and g[3] - g[1] >= 10]


def hand_row_bands(strip: np.ndarray) -> list[int]:
    """Top y of each card row inside the hand strip."""

    mask = ink_mask(strip)
    profile = mask.sum(axis=1).astype(float)
    if profile.max() < 10:
        return []
    threshold = max(20.0, profile.max() * 0.30)
    bands: list[tuple[float, int]] = []
    start = None
    for y, value in enumerate(profile):
        if value >= threshold and start is None:
            start = y
        elif value < threshold * 0.4 and start is not None:
            bands.append((profile[start:y].sum(), start))
            start = None
    if start is not None:
        bands.append((profile[start:].sum(), start))
    bands.sort(reverse=True)
    return sorted(y for _, y in bands[:2])


def cut_tile(strip: np.ndarray, glyph: list[int], scale: str, tall: bool = False):
    """Cut the corner tile around a glyph.

    Row 2 of the hand is clipped by the 140px strip in the app, so a short tile
    there is the *correct* sample. Row 1 (``tall=True``) is cut down to
    HAND_ROW1_H so the small suit pip under the rank glyph is fully inside the
    tile. The pip is never labelled: it is background the model must ignore.
    Leading felt columns (a joker glyph sits on the card edge) are trimmed so
    tiles can be stacked without green seams.
    Returns (tile, off_x, off_y) or None.
    """

    spec = TILES[scale]
    x0, y0 = glyph[0] - spec["off"][0], glyph[1] - spec["off"][1]
    if x0 < 0 or y0 < 0:
        return None
    height = HAND_ROW1_H if (tall and scale == "hand") else spec["h"]
    y1 = min(y0 + height, strip.shape[0])
    x1 = min(x0 + spec["w"], strip.shape[1])
    tile = strip[y0:y1, x0:x1]
    if tile.shape[0] < 14 or tile.shape[1] < 12:
        return None
    white = white_mask(tile)
    columns = white.mean(axis=0) > 0.5
    trim = 0
    while trim < columns.size and trim < 6 and not columns[trim]:
        trim += 1
    if trim:
        tile = tile[:, trim:]
    return tile, spec["off"][0] - trim, spec["off"][1]


class SpriteBank:
    def __init__(self) -> None:
        self.entries: list[dict] = []
        self.hashes: set[str] = set()
        for scale in ALL_SCALES:
            for rank in C.RANKS:
                folder = C.SPRITE_DIR / scale / rank
                folder.mkdir(parents=True, exist_ok=True)
                for stale in folder.glob("*.png"):
                    stale.unlink()
        for folder in ("_neg", PIP_NEG_DIR):
            (C.SPRITE_DIR / folder).mkdir(parents=True, exist_ok=True)
            for stale in (C.SPRITE_DIR / folder).glob("*.png"):
                stale.unlink()

    def add(self, tile, off, rank: str, scale: str, source: str, glyph: tuple[int, int]) -> bool:
        digest = hashlib.sha1(tile.tobytes()).hexdigest()[:10]
        if digest in self.hashes:
            return False
        self.hashes.add(digest)
        name = f"{source}_{digest}.png"
        path = C.SPRITE_DIR / scale / rank / name
        cv2.imwrite(str(path), tile)
        self.entries.append(
            {
                "path": str(path.relative_to(C.DATA_ROOT)).replace("\\", "/"),
                "rank": rank,
                "scale": scale,
                "source": source,
                "glyph_w": glyph[0],
                "glyph_h": glyph[1],
                "tile_w": int(tile.shape[1]),
                "tile_h": int(tile.shape[0]),
                "off_x": int(off[0]),
                "off_y": int(off[1]),
            }
        )
        return True

    def add_negative(self, patch: np.ndarray, source: str, folder: str = "_neg") -> bool:
        """Write a negative patch; False when an identical patch was already kept."""

        digest = hashlib.sha1(patch.tobytes()).hexdigest()[:10]
        if digest in self.hashes:
            return False
        self.hashes.add(digest)
        cv2.imwrite(str(C.SPRITE_DIR / folder / f"{source}_{digest}.png"), patch)
        return True

    def save(self) -> None:
        C.save_json(
            C.SPRITE_DIR / "index.json",
            {"tiles": TILES, "entries": self.entries},
        )


def read_hand_labels(video: Path, truth: dict, timestamps=None) -> list[dict]:
    """Locate hand glyphs and attach truth ranks. Returns labelled frames."""

    key = C.clip_key(video)
    if key not in truth:
        print(f"  skip {video.name}: no truth vector")
        return []
    expected = C.expanded_hand(truth[key]["counts"])
    if timestamps is None:
        anchor = float(truth[key]["frame_s"])
        timestamps = (anchor - 0.6, anchor, anchor + 0.8)
    frames = C.grab_frames(video, timestamps)
    labelled = []
    for second, frame in frames.items():
        strip = C.hand_strip(frame)
        bands = hand_row_bands(strip)
        if len(bands) < 1:
            print(f"  {video.name}@{second}s: no ink rows")
            continue
        clusters: list[tuple[int, list[int]]] = []
        for row_index, band_top in enumerate(bands):
            band = strip[band_top:band_top + 38]
            row_glyphs = clusters_from_mask(ink_mask(band))
            row_glyphs = [g for g in row_glyphs if g[2] - g[0] <= 40]
            for g in row_glyphs:
                g[1] += band_top
                g[3] += band_top
            clusters.extend((row_index, g) for g in row_glyphs)
        if len(clusters) != len(expected):
            print(f"  {video.name}@{second}s: found {len(clusters)} glyphs, truth has {len(expected)} cards")
            continue
        labelled.append(
            {
                "video": video.name,
                "second": second,
                "clusters": clusters,
                "ranks": [C.RANKS[i] for i in expected],
            }
        )
    return labelled


def extract_hand(bank: SpriteBank, truth: dict) -> tuple[int, list[dict]]:
    total = 0
    labelled_frames: list[dict] = []
    for video in C.nine_games(train_only=True):
        frames = read_hand_labels(video, truth)
        for item in frames:
            frame = C.grab_frames(video, [item["second"]])[item["second"]]
            strip = C.hand_strip(frame)
            for (row, glyph), rank in zip(item["clusters"], item["ranks"]):
                cut = cut_tile(strip, glyph, "hand", tall=(row == 0))
                if cut is None:
                    continue
                tile, off_x, off_y = cut
                if bank.add(
                    tile, (off_x, off_y), rank, "hand", C.clip_key(video),
                    (glyph[2] - glyph[0], glyph[3] - glyph[1]),
                ):
                    total += 1
        labelled_frames.extend(frames)
        print(f"  {video.name}: {len(frames)} labelled hand frames")
    return total, labelled_frames


def save_real_hand_frames(labelled: list[dict], out_dir: Path) -> int:
    """Write model-space 320x320 crops + YOLO labels for domain alignment."""

    images = out_dir / "images"
    labels = out_dir / "labels"
    images.mkdir(parents=True, exist_ok=True)
    labels.mkdir(parents=True, exist_ok=True)
    for existing in images.glob("*.png"):
        existing.unlink()
    for existing in labels.glob("*.txt"):
        existing.unlink()
    written = 0
    for item in labelled:
        video = C.ensure_allowed(C.NINE_GAMES / item["video"])
        frame = C.grab_frames(video, [item["second"]])[item["second"]]
        strip = C.hand_strip(frame)
        model_img = C.model_input_hand(strip)
        scale = C.MODEL_INPUT / (C.HAND_RIGHT - C.HAND_LEFT)
        boxes, classes = [], []
        for (_, glyph), rank in zip(item["clusters"], item["ranks"]):
            x0, y0 = glyph[0] * scale, (glyph[1] + C.HAND_PAD_TOP) * scale
            y1_source = min(glyph[3], strip.shape[0])
            x1 = min(glyph[2], strip.shape[1]) * scale
            y1 = (y1_source + C.HAND_PAD_TOP) * scale
            boxes.append([x0, y0, x1, y1])
            classes.append(C.RANK_TO_ID[rank])
        name = f"{C.clip_key(video)}_{int(item['second'] * 10):04d}"
        cv2.imwrite(str(images / f"{name}.png"), model_img)
        (labels / f"{name}.txt").write_text(
            "\n".join(C.boxes_to_yolo_labels(boxes, classes)) + "\n", encoding="utf-8"
        )
        written += 1
    return written


def extract_play(bank: SpriteBank, detector, pitch: defaultdict) -> dict:
    """Measure play-zone card geometry and collect empty play crops.

    The old model is used for *positions* only. Its classes are unusable on
    stacked plays (measured: a 9/9 play scored as TEN, a K/K play as ACE), and
    template matching on 10x15px glyphs does not separate the ranks either, so
    play-scale sprites are derived from the truth-labelled hand glyphs in
    ``derive_play_tiles`` instead of being labelled from real play frames.
    """

    stats = Counter()
    for video in [C.ensure_allowed(C.SOURCE / n) for n in C.PLAY_GEOMETRY_VIDEOS]:
        capture = C.open_video(video)
        duration = capture.get(cv2.CAP_PROP_FRAME_COUNT) / max(1.0, capture.get(cv2.CAP_PROP_FPS))
        capture.release()
        times = [float(t) for t in np.arange(40.0, min(duration, 600.0), 2.0)]
        for second, frame in C.grab_frames(video, times).items():
            for zone in C.ZONES:
                crop = C.zone_crop(frame, zone)
                if crop.size == 0:
                    continue
                # Same input as the phone (publicPlayBitmap): proportional resize +
                # centred 114-grey padding; boxes come back in crop pixels.
                detections = detector.detect_letterbox(crop, conf=0.5, iou=0.4)
                if not detections:
                    # Real empty play-zone crop (felt + overlay + count board).
                    # stable_hash, not hash(): str hashing is randomised per process.
                    if (video.name in C.NEGATIVE_SOURCE_VIDEOS and len(bank.entries) and stats["empty_bg"] < 120
                            and C.stable_hash(video.name, zone, int(second)) % 3 == 0):
                        if bank.add_negative(crop.copy(), C.clip_key(video)):
                            stats["empty_bg"] += 1
                    continue
                if len(detections) > 12:
                    continue
                scale = "self" if C.ZONES[zone][4] == "SELF" else "outside"
                detections.sort(key=lambda d: d["box"][0])
                centres = [0.5 * (d["box"][0] + d["box"][2]) for d in detections]
                for left, right in zip(centres, centres[1:]):
                    pitch[(scale, len(detections))].append(round(right - left, 1))
        print(f"  {video.name}: sprites so far {dict(stats) if stats else 0}")
    return dict(stats)


def extract_negatives(bank: SpriteBank, count_per_video: int = 120) -> int:
    rng = np.random.default_rng(7)
    videos = C.nine_games(train_only=True)[:3] + [C.ensure_allowed(C.SOURCE / n) for n in C.NEGATIVE_SOURCE_VIDEOS]
    total = 0
    for video in videos:
        capture = C.open_video(video)
        duration = capture.get(cv2.CAP_PROP_FRAME_COUNT) / max(1.0, capture.get(cv2.CAP_PROP_FPS))
        capture.release()
        times = np.linspace(2.0, max(3.0, min(duration, 400.0) - 3.0), 40)
        frames = C.grab_frames(video, [float(t) for t in times])
        every = max(1, len(frames) // 10)
        for index, (_, frame) in enumerate(frames.items()):
            if index % every:
                continue
            for _ in range(count_per_video // 10):
                size = int(rng.integers(40, 160))
                x = int(rng.integers(0, C.FRAME_W - size))
                y = int(rng.integers(0, C.FRAME_H - size))
                patch = frame[y:y + size, x:x + size]
                cards = white_mask(patch).mean()
                if cards > 0.03:
                    continue
                scale = 2 if size < 80 else 1
                kept = bank.add_negative(
                    cv2.resize(patch, (size * scale, size * scale), interpolation=cv2.INTER_LINEAR)
                    if scale > 1 else patch,
                    C.clip_key(video),
                )
                if kept:  # duplicates dropped by hash are not counted
                    total += 1
    return total


# Played cards are drawn smaller than hand cards: measured digit height 15px
# (outside seats) and 23px (own seat) against 30px in the hand, so the corner
# art is resampled by 0.50 / 0.77 onto a white card of the measured size.
# Real play frames of the 6 training clips (digit top -> small-pip bottom, median):
# outside 28px (55 pairs), own seat 42px (58 pairs) = 0.50 / 0.77 x the hand's
# 54px, so a scaled hand corner (digit + gap + pip) lands where the real play
# card has it. Only the top card of a stack shows the big centre pip.
# Suits are never labelled and never classified: pips are background the model
# must learn to ignore (it used to read a small club under a 3 as THREE).
PLAY_CARD = {
    "outside": {"w": 44, "h": 62, "scale": 0.50, "pad": 1},
    "self": {"w": 68, "h": 90, "scale": 0.77, "pad": 2},
}
BIG_PIP_ZOOM = 2.0     # big pip = small pip x2 (measured 19-20px outside, 28px own seat)
BIG_PIP_DROP = 22.0    # outside px (scale 0.50) from the digit top down to the big pip top
BIG_PIP_CX = 0.55      # big pip centre as a share of the card width
PIP_NEG_DIR = "_pipneg"


def play_card(w: int, h: int) -> np.ndarray:
    card = np.full((h, w, 3), FACE, np.uint8)
    cv2.rectangle(card, (0, 0), (w - 1, h - 1), (214, 214, 214), 1)
    return card


def paste_dark(canvas: np.ndarray, patch: np.ndarray, x: int, y: int) -> None:
    """Paste ``patch`` clipped to the canvas, the darker pixel wins (ink on a white face)."""

    x0, y0 = max(0, x), max(0, y)
    x1 = min(canvas.shape[1], x + patch.shape[1])
    y1 = min(canvas.shape[0], y + patch.shape[0])
    if x1 <= x0 or y1 <= y0:
        return
    roi = canvas[y0:y1, x0:x1]
    np.minimum(roi, patch[y0 - y:y1 - y, x0 - x:x1 - x], out=roi)


# The tribute badge (red disc with a white "贡") sits where a TWO's suit pip should be.
# Share of near-white pixels in the middle of the pip box, measured on the 6 training
# clips: badge 0.33-0.35, every real suit pip <= 0.12.
PIP_BADGE_WHITE = 0.22


def is_badge(pip: np.ndarray) -> bool:
    h, w = pip.shape[:2]
    centre = pip[h // 4:h - h // 4, w // 4:w - w // 4]
    if centre.size == 0:
        return False
    return float((centre.min(axis=2) > 170).mean()) > PIP_BADGE_WHITE


def find_pip(tile: np.ndarray, off, glyph):
    """Locate the small suit pip under the rank glyph of a hand corner tile.

    Returns (x0, y0, x1, y1) in tile pixels (end exclusive, 1px margin) or None
    when the tile is cut above the pip (hand row 2) or nothing pip-like is there.
    """

    gx, gy = off
    gw, gh = glyph
    y_start = gy + gh
    if y_start + 4 >= tile.shape[0]:
        return None
    ink = ink_mask(tile)
    cx0, cx1 = max(0, gx - 2), min(tile.shape[1], gx + gw + 3)
    profile = ink[y_start:, cx0:cx1].sum(axis=1)
    rows = np.flatnonzero(profile > 0)
    if rows.size == 0:
        return None
    start = int(rows[0])
    end, zeros = start, 0
    for y in range(start, profile.size):
        if profile[y] > 0:
            end, zeros = y, 0
        else:
            zeros += 1
            if zeros >= 3:
                break
    height = end - start + 1
    if not (2 <= start <= 10) or not (PIP_MIN_H <= height <= PIP_MAX_H):
        return None
    if y_start + end >= tile.shape[0] - 1:
        return None  # pip touches the tile edge: it was cut
    cols = np.flatnonzero(ink[y_start + start:y_start + end + 1, cx0:cx1].sum(axis=0) > 0)
    x0, x1 = cx0 + int(cols[0]), cx0 + int(cols[-1]) + 1
    box = (max(0, x0 - 1), y_start + start - 1, min(tile.shape[1], x1 + 1), y_start + end + 2)
    if is_badge(tile[box[1]:box[3], box[0]:box[2]]):
        return None  # tribute badge, not a suit pip
    return box


def compose_play_card(tile, off, glyph, spec, pip=None, big=False, joker=False):
    """Render one play-scale card: scaled hand corner + small pip (+ big pip).

    ``pip`` is a hand-scale pip crop grafted under the glyph when the hand tile
    has none of its own (rows 3..9 only appear in hand row 2, which the 140px
    strip cuts above the pip). Returns (card, (off_x, off_y), (glyph_w, glyph_h));
    the label box is the rank glyph only, never the pip.
    """

    s = spec["scale"]
    card = play_card(spec["w"], spec["h"])
    own = None if joker else find_pip(tile, off, glyph)
    if own is None and not joker:
        # A cut tile keeps the top rows of its real pip (its own suit). Wipe them so
        # only the grafted pip remains and the small and big pip always agree.
        tile = tile.copy()
        below = off[1] + glyph[1] + 1
        if below < tile.shape[0]:
            tile[below:, max(0, off[0] - 2):off[0] + glyph[0] + 3] = FACE
    resized = cv2.resize(
        tile, (max(1, round(tile.shape[1] * s)), max(1, round(tile.shape[0] * s))),
        interpolation=cv2.INTER_AREA,
    )
    x0 = y0 = spec["pad"]
    height = min(resized.shape[0], card.shape[0] - y0)
    width = min(resized.shape[1], card.shape[1] - x0)
    card[y0:y0 + height, x0:x0 + width] = resized[:height, :width]
    # The hand tile also contains the *next* card's glyph (hand pitch 42 < tile
    # width). Wipe everything right of this card's own glyph/pip so a stacked play
    # does not show a ghost glyph on the last card.
    right = off[0] + glyph[0]
    if own:
        right = max(right, own[2])
    glyph_right = x0 + round(right * s) + 2
    if glyph_right < spec["w"]:
        card[y0:y0 + height, max(x0, glyph_right):spec["w"] - 1] = FACE
    gx = x0 + round(off[0] * s)
    gy = y0 + round(off[1] * s)
    gw = max(3, round(glyph[0] * s))
    gh = max(5, round(glyph[1] * s))
    pip_img = None
    if own:
        pip_img = tile[own[1]:own[3], own[0]:own[2]]
    elif pip is not None and not joker:
        pip_img = pip
        small = cv2.resize(
            pip, (max(3, round(pip.shape[1] * s)), max(3, round(pip.shape[0] * s))),
            interpolation=cv2.INTER_AREA,
        )
        paste_dark(card, small, gx, gy + gh + round((PIP_GAP - 1) * s))
    if big and pip_img is not None:
        z = s * BIG_PIP_ZOOM
        big_img = cv2.resize(
            pip_img, (max(3, round(pip_img.shape[1] * z)), max(3, round(pip_img.shape[0] * z))),
            interpolation=cv2.INTER_AREA if z < 1 else cv2.INTER_LINEAR,
        )
        bx = round(spec["w"] * BIG_PIP_CX - big_img.shape[1] / 2)
        by = gy + round(BIG_PIP_DROP * s / 0.5)
        paste_dark(card, big_img, bx, by)
    return card, (gx, gy), (gw, gh)


def build_pip_bank(bank: SpriteBank) -> list:
    """[(pip crop, source clip)] cut from real row-1 hand tiles of the training clips."""

    pips = []
    for entry in bank.entries:
        if entry["scale"] != "hand" or entry["rank"] in JOKERS or entry["tile_h"] < HAND_ROW1_H - 4:
            continue
        tile = cv2.imread(str(C.DATA_ROOT / entry["path"]))
        if tile is None:
            continue
        box = find_pip(tile, (entry["off_x"], entry["off_y"]), (entry["glyph_w"], entry["glyph_h"]))
        if box:
            pips.append((tile[box[1]:box[3], box[0]:box[2]].copy(), entry["source"]))
    return pips


def derive_play_tiles(bank: SpriteBank, pips: list) -> dict:
    """Build play-scale corners (with suit pips) from truth-labelled hand corners.

    Every rank gets a plain card and a ``*_top`` card that also shows the big
    centre pip (only the top card of a played stack shows it).
    """

    if not pips:
        raise SystemExit("no suit pip found on any row-1 hand tile: check HAND_ROW1_H / find_pip")
    stats = Counter()
    rng = np.random.default_rng(11)
    hand_entries = [entry for entry in bank.entries if entry["scale"] == "hand"]
    for entry in hand_entries:
        tile = cv2.imread(str(C.DATA_ROOT / entry["path"]))
        if tile is None:
            continue
        joker = entry["rank"] in JOKERS
        pip = pips[int(rng.integers(len(pips)))][0]
        for scale, spec in PLAY_CARD.items():
            for suffix, big in (("", False), ("_top", True)):
                card, off, glyph = compose_play_card(
                    tile, (entry["off_x"], entry["off_y"]), (entry["glyph_w"], entry["glyph_h"]),
                    spec, pip, big and not joker, joker,
                )
                if bank.add(card, off, entry["rank"], scale + suffix, entry["source"], glyph):
                    stats[f"{scale}{suffix}:{entry['rank']}"] += 1
    return dict(stats)


def _face_block(w: int, h: int, rng) -> np.ndarray:
    block = np.full((h, w, 3), FACE, np.uint8)
    left, top, right, bottom = (bool(v) for v in rng.integers(0, 2, 4))
    edge = (214, 214, 214)
    if left:
        block[:, 0] = edge
    if top:
        block[0, :] = edge
    if right:
        block[:, -1] = edge
    if bottom:
        block[-1, :] = edge
    return block


def extract_pip_negatives(bank: SpriteBank, pips: list, per_kind: int = 60) -> int:
    """Suit-only play-zone patches with no rank glyph: small pip, big pip, a pip
    cut by the crop edge and a bare half card body. Never labelled."""

    if not pips:
        return 0
    rng = np.random.default_rng(23)
    total = 0
    for kind in ("small", "big", "cut", "half"):
        for _ in range(per_kind):
            scale_name = ("outside", "self")[int(rng.integers(2))]
            spec = PLAY_CARD[scale_name]
            image, source = pips[int(rng.integers(len(pips)))]
            if kind == "half":
                patch = _face_block(int(rng.integers(18, spec["w"] + 1)), int(rng.integers(10, 36)), rng)
            else:
                zoom = spec["scale"] * (BIG_PIP_ZOOM if kind == "big" else 1.0)
                pip_img = cv2.resize(
                    image, (max(3, round(image.shape[1] * zoom)), max(3, round(image.shape[0] * zoom))),
                    interpolation=cv2.INTER_AREA if zoom < 1 else cv2.INTER_LINEAR,
                )
                margin = int(rng.integers(1, 5))
                patch = _face_block(pip_img.shape[1] + 2 * margin, pip_img.shape[0] + 2 * margin, rng)
                paste_dark(patch, pip_img, margin, margin)
                if kind == "cut":
                    cut = int(rng.integers(1, max(2, patch.shape[0] // 2)))
                    patch = patch[cut:] if rng.random() < 0.5 else patch[:-cut]
            if bank.add_negative(patch, source, PIP_NEG_DIR):
                total += 1
    return total


def build_mosaics(bank: SpriteBank) -> None:
    out_dir = C.SPRITE_DIR / "_mosaic"
    out_dir.mkdir(parents=True, exist_ok=True)
    for rank in C.RANKS:
        tiles = [e for e in bank.entries if e["rank"] == rank]
        if not tiles:
            continue
        cols, zoom = 8, 4
        cell_w = max(t["tile_w"] for t in tiles) * zoom + 6
        cell_h = max(t["tile_h"] for t in tiles) * zoom + 6
        rows = (len(tiles) + cols - 1) // cols
        sheet = np.full((rows * cell_h + 28, cols * cell_w, 3), 30, np.uint8)
        cv2.putText(sheet, f"{rank}  n={len(tiles)}", (6, 20), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 1)
        for index, entry in enumerate(tiles):
            tile = cv2.imread(str(C.DATA_ROOT / entry["path"]))
            if tile is None:
                continue
            tile = cv2.resize(tile, None, fx=zoom, fy=zoom, interpolation=cv2.INTER_NEAREST)
            row, col = divmod(index, cols)
            y, x = 28 + row * cell_h + 3, col * cell_w + 3
            sheet[y:y + tile.shape[0], x:x + tile.shape[1]] = tile
            cv2.putText(sheet, entry["scale"][0], (x + 2, y + tile.shape[0] - 4),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 255, 0), 1)
        cv2.imwrite(str(out_dir / f"{rank}.png"), sheet)
    negatives = sorted((C.SPRITE_DIR / "_neg").glob("*.png"))[:48]
    if negatives:
        sheet = np.full((4 * 130, 12 * 130, 3), 30, np.uint8)
        for index, path in enumerate(negatives[:48]):
            img = cv2.imread(str(path))
            if img is None:
                continue
            img = cv2.resize(img, (126, 126))
            row, col = divmod(index, 12)
            sheet[row * 130 + 2:row * 130 + 128, col * 130 + 2:col * 130 + 128] = img
        cv2.imwrite(str(out_dir / "_negatives.png"), sheet)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-play", action="store_true")
    parser.add_argument("--skip-neg", action="store_true")
    args = parser.parse_args()

    C.SPRITE_DIR.mkdir(parents=True, exist_ok=True)
    truth = C.load_rank_truth()
    bank = SpriteBank()
    pitch: defaultdict = defaultdict(list)

    print("[1/4] hand sprites from 9局 truth clips")
    hand_count, labelled = extract_hand(bank, truth)
    print(f"  hand tiles: {hand_count}, labelled frames: {len(labelled)}")

    pips = build_pip_bank(bank)
    print(f"  suit pips cut from row-1 hand tiles: {len(pips)}")

    play_stats = {}
    if not args.skip_play:
        print("[2/4] play-zone geometry + empty crops (old MNN model locates corners)")
        from oldmodel import OldDetector

        play_stats = extract_play(bank, OldDetector(), pitch)
        print(f"  play geometry: {play_stats}")
        derived = derive_play_tiles(bank, pips)
        print(f"  play tiles derived from hand glyphs: {sum(derived.values())}")
    if not args.skip_neg:
        print("[3/4] negatives")
        print(f"  negatives: {extract_negatives(bank)}")
        print(f"  suit-only negatives: {extract_pip_negatives(bank, pips)}")

    bank.save()
    build_mosaics(bank)
    written = save_real_hand_frames(labelled, C.SYNTH_DIR / "real_hand")
    print(f"[4/4] real hand frames: {written}")

    summary = Counter(entry["rank"] for entry in bank.entries)
    by_scale = Counter((entry["scale"], entry["rank"]) for entry in bank.entries)
    pitch_summary = {f"{scale}/n{n}": sorted(values) for (scale, n), values in pitch.items()}
    C.save_json(
        C.SPRITE_DIR / "summary.json",
        {
            "tiles_total": len(bank.entries),
            "per_rank": dict(summary),
            "per_scale_rank": {f"{s}:{r}": c for (s, r), c in by_scale.items()},
            "pitch_samples": {k: v[:20] for k, v in pitch_summary.items()},
            "real_hand_frames": written,
        },
    )
    print("per-rank:", dict(summary))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
