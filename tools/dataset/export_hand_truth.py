"""为 dev + tuning + clip（共 33 局，严格排除 blind）导出换牌结束、第一手出牌前（含自己首出前）的放大手牌条截图并生成 ONNX 草稿。

输出:
- D:/CardCounterData/w13/hand_truth/<gameId>.png
- D:/CardCounterData/w13/hand_truth/draft.json
"""
from __future__ import annotations

import json
from pathlib import Path
import sys

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402
import hand_vision as hv  # noqa: E402
import onnx_detect as od  # noqa: E402
import phone_vision as pv  # noqa: E402

OUT_DIR = ds.DATA_ROOT / "w13" / "hand_truth"
MODEL_PATH = ds.REPO / "app" / "src" / "main" / "assets" / "poker_rank15_yolo11n_320.onnx"

# 放大手牌截图裁剪范围（1280x578 画布坐标系，覆盖完整上下两排手牌及角标）
CROP_X0 = 120
CROP_X1 = 1160
CROP_Y0 = 345
CROP_Y1 = 500
SCALE = 2
HEADER_H = 74

# 若在 prePlayEnd/firstPlayMs 之前自己已经首出过牌（或 clip 局没有 prePlayEnd/firstPlay），
# 指定换牌结束且第一手出牌（含自己首出）前的搜索窗口 (win_start_ms, win_end_ms)。
CUSTOM_TRUTH_WINDOWS: dict[str, tuple[int, int]] = {
    "g102658-1": (18000, 31000),   # 换牌结束于 ~15s，自己在 ~31.8s 首出 4个5
    "g164333-1": (2000, 8600),     # 自己在 ~8.9s 首出 1个5
    "g201930-1": (10500, 16000),   # 自己在 ~16.5s 首出
    "g135222-1": (12500, 19500),   # 自己在 ~20.0s 首出
    "g214558-1": (9000, 11500),    # 自己在 ~12.0s 首出
    "g145252-1": (11500, 17500),   # 自己在 ~18.0s 首出
    "g145333-1": (10000, 16500),   # 自己在 ~17.0s 首出
    "g145406-1": (9500, 14000),    # 自己在 ~14.5s 首出
    "g145436-1": (10000, 14500),   # 自己在 ~15.0s 首出
    "g145511-1": (7500, 13000),    # 自己在 ~13.5s 首出
    "g145541-1": (8000, 13000),    # 自己在 ~13.5s 首出
    "g145608-1": (7500, 12000),    # 自己在 ~12.5s 首出
    "g145637-1": (7000, 11500),    # 自己在 ~12.0s 首出
    "g145704-1": (7000, 10500),    # 自己在 ~11.0s 首出
}


def render_hand_truth_image(
    game: dict, ts_ms: int, bgr: np.ndarray, pos_rows: tuple, pos_total: int | None, draft_counts: dict[str, int]
) -> np.ndarray:
    screen = pv.Screen(bgr)
    strip = screen.canvas[CROP_Y0:CROP_Y1, CROP_X0:CROP_X1]
    enlarged = cv2.resize(
        strip,
        ((CROP_X1 - CROP_X0) * SCALE, (CROP_Y1 - CROP_Y0) * SCALE),
        interpolation=cv2.INTER_CUBIC,
    )
    _, w = enlarged.shape[:2]
    header = np.full((HEADER_H, w, 3), 24, dtype=np.uint8)
    line1 = (
        f"{game['gameId']}  split={game['split']}  t={ts_ms}ms  "
        f"(start={game['startMs']}, prePlayEnd={game['prePlayEndMs']}, firstPlay={game['firstPlayMs']}, end={game['endMs']})"
    )
    draft_total = hv.counts_total(draft_counts)
    line2 = (
        f"pos=({pos_rows[0]},{pos_rows[1]})={pos_total}  "
        f"ONNX_draft({draft_total}): {hv.counts_to_str(draft_counts)}"
    )
    cv2.putText(header, line1, (16, 28), cv2.FONT_HERSHEY_SIMPLEX, 0.72, (240, 240, 240), 2, cv2.LINE_AA)
    cv2.putText(header, line2, (16, 60), cv2.FONT_HERSHEY_SIMPLEX, 0.62, (120, 235, 160), 1, cv2.LINE_AA)
    return np.vstack([header, enlarged])


def process_video(video: str, games: list[dict], detector: od.RankDetector) -> list[dict]:
    path = ds.SOURCE_BASE / video
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise SystemExit(f"打不开视频: {path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0

    windows: dict[str, tuple[int, int]] = {}
    next_sample: dict[str, int] = {}
    prev_sig: dict[str, np.ndarray | None] = {}
    still_streak: dict[str, int] = {}
    candidates: dict[str, list[dict]] = {}

    for g in games:
        gid = g["gameId"]
        start = int(g["startMs"])
        end = int(g["endMs"])
        first_play = int(g["firstPlayMs"])
        if gid in CUSTOM_TRUTH_WINDOWS:
            w_start, w_end = CUSTOM_TRUTH_WINDOWS[gid]
        else:
            w_start = start
            w_end = min(end, first_play) if first_play > start else end
        windows[gid] = (w_start, w_end)
        next_sample[gid] = w_start
        prev_sig[gid] = None
        still_streak[gid] = 0
        candidates[gid] = []

    last_end = max(w[1] for w in windows.values())
    index = 0
    while True:
        if not cap.grab():
            break
        t = cap.get(cv2.CAP_PROP_POS_MSEC)
        if index == 0 or t <= 0:
            t = index * 1000.0 / fps
        index += 1
        if t > last_end + 100:
            break
        due = [g["gameId"] for g in games if windows[g["gameId"]][0] <= t <= windows[g["gameId"]][1] and t >= next_sample[g["gameId"]]]
        if not due:
            continue
        ok, frame = cap.retrieve()
        if not ok:
            break
        ts_ms = int(round(t))
        screen = pv.Screen(frame)
        table_vis = pv.is_table_visible(screen)
        for gid in due:
            while next_sample[gid] <= t:
                next_sample[gid] += hv.HAND_SAMPLE_INTERVAL_MS
            if not table_vis:
                prev_sig[gid] = None
                still_streak[gid] = 0
                continue
            sig = hv.hand_signature(frame)
            chg = hv.hand_change_percent(prev_sig[gid], sig)
            prev_sig[gid] = sig
            is_still = chg is not None and chg <= hv.STABLE_CHANGE_PERCENT
            still_streak[gid] = still_streak[gid] + 1 if is_still else 0

            pos_rows, pos_total = hv.detect_hand_positions(frame)
            dets = hv.detect_hand_boxes(detector, screen)
            counts = hv.counts_from_detections(dets)
            box_total = hv.counts_total(counts)
            candidates[gid].append({
                "ts_ms": ts_ms,
                "change": chg,
                "still_streak": still_streak[gid],
                "pos_rows": pos_rows,
                "pos_total": pos_total,
                "box_total": box_total,
                "counts": counts,
                "min_conf": min((d[1] for d in dets), default=0.0),
                "frame": frame.copy(),
            })
    cap.release()

    results: list[dict] = []
    for g in games:
        gid = g["gameId"]
        cands = candidates[gid]
        if not cands:
            raise RuntimeError(f"{gid}: 未采到任何 table_visible 帧！")
        pre = int(g["prePlayEndMs"])
        first_play = int(g["firstPlayMs"])
        if gid in CUSTOM_TRUTH_WINDOWS:
            in_golden = cands
        else:
            in_golden = [c for c in cands if (pre <= 0 or c["ts_ms"] >= pre) and (first_play <= 0 or c["ts_ms"] < first_play)]

        def pick_best(pool: list[dict]) -> dict | None:
            if not pool:
                return None
            p1 = [
                c for c in pool
                if c["still_streak"] >= 2
                and c["pos_total"] is not None
                and c["box_total"] == c["pos_total"]
                and hv.fits_within(c["counts"], hv.INVENTORY_BY_RANK)
            ]
            if p1:
                return p1[-1]
            p2 = [c for c in pool if c["still_streak"] >= 1 and c["pos_total"] is not None]
            if p2:
                return p2[-1]
            p3 = [c for c in pool if c["still_streak"] >= 1]
            if p3:
                return p3[-1]
            return pool[-1]

        chosen = pick_best(in_golden)
        used_golden = True
        if chosen is None or (chosen["still_streak"] == 0 and any(c["still_streak"] >= 1 for c in cands)):
            fallback = pick_best(cands)
            if fallback is not None:
                chosen = fallback
                used_golden = False

        assert chosen is not None
        out_img = render_hand_truth_image(
            g, chosen["ts_ms"], chosen["frame"], chosen["pos_rows"], chosen["pos_total"], chosen["counts"]
        )
        out_path = OUT_DIR / f"{gid}.png"
        cv2.imwrite(str(out_path), out_img)

        summary = {
            "gameId": gid,
            "split": g["split"],
            "video": g["video"],
            "startMs": g["startMs"],
            "prePlayEndMs": g["prePlayEndMs"],
            "firstPlayMs": g["firstPlayMs"],
            "endMs": g["endMs"],
            "usedGoldenWindow": used_golden,
            "selfHandAtMs": chosen["ts_ms"],
            "stillStreak": chosen["still_streak"],
            "changePercent": round(chosen["change"], 3) if chosen["change"] is not None else None,
            "posRows": list(chosen["pos_rows"]),
            "posTotal": chosen["pos_total"],
            "draftTotal": chosen["box_total"],
            "draftMinConf": round(chosen["min_conf"], 4),
            "draftStr": hv.counts_to_str(chosen["counts"]),
            "draftCounts": chosen["counts"],
            "imagePath": str(out_path),
            "totalCandidates": len(cands),
        }
        results.append(summary)
        print(
            f"{gid} ({g['split']}): t={chosen['ts_ms']}ms golden={used_golden} "
            f"streak={chosen['still_streak']} pos={chosen['pos_total']} draft={chosen['box_total']} "
            f"minConf={chosen['min_conf']:.2f}",
            flush=True,
        )
    return results


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    payload = ds.load_games()
    selected = [g for g in payload["games"] if g["split"] in hv.HAND_ALLOWED_SPLITS]
    for g in selected:
        hv.assert_hand_allowed(g)
    print(f"共 {len(selected)} 局（已排除 blind）", flush=True)

    detector = od.RankDetector(MODEL_PATH, threads=4)
    by_video: dict[str, list[dict]] = {}
    for g in selected:
        by_video.setdefault(g["video"], []).append(g)

    all_results: list[dict] = []
    for video, games in by_video.items():
        all_results.extend(process_video(video, games, detector))

    order = {g["gameId"]: i for i, g in enumerate(selected)}
    all_results.sort(key=lambda r: order[r["gameId"]])

    draft_path = OUT_DIR / "draft.json"
    draft_path.write_text(json.dumps(all_results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"草稿已写入 {draft_path}", flush=True)


if __name__ == "__main__":
    main()
