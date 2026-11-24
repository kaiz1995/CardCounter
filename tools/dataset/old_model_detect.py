"""用旧 28 类 MNN 模型按 100ms 采样六个出牌区，输出 JSONL 检测流。

输入: app/src/main/assets/poker_yolo11_320.mnn（输出 [1,32,2100]，4 框 + 28 类）
输出: D:\\CardCounterData\\detections\\<gameId>.jsonl
每行: {"ts_ms", "table_visible", "zones": {zone: {"white_ratio", "still"}}, "dets": {zone: [...]}}

坐标约定: 模型输出的 cx/cy/w/h 是 320x320 输入画布的像素（非 0..1 归一化）。
本脚本与 :app 的 MnnYoloDetector 有一处差异：app 直接把裁剪拉伸到 320x320，
派活单要求等比缩放 + letterbox，这里按派活单实现（填充 114）。

用法:
    python tools/dataset/old_model_detect.py [--games g102658-3] [--limit 1]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

import cv2
import MNN
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402

STEP_MS = 100
INFER_WHITE_GATE = 0.02   # 白区占比低于此值不做推理（同 app 的 isPlayVisible 语义）
STILL_PIXEL_DELTA = 12
STILL_CHANGED_RATIO = 0.03


def prepare_still_input(crop: np.ndarray) -> np.ndarray:
    small = cv2.resize(crop, (64, 32), interpolation=cv2.INTER_AREA)
    return small.astype(np.int16)


def is_still(previous: np.ndarray | None, current: np.ndarray) -> bool:
    if previous is None:
        return False
    changed = (np.abs(current - previous).max(axis=2) > STILL_PIXEL_DELTA)
    return float(changed.mean()) < STILL_CHANGED_RATIO


class Detector:
    def __init__(self, model_path: Path, threads: int = 4):
        self.interp = MNN.Interpreter(str(model_path))
        self.session = self.interp.createSession(
            {"numThread": threads, "backend": "CPU"}
        )
        self.input = self.interp.getSessionInput(self.session)

    def detect(self, crop: np.ndarray):
        tensor, _scale, _dx, _dy = ds.letterbox_rgb(crop, 320)
        mnn_tensor = MNN.Tensor(
            (1, 3, 320, 320),
            MNN.Halide_Type_Float,
            tensor,
            MNN.Tensor_DimensionType_Caffe,
        )
        self.input.copyFrom(mnn_tensor)
        self.interp.runSession(self.session)
        output = self.interp.getSessionOutput(self.session)
        pred = np.array(output.getData(), dtype=np.float32).reshape(output.getShape())
        return ds.decode_output(pred)


def iter_sample_frames(path: Path, start_ms: int, end_ms: int, step_ms: int):
    """顺序解码，按 step_ms 产出 (t_ms, frame)。"""
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise SystemExit(f"打不开视频: {path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    index = 0
    next_t = start_ms
    while True:
        if not cap.grab():
            break
        # 容器时间戳；部分录屏是 VFR，index/fps 会漂到 0.7s
        t = cap.get(cv2.CAP_PROP_POS_MSEC)
        if index == 0 or t <= 0:
            t = index * 1000.0 / fps
        if t > end_ms:
            break
        if t >= next_t:
            ok, frame = cap.retrieve()
            if not ok:
                break
            yield int(round(t)), frame
            next_t += step_ms
            while next_t <= t:
                next_t += step_ms
        index += 1
    cap.release()


def run_game(detector: Detector, game: dict, out_path: Path) -> dict:
    video = ds.SOURCE_BASE / game["video"]
    start_ms, end_ms = int(game["startMs"]), int(game["endMs"])
    previous = {zone: None for zone in ds.ZONES}
    stats = {"samples": 0, "det_samples": 0, "detections": 0}
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8", newline="\n") as handle:
        for t, frame in iter_sample_frames(video, start_ms, end_ms, STEP_MS):
            zones = {}
            dets = {}
            for zone in ds.ZONES:
                crop = ds.crop_zone(frame, zone)
                ratio = ds.white_ratio(frame, zone)
                current = prepare_still_input(crop)
                still = is_still(previous[zone], current)
                previous[zone] = current
                zones[zone] = {"white_ratio": round(ratio, 4), "still": still}
                if ratio >= INFER_WHITE_GATE:
                    found = detector.detect(crop)
                    dets[zone] = [
                        {
                            "label": item[0],
                            "rank": item[1],
                            "score": round(item[2], 4),
                            "cx": round(item[3], 2),
                            "cy": round(item[4], 2),
                            "w": round(item[5], 2),
                            "h": round(item[6], 2),
                        }
                        for item in found
                    ]
                    stats["det_samples"] += 1
                    stats["detections"] += len(found)
                else:
                    dets[zone] = []
            handle.write(
                json.dumps(
                    {
                        "ts_ms": t,
                        "table_visible": ds.table_visible(frame),
                        "zones": zones,
                        "dets": dets,
                    },
                    ensure_ascii=False,
                )
                + "\n"
            )
            stats["samples"] += 1
    return stats


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--games", help="逗号分隔的 gameId，缺省全部")
    parser.add_argument("--limit", type=int, default=0, help="只跑前 N 局")
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()

    payload = ds.load_games()
    wanted = set(args.games.split(",")) if args.games else None
    games = [
        g for g in payload["games"] if wanted is None or g["gameId"] in wanted
    ]
    if args.limit:
        games = games[: args.limit]
    if not games:
        raise SystemExit("没有匹配的牌局")

    detector = Detector(ds.ASSET_MODEL, threads=args.threads)
    for game in games:
        out = ds.DETECTIONS_DIR / f"{game['gameId']}.jsonl"
        stats = run_game(detector, game, out)
        print(
            f"{game['gameId']}: {stats['samples']} 帧, "
            f"{stats['det_samples']} 次区推理, {stats['detections']} 个框 -> {out}"
        )


if __name__ == "__main__":
    main()
