"""生成手牌链路的 Python vs Kotlin 逐帧一致性守卫资源（12 帧，dev + clip，绝不碰 blind/tuning）。

产物（app/src/test/resources/hand_golden/）：
* h01.png ... h12.png：1280x578 帧（仅保留手牌指纹/张数/YOLO区与7个牌桌探针点，其余涂黑）；
* expected.json：Python（hand_vision.py）算出的每帧 table_visible、signature、pos_rows、pos_count、
  detections、HandFrameMerger(single/merged)、HandPositionVoter(voted_position)、HandAutoLock(streak/qualified)。

Kotlin 端 app/.../capture/HandGoldenConsistencyTest 用手机同款代码逐帧跑完上述全部环节，必须零差异。

用法：
    python tools/dataset/make_hand_golden.py
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

OUT_DIR = ds.REPO / "app" / "src" / "test" / "resources" / "hand_golden"
MODEL_PATH = ds.REPO / "app" / "src" / "main" / "assets" / "poker_rank15_yolo11n_320.onnx"
ALLOWED_SPLITS = {"dev", "clip"}

# (id, gameId, target_ts_ms, label)
PICKS = [
    ("h01", "g102658-1", 29500, "g102658-1 稳定第1帧(33张)"),
    ("h02", "g102658-1", 29750, "g102658-1 稳定第2帧(同scene)"),
    ("h03", "g102658-1", 30000, "g102658-1 稳定第3帧(同scene)"),
    ("h04", "g102658-1", 30250, "g102658-1 稳定第4帧(触发AutoLock)"),
    ("h05", "g102658-4", 555500, "g102658-4 大手牌39张(19+20)"),
    ("h06", "g102658-4", 595000, "g102658-4 漏Q变38张(19+19)"),
    ("h07", "g143651-2", 214000, "g143651-2 张数少1(pos=32 vs model=33)"),
    ("h08", "g143651-2", 214250, "g143651-2 连续同scene下一帧"),
    ("h09", "g075639-9", 165250, "g075639-9 下排未检出(pos=None, model=34)"),
    ("h10", "g075639-9", 159500, "g075639-9 换牌过渡期"),
    ("h11", "g145252-1", 15000, "g145252-1 clip 32张带贡牌(上下排同宽16+16)"),
    ("h12", "g102658-2", 283250, "g102658-2 34张带贡牌与低把握牌"),
    ("h13", "g135222-1", 19250, "g135222-1 clip 34张(上下排同宽17+17)"),
    ("h14", "g145608-1", 11750, "g145608-1 clip 34张(17+17且大王弹起)"),
    ("h15", "g075639-10", 196250, "g075639-10 Q认成9被独立防线拦住"),
]


def grab_frames(picks: list[tuple[str, str, int, str]]) -> dict[str, np.ndarray]:
    games = {g["gameId"]: g for g in ds.load_games()["games"]}
    by_video: dict[str, list[tuple[str, int]]] = {}
    for fid, gid, ts, _ in picks:
        g = games[gid]
        if g["split"] not in ALLOWED_SPLITS:
            raise SystemExit(f"拒绝非 dev/clip 局 {gid} (split={g['split']})")
        hv.assert_hand_allowed(g)
        by_video.setdefault(g["video"], []).append((fid, ts))

    frames: dict[str, np.ndarray] = {}
    for video, items in by_video.items():
        cap = cv2.VideoCapture(str(ds.SOURCE_BASE / video))
        if not cap.isOpened():
            raise SystemExit(f"打不开视频: {video}")
        fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        wanted = {fid: ts for fid, ts in items}
        last_t = max(wanted.values()) + 500
        best_diff = {fid: 10**9 for fid in wanted}
        idx = 0
        while True:
            if not cap.grab():
                break
            t = cap.get(cv2.CAP_PROP_POS_MSEC)
            if idx == 0 or t <= 0:
                t = idx * 1000.0 / fps
            idx += 1
            if t > last_t:
                break
            ts_ms = int(round(t))
            hit = [fid for fid, target in wanted.items() if abs(ts_ms - target) < best_diff[fid] and abs(ts_ms - target) <= 45]
            if not hit:
                continue
            ok, frame = cap.retrieve()
            if not ok:
                break
            for fid in hit:
                best_diff[fid] = abs(ts_ms - wanted[fid])
                frames[fid] = frame.copy()
        cap.release()
    missing = [fid for fid, _, _, _ in picks if fid not in frames]
    if missing:
        raise SystemExit(f"未取到帧: {missing}")
    return frames


def mask_hand_frame(frame: np.ndarray) -> np.ndarray:
    """仅保留手牌区（x in [64, 1152), y in [335, 578)）及 7 个牌桌探针点，其余涂黑。"""
    h, w = frame.shape[:2]
    if (w, h) != (pv.CANVAS_W, pv.CANVAS_H):
        raise SystemExit(f"期望 1280x578 帧，实际 {w}x{h}")
    out = np.zeros_like(frame)
    x0 = w * hv.SIGNATURE_LEFT_PERCENT // 100
    x1 = w * hv.SIGNATURE_RIGHT_PERCENT // 100
    y0 = h * hv.SIGNATURE_TOP_PERCENT // 100
    out[y0:h, x0:x1] = frame[y0:h, x0:x1]
    for px, py in pv.TABLE_GREEN_PROBES:
        out[py, px] = frame[py, px]
    return out


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for old in OUT_DIR.glob("h*.png"):
        old.unlink()

    frames = grab_frames(PICKS)
    detector = od.RankDetector(MODEL_PATH, threads=4)

    merger = hv.HandFrameMerger()
    voter = hv.HandPositionVoter()
    auto_lock = hv.HandAutoLock()
    prev_sig: np.ndarray | None = None
    scene = 1

    expected_frames: list[dict] = []
    for fid, gid, target_ts, label in PICKS:
        orig = frames[fid]
        masked = mask_hand_frame(orig)

        # 验证涂黑不改变任何计算结果
        assert np.array_equal(hv.hand_signature(orig), hv.hand_signature(masked))
        assert hv.detect_hand_positions(orig) == hv.detect_hand_positions(masked)
        assert pv.is_table_visible(pv.Screen(orig)) == pv.is_table_visible(pv.Screen(masked))
        dets_orig = hv.detect_hand_boxes(detector, pv.Screen(orig))
        dets_mask = hv.detect_hand_boxes(detector, pv.Screen(masked))
        assert dets_orig == dets_mask, f"{fid} 涂黑后检测结果改变"

        cv2.imwrite(str(OUT_DIR / f"{fid}.png"), masked, [cv2.IMWRITE_PNG_COMPRESSION, 9])

        sig = hv.hand_signature(masked)
        chg = hv.hand_change_percent(prev_sig, sig)
        still_scene = chg is not None and chg <= hv.STABLE_CHANGE_PERCENT
        if prev_sig is not None and not still_scene:
            scene += 1
        prev_sig = sig

        pos_rows, pos_count = hv.detect_hand_positions(masked)
        table_vis = pv.is_table_visible(pv.Screen(masked))
        merge = merger.add(scene, dets_mask)
        valid_rows = hv.valid_row_counts(pos_rows)
        voted_rows = voter.add(scene, valid_rows)
        reading = hv.build_hand_reading(merge.merged, merge.slot_detections, voted_rows)
        voted_pos = reading.position_count
        auto_lock.observe(reading, ts_ms=target_ts)

        expected_frames.append({
            "id": fid,
            "source": f"{gid}@{target_ts}ms",
            "label": label,
            "table_visible": table_vis,
            "sig_change_percent": round(chg, 6) if chg is not None else None,
            "scene": scene,
            "pos_rows": [pos_rows[0], pos_rows[1]],
            "pos_count": pos_count,
            "detections": [
                {
                    "class": c,
                    "rank": ds.RANK_ORDER[c],
                    "conf": round(s, 6),
                    "cx": round(cx, 4),
                    "cy": round(cy, 4),
                    "w": round(w, 4),
                    "h": round(h, 4),
                }
                for c, s, cx, cy, w, h in sorted(dets_mask, key=lambda d: (round(d[3] / 12.0), d[2], d[0]))
            ],
            "single": {r: merge.single[r] for r in ds.RANK_ORDER if merge.single[r] > 0},
            "merged": {r: merge.merged[r] for r in ds.RANK_ORDER if merge.merged[r] > 0},
            "merged_frames": merge.frames,
            "merged_fell_back": merge.fell_back,
            "voted_rows": [voted_rows[0], voted_rows[1]] if voted_rows is not None else None,
            "voted_position": voted_pos,
            "structure_valid": reading.structure_valid,
            "auto_lock_streak": auto_lock.streak,
            "auto_lock_qualified": (
                {
                    "cards": {r: auto_lock.qualified[0][r] for r in ds.RANK_ORDER if auto_lock.qualified[0][r] > 0},
                    "hand_count": auto_lock.qualified[1],
                }
                if auto_lock.qualified is not None
                else None
            ),
        })

    payload = {
        "canvas": [pv.CANVAS_W, pv.CANVAS_H],
        "ranks": list(ds.RANK_ORDER),
        "frames": expected_frames,
    }
    (OUT_DIR / "expected.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    total_kb = sum(p.stat().st_size for p in OUT_DIR.glob("h*.png")) / 1024
    print(f"生成 {len(expected_frames)} 帧手牌一致性夹具，PNG 合计 {total_kb:.0f} KB -> {OUT_DIR}")


if __name__ == "__main__":
    main()
