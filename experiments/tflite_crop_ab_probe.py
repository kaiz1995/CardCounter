"""Fixed ROI geometry A/B/C probe; video approximation, truth used only for scoring."""
import csv, json
from pathlib import Path
import cv2
import numpy as np
from ai_edge_litert.interpreter import Interpreter
from tflite_truth_probe import LABELS, parse_truth_cards

W, H, SIZE = 1280, 578, 320
ZONES = {
    "TOP": (.539, .602, .022, 70), "LEFT_TOP": (.148, .266, .156, 56),
    "LEFT_BOTTOM": (.141, .281, .429, 70), "RIGHT_TOP": (.750, .859, .156, 56),
    "RIGHT_BOTTOM": (.750, .859, .429, 70),
}


def crop_variant(frame, zone, variant):
    left_r, right_r, y_r, height = ZONES[zone]
    left, right = round(W * left_r), round(W * right_r)
    width = right - left + 1
    rank_y = round(H * y_r)
    if variant == "baseline":
        size = width
        top = round(rank_y - size * 43 / 100)
        top = max(0, min(top, frame.shape[0] - size)); crop = frame[top:top + size, left:right + 1]
        return cv2.resize(crop, (SIZE, SIZE), interpolation=cv2.INTER_LINEAR)
    if variant == "A":
        extra = round(width * .10)
        left, right = max(0, left - extra), min(frame.shape[1], right + extra + 1)
        size = right - left
        top = round(rank_y - size * 43 / 100)
        top = max(0, min(top, frame.shape[0] - size)); crop = frame[top:top + size, left:right]
        return cv2.resize(crop, (SIZE, SIZE), interpolation=cv2.INTER_LINEAR)
    if variant == "B":
        size = width
        top = round(rank_y - size * 43 / 100 - size * .10)
        top = max(0, min(top, frame.shape[0] - size)); crop = frame[top:top + size, left:right + 1]
        return cv2.resize(crop, (SIZE, SIZE), interpolation=cv2.INTER_LINEAR)
    if variant == "C":
        top = round(rank_y - height / 2)
        top = max(0, min(top, frame.shape[0] - height)); crop = frame[top:top + height, left:right + 1]
        h, w = crop.shape[:2]
        scale = min(SIZE / w, SIZE / h)
        resized = cv2.resize(crop, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_LINEAR)
        canvas = np.full((SIZE, SIZE, 3), 114, dtype=np.uint8)
        y0, x0 = (SIZE - resized.shape[0]) // 2, (SIZE - resized.shape[1]) // 2
        canvas[y0:y0 + resized.shape[0], x0:x0 + resized.shape[1]] = resized
        return canvas
    raise ValueError(variant)


def detect_image(interpreter, image):
    x = image[:, :, ::-1].astype(np.float32) / 255.0
    interpreter.set_tensor(interpreter.get_input_details()[0]["index"], x[None]); interpreter.invoke()
    out = interpreter.get_tensor(interpreter.get_output_details()[0]["index"])[0]
    found = []
    for i in range(out.shape[1]):
        scores = out[4:, i]; cid = int(scores.argmax()); conf = float(scores[cid])
        if conf >= .25: found.append((conf, cid, out[:4, i]))
    keep = []
    for item in sorted(found, reverse=True):
        a = item[2]; ax1, ay1, ax2, ay2 = a[0]-a[2]/2, a[1]-a[3]/2, a[0]+a[2]/2, a[1]+a[3]/2
        overlap = False
        for old in keep:
            b = old[2]; bx1, by1, bx2, by2 = b[0]-b[2]/2, b[1]-b[3]/2, b[0]+b[2]/2, b[1]+b[3]/2
            inter = max(0, min(ax2, bx2)-max(ax1, bx1))*max(0, min(ay2, by2)-max(ay1, by1))
            union = a[2]*a[3] + b[2]*b[3] - inter
            if union and inter / union > .45: overlap = True; break
        if not overlap: keep.append(item)
    counts = {}
    for _, cid, _ in keep:
        raw = LABELS[cid]; rank = "BIG_JOKER" if raw == "D" else "SMALL_JOKER" if raw == "X" else raw[1:]
        counts[rank] = counts.get(rank, 0) + 1
    return counts


def main():
    base = Path(__file__).parents[1]
    truth = list(csv.DictReader((base / "experiments/results/truth_102658_516_536.csv").open(encoding="utf-8")))
    variants = ["baseline", "A", "B", "C"]
    cap = cv2.VideoCapture(str(base / "source/SVID_20260810_102658_1.mp4"))
    all_rows = {v: [] for v in variants}
    for event in truth:
        cap.set(cv2.CAP_PROP_POS_MSEC, float(event["time_s"]) * 1000); ok, frame = cap.read()
        for variant in variants:
            pred = detect_image(it, crop_variant(frame, event["zone"], variant)) if ok else {}
            all_rows[variant].append({"event_id": event["event_id"], "truth": event["cards"],
                                      "predicted": pred, "exact_match": pred == parse_truth_cards(event["cards"])})
    cap.release()
    summary = {}
    for variant, rows in all_rows.items():
        summary[variant] = {"exact": sum(r["exact_match"] for r in rows), "total": len(rows),
                            "focus": [r for r in rows if r["event_id"] in {"G3-E039", "G3-E044", "G3-E065", "G3-E070"}]}
    out = base / "experiments/results/tflite_crop_ab_probe_102658.json"
    out.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: {"exact": v["exact"], "total": v["total"]} for k, v in summary.items()}, ensure_ascii=False))


if __name__ == "__main__":
    base = Path(__file__).parents[1]
    it = Interpreter(model_path=str(base / "app/src/main/assets/poker_yolo11_320.tflite")); it.allocate_tensors()
    main()
