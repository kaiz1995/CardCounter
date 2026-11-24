"""Evaluate rank detection on real crops the models never trained on.

Set A (primary): hand crops from the three 9局 clips held out of sprite
extraction and training (SVID_..._145608 / 145637 / 145704). Ground truth is
the hand vector in g2-analysis/rank-ground-truth.md plus the glyph geometry
measured on the frame itself (the game sorts the hand by rank), so no model
output is used as truth. Frames are sampled across each clip's stable hand
window; the hand does not move, so the 240 crops cover three distinct hands.

Set B (spot check): real app screenshots under app/src/androidTest/assets whose
file names state the public play. Used to show play-zone behaviour and A
false positives; the labels are the file names, verified by eye.

Both models get the exact app preprocessing for the hand zone
(handYoloBitmap -> 870x870 canvas -> 320x320). Latency is measured on the same
machine, single thread, CPU.

Usage: python tools/training/eval_crops.py [--onnx path] [--frames-per-clip 80]
"""

from __future__ import annotations

import argparse
import os
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

os.environ.setdefault("USERNAME", os.environ.get("LOGNAME", "codex"))

sys.path.insert(0, str(Path(__file__).resolve().parent))

import cv2  # noqa: E402
import numpy as np  # noqa: E402

import common as C  # noqa: E402
import extract_sprites as E  # noqa: E402

CONF = 0.25
IOU_MATCH = 0.3
# Hand glyphs are ~10x11 px in model space, so an IoU gate alone turns a 2px
# offset into a missed card *and* a false positive. A detection also counts as
# a match when its centre is within half a glyph of the truth centre.
CENTRE_TOL = 0.5


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


def repair_rows(rows: list[list[list[int]]], expected: int) -> list[list[int]] | None:
    rows = [[list(g) for g in row] for row in rows]
    total = sum(len(row) for row in rows)
    if total > expected:
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
    # Row order must be preserved: row 1 holds the high cards, row 2 the rest,
    # which is exactly how the truth vector maps onto the hand.
    flat = [g for row in rows for g in sorted(row, key=lambda g: g[0])]
    # A glyph that the row split clipped into a sliver would otherwise score as
    # a permanent miss; grow it back to the median glyph size around its centre.
    widths = [g[2] - g[0] for g in flat]
    heights = [g[3] - g[1] for g in flat]
    median_w = float(np.median(widths)) if widths else 0
    median_h = float(np.median(heights)) if heights else 0
    grown = []
    for g in flat:
        w, h = g[2] - g[0], g[3] - g[1]
        if w < 0.6 * median_w or h < 0.6 * median_h:
            cx, cy = (g[0] + g[2]) // 2, (g[1] + g[3]) // 2
            g = [cx - int(median_w // 2), cy - int(median_h // 2),
                 cx + int(median_w // 2), cy + int(median_h // 2)]
        grown.append(g)
    return grown


def grid_ok(rows: list[list[list[int]]], tol: float = 5.0) -> bool:
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


class OnnxModel:
    def __init__(self, path: Path, threads: int = 1):
        import onnxruntime as ort

        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        self.session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
        self.input_name = self.session.get_inputs()[0].name
        self.times: list[float] = []

    def detect(self, model_input_bgr: np.ndarray) -> list[dict]:
        tensor = (model_input_bgr[:, :, ::-1].astype(np.float32) / 255.0).transpose(2, 0, 1)[None]
        started = time.perf_counter()
        raw = self.session.run(None, {self.input_name: np.ascontiguousarray(tensor)})[0]
        self.times.append((time.perf_counter() - started) * 1000.0)
        return decode_new(raw[0], CONF, 0.45)


def decode_new(raw: np.ndarray, conf: float, iou: float) -> list[dict]:
    scores = raw[4:]
    best = scores.max(axis=0)
    class_ids = scores.argmax(axis=0)
    mask = best >= conf
    if not mask.any():
        return []
    cx, cy, bw, bh = raw[0][mask], raw[1][mask], raw[2][mask], raw[3][mask]
    boxes = np.stack([cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2], axis=1)
    from oldmodel import nms

    keep = nms(boxes, best[mask], iou)
    return [
        {
            "class": int(class_ids[mask][i]),
            "conf": float(best[mask][i]),
            "box": boxes[i].tolist(),
        }
        for i in keep
    ]


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


def evaluate_hand_set(onnx_model: OnnxModel, old_model, frames_per_clip: int, details: dict):
    truth = C.load_rank_truth()
    tp, fp, fn = Counter(), Counter(), Counter()
    per_clip = []
    crops = 0
    old_times: list[float] = []
    for clip in C.EVAL_HAND_CLIPS:
        video = C.ensure_allowed(C.NINE_GAMES / clip)
        key = C.clip_key(video)
        expected = C.expanded_hand(truth[key]["counts"])
        anchor = float(truth[key]["frame_s"])
        # The truth vector describes the hand as dealt: within ~1s the game
        # starts moving cards (进贡/还贡/首出), so only this window is valid.
        window = 1.3
        stamps = [anchor - 0.8 + i * (window / frames_per_clip) for i in range(frames_per_clip)]
        frames = C.grab_frames(video, stamps)
        accepted = 0
        for second, frame in frames.items():
            strip = C.hand_strip(frame)
            bands = E.hand_row_bands(strip)
            if not bands:
                continue
            rows = []
            for band_top in bands:
                band = strip[band_top:band_top + 38]
                glyphs = E.clusters_from_mask(E.ink_mask(band))
                for g in glyphs:
                    g[1] += band_top
                    g[3] += band_top
                rows.append(sorted(glyphs, key=lambda g: g[0]))
            if not grid_ok(rows):
                continue
            clusters = repair_rows(rows, len(expected))
            if clusters is None:
                continue
            accepted += 1
            model_input = C.model_input_hand(strip)
            scale = C.MODEL_INPUT / (C.HAND_RIGHT - C.HAND_LEFT)
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
                for g, rank in zip(clusters, [C.RANKS[i] for i in expected])
            ]
            new_det = onnx_model.detect(model_input)
            started = time.perf_counter()
            canvas = C.hand_canvas(strip)
            old_det = old_model.detect(canvas)
            old_times.append((time.perf_counter() - started) * 1000.0)
            canvas_scale = C.MODEL_INPUT / float(canvas.shape[1])
            old_merged = [
                {
                    "class": C.RANK_TO_ID[d["rank"]],
                    "conf": d["conf"],
                    "box": [value * canvas_scale for value in d["box"]],
                }
                for d in old_det
            ]
            for boxes, bucket in ((new_det, "new"), (old_merged, "old")):
                t, f, m = match(boxes, truths)
                target = details[bucket]
                target["tp"].update(t)
                target["fp"].update(f)
                target["fn"].update(m)
            crops += 1
        per_clip.append({"clip": key, "cards": truth[key]["total"], "accepted_frames": accepted})
    old_model.times = old_times
    return crops, per_clip


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


def evaluate_screenshots(onnx_model: OnnxModel, old_model, details: dict) -> list[dict]:
    """Real app screenshots with the play stated in the file name."""

    # Expected plays confirmed by eye on each screenshot (the file name alone is not
    # enough: current_a_right_bottom shows two stacked aces, club + diamond).
    cases = [
        ("current_a_right_bottom.png", "RIGHT_BOTTOM", "ACE", 2),
        ("current_left_bottom_7x4.png", "LEFT_BOTTOM", "SEVEN", 4),
        ("current_right_top_7x2_overcount.png", "RIGHT_TOP", "SEVEN", 2),
        ("current_pass_and_sixes.png", "SELF", "SIX", 1),
    ]
    base = C.ROOT / "app" / "src" / "androidTest" / "assets"
    results = []
    for name, zone, expected_rank, expected_count in cases:
        path = base / name
        if not path.is_file():
            continue
        frame = cv2.imread(str(path))
        if frame is None:
            continue
        frame = cv2.resize(frame, (C.FRAME_W, C.FRAME_H), interpolation=cv2.INTER_AREA)
        crop = C.zone_crop(frame, zone)
        image, scale, dx, dy = C.letterbox(crop)
        new_det = onnx_model.detect(image)
        old_det = old_model.detect(cv2.resize(crop, (C.MODEL_INPUT, C.MODEL_INPUT)))
        expected_id = C.RANK_TO_ID[expected_rank]
        new_hits = [d for d in new_det if d["class"] == expected_id]
        old_merged = [d for d in old_det if C.RANK_TO_ID[d["rank"]] == expected_id]
        ace_id = C.RANK_TO_ID["ACE"]
        results.append(
            {
                "file": name,
                "zone": zone,
                "expected_rank": expected_rank,
                "expected_count": expected_count,
                "new_detections": len(new_det),
                "new_expected_rank": len(new_hits),
                "new_ace_fp": len([d for d in new_det if d["class"] == ace_id]) - len(
                    [d for d in new_hits if d["class"] == ace_id]
                ),
                "old_detections": len(old_det),
                "old_expected_rank": len(old_merged),
                "new_classes": dict(Counter(C.RANKS[d["class"]] for d in new_det)),
                "old_classes": dict(Counter(d["rank"] for d in old_det)),
            }
        )
        for bucket, dets in (("new", new_det), ("old", old_det)):
            details[bucket]["shots"].append(
                {
                    "file": name,
                    "expected": f"{expected_rank}x{expected_count}",
                    "hits": len(new_hits) if bucket == "new" else len(old_merged),
                }
            )
    return results


def evaluate_empty_play_zones(onnx_model: OnnxModel, old_model, limit: int = 200) -> dict:
    """False positives on real play-zone crops that contain no cards.

    A crop is "empty" when under 2% of its pixels look like card white, which is
    independent of both models. Every detection on these crops is a false
    positive, so this is the honest measure of the A over-reporting problem
    that motivates the rebuild.
    """

    crops = 0
    new_hits = Counter()
    old_hits = Counter()
    new_crops_with_det = 0
    old_crops_with_det = 0
    started_new = started_old = 0.0
    per_video = Counter()
    per_video_limit = limit // len(C.EMPTY_ZONE_EVAL_VIDEOS)
    for video in [C.ensure_allowed(C.SOURCE / n) for n in C.EMPTY_ZONE_EVAL_VIDEOS]:
        capture = C.open_video(video)
        duration = capture.get(cv2.CAP_PROP_FRAME_COUNT) / max(1.0, capture.get(cv2.CAP_PROP_FPS))
        capture.release()
        times = [float(t) for t in np.arange(30.0, min(duration, 500.0), 3.0)]
        for second, frame in C.grab_frames(video, times).items():
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
                image, _, _, _ = C.letterbox(crop)
                t0 = time.perf_counter()
                det_new = onnx_model.detect(image)
                started_new += time.perf_counter() - t0
                t0 = time.perf_counter()
                det_old = old_model.detect(cv2.resize(crop, (C.MODEL_INPUT, C.MODEL_INPUT)))
                started_old += time.perf_counter() - t0
                crops += 1
                per_video[C.clip_key(video)] += 1
                if det_new:
                    new_crops_with_det += 1
                    new_hits.update(C.RANKS[d["class"]] for d in det_new)
                if det_old:
                    old_crops_with_det += 1
                    old_hits.update(d["rank"] for d in det_old)
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
        "seconds": {"new": round(started_new, 1), "old": round(started_old, 1)},
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--onnx", default=str(C.ROOT / "models" / "poker_rank15_yolo11n_320.onnx"))
    parser.add_argument("--frames-per-clip", type=int, default=240)
    args = parser.parse_args()

    onnx_path = Path(args.onnx)
    if not onnx_path.is_file():
        raise SystemExit(f"onnx not found: {onnx_path}")

    from oldmodel import OldDetector

    onnx_model = OnnxModel(onnx_path)
    old_model = OldDetector(threads=1)
    details = {
        "new": {"tp": Counter(), "fp": Counter(), "fn": Counter(), "shots": []},
        "old": {"tp": Counter(), "fp": Counter(), "fn": Counter(), "shots": []},
    }
    crops, per_clip = evaluate_hand_set(onnx_model, old_model, args.frames_per_clip, details)
    shots = evaluate_screenshots(onnx_model, old_model, details)
    empty_zones = evaluate_empty_play_zones(onnx_model, old_model)

    payload = {
        "onnx": str(onnx_path),
        "conf": CONF,
        "iou_match": IOU_MATCH,
        "hand_crops": crops,
        "per_clip": per_clip,
        "new": {
            "per_class": metrics(details["new"]["tp"], details["new"]["fp"], details["new"]["fn"]),
            "latency_ms": {
                "median": round(float(np.median(onnx_model.times)), 2),
                "p95": round(float(np.percentile(onnx_model.times, 95)), 2),
            },
        },
        "old": {
            "per_class": metrics(details["old"]["tp"], details["old"]["fp"], details["old"]["fn"]),
            "latency_ms": {
                "median": round(float(np.median(old_model.times)), 2),
                "p95": round(float(np.percentile(old_model.times, 95)), 2),
            },
        },
        "screenshots": shots,
        "empty_play_zones": empty_zones,
    }
    C.save_json(C.RUNS_DIR / "eval" / "eval.json", payload)

    print(f"hand crops: {crops}  per clip: {per_clip}")
    print(f"{'rank':12s} {'new P':>6s} {'new R':>6s} {'old P':>6s} {'old R':>6s}")
    for new_row, old_row in zip(payload["new"]["per_class"], payload["old"]["per_class"]):
        print(
            f"{new_row['rank']:12s} {str(new_row['precision']):>6s} {str(new_row['recall']):>6s} "
            f"{str(old_row['precision']):>6s} {str(old_row['recall']):>6s}"
        )
    print("latency new:", payload["new"]["latency_ms"], "old:", payload["old"]["latency_ms"])
    for shot in shots:
        print("shot:", shot["file"], shot["expected_rank"], "new:", shot["new_classes"], "old:", shot["old_classes"])
    print("empty play zones:", empty_zones["crops"], "crops; new:", empty_zones["new"],
          "old:", empty_zones["old"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
