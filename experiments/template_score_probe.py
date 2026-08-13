"""HandRankMatcher template score approximation on video frames (not Android pixel-equivalent)."""
import json
from pathlib import Path
import cv2
import numpy as np
from PIL import Image

W, H = 1280, 578
NORMAL_THRESHOLD = 0.55
ZONES = {"TOP": (.539, .602, .022), "LEFT_TOP": (.148, .266, .156),
         "LEFT_BOTTOM": (.141, .281, .429), "RIGHT_TOP": (.750, .859, .156),
         "RIGHT_BOTTOM": (.750, .859, .429)}


def template(path):
    gray = np.asarray(Image.open(path).convert("L"), dtype=np.uint8)
    return gray < 180


def scene_ink(frame):
    # Android source reads RGB plane; OpenCV frame is BGR.
    r, g, b = frame[:, :, 2].astype(np.int32), frame[:, :, 1].astype(np.int32), frame[:, :, 0].astype(np.int32)
    return ((30 * r + 59 * g + 11 * b) / 100) < 180


def best_score(ink, zone, tmpl):
    left, right, rank_y = ZONES[zone]
    x0, x1 = round(W * left), round(W * right)
    best = {"score": 0.0, "x": None, "y": None}
    h, w = tmpl.shape
    for y in range(round(H * rank_y) - 4, round(H * rank_y) + 5):
        for x in range(x0, x1 - w + 1):
            patch = ink[y:y + h, x:x + w]
            if patch.shape != tmpl.shape:
                continue
            inter = np.logical_and(patch, tmpl).sum()
            union = np.logical_or(patch, tmpl).sum()
            score = float(inter / union) if union else 0.0
            if score > best["score"]:
                best = {"score": score, "x": x, "y": y}
    return best


def main():
    base = Path(__file__).parents[1]
    assets = base / "app/src/main/res/drawable-nodpi"
    templates = {r: template(assets / f"rank_{name}.png") for r, name in (("K", "king"), ("9", "nine"), ("3", "three"))}
    cap = cv2.VideoCapture(str(base / "source/SVID_20260810_102658_1.mp4"))
    probes = [("E039", 518.178, "LEFT_BOTTOM"), ("E065", 532.355, "LEFT_TOP")]
    rows = []
    for event, t, zone in probes:
        for offset in (-0.10, -0.05, 0.0, 0.05, 0.10):
            cap.set(cv2.CAP_PROP_POS_MSEC, (t + offset) * 1000)
            ok, frame = cap.read()
            if not ok:
                continue
            ink = scene_ink(frame)
            rows.append({"event": event, "time_s": round(cap.get(cv2.CAP_PROP_POS_MSEC) / 1000, 6), "zone": zone,
                         "offset_s": offset,
                         "scores": {rank: best_score(ink, zone, tmpl) for rank, tmpl in templates.items()}})
    cap.release()
    out = base / "experiments/results/template_score_probe.json"
    out.write_text(json.dumps({"approximation": True, "threshold": NORMAL_THRESHOLD,
                               "template_sizes": {k: list(v.shape[::-1]) for k, v in templates.items()},
                               "rows": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(out), "rows": len(rows), "template_sizes": {k: list(v.shape[::-1]) for k, v in templates.items()}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
