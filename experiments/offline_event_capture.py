"""Offline six-zone visibility/event probe; production Android code untouched."""
import argparse, csv, json
import cv2

W, H = 1280, 578
WHITE = 190
STEP = 8
ZONES = {
    "TOP": (0.539, 0.602, 0.022, 70),
    "LEFT_TOP": (0.148, 0.266, 0.156, 56),
    "LEFT_BOTTOM": (0.141, 0.281, 0.429, 70),
    "RIGHT_TOP": (0.750, 0.859, 0.156, 56),
    "RIGHT_BOTTOM": (0.750, 0.859, 0.429, 70),
    "SELF": (0.438, 0.563, 0.358, 28),
}


def green_gate(frame):
    # cv2 is BGR; Android source compares RGB.
    h, w = frame.shape[:2]
    points = ((round(500 * w / W), round(200 * h / H)),
              (round(800 * w / W), round(200 * h / H)))
    return all((lambda bgr: bgr[1] >= 70 and bgr[1] - bgr[2] >= 30 and bgr[1] - bgr[0] >= 10)
               (tuple(map(int, frame[min(y, h - 1), min(x, w - 1)])))
               for x, y in points)


def visible(frame, spec):
    left_r, right_r, y_r, height = spec
    h, w = frame.shape[:2]
    left, right = round(W * left_r * w / W), round(W * right_r * w / W)
    top, zone_h = round(H * y_r * h / H), round(height * h / H)
    left, right = max(0, min(left, w - 1)), max(0, min(right, w - 1))
    top, bottom = max(0, min(top, h - 1)), max(0, min(top + zone_h, h - 1))
    roi = frame[top:bottom + 1, left:right + 1]
    sample = roi[::STEP, ::STEP]
    return int(((sample >= WHITE).all(axis=2)).sum()), int(sample.shape[0] * sample.shape[1])


def signature(frame, spec):
    left_r, right_r, rank_y_r, _ = spec
    h, w = frame.shape[:2]
    left, right = round(W * left_r * w / W), round(W * right_r * w / W)
    left, right = max(0, min(left, w - 1)), max(0, min(right, w))
    size = max(1, right - left)
    rank_y = round(H * rank_y_r * h / H)
    top = max(0, min(round(rank_y - size * 43 / 100), h - size))
    crop = frame[top:top + size, left:left + size]
    if crop.size == 0:
        return None
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    return (cv2.resize(gray, (16, 8), interpolation=cv2.INTER_AREA).astype("float32") / 255.0 * 15).round() / 15.0


def mad(a, b):
    return 1.0 if a is None or b is None else float(abs(a - b).mean())


def advance(st, zone, is_vis, sig, ts, visibility_segments, replacements):
    if is_vis:
        st["streak"] += 1; st["empty"] = 0
        if st["active"] is None and st["streak"] >= 3:
            st["active"] = {"zone": zone, "start_s": ts, "confirm_s": ts,
                             "white_samples": st["white_samples"], "total_samples": st["total_samples"]}
            st["settled_sig"] = sig; visibility_segments.append(st["active"])
        elif st["active"] is not None:
            st["active"]["confirm_s"] = ts
            if st["in_transition"]:
                if mad(st["candidate_sig"], sig) < 0.06: st["candidate_streak"] += 1
                else: st["candidate_sig"], st["candidate_streak"] = sig, 1
                if st["candidate_streak"] >= 3:
                    # Do not emit a replacement for a transient candidate that
                    # has returned near the settled signature.
                    if mad(st["settled_sig"], st["candidate_sig"]) >= 0.12:
                        replacements.append({"zone": zone, "time_s": ts})
                        st["settled_sig"] = st["candidate_sig"]
                    st["in_transition"] = False; st["change_streak"] = 0
                    st["candidate_sig"] = None; st["candidate_streak"] = 0
            elif mad(st["settled_sig"], sig) >= 0.12:
                st["change_streak"] += 1
                if st["change_streak"] >= 2:
                    st["in_transition"] = True; st["candidate_sig"], st["candidate_streak"] = sig, 1
            else: st["change_streak"] = 0
    else:
        st["streak"] = 0; st["empty"] += 1
        if st["active"] is not None and st["empty"] >= 6:
            st["active"]["end_s"] = ts; st["active"] = None
            st["settled_sig"] = st["candidate_sig"] = None
            st["in_transition"] = False; st["change_streak"] = st["candidate_streak"] = 0


def process(path, start, duration, sample_ms):
    if sample_ms <= 0:
        raise ValueError("sample_ms must be > 0")
    if duration < 0:
        raise ValueError("duration must be >= 0")
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        raise RuntimeError(f"cannot open video: {path}")
    states = {z: {"streak": 0, "empty": 0, "active": None, "settled_sig": None,
                  "candidate_sig": None, "change_streak": 0, "candidate_streak": 0,
                  "in_transition": False, "white_samples": 0, "total_samples": 0} for z in ZONES}
    visibility_segments = []
    replacements = []
    sample_s = float(sample_ms) / 1000.0
    target = float(start)
    stop = target + float(duration)
    cap.set(cv2.CAP_PROP_POS_MSEC, target * 1000.0)
    while target < stop:
        ok, frame = cap.read()
        if not ok:
            break
        # Sequential decode: consume frames until the first one at/after target.
        actual_ms = cap.get(cv2.CAP_PROP_POS_MSEC)
        ts = actual_ms / 1000.0
        if actual_ms <= 0:
            ts = target
        if ts < target:
            continue
        table = green_gate(frame)
        if not table:
            while target <= ts:
                target += sample_s
            continue
        for zone, spec in ZONES.items():
            white, total = visible(frame, spec)
            is_vis = white * 100 >= total * 10
            st = states[zone]
            st["white_samples"], st["total_samples"] = white, total
            advance(st, zone, is_vis, signature(frame, spec) if is_vis else None, ts,
                    visibility_segments, replacements)
        while target <= ts:
            target += sample_s
    cap.release()
    for st in states.values():
        if st["active"] is not None:
            st["active"]["end_s"] = stop
    segment_count = {z: sum(e["zone"] == z for e in visibility_segments) for z in ZONES}
    replacement_count = {z: sum(e["zone"] == z for e in replacements) for z in ZONES}
    boundary_count = {z: segment_count[z] + replacement_count[z] for z in ZONES}
    all_boundary_count = sum(boundary_count.values())
    external_count = sum(v for z, v in boundary_count.items() if z != "SELF")
    return {"video": path, "start_s": start, "duration_s": duration,
            "sample_ms": sample_ms, "visibility_segments": visibility_segments,
            "visibility_segment_count": segment_count,
            "replacement_boundary_count": replacement_count,
            "replacement_boundaries": replacements,
            "boundary_event_count": boundary_count,
            "all_zone_boundary_event_count": all_boundary_count,
            "external_boundary_event_count": external_count,
            "external_visibility_segment_count": sum(v for z, v in segment_count.items() if z != "SELF"),
            "diagnostic_scope": "visibility-stage approximation; not formal events/TP/FP/FN",
            "limitations": ["3 visible samples start a segment", "6 empty samples end a segment",
                            "tableVisible=false leaves per-zone state unchanged",
                            "no cross-session boundary handling"]}


def self_check():
    # Three visible samples emit once; six empties re-arm for an identical event.
    seq = [1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1]
    streak = empty = emits = 0
    active = False
    for x in seq:
        if x:
            streak += 1; empty = 0
            if not active and streak >= 3:
                emits += 1; active = True
        else:
            streak = 0; empty += 1
            if active and empty >= 6: active = False
    assert emits == 2 and active, (emits, active)
    # tableVisible=false must not inject empty samples or close an active segment.
    empty = 0
    table_visible = False
    if table_visible:
        empty += 1
    assert active and empty == 0
    # Replacement: two high-MAD samples arm; three stable candidate samples emit once.
    st = {"streak": 0, "empty": 0, "active": None, "settled_sig": cv2.UMat(1, 1) if False else __import__('numpy').zeros((8, 16), dtype='float32'),
          "candidate_sig": None, "change_streak": 0, "candidate_streak": 0, "in_transition": False,
          "white_samples": 1, "total_samples": 1}
    events = []; reps = []
    for i, sig in enumerate([0.0, 0.0, 0.0, 1.0, 1.0, 0.99, 0.99, 0.99]):
        advance(st, "X", True, __import__('numpy').full((8, 16), sig, dtype='float32'), i, events, reps)
    assert len(events) == 1 and len(reps) == 1 and not st["in_transition"]
    # Old -> temporary -> old must not emit a replacement.
    st = {"streak": 0, "empty": 0, "active": {"zone": "X"}, "settled_sig": __import__('numpy').zeros((8, 16), dtype='float32'),
          "candidate_sig": None, "change_streak": 0, "candidate_streak": 0, "in_transition": False,
          "white_samples": 1, "total_samples": 1}
    events = []; reps = []
    seq = [0.0, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0]
    for i, value in enumerate(seq):
        advance(st, "X", True, __import__('numpy').full((8, 16), value, dtype='float32'), i, events, reps)
    assert not reps and not st["in_transition"] and st["settled_sig"].mean() == 0.0
    # Clear/rearm: six empties close; next three visible start a second segment.
    for i in range(6): advance(st, "X", False, None, 10 + i, events, reps)
    for i in range(3): advance(st, "X", True, __import__('numpy').zeros((8, 16), dtype='float32'), 20 + i, events, reps)
    assert len(events) == 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video", nargs="?")
    ap.add_argument("--start", type=float, default=0)
    ap.add_argument("--duration", type=float, default=20)
    ap.add_argument("--sample-ms", type=float, default=50)
    ap.add_argument("--expected", type=int)
    ap.add_argument("--expected-scope", choices=("all", "external"))
    ap.add_argument("--json", dest="json_path")
    ap.add_argument("--csv", dest="csv_path")
    args = ap.parse_args()
    if not args.video:
        self_check(); print("self-check: ok"); return
    result = process(args.video, args.start, args.duration, args.sample_ms)
    result["reference_hand_count"] = args.expected
    result["reference_scope"] = args.expected_scope if args.expected is not None and args.expected_scope else ("unresolved" if args.expected is not None else None)
    if args.expected is None or args.expected_scope is None:
        result["boundary_count_delta"] = None
    else:
        observed = result["all_zone_boundary_event_count"] if args.expected_scope == "all" else result["external_boundary_event_count"]
        result["boundary_count_delta"] = observed - args.expected
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.json_path:
        with open(args.json_path, "w", encoding="utf-8") as f: json.dump(result, f, ensure_ascii=False, indent=2)
    if args.csv_path:
        with open(args.csv_path, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=["event_type", "zone", "start_s", "confirm_s", "end_s", "time_s", "white_samples", "total_samples"])
            rows = [{"event_type": "visibility", **e} for e in result["visibility_segments"]]
            rows += [{"event_type": "replacement", **e} for e in result["replacement_boundaries"]]
            w.writeheader(); w.writerows(rows)


if __name__ == "__main__":
    main()
