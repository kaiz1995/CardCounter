"""用现行 15 类 ONNX 模型按 50ms 采样，输出 DATA-FORMATS 第 5 节的逐帧观察 JSONL。

输入: app/src/main/assets/poker_rank15_yolo11n_320.onnx
输出: D:\\CardCounterData\\detections_v2\\<gameId>.jsonl

预处理、三态可见性、圆标、静止、长串扩展、letterbox 全部来自 phone_vision.py（逐项对照手机 Kotlin），
解码与 :core 的 decodeRankOutput 一致（阈值 0.25、不分类别 NMS IoU 0.45、分数同分按候选编号）。

只处理 dev 和 tuning；盲测局（g202234-1、g210429-1）和盲测录屏在这里硬拦。
自己那一区（SELF）评测不用，不写。

用法:
    python tools/dataset/onnx_detect.py [--games g102658-3,g143651-1] [--threads 4]
"""
from __future__ import annotations

import argparse
import base64
import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402
import phone_vision as pv  # noqa: E402

MODEL_PATH = ds.REPO / "app" / "src" / "main" / "assets" / "poker_rank15_yolo11n_320.onnx"
OUT_DIR = ds.DATA_ROOT / "detections_v2"

STEP_MS = 50  # 手机 ROI_SAMPLE_INTERVAL_MS
CONF_THRESHOLD = 0.25
NMS_IOU = 0.45
CLASS_COUNT = 15
CANDIDATES = 2100

ALLOWED_SPLITS = ("dev", "tuning")
BLIND_GAME_IDS = ("g202234-1", "g210429-1")
BLIND_VIDEO_TOKENS = ("20260815_202234", "20260815_210429")

# 一段静止的画面连续静止超过这个次数后手机不再截图送识别（RoiPlayEventCache 已交出，只取指纹）。
# 手机 15 次交出，这里留余量，评测把 stableSamples 调大一点也有框可用。
INFER_STILL_RUN_LIMIT = 30

OUTSIDE_SPECS = tuple(spec for spec in pv.SPECS if spec.outside)


def assert_allowed(game: dict) -> None:
    """盲测局和盲测录屏一律拒绝，不论怎么选局。"""
    if (
        game["split"] not in ALLOWED_SPLITS
        or game["gameId"] in BLIND_GAME_IDS
        or any(token in game["video"] for token in BLIND_VIDEO_TOKENS)
    ):
        raise SystemExit(
            f"拒绝处理 {game['gameId']}（split={game['split']}）：只允许 dev 和 tuning，不碰盲测"
        )


class RankDetector:
    def __init__(self, model_path: Path, threads: int):
        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        self.session = ort.InferenceSession(
            str(model_path), options, providers=["CPUExecutionProvider"]
        )
        self.input_name = self.session.get_inputs()[0].name

    def detect(self, rgb320: np.ndarray) -> list[tuple[int, float, float, float, float, float]]:
        raw = self.session.run(None, {self.input_name: pv.rank_tensor(rgb320)})[0]
        return decode_rank_output(raw)


def decode_rank_output(raw: np.ndarray) -> list[tuple[int, float, float, float, float, float]]:
    """对应 RankDecoder.decodeRankOutput，返回 (类别号, 分数, cx, cy, w, h)，坐标是 320 画布像素。"""
    pred = np.asarray(raw, dtype=np.float32).reshape(4 + CLASS_COUNT, CANDIDATES)
    scores = pred[4:]
    class_ids = scores.argmax(axis=0)  # 并列取编号小的类
    best = scores.max(axis=0)
    keep = np.nonzero(best >= np.float32(CONF_THRESHOLD))[0]
    if keep.size == 0:
        return []
    cx, cy, w, h = pred[0][keep], pred[1][keep], pred[2][keep], pred[3][keep]
    score = best[keep]
    cls = class_ids[keep]
    # 分数从高到低，同分按候选编号从小到大
    order = np.lexsort((keep, -score))
    cx, cy, w, h, score, cls = cx[order], cy[order], w[order], h[order], score[order], cls[order]
    two = np.float32(2.0)
    x1, y1, x2, y2 = cx - w / two, cy - h / two, cx + w / two, cy + h / two
    area = (x2 - x1) * (y2 - y1)
    floor = np.float32(1e-6)
    suppressed = np.zeros(len(order), dtype=bool)
    kept: list[int] = []
    for i in range(len(order)):
        if suppressed[i]:
            continue
        kept.append(i)
        rest = slice(i + 1, len(order))
        overlap_w = np.maximum(np.minimum(x2[i], x2[rest]) - np.maximum(x1[i], x1[rest]), np.float32(0))
        overlap_h = np.maximum(np.minimum(y2[i], y2[rest]) - np.maximum(y1[i], y1[rest]), np.float32(0))
        inter = overlap_w * overlap_h
        iou = inter / np.maximum(area[i] + area[rest] - inter, floor)
        suppressed[rest] |= iou > np.float32(NMS_IOU)
    return [(int(cls[i]), float(score[i]), float(cx[i]), float(cy[i]), float(w[i]), float(h[i])) for i in kept]


def _unit(value: float, counters: dict) -> float:
    """观察文件要求 0..1；模型框中心或宽高偶尔越界时夹住并计数。"""
    scaled = value / pv.MODEL_SIZE
    if scaled < 0.0 or scaled > 1.0:
        counters["clamped"] += 1
        scaled = min(1.0, max(0.0, scaled))
    return round(scaled, 6)


class GameSampler:
    """一局的采样状态：下一次采样时刻、每区上一次指纹和连续静止次数。"""

    def __init__(self, game: dict, out_path: Path):
        self.game = game
        start, end, pre = int(game["startMs"]), int(game["endMs"]), int(game["prePlayEndMs"])
        # 评测只回放 [prePlayEndMs, endMs]；从它前 1 秒开始，让指纹和静止计数先热起来。
        self.begin = max(start, pre - 1000) if 0 < pre < end else start
        self.end = end
        self.next_t = self.begin
        self.previous_fp: dict[str, np.ndarray | None] = {spec.name: None for spec in OUTSIDE_SPECS}
        self.still_run: dict[str, int] = {spec.name: 0 for spec in OUTSIDE_SPECS}
        self.stats = {"samples": 0, "table_samples": 0, "inferences": 0, "boxes": 0, "clamped": 0}
        out_path.parent.mkdir(parents=True, exist_ok=True)
        self.tmp_path = out_path.with_suffix(".jsonl.tmp")
        self.out_path = out_path
        self.handle = self.tmp_path.open("w", encoding="utf-8", newline="\n")

    def wants(self, t_ms: float) -> bool:
        return self.begin <= t_ms <= self.end and t_ms >= self.next_t

    def advance(self, t_ms: float) -> None:
        self.next_t += STEP_MS
        while self.next_t <= t_ms:
            self.next_t += STEP_MS

    def sample(self, t_ms: int, frame: np.ndarray, detector: RankDetector) -> None:
        screen = pv.Screen(frame)
        table = pv.is_table_visible(screen)
        zones: dict[str, dict] = {}
        if table:
            for spec in OUTSIDE_SPECS:
                zones[spec.name] = self._zone(screen, spec, detector)
            self.stats["table_samples"] += 1
        self.stats["samples"] += 1
        self.handle.write(
            json.dumps(
                {"ts_ms": int(t_ms), "table_visible": table, "zones": zones},
                ensure_ascii=False,
                separators=(",", ":"),
            )
            + "\n"
        )

    def _zone(self, screen: pv.Screen, spec: pv.ZoneSpec, detector: RankDetector) -> dict:
        state = pv.zone_visibility(screen, spec)
        name = spec.name
        if state == pv.OCCLUDED:
            # 手机：本区按桌面不可见处理，缓存里的上一帧保留，圆标和清桌都不推进。
            return {"card_visible": False, "still": False, "occluded": True, "turn": False}
        turn = pv.turn_badge_shown(screen, spec, pv.BADGE_REC_SHIFT[name])
        if state == pv.EMPTY:
            self.previous_fp[name] = None
            self.still_run[name] = 0
            return {"card_visible": False, "still": False, "turn": turn}
        fingerprint = pv.fingerprint(screen, spec)
        still = pv.fingerprint_still(self.previous_fp[name], fingerprint)
        self.previous_fp[name] = fingerprint
        self.still_run[name] = self.still_run[name] + 1 if still else 0
        zone: dict = {
            "card_visible": True,
            "still": still,
            "turn": turn,
            "fp": base64.b64encode(fingerprint.astype(np.uint8).tobytes()).decode("ascii"),
        }
        if self.still_run[name] <= INFER_STILL_RUN_LIMIT:
            crop = pv.expanded_crop(screen, spec)
            rgb320 = pv.play_zone_input(screen, crop)
            found = pv.refine_narrow_left_joker(detector.detect(rgb320), rgb320)
            self.stats["inferences"] += 1
            self.stats["boxes"] += len(found)
            zone["dets"] = [
                {
                    "rank": ds.RANK_ORDER[class_id],
                    "conf": round(score, 6),
                    "cx": _unit(cx, self.stats),
                    "cy": _unit(cy, self.stats),
                    "w": _unit(w, self.stats),
                    "h": _unit(h, self.stats),
                }
                for class_id, score, cx, cy, w, h in found
            ]
        return zone

    def close(self) -> None:
        self.handle.close()
        self.tmp_path.replace(self.out_path)


def run_video(video: str, games: list[dict], detector: RankDetector) -> None:
    path = ds.SOURCE_BASE / video
    samplers = [GameSampler(g, OUT_DIR / f"{g['gameId']}.jsonl") for g in games]
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise SystemExit(f"打不开视频: {path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    last_end = max(s.end for s in samplers)
    started = time.time()
    index = 0
    while True:
        if not cap.grab():
            break
        # 容器时间戳；部分录屏是 VFR，index/fps 会漂，只在第 0 帧或时间戳缺失时才退回它
        t = cap.get(cv2.CAP_PROP_POS_MSEC)
        if index == 0 or t <= 0:
            t = index * 1000.0 / fps
        index += 1
        if t > last_end:
            break
        due = [s for s in samplers if s.wants(t)]
        if not due:
            continue
        ok, frame = cap.retrieve()
        if not ok:
            break
        for sampler in due:
            sampler.sample(int(round(t)), frame, detector)
            sampler.advance(t)
    cap.release()
    for sampler in samplers:
        sampler.close()
        stats = sampler.stats
        print(
            f"{sampler.game['gameId']}: {stats['samples']} 帧（牌桌可见 {stats['table_samples']}）, "
            f"{stats['inferences']} 次推理, {stats['boxes']} 个框, 越界夹住 {stats['clamped']} 个 "
            f"-> {sampler.out_path}  [{time.time() - started:.0f}s]",
            flush=True,
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--games", help="逗号分隔的 gameId，缺省 = 全部 dev + tuning")
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()

    payload = ds.load_games()
    all_games = payload["games"]
    if args.games:
        wanted = args.games.split(",")
        by_id = {g["gameId"]: g for g in all_games}
        missing = [gid for gid in wanted if gid not in by_id]
        if missing:
            raise SystemExit(f"games.json 里没有: {','.join(missing)}")
        selected = [by_id[gid] for gid in wanted]
    else:
        selected = [g for g in all_games if g["split"] in ALLOWED_SPLITS]
    for game in selected:
        assert_allowed(game)
    if not selected:
        raise SystemExit("没有可处理的牌局")

    detector = RankDetector(MODEL_PATH, args.threads)
    by_video: dict[str, list[dict]] = {}
    for game in selected:
        by_video.setdefault(game["video"], []).append(game)
    for video, games in by_video.items():
        run_video(video, games, detector)


if __name__ == "__main__":
    main()
