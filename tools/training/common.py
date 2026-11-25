"""Shared constants, whitelist gate, geometry and IO for tools/training.

The geometry below is a faithful copy of the app's crops, measured against
real 1280x578 recordings:

* play zones:  ``PlayZone.yoloCrop`` (app/src/main/java/.../capture/PlayZone.kt)
* hand strip:  ``CaptureProbeService.handYoloBitmap`` (HAND_YOLO_* constants)
* rank-only labels: boxes cover the rank glyph inside the card corner.

Nothing here may read a video that is not on the whitelist: ``ensure_allowed``
raises for blind/tuning recordings and for unknown paths.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
DATA_ROOT = Path(r"D:\CardCounterData")
SPRITE_DIR = DATA_ROOT / "sprites"
SYNTH_DIR = DATA_ROOT / "synth"
RUNS_DIR = DATA_ROOT / "runs"

VIDEO_ROOT = Path(r"D:\HuaweiMoveData\Users\张大脸小太阳\Documents\workbuddy\记牌器开发")
NINE_GAMES = VIDEO_ROOT / "9局"
SOURCE = VIDEO_ROOT / "source"
TRUTH_MD = VIDEO_ROOT / "g2-analysis" / "rank-ground-truth.md"

MODEL_INPUT = 320
LETTERBOX_PAD = 114  # 114/255 per DATA-FORMATS.md section 4

# Class id == Rank enum order (DATA-FORMATS.md section 4). Do not reorder.
RANKS = [
    "BIG_JOKER",
    "SMALL_JOKER",
    "TWO",
    "ACE",
    "KING",
    "QUEEN",
    "JACK",
    "TEN",
    "NINE",
    "EIGHT",
    "SEVEN",
    "SIX",
    "FIVE",
    "FOUR",
    "THREE",
]
RANK_TO_ID = {name: index for index, name in enumerate(RANKS)}

# Old 28-class model (app/src/main/java/.../vision/YoloLabelMap.kt).
OLD_LABELS = [
    "RA", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R9", "R10", "RJ", "RQ", "RK",
    "BA", "B2", "B3", "B4", "B5", "B6", "B7", "B8", "B9", "B10", "BJ", "BQ", "BK",
    "D", "X",
]
_OLD_SUFFIX_TO_RANK = {
    "2": "TWO", "A": "ACE", "K": "KING", "Q": "QUEEN", "J": "JACK", "10": "TEN",
    "9": "NINE", "8": "EIGHT", "7": "SEVEN", "6": "SIX", "5": "FIVE", "4": "FOUR",
    "3": "THREE",
}


def old_label_to_rank(label: str) -> str:
    if label == "D":
        return "BIG_JOKER"
    if label == "X":
        return "SMALL_JOKER"
    return _OLD_SUFFIX_TO_RANK[label[1:]]


# --------------------------------------------------------------------------
# video whitelist (DATA-FORMATS.md section 1)
# --------------------------------------------------------------------------

ALLOWED_NINE_GAMES = {
    "SVID_20260728_145252_1.mp4",
    "SVID_20260728_145333_1.mp4",
    "SVID_20260728_145406_1.mp4",
    "SVID_20260728_145436_1.mp4",
    "SVID_20260728_145511_1.mp4",
    "SVID_20260728_145541_1.mp4",
    "SVID_20260728_145608_1.mp4",
    "SVID_20260728_145637_1.mp4",
    "SVID_20260728_145704_1.mp4",
}
ALLOWED_SOURCE = {
    "SVID_20260810_102658_1.mp4",  # dev
    "SVID_20260810_143651_1.mp4",  # dev
    "SVID_20260811_075639_1.mp4",  # dev
    "SVID_20260727_201930_1.mp4",  # clip
    "SVID_20260728_135222_1.mp4",  # clip
    "SVID_20260811_214558_1.mp4",  # clip
}
# Hard deny: blind and tuning sets must never be opened by training scripts.
DENIED_TOKENS = ("20260815_202234", "20260815_210429", "20260813_164333")

DEV_VIDEOS = ["SVID_20260810_102658_1.mp4", "SVID_20260810_143651_1.mp4", "SVID_20260811_075639_1.mp4"]
CLIP_VIDEOS = ["SVID_20260727_201930_1.mp4", "SVID_20260728_135222_1.mp4", "SVID_20260811_214558_1.mp4"]

# Hand-zone validation split: three clips never used as sprite/training source.
EVAL_CLIPS = ["SVID_20260728_145608_1.mp4", "SVID_20260728_145637_1.mp4", "SVID_20260728_145704_1.mp4"]

# All three hold-out clips are scored. 145608's truth vector (2 big jokers + 1
# small joker = 34 cards) matches the screen from 8.5 s to 12.0 s, which covers
# the whole eval window [anchor-0.8, anchor+0.5]; the hand only drops to 33
# cards at ~13 s. Earlier versions skipped it because eval_crops' own connected-
# component locator lost a thin J (its broken hook fragments fell under the size
# gates, see eval_crops._band_rows), which is a locator problem, not a
# truth/screen mismatch.
EVAL_HAND_CLIPS = list(EVAL_CLIPS)

# The only source/ recordings allowed to give negative (no-card) patches to training.
NEGATIVE_SOURCE_VIDEOS = ["SVID_20260810_102658_1.mp4"]
# source/ recordings used to measure play-zone card geometry (numbers only, no
# pixels enter training). Must stay disjoint from the evaluation recordings.
PLAY_GEOMETRY_VIDEOS = ["SVID_20260810_102658_1.mp4", "SVID_20260727_201930_1.mp4"]
# Empty play-zone false-positive check: recordings that never fed a negative.
# Must stay disjoint from NEGATIVE_SOURCE_VIDEOS (guarded by test_whitelist.py).
EMPTY_ZONE_EVAL_VIDEOS = ["SVID_20260810_143651_1.mp4", "SVID_20260811_075639_1.mp4"]


class NotWhitelisted(RuntimeError):
    """Raised when a script touches a video outside the allowed sets."""


# Case/separator-normalised absolute locations. The gate compares the *whole*
# parent directory, never just its last path component, so a same-named folder
# anywhere else (D:/x/source/..., C:/tmp/9局/...) is rejected.
_NINE_DIR = os.path.normcase(str(NINE_GAMES.resolve()))
_SOURCE_DIR = os.path.normcase(str(SOURCE.resolve()))
_NINE_NAMES = {os.path.normcase(n) for n in ALLOWED_NINE_GAMES}
_SOURCE_NAMES = {os.path.normcase(n) for n in ALLOWED_SOURCE}


def ensure_allowed(path: str | Path) -> Path:
    """Return the resolved path, or raise if it is blind/tuning/unknown."""

    resolved = Path(path).resolve()
    name = os.path.normcase(resolved.name)
    parent = os.path.normcase(str(resolved.parent))
    text = str(resolved).replace("\\", "/")
    for token in DENIED_TOKENS:
        if token in text:
            raise NotWhitelisted(f"refusing to read non-whitelisted video (denied token {token}): {resolved}")
    if name in _NINE_NAMES and parent == _NINE_DIR:
        return resolved
    if name in _SOURCE_NAMES and parent == _SOURCE_DIR:
        return resolved
    raise NotWhitelisted(f"refusing to read video outside whitelist: {resolved}")


def nine_games(train_only: bool = False) -> list[Path]:
    names = sorted(ALLOWED_NINE_GAMES)
    if train_only:
        names = [n for n in names if n not in EVAL_CLIPS]
    return [ensure_allowed(NINE_GAMES / n) for n in names]


def source_videos(kind: str = "dev") -> list[Path]:
    names = DEV_VIDEOS if kind == "dev" else CLIP_VIDEOS
    return [ensure_allowed(SOURCE / n) for n in names]


def open_video(path: str | Path) -> cv2.VideoCapture:
    """Only entry point that may open a recording: whitelist gate first."""

    resolved = ensure_allowed(path)
    capture = cv2.VideoCapture(str(resolved))
    if not capture.isOpened():
        raise RuntimeError(f"cannot open video: {resolved}")
    return capture


def grab_frames(path: str | Path, seconds) -> dict[float, np.ndarray]:
    """Seek-and-read helper. Returns {second: bgr frame}."""

    capture = open_video(path)
    frames: dict[float, np.ndarray] = {}
    try:
        for second in seconds:
            capture.set(cv2.CAP_PROP_POS_MSEC, float(second) * 1000.0)
            ok, frame = capture.read()
            if ok and frame is not None:
                frames[float(second)] = frame
    finally:
        capture.release()
    return frames


def read_window(path: str | Path, start_s: float, end_s: float) -> list[tuple[int, float, np.ndarray]]:
    """Every physical frame of the video inside [start_s, end_s].

    Returns [(frame_index, second, bgr frame)]. The clip's real frame rate sets
    the sampling, so a 60 fps clip yields ~60 frames per second (not a fixed
    count that would score the same frame several times).
    """

    capture = open_video(path)
    frames: list[tuple[int, float, np.ndarray]] = []
    try:
        fps = capture.get(cv2.CAP_PROP_FPS) or 30.0
        first = max(0, int(round(start_s * fps)))
        last = int(round(end_s * fps))
        capture.set(cv2.CAP_PROP_POS_FRAMES, first)
        for index in range(first, last + 1):
            ok, frame = capture.read()
            if not ok or frame is None:
                break
            frames.append((index, index / fps, frame))
    finally:
        capture.release()
    return frames


# --------------------------------------------------------------------------
# crop geometry (mirrors the app)
# --------------------------------------------------------------------------

FRAME_W, FRAME_H = 1280, 578

# name -> (left_ratio, right_ratio, rank_y_ratio, visibility_height, source)
ZONES = {
    "TOP": (0.539, 0.602, 0.022, 70, "OUTSIDE"),
    "LEFT_TOP": (0.148, 0.266, 0.156, 56, "OUTSIDE"),
    "LEFT_BOTTOM": (0.141, 0.281, 0.429, 70, "OUTSIDE"),
    "RIGHT_TOP": (0.750, 0.859, 0.156, 56, "OUTSIDE"),
    "RIGHT_BOTTOM": (0.750, 0.859, 0.429, 70, "OUTSIDE"),
    "SELF": (0.438, 0.563, 0.358, 28, "SELF"),
}

HAND_LEFT, HAND_RIGHT, HAND_TOP, HAND_BOTTOM, HAND_PAD_TOP = 180, 1050, 350, 490, 540


def zone_crop_rect(zone: str, width: int = FRAME_W, height: int = FRAME_H) -> tuple[int, int, int, int]:
    left_ratio, right_ratio, rank_ratio, visibility, source = ZONES[zone]
    left = round(width * left_ratio)
    right = round(width * right_ratio)
    crop_w = right - left + 1
    crop_h = crop_w if source == "SELF" else visibility
    rank_y = round(height * rank_ratio)
    if source == "SELF":
        top = rank_y - crop_h * 43 // 100
    else:
        top = rank_y - crop_h // 2
    top = max(0, min(top, height - crop_h))
    return left, top, crop_w, crop_h


def zone_crop(frame: np.ndarray, zone: str) -> np.ndarray:
    left, top, crop_w, crop_h = zone_crop_rect(zone, frame.shape[1], frame.shape[0])
    return frame[top:top + crop_h, left:left + crop_w]


def hand_strip(frame: np.ndarray) -> np.ndarray:
    return frame[HAND_TOP:HAND_BOTTOM, HAND_LEFT:HAND_RIGHT]


def hand_canvas(strip: np.ndarray, fill: int = 0) -> np.ndarray:
    """Reproduce handYoloBitmap: 870x870 int array, strip parked at y=540."""

    width = HAND_RIGHT - HAND_LEFT
    rows = HAND_PAD_TOP + strip.shape[0]
    canvas = np.full((max(rows, width), width, 3), fill, np.uint8)
    canvas[HAND_PAD_TOP:HAND_PAD_TOP + strip.shape[0], : strip.shape[1]] = strip
    return canvas[: max(rows, width)]


def model_input_hand(strip: np.ndarray) -> np.ndarray:
    return cv2.resize(hand_canvas(strip), (MODEL_INPUT, MODEL_INPUT), interpolation=cv2.INTER_LINEAR)


def letterbox(img: np.ndarray, size: int = MODEL_INPUT, pad: int = LETTERBOX_PAD):
    """Aspect-preserving resize with centered padding, same as the app contract."""

    h, w = img.shape[:2]
    scale = min(size / w, size / h)
    new_w, new_h = max(1, round(w * scale)), max(1, round(h * scale))
    resized = cv2.resize(img, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
    out = np.full((size, size, 3), pad, np.uint8)
    dx, dy = (size - new_w) // 2, (size - new_h) // 2
    out[dy:dy + new_h, dx:dx + new_w] = resized
    return out, scale, dx, dy


def unletterbox_boxes(boxes, scale, dx, dy) -> np.ndarray:
    """Inverse of boxes_to_letterbox: model pixels -> source-crop pixels."""

    boxes = np.asarray(boxes, np.float32).reshape(-1, 4).copy()
    boxes[:, [0, 2]] -= dx
    boxes[:, [1, 3]] -= dy
    return boxes / scale


def stable_hash(*parts) -> int:
    """Process-independent hash (the builtin hash() of str is randomised per run)."""

    digest = hashlib.sha1("|".join(str(p) for p in parts).encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big")


def boxes_to_letterbox(boxes, scale, dx, dy) -> np.ndarray:
    """boxes: Nx4 (x0, y0, x1, y1) in source pixels -> model pixels."""

    boxes = np.asarray(boxes, np.float32).reshape(-1, 4)
    out = boxes * scale
    out[:, [0, 2]] += dx
    out[:, [1, 3]] += dy
    return out


def boxes_to_yolo_labels(boxes, class_ids, size: int = MODEL_INPUT) -> list[str]:
    lines = []
    for (x0, y0, x1, y1), cls in zip(boxes, class_ids):
        cx, cy = (x0 + x1) / 2 / size, (y0 + y1) / 2 / size
        bw, bh = (x1 - x0) / size, (y1 - y0) / size
        if bw <= 0 or bh <= 0:
            continue
        lines.append(f"{int(cls)} {cx:.6f} {cy:.6f} {bw:.6f} {bh:.6f}")
    return lines


# --------------------------------------------------------------------------
# rank ground truth (g2-analysis/rank-ground-truth.md)
# --------------------------------------------------------------------------

_TRUTH_ROW = re.compile(r"^\|\s*(\d{6})\s*\|\s*(\d+)s\s*\|\s*([\d,]+)\s*=\s*(\d+)\s*\|$")


def load_rank_truth(path: Path = TRUTH_MD) -> dict[str, dict]:
    """Return {clip_6digits: {counts: [15 ints], total: int, frame_s: int}}."""

    truth: dict[str, dict] = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        match = _TRUTH_ROW.match(line.strip())
        if not match:
            continue
        clip, frame_s, counts, total = match.groups()
        values = [int(v) for v in counts.split(",")]
        if len(values) != len(RANKS):
            raise ValueError(f"{clip}: expected {len(RANKS)} rank counts, got {len(values)}")
        if sum(values) != int(total):
            raise ValueError(f"{clip}: counts sum {sum(values)} != declared total {total}")
        truth[clip] = {"counts": values, "total": int(total), "frame_s": int(frame_s)}
    if not truth:
        raise ValueError(f"no truth rows parsed from {path}")
    return truth


def expanded_hand(counts: list[int]) -> list[int]:
    """Sorted descending hand -> class id per card, left to right."""

    cards: list[int] = []
    for index in range(len(RANKS)):
        cards.extend([index] * counts[index])
    return cards


def clip_key(video: str | Path) -> str:
    """SVID_20260728_145252_1.mp4 -> 145252 (matches truth keys)."""

    parts = Path(video).name.split("_")
    return parts[2][-6:] if len(parts) >= 3 and parts[0] == "SVID" else Path(video).stem


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path, default=None):
    if not Path(path).is_file():
        return default
    return json.loads(Path(path).read_text(encoding="utf-8"))


def save_json(path: Path, payload) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
