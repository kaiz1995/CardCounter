import csv, json
from pathlib import Path

import cv2
from ai_edge_litert.interpreter import Interpreter

from offline_event_capture import ZONES, green_gate, visible
from tflite_truth_probe import detect, parse_truth_cards

EXTERNAL = tuple(z for z in ZONES if z != "SELF")
SAMPLE_MS = 50.0
CLEAR_SAMPLES = 6
STABLE_SAMPLES = 2


def run(video, start, duration):
    base = Path(__file__).parents[1]
    interpreter = Interpreter(model_path=str(base / "app/src/main/assets/poker_yolo11_320.tflite"))
    interpreter.allocate_tensors()
    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise RuntimeError(f"cannot open video: {video}")
    states = {z: {"candidate": None, "streak": 0, "empty": 0, "accepted": None} for z in EXTERNAL}
    events = []
    sample_s = SAMPLE_MS / 1000.0
    target, stop = float(start), float(start + duration)
    cap.set(cv2.CAP_PROP_POS_MSEC, target * 1000.0)
    while target < stop:
        ok, frame = cap.read()
        if not ok:
            break
        actual_ms = cap.get(cv2.CAP_PROP_POS_MSEC)
        ts = actual_ms / 1000.0 if actual_ms > 0 else target
        if ts < target:
            continue
        table = green_gate(frame)
        for zone in EXTERNAL:
            st = states[zone]
            if not table:
                continue
            white, total = visible(frame, ZONES[zone])
            is_visible = white * 100 >= total * 10
            if not is_visible:
                st["candidate"], st["streak"] = None, 0
                st["empty"] = min(CLEAR_SAMPLES, st["empty"] + 1)
                if st["empty"] >= CLEAR_SAMPLES:
                    st["accepted"] = None
                continue
            st["empty"] = 0
            cards, _, confidence = detect(interpreter, frame, zone)
            if not cards:
                st["candidate"], st["streak"] = None, 0
                continue
            if cards == st["candidate"]:
                st["streak"] += 1
            else:
                st["candidate"], st["streak"] = cards, 1
            if st["streak"] >= STABLE_SAMPLES and cards != st["accepted"]:
                events.append({"time_s": round(ts, 6), "zone": zone, "cards": cards,
                               "confidence": round(confidence, 4)})
                st["accepted"] = cards
        while target <= ts:
            target += sample_s
    cap.release()
    return events


def score(events, truth_path):
    truth = [dict(e) for e in csv.DictReader(open(truth_path, encoding="utf-8")) if e["zone"] in EXTERNAL]
    parsed_truth = [{**e, "time_s": float(e["time_s"]), "counts": parse_truth_cards(e["cards"])} for e in truth]
    used = set(); matches = []
    for pred in events:
        choices = [(abs(pred["time_s"] - t["time_s"]), i, t) for i, t in enumerate(parsed_truth)
                   if i not in used and t["zone"] == pred["zone"] and pred["cards"] == t["counts"]
                   and abs(pred["time_s"] - t["time_s"]) <= 0.3]
        if not choices:
            continue
        _, i, t = min(choices)
        used.add(i)
        matches.append({"event": pred, "truth": t})
    tp = len(matches)
    fp = len(events) - tp
    fn = len(parsed_truth) - tp
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    return {"truth_events": len(parsed_truth), "predicted_events": len(events), "tp": tp,
            "fp": fp, "fn": fn, "precision": precision, "recall": recall, "matches": matches}


def main():
    base = Path(__file__).parents[1]
    events = run(base / "source/SVID_20260810_102658_1.mp4", 516.0, 20.0)
    result = {"video": "source/SVID_20260810_102658_1.mp4", "start_s": 516.0,
              "duration_s": 20.0, "sample_ms": SAMPLE_MS, "stable_samples": STABLE_SAMPLES,
              "clear_samples": CLEAR_SAMPLES, "events": events,
              **score(events, base / "experiments/results/truth_102658_516_536.csv")}
    out = base / "experiments/results/tflite_temporal_probe_102658.json"
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("predicted_events", "tp", "fp", "fn", "precision", "recall")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
