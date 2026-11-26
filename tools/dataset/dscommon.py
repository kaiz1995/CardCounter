"""共享常量与几何/推理工具：切局、旧模型检测、真值草稿、核对页共用。

坐标与裁剪完全对齐 app/src/main/java/com/example/cardcounter/capture/PlayZone.kt
（1280x578 归一化比例 + yoloCrop 的纵向规则），白色牌区阈值对齐
CaptureProbeService.playZoneVisibility（>=190 视为白，占比 >=10% 视为有牌）。
"""
from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
import subprocess

import cv2
import numpy as np

REPO = Path(__file__).resolve().parents[2]
ASSET_MODEL = REPO / "app" / "src" / "main" / "assets" / "poker_yolo11_320.mnn"
GAMES_JSON = REPO / "datasets" / "games.json"
TRUTH_DIR = REPO / "datasets" / "truth"

SOURCE_BASE = Path(
    r"D:\HuaweiMoveData\Users\张大脸小太阳\Documents\workbuddy\记牌器开发"
)
DATA_ROOT = Path(r"D:\CardCounterData")
FRAMES_DIR = DATA_ROOT / "frames"
CROPS_DIR = DATA_ROOT / "crops"
REVIEW_DIR = DATA_ROOT / "review"
DETECTIONS_DIR = DATA_ROOT / "detections"

CANON_W = 1280
CANON_H = 578

# 与 PlayZone 枚举同名、同顺序
ZONES = (
    "TOP",
    "LEFT_TOP",
    "LEFT_BOTTOM",
    "RIGHT_TOP",
    "RIGHT_BOTTOM",
    "SELF",
)
ZONE_SPEC = {
    # left_ratio, right_ratio, visibility_height, rank_y_ratio, is_self
    "TOP": (0.539, 0.602, 70, 0.022, False),
    "LEFT_TOP": (0.148, 0.266, 56, 0.156, False),
    "LEFT_BOTTOM": (0.141, 0.281, 70, 0.429, False),
    "RIGHT_TOP": (0.750, 0.859, 56, 0.156, False),
    "RIGHT_BOTTOM": (0.750, 0.859, 70, 0.429, False),
    "SELF": (0.438, 0.563, 28, 0.358, True),
}

# 旧模型 28 类 -> Rank 名（YoloLabelMap.kt）
YOLO_LABELS = (
    "RA", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R9", "R10", "RJ", "RQ", "RK",
    "BA", "B2", "B3", "B4", "B5", "B6", "B7", "B8", "B9", "B10", "BJ", "BQ", "BK",
    "D", "X",
)
LABEL_RANK = {}
for _label in YOLO_LABELS:
    if _label == "D":
        LABEL_RANK[_label] = "BIG_JOKER"
    elif _label == "X":
        LABEL_RANK[_label] = "SMALL_JOKER"
    else:
        LABEL_RANK[_label] = {
            "2": "TWO", "A": "ACE", "K": "KING", "Q": "QUEEN", "J": "JACK",
            "10": "TEN", "9": "NINE", "8": "EIGHT", "7": "SEVEN", "6": "SIX",
            "5": "FIVE", "4": "FOUR", "3": "THREE",
        }[_label[1:]]

RANK_ORDER = (
    "BIG_JOKER", "SMALL_JOKER", "TWO", "ACE", "KING", "QUEEN", "JACK",
    "TEN", "NINE", "EIGHT", "SEVEN", "SIX", "FIVE", "FOUR", "THREE",
)
RANK_INDEX = {r: i for i, r in enumerate(RANK_ORDER)}

CONF_THRESHOLD = 0.25
NMS_THRESHOLD = 0.45
WHITE_LIMIT = 190
WHITE_PRESENT_RATIO = 0.10


def _round_half_up(value: float) -> int:
    return int(math.floor(value + 0.5))


def zone_rect(zone: str, width: int = CANON_W, height: int = CANON_H):
    """返回 (left, top, w, h)，与 PlayZone.yoloCrop 完全一致。"""
    left_ratio, right_ratio, visibility_height, rank_y_ratio, is_self = ZONE_SPEC[zone]
    left = _round_half_up(width * left_ratio)
    right = _round_half_up(width * right_ratio)
    crop_w = right - left + 1
    crop_h = crop_w if is_self else visibility_height
    rank_y = _round_half_up(height * rank_y_ratio)
    if is_self:
        # Kotlin: cropHeight * 43 / 100 是 Int 除法
        top = rank_y - crop_h * 43 // 100
    else:
        top = rank_y - crop_h // 2
    top = max(0, min(top, height - crop_h))
    return left, top, crop_w, crop_h


def zone_visibility_band(zone: str, width: int = CANON_W, height: int = CANON_H):
    """playZoneVisibility 的采样带：x=left..right, y=rankY..rankY+visibilityHeight。"""
    left_ratio, right_ratio, visibility_height, rank_y_ratio, _ = ZONE_SPEC[zone]
    left = _round_half_up(width * left_ratio)
    right = _round_half_up(width * right_ratio)
    rank_y = _round_half_up(height * rank_y_ratio)
    return left, rank_y, right, rank_y + visibility_height


def crop_zone(frame: np.ndarray, zone: str) -> np.ndarray:
    left, top, w, h = zone_rect(zone, frame.shape[1], frame.shape[0])
    return frame[top:top + h, left:left + w]


def white_ratio(frame: np.ndarray, zone: str, step: int = 4) -> float:
    """采样带内三通道都 >=190 的样本占比（阈值同 playZoneVisibility，步长加密）。"""
    left, top, right, bottom = zone_visibility_band(zone, frame.shape[1], frame.shape[0])
    region = frame[top:bottom + 1:step, left:right + 1:step]
    if region.size == 0:
        return 0.0
    return float((region.min(axis=2) >= WHITE_LIMIT).mean())


def table_visible(frame: np.ndarray) -> bool:
    """gameTableVisible：两点都是牌桌绿。"""
    def green(pixel) -> bool:
        blue, grn, red = int(pixel[0]), int(pixel[1]), int(pixel[2])
        return grn >= 70 and grn - red >= 30 and grn - blue >= 10

    return bool(green(frame[200, 500]) and green(frame[200, 800]))


def region_ratio(frame: np.ndarray, x0: int, y0: int, x1: int, y1: int, fn, step: int = 2) -> float:
    region = frame[y0:y1:step, x0:x1:step].astype(np.int16)
    blue, grn, red = region[:, :, 0], region[:, :, 1], region[:, :, 2]
    return float(fn(blue, grn, red).mean())


def settlement_visible(frame: np.ndarray) -> bool:
    """结算画面：中央大暗板 + 得分表蓝色条。烧牌动画只有暗+红，没有蓝。"""
    panel = region_ratio(
        frame, 360, 150, 920, 400,
        lambda b, g, r: (r < 70) & (g < 70) & (b < 70), step=3,
    )
    blue = region_ratio(
        frame, 360, 150, 920, 400,
        lambda b, g, r: (b > 100) & ((b - r) > 30) & ((b - g) > 10), step=3,
    )
    return panel > 0.5 and blue > 0.03


def hand_white(frame: np.ndarray) -> float:
    """自己手牌上沿白带（避开底部记牌器横条与按钮）。"""
    band = frame[450:475, 220:1020]
    return float((band.min(axis=2) >= WHITE_LIMIT).mean())


def hand_ratio(frame: np.ndarray) -> float:
    """手牌上沿的"低饱和亮块"占比。

    烧牌/开点会把整屏压暗，单纯 >=190 的白阈值会把手牌误判为空；
    牌面是低饱和的灰白，牌桌是绿色（高饱和），因此用 max-min<=45 且 max>=115。
    """
    band = frame[450:475, 220:1020].astype(np.int16)
    high = band.max(axis=2)
    low = band.min(axis=2)
    return float((((high - low) <= 45) & (high >= 115)).mean())


def xuan_buttons_visible(frame: np.ndarray) -> bool:
    """宣点/不宣点两个橙色按钮同时出现（只在自己需要决策时显示）。"""
    def orange(region) -> float:
        region = region.astype(np.int16)
        blue, grn, red = region[:, :, 0], region[:, :, 1], region[:, :, 2]
        return float(
            ((red > 190) & (grn > 110) & (blue < 120) & ((grn - blue) > 40)).mean()
        )

    left = orange(frame[320:380, 499:620])
    right = orange(frame[320:380, 666:780])
    return left > 0.35 and right > 0.35


def lobby_visible(frame: np.ndarray) -> bool:
    """大厅：三个大绿按钮（开点发4 / 大王2杀1 / 6副牌够级）同时在场。"""
    def green(x0: int, x1: int) -> float:
        region = frame[345:415, x0:x1].astype(np.int16)
        blue, grn, red = region[:, :, 0], region[:, :, 1], region[:, :, 2]
        return float(
            ((grn > 140) & ((grn - red) > 60) & ((grn - blue) > 40)).mean()
        )

    return green(170, 300) > 0.2 and green(540, 740) > 0.2 and green(920, 1120) > 0.2


def pregame_dialog_visible(frame: np.ndarray) -> bool:
    """战前选择道具弹窗：青色描边。"""
    cyan = region_ratio(
        frame, 330, 70, 950, 500,
        lambda b, g, r: (g > 140) & (b > 140) & (r < 120), step=3,
    )
    return cyan > 0.015


def letterbox_rgb(crop: np.ndarray, size: int = 320):
    """等比缩放 + 居中 padding(114)，返回 float32 NCHW、RGB 0..1 与映射参数。"""
    h, w = crop.shape[:2]
    scale = size / max(h, w)
    new_w = max(1, int(round(w * scale)))
    new_h = max(1, int(round(h * scale)))
    resized = cv2.resize(crop, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
    canvas = np.full((size, size, 3), 114, dtype=np.uint8)
    dx = (size - new_w) // 2
    dy = (size - new_h) // 2
    canvas[dy:dy + new_h, dx:dx + new_w] = resized
    rgb = canvas[:, :, ::-1].astype(np.float32) / 255.0
    tensor = np.ascontiguousarray(np.transpose(rgb, (2, 0, 1))[None])
    return tensor, scale, dx, dy


def nms(boxes: np.ndarray, scores: np.ndarray, threshold: float = NMS_THRESHOLD):
    """boxes 为 xyxy。返回保留索引。"""
    if len(boxes) == 0:
        return np.array([], dtype=np.int64)
    x1, y1, x2, y2 = boxes[:, 0], boxes[:, 1], boxes[:, 2], boxes[:, 3]
    areas = np.maximum(0.0, x2 - x1) * np.maximum(0.0, y2 - y1)
    order = scores.argsort()[::-1]
    keep = []
    while order.size > 0:
        i = order[0]
        keep.append(int(i))
        xx1 = np.maximum(x1[i], x1[order[1:]])
        yy1 = np.maximum(y1[i], y1[order[1:]])
        xx2 = np.minimum(x2[i], x2[order[1:]])
        yy2 = np.minimum(y2[i], y2[order[1:]])
        inter = np.maximum(0.0, xx2 - xx1) * np.maximum(0.0, yy2 - yy1)
        union = areas[i] + areas[order[1:]] - inter
        iou = np.where(union > 0, inter / union, 0.0)
        order = order[1:][iou <= threshold]
    return np.array(keep, dtype=np.int64)


def decode_output(output: np.ndarray):
    """[1,32,2100] -> NMS 后检测 [(label, rank, score, cx, cy, w, h)]，坐标是 320 画布像素。"""
    pred = output[0]
    boxes = pred[:4].T
    class_scores = pred[4:].T
    best = class_scores.max(axis=1)
    class_ids = class_scores.argmax(axis=1)
    mask = best > CONF_THRESHOLD
    if not mask.any():
        return []
    boxes = boxes[mask]
    scores = best[mask]
    class_ids = class_ids[mask]
    xyxy = np.stack(
        [boxes[:, 0] - boxes[:, 2] / 2, boxes[:, 1] - boxes[:, 3] / 2,
         boxes[:, 0] + boxes[:, 2] / 2, boxes[:, 1] + boxes[:, 3] / 2],
        axis=1,
    )
    dets = []
    for class_id in np.unique(class_ids):
        idx = np.where(class_ids == class_id)[0]
        keep = nms(xyxy[idx], scores[idx])
        for k in keep:
            j = idx[k]
            label = YOLO_LABELS[int(class_id)]
            dets.append(
                (
                    label,
                    LABEL_RANK[label],
                    float(scores[j]),
                    float(boxes[j, 0]),
                    float(boxes[j, 1]),
                    float(boxes[j, 2]),
                    float(boxes[j, 3]),
                )
            )
    dets.sort(key=lambda d: d[3])
    return dets


def sha256_file(path: Path, chunk: int = 1 << 20) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(chunk), b""):
            digest.update(block)
    return digest.hexdigest()


def video_duration_ms(path: Path) -> int:
    cap = cv2.VideoCapture(str(path))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    frames = cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0
    cap.release()
    return int(round(frames / fps * 1000))


LONG_VIDEOS = (
    # (文件名, 目录, split)
    ("SVID_20260810_102658_1.mp4", "source", "dev"),
    ("SVID_20260810_143651_1.mp4", "source", "dev"),
    ("SVID_20260811_075639_1.mp4", "source", "dev"),
    ("SVID_20260813_164333_1.mp4", "source", "tuning"),
    ("SVID_20260815_202234_1.mp4", "source", "blind"),
    ("SVID_20260815_210429_1.mp4", "source", "blind"),
)

CLIP_VIDEOS = (
    # 只有开局手牌片段，不做整局切分
    ("SVID_20260727_201930_1.mp4", "source"),
    ("SVID_20260728_135222_1.mp4", "source"),
    ("SVID_20260811_214558_1.mp4", "source"),
    ("SVID_20260728_145252_1.mp4", "9局"),
    ("SVID_20260728_145333_1.mp4", "9局"),
    ("SVID_20260728_145406_1.mp4", "9局"),
    ("SVID_20260728_145436_1.mp4", "9局"),
    ("SVID_20260728_145511_1.mp4", "9局"),
    ("SVID_20260728_145541_1.mp4", "9局"),
    ("SVID_20260728_145608_1.mp4", "9局"),
    ("SVID_20260728_145637_1.mp4", "9局"),
    ("SVID_20260728_145704_1.mp4", "9局"),
)


def video_path(group: str, name: str) -> Path:
    return SOURCE_BASE / group / name


def game_id(name: str, index: int) -> str:
    stem = Path(name).stem
    parts = stem.split("_")
    stamp = parts[2] if len(parts) >= 3 else stem[-6:]
    return f"g{stamp[-6:]}-{index}"


def load_games() -> dict:
    return json.loads(GAMES_JSON.read_text(encoding="utf-8"))


def save_games(data: dict) -> None:
    GAMES_JSON.parent.mkdir(parents=True, exist_ok=True)
    GAMES_JSON.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def sample_times(start_ms: int, end_ms: int, step_ms: int = 100):
    t = start_ms
    while t <= end_ms:
        yield t
        t += step_ms


def frame_at(path: Path, t_ms: int) -> np.ndarray | None:
    """用 ffmpeg 取指定毫秒的帧（cv2 的 POS_MSEC 在部分长录屏上会落回同一关键帧）。"""
    cmd = [
        "ffmpeg", "-v", "error",
        "-ss", f"{t_ms / 1000.0:.3f}",
        "-i", str(path),
        "-frames:v", "1",
        "-f", "image2pipe", "-vcodec", "png", "-",
    ]
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode != 0 or not result.stdout:
        return None
    return cv2.imdecode(np.frombuffer(result.stdout, np.uint8), cv2.IMREAD_COLOR)
