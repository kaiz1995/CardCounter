"""Evaluate rank detection on real crops the models never trained on.

Both models get the phone's real preprocessing:

* play zones (TOP / LEFT_* / RIGHT_* / SELF): the zone crop is scaled
  proportionally and centred on a 320x320 canvas filled with 114
  (CaptureProbeService.publicPlayBitmap, i.e. common.letterbox). Crops are
  never stretched.
* hand zone: handYoloBitmap -> square 870x870 canvas (strip parked at y=540,
  everything else 0) -> direct resize to 320x320.

Set A (hand crops, primary): the three 9局 clips held out of sprite extraction
and training (SVID_..._145608 / 145637 / 145704). Truth is the hand vector in
g2-analysis/rank-ground-truth.md plus the glyph geometry measured on the frame
itself (the game sorts the hand by rank); no model output is used as truth.
Every physical video frame inside [anchor-0.8 s, anchor+0.5 s] is read at the
clip's real frame rate, frames whose hand_strip is byte-identical (MD5) are
scored once, and the report states the de-duplicated frame count and the number
of independent hands (one per clip). The glyph locator splits merged clusters
(repair_rows) BEFORE the grid check, so a frame is not rejected just because two
touching glyphs merged. When the plain clustering cannot reach the truth count it
is retried once on a closed ink mask (5x3), which reconnects thin glyphs such as
a J whose hook is broken into fragments (145608). Frames that still cannot be
located are reported as locator rejections, never as model errors.

Set B (screenshots): the four real app screenshots under
app/src/androidTest/assets. All six play zones of every screenshot are checked
against a per-zone expectation {rank: count} confirmed by eye (see SHOT_CASES;
{} = empty zone, any detection there is a false positive).

Set C (empty play zones): real play-zone crops without cards from recordings
that never fed training; every detection there is a false positive.

Latency (same machine, single thread, CPU) is measured on the same
de-duplicated hand samples for both models and reported twice: inference only
(the session.run / runSession call) and end to end (canvas + resize + tensor +
inference + decode + class-agnostic NMS).

Usage: python tools/training/eval_crops.py [--onnx path]
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys
import time
from collections import Counter
from pathlib import Path

os.environ.setdefault("USERNAME", os.environ.get("LOGNAME", "codex"))

sys.path.insert(0, str(Path(__file__).resolve().parent))

import cv2  # noqa: E402
import numpy as np  # noqa: E402

import common as C  # noqa: E402
import extract_sprites as E  # noqa: E402
import oldmodel as O  # noqa: E402

CONF = 0.25
NMS_IOU = 0.45
IOU_MATCH = 0.3
# Hand glyphs are ~10x11 px in model space, so an IoU gate alone turns a 2px
# offset into a missed card *and* a false positive. A detection also counts as
# a match when its centre is within half a glyph of the truth centre.
CENTRE_TOL = 0.5
WINDOW_BEFORE = 0.8
WINDOW_AFTER = 0.5


def _shot(**zones) -> dict[str, dict[str, int]]:
    return {zone: dict(zones.get(zone, {})) for zone in C.ZONES}


# Expected plays, confirmed zone by zone by eye on the 3x enlarged zone crops.
# The file name alone is not enough (e.g. the 7x2 screenshot also shows 9x2 and
# 6x2). Zones not listed are empty (a bare "pass" label or the play button).
SHOT_CASES = {
    "current_a_right_bottom.png": _shot(RIGHT_BOTTOM={"ACE": 2}),
    "current_left_bottom_7x4.png": _shot(LEFT_BOTTOM={"SEVEN": 4}),
    "current_right_top_7x2_overcount.png": _shot(
        TOP={"NINE": 2}, RIGHT_TOP={"SEVEN": 2}, RIGHT_BOTTOM={"SIX": 2}
    ),
    "current_pass_and_sixes.png": _shot(
        TOP={"SEVEN": 2}, LEFT_TOP={"TEN": 2}, LEFT_BOTTOM={"QUEEN": 2}, RIGHT_BOTTOM={"SIX": 2}
    ),
}


def iou_matrix(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    if len(a) == 0 or len(b) == 0:
        return np.zeros((len(a), len(b)), np.float32)
    x1 = np.maximum(a[:, None, 0], b[None, :, 0])
    y1 = np.maximum(a[:, None, 1], b[None, :, 1])
    x2 = np.minimum(a[:, None, 2], b[None, :, 2])
    y2 = np.minimum(a[:, None, 3], b[None, :, 3])
    inter = np.clip(x2 - x1, 0, None) * np.clip(y2 - y1, 0, None)
    area_a = (a[:, 2] - a[:, 0]) * (a[:, 3] - a[:, 1])
    area_b = (b[:, 2] - b[:, 0]) * (b[:, 3] - b[:, 1])
    return inter / np.maximum(area_a[:, None] + area_b[None, :] - inter, 1e-6)


# --------------------------------------------------------------------------
# hand glyph locator (truth geometry; never uses a model)
# --------------------------------------------------------------------------


def repair_row(glyphs: list[list[int]], target: int) -> list[list[int]]:
    """Split glyphs that two touching cards merged into, or drop noise.

    The hand is evenly spread, so a cluster ~2x wider than its neighbours is
    two cards. Only used for evaluation crops; sprite extraction still requires
    an exact glyph count.
    """

    glyphs = [list(g) for g in glyphs]
    while len(glyphs) < target:
        widths = [g[2] - g[0] for g in glyphs]
        median = float(np.median(widths))
        index = int(np.argmax(widths))
        if median <= 0 or widths[index] < 1.5 * median:
            return glyphs
        g = glyphs[index]
        mid = (g[0] + g[2]) // 2
        glyphs[index:index + 1] = [[g[0], g[1], mid, g[3]], [mid, g[1], g[2], g[3]]]
    while len(glyphs) > target:
        widths = [g[2] - g[0] for g in glyphs]
        glyphs.pop(int(np.argmin(widths)))
    return glyphs


def repair_rows(rows: list[list[list[int]]], expected: int) -> list[list[list[int]]] | None:
    """Split merged clusters until the glyph count equals the truth count.

    Runs before grid_ok: a merged pair of glyphs makes the next gap look twice
    as wide, which the grid check would otherwise reject. Returns the repaired
    rows (each sorted left to right) or None when the count cannot be matched.
    """

    rows = [[list(g) for g in row] for row in rows]
    if sum(len(row) for row in rows) > expected:
        return None
    guard = 0
    while sum(len(row) for row in rows) < expected and guard < 6:
        guard += 1
        ratios = []
        for row in rows:
            widths = [g[2] - g[0] for g in row]
            median = float(np.median(widths)) if widths else 1.0
            ratios.append(max(widths) / max(median, 1.0) if widths else 0.0)
        index = int(np.argmax(ratios))
        if ratios[index] < 1.5:
            return None
        rows[index] = repair_row(rows[index], len(rows[index]) + 1)
    if sum(len(row) for row in rows) != expected:
        return None
    return [sorted(row, key=lambda g: g[0]) for row in rows]


def flatten_rows(rows: list[list[list[int]]]) -> list[list[int]]:
    # Row order must be preserved: row 1 holds the high cards, row 2 the rest,
    # which is exactly how the truth vector maps onto the hand.
    flat = [g for row in rows for g in row]
    # A glyph that the row split clipped into a sliver would otherwise score as
    # a permanent miss; grow it back to the median glyph size around its centre.
    median_w = float(np.median([g[2] - g[0] for g in flat])) if flat else 0.0
    median_h = float(np.median([g[3] - g[1] for g in flat])) if flat else 0.0
    grown = []
    for g in flat:
        w, h = g[2] - g[0], g[3] - g[1]
        if w < 0.6 * median_w or h < 0.6 * median_h:
            cx, cy = (g[0] + g[2]) // 2, (g[1] + g[3]) // 2
            g = [cx - int(median_w // 2), cy - int(median_h // 2),
                 cx + int(median_w // 2), cy + int(median_h // 2)]
        grown.append(g)
    return grown


def grid_ok(rows: list[list[list[int]]]) -> bool:
    """Reject frames where the hand is still sliding in (uneven glyph grid)."""

    for row in rows:
        if len(row) < 4:
            continue
        xs = np.array([g[0] for g in row], np.float32)
        gaps = np.diff(xs)
        pitch = float(np.median(gaps))
        if pitch <= 0:
            return False
        for gap in gaps:
            if gap > pitch * 1.5 or gap < pitch * 0.55:
                return False
    return True


def _band_rows(strip: np.ndarray, bands: list[int], tolerant: bool) -> list[list[list[int]]]:
    """Glyph clusters of every row band, left to right.

    ``tolerant`` closes the ink mask first. A thin J is a 5 px stem plus a hook
    broken into 3-4 px fragments; each piece alone is under the size gates of
    ``clusters_from_mask`` so the whole glyph vanishes (145608, one J missing).
    """

    kernel = np.ones((5, 3), np.uint8)
    rows = []
    for band_top in bands:
        band = strip[band_top:band_top + 38]
        mask = E.ink_mask(band)
        if tolerant:
            mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)
        glyphs = E.clusters_from_mask(mask)
        for g in glyphs:
            g[1] += band_top
            g[3] += band_top
        rows.append(sorted(glyphs, key=lambda g: g[0]))
    return rows


def locate_hand(strip: np.ndarray, expected: int) -> tuple[list[list[int]] | None, str]:
    """Glyph boxes (strip pixels, truth order) or (None, reason).

    Plain clustering first; only when the glyph count cannot be matched is the
    tolerant (closed-mask) clustering tried. Sprite extraction does not use this.
    """

    bands = E.hand_row_bands(strip)
    if not bands:
        return None, "no_rows"
    repaired = repair_rows(_band_rows(strip, bands, False), expected)  # split merged clusters first ...
    if repaired is None:
        repaired = repair_rows(_band_rows(strip, bands, True), expected)
        if repaired is None:
            return None, "count"
    if not grid_ok(repaired):  # ... then judge the grid
        return None, "grid"
    return flatten_rows(repaired), "ok"


# --------------------------------------------------------------------------
# models (both timed the same way)
# --------------------------------------------------------------------------


def decode_new(raw: np.ndarray, conf: float, iou: float) -> list[dict]:
    scores = raw[4:]
    best = scores.max(axis=0)
    class_ids = scores.argmax(axis=0)
    mask = best >= conf
    if not mask.any():
        return []
    cx, cy, bw, bh = raw[0][mask], raw[1][mask], raw[2][mask], raw[3][mask]
    boxes = np.stack([cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2], axis=1)
    keep = O.nms(boxes, best[mask], iou)  # class-agnostic, like the phone detectors
    return [
        {"class": int(class_ids[mask][i]), "conf": float(best[mask][i]), "box": boxes[i].tolist()}
        for i in keep
    ]


class OnnxModel:
    """New 15-class model. ``pure_ms`` collects session.run time only."""

    def __init__(self, path: Path, threads: int = 1):
        import onnxruntime as ort

        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        self.session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
        self.input_name = self.session.get_inputs()[0].name
        self.pure_ms: list[float] = []

    def run(self, tensor: np.ndarray) -> np.ndarray:
        started = time.perf_counter()
        raw = self.session.run(None, {self.input_name: tensor})[0]
        self.pure_ms.append((time.perf_counter() - started) * 1000.0)
        return raw[0]

    def detect_image(self, image_320_bgr: np.ndarray) -> list[dict]:
        return decode_new(self.run(O.to_tensor(image_320_bgr)), CONF, NMS_IOU)

    def detect_hand(self, strip: np.ndarray) -> list[dict]:
        return self.detect_image(C.model_input_hand(strip))

    def detect_crop(self, crop: np.ndarray) -> list[dict]:
        image, _, _, _ = C.letterbox(crop)
        return self.detect_image(image)


class OldModel:
    """Shipped 28-class MNN model, fed like the phone. ``pure_ms`` = runSession only."""

    def __init__(self, detector):
        self.detector = detector
        self.pure_ms: list[float] = []

    def run(self, tensor: np.ndarray) -> np.ndarray:
        started = time.perf_counter()
        raw = self.detector.run_tensor(tensor)
        self.pure_ms.append((time.perf_counter() - started) * 1000.0)
        return raw

    def detect_hand(self, strip: np.ndarray) -> list[dict]:
        canvas = C.hand_canvas(strip)  # square 870x870 -> squash == proportional resize
        raw = self.run(O.preprocess(canvas))
        detections = O.decode_canvas(raw, CONF, NMS_IOU)  # boxes in 320-canvas pixels
        return [
            {"class": C.RANK_TO_ID[d["rank"]], "conf": d["conf"], "box": list(d["box"])}
            for d in detections
        ]

    def detect_crop(self, crop: np.ndarray) -> list[dict]:
        tensor, scale, dx, dy = O.preprocess_letterbox(crop)
        detections = O.decode_letterbox(self.run(tensor), scale, dx, dy, CONF, NMS_IOU)
        return [
            {"class": C.RANK_TO_ID[d["rank"]], "conf": d["conf"], "box": list(d["box"])}
            for d in detections
        ]


def latency_stats(values: list[float]) -> dict:
    if not values:
        return {"n": 0, "median": None, "p95": None}
    return {
        "n": len(values),
        "median": round(float(np.median(values)), 2),
        "p95": round(float(np.percentile(values, 95)), 2),
    }


# --------------------------------------------------------------------------
# scoring
# --------------------------------------------------------------------------


def match(detections: list[dict], truths: list[dict]) -> tuple[Counter, Counter, Counter]:
    tp, fp, fn = Counter(), Counter(), Counter()
    if truths:
        gt_boxes = np.array([t["box"] for t in truths], np.float32)
        gt_cls = [t["class"] for t in truths]
    else:
        gt_boxes, gt_cls = np.zeros((0, 4), np.float32), []
    used = [False] * len(truths)
    for det in sorted(detections, key=lambda d: -d["conf"]):
        best_iou, best_index = 0.0, None
        for index, box in enumerate(gt_boxes):
            if used[index] or gt_cls[index] != det["class"]:
                continue
            value = float(iou_matrix(np.array([det["box"]], np.float32), gt_boxes[index:index + 1])[0, 0])
            gt_centre = ((box[0] + box[2]) / 2, (box[1] + box[3]) / 2)
            det_centre = ((det["box"][0] + det["box"][2]) / 2, (det["box"][1] + det["box"][3]) / 2)
            radius = CENTRE_TOL * min(box[2] - box[0], box[3] - box[1])
            close = (
                abs(gt_centre[0] - det_centre[0]) <= radius
                and abs(gt_centre[1] - det_centre[1]) <= radius
            )
            if close:
                value = max(value, 1.0)
            if value > best_iou:
                best_iou, best_index = value, index
        if best_index is not None and best_iou >= IOU_MATCH:
            used[best_index] = True
            tp[det["class"]] += 1
        else:
            fp[det["class"]] += 1
    for index, cls in enumerate(gt_cls):
        if not used[index]:
            fn[cls] += 1
    return tp, fp, fn


def metrics(tp: Counter, fp: Counter, fn: Counter) -> list[dict]:
    rows = []
    for index, rank in enumerate(C.RANKS):
        t, f, m = tp[index], fp[index], fn[index]
        precision = t / (t + f) if (t + f) else float("nan")
        recall = t / (t + m) if (t + m) else float("nan")
        rows.append(
            {
                "class": index,
                "rank": rank,
                "tp": t,
                "fp": f,
                "fn": m,
                "precision": round(precision, 3) if precision == precision else None,
                "recall": round(recall, 3) if recall == recall else None,
            }
        )
    return rows


def evaluate_hand_set(new: OnnxModel, old: OldModel, details: dict) -> tuple[list[dict], dict]:
    """Score every distinct hand frame once. Returns (per_clip, end_to_end_ms)."""

    truth = C.load_rank_truth()
    per_clip = []
    end_to_end = {"new": [], "old": []}
    warm = np.zeros((C.HAND_BOTTOM - C.HAND_TOP, C.HAND_RIGHT - C.HAND_LEFT, 3), np.uint8)
    for _ in range(3):  # warm-up, not timed
        new.detect_hand(warm)
        old.detect_hand(warm)
    new.pure_ms.clear()
    old.pure_ms.clear()

    scale = C.MODEL_INPUT / (C.HAND_RIGHT - C.HAND_LEFT)
    for clip in C.EVAL_HAND_CLIPS:
        video = C.ensure_allowed(C.NINE_GAMES / clip)
        key = C.clip_key(video)
        expected = C.expanded_hand(truth[key]["counts"])
        ranks = [C.RANKS[i] for i in expected]
        anchor = float(truth[key]["frame_s"])
        capture = C.open_video(video)
        fps = float(capture.get(cv2.CAP_PROP_FPS))
        capture.release()
        # The truth vector describes the hand as dealt: within ~1 s the game
        # starts moving cards, so only this window is valid.
        frames = C.read_window(video, anchor - WINDOW_BEFORE, anchor + WINDOW_AFTER)
        seen: set[str] = set()
        rejected: Counter = Counter()
        accepted = 0
        for _, _, frame in frames:
            strip = C.hand_strip(frame)
            digest = hashlib.md5(strip.tobytes()).hexdigest()
            if digest in seen:
                continue
            seen.add(digest)
            clusters, reason = locate_hand(strip, len(expected))
            if clusters is None:
                rejected[reason] += 1
                continue
            accepted += 1
            truths = [
                {
                    "class": C.RANK_TO_ID[rank],
                    "box": [
                        g[0] * scale,
                        (g[1] + C.HAND_PAD_TOP) * scale,
                        g[2] * scale,
                        (min(g[3], strip.shape[0]) + C.HAND_PAD_TOP) * scale,
                    ],
                }
                for g, rank in zip(clusters, ranks)
            ]
            for name, model in (("new", new), ("old", old)):
                started = time.perf_counter()
                detections = model.detect_hand(strip)
                end_to_end[name].append((time.perf_counter() - started) * 1000.0)
                t, f, m = match(detections, truths)
                details[name]["tp"].update(t)
                details[name]["fp"].update(f)
                details[name]["fn"].update(m)
        per_clip.append(
            {
                "clip": key,
                "cards": truth[key]["total"],
                "fps": round(fps, 2),
                "physical_frames": len(frames),
                "unique_strips": len(seen),
                "scored_frames": accepted,
                "locator_rejected": dict(rejected),
            }
        )
    return per_clip, end_to_end


def evaluate_screenshots(new: OnnxModel, old: OldModel) -> list[dict]:
    """Real app screenshots, all six zones each, both models letterboxed."""

    base = C.ROOT / "app" / "src" / "androidTest" / "assets"
    rows = []
    for name, zones in SHOT_CASES.items():
        path = base / name
        frame = cv2.imread(str(path)) if path.is_file() else None
        if frame is None:
            print(f"screenshot missing: {path}")
            continue
        frame = cv2.resize(frame, (C.FRAME_W, C.FRAME_H), interpolation=cv2.INTER_AREA)
        for zone in C.ZONES:
            expected = dict(zones[zone])
            crop = C.zone_crop(frame, zone)
            new_counts = dict(Counter(C.RANKS[d["class"]] for d in new.detect_crop(crop)))
            old_counts = dict(Counter(C.RANKS[d["class"]] for d in old.detect_crop(crop)))
            rows.append(
                {
                    "file": name,
                    "zone": zone,
                    "expected": expected,
                    "new": new_counts,
                    "new_ok": new_counts == expected,
                    "old": old_counts,
                    "old_ok": old_counts == expected,
                }
            )
    return rows


def summarize_screenshots(rows: list[dict]) -> dict:
    summary = {}
    for model in ("new", "old"):
        empty = [r for r in rows if not r["expected"]]
        with_cards = [r for r in rows if r["expected"]]
        summary[model] = {
            "zones": len(rows),
            "correct": sum(r[f"{model}_ok"] for r in rows),
            "empty_zones": len(empty),
            "empty_zones_with_detection": sum(bool(r[model]) for r in empty),
            "card_zones": len(with_cards),
            "card_zones_correct": sum(r[f"{model}_ok"] for r in with_cards),
        }
    return summary


def evaluate_empty_play_zones(new: OnnxModel, old: OldModel, limit: int = 200) -> dict:
    """False positives on real play-zone crops that contain no cards.

    A crop is "empty" when under 2% of its pixels look like card white, which is
    independent of both models. Every detection on these crops is a false
    positive. The recordings are disjoint from every training source.
    """

    crops = 0
    new_hits: Counter = Counter()
    old_hits: Counter = Counter()
    new_crops_with_det = old_crops_with_det = 0
    per_video: Counter = Counter()
    per_video_limit = limit // len(C.EMPTY_ZONE_EVAL_VIDEOS)
    for video in [C.ensure_allowed(C.SOURCE / n) for n in C.EMPTY_ZONE_EVAL_VIDEOS]:
        capture = C.open_video(video)
        duration = capture.get(cv2.CAP_PROP_FRAME_COUNT) / max(1.0, capture.get(cv2.CAP_PROP_FPS))
        capture.release()
        times = [float(t) for t in np.arange(30.0, min(duration, 500.0), 3.0)]
        for _, frame in C.grab_frames(video, times).items():
            for zone in C.ZONES:
                if per_video[C.clip_key(video)] >= per_video_limit:
                    break
                crop = C.zone_crop(frame, zone)
                if crop.size == 0:
                    continue
                hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
                white = ((hsv[:, :, 2] > 170) & (hsv[:, :, 1] < 45)).mean()
                if white >= 0.02:
                    continue
                det_new = new.detect_crop(crop)
                det_old = old.detect_crop(crop)
                crops += 1
                per_video[C.clip_key(video)] += 1
                if det_new:
                    new_crops_with_det += 1
                    new_hits.update(C.RANKS[d["class"]] for d in det_new)
                if det_old:
                    old_crops_with_det += 1
                    old_hits.update(C.RANKS[d["class"]] for d in det_old)
    return {
        "crops": crops,
        "per_video": dict(per_video),
        "new": {
            "crops_with_detection": new_crops_with_det,
            "detections": dict(new_hits),
            "ace_detections": new_hits["ACE"],
        },
        "old": {
            "crops_with_detection": old_crops_with_det,
            "detections": dict(old_hits),
            "ace_detections": old_hits["ACE"],
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--onnx", default=str(C.ROOT / "models" / "poker_rank15_yolo11n_320.onnx"))
    args = parser.parse_args()

    onnx_path = Path(args.onnx)
    if not onnx_path.is_file():
        raise SystemExit(f"onnx not found: {onnx_path}")

    new = OnnxModel(onnx_path)
    old = OldModel(O.OldDetector(threads=1))
    details = {
        "new": {"tp": Counter(), "fp": Counter(), "fn": Counter()},
        "old": {"tp": Counter(), "fp": Counter(), "fn": Counter()},
    }

    per_clip, end_to_end = evaluate_hand_set(new, old, details)
    pure = {"new": list(new.pure_ms), "old": list(old.pure_ms)}
    new.pure_ms.clear()
    old.pure_ms.clear()
    latency = {
        name: {"inference_only": latency_stats(pure[name]), "end_to_end": latency_stats(end_to_end[name])}
        for name in ("new", "old")
    }
    scored = sum(item["scored_frames"] for item in per_clip)
    hands = sum(1 for item in per_clip if item["scored_frames"] > 0)

    shots = evaluate_screenshots(new, old)
    shot_summary = summarize_screenshots(shots)
    empty_zones = evaluate_empty_play_zones(new, old)

    payload = {
        "onnx": str(onnx_path),
        "conf": CONF,
        "nms_iou": NMS_IOU,
        "iou_match": IOU_MATCH,
        "hand_set": {
            "window_s": [-WINDOW_BEFORE, WINDOW_AFTER],
            "physical_frames": sum(item["physical_frames"] for item in per_clip),
            "unique_scored_frames": scored,
            "independent_hands": hands,
            "per_clip": per_clip,
        },
        "new": {"per_class": metrics(details["new"]["tp"], details["new"]["fp"], details["new"]["fn"])},
        "old": {"per_class": metrics(details["old"]["tp"], details["old"]["fp"], details["old"]["fn"])},
        "latency_ms": latency,
        "latency_samples": len(end_to_end["new"]),
        "screenshots": shots,
        "screenshot_summary": shot_summary,
        "empty_play_zones": empty_zones,
    }
    C.save_json(C.RUNS_DIR / "eval" / "eval.json", payload)

    print(f"hand set: {payload['hand_set']['physical_frames']} physical frames read, "
          f"{scored} unique scored frames, {hands} independent hands")
    for item in per_clip:
        print("  ", item)
    print(f"{'rank':12s} {'new P':>6s} {'new R':>6s} {'old P':>6s} {'old R':>6s}")
    for new_row, old_row in zip(payload["new"]["per_class"], payload["old"]["per_class"]):
        print(
            f"{new_row['rank']:12s} {str(new_row['precision']):>6s} {str(new_row['recall']):>6s} "
            f"{str(old_row['precision']):>6s} {str(old_row['recall']):>6s}"
        )
    print(f"latency (ms, {len(end_to_end['new'])} identical hand samples):")
    for name in ("new", "old"):
        print(f"  {name}: {latency[name]}")
    for row in shots:
        print(
            f"shot {row['file']:38s} {row['zone']:13s} expect {row['expected']} | "
            f"new {row['new']} {'OK' if row['new_ok'] else 'WRONG'} | "
            f"old {row['old']} {'OK' if row['old_ok'] else 'WRONG'}"
        )
    print("screenshot summary:", shot_summary)
    print("empty play zones:", empty_zones["crops"], "crops; new:", empty_zones["new"],
          "old:", empty_zones["old"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
