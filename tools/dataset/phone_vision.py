"""手机出牌区视觉判定的 Python 对照实现，逐项对齐 Kotlin 代码。

对照来源（以 Kotlin 为准，改一边必须改另一边，core 的 PhoneGoldenFramesTest 守着）：
* 出牌区裁剪、长串扩展、三态可见性、7 点牌桌判断：core/.../capture/PlayZone.kt
* “出牌”圆标取色：core/.../capture/TurnBadge.kt
* 静止判断指纹：core/.../capture/RoiFingerprint.kt
* 屏幕缩放、等比缩放贴 320 画布：core/.../vision/ScreenScale.kt

取点方式与手机一致：判定用的探针点按“整数乘除取最近像素”从原始画面取
（CaptureProbeService.pixelRgb），送模型的裁剪区先缩到 1280x578 画布再取。
录屏本身就是 1280x578 时，画布与原始画面逐像素相同。
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import cv2
import numpy as np

CANVAS_W = 1280
CANVAS_H = 578
MODEL_SIZE = 320
LETTERBOX_PAD = 114

# PlayZone.kt
CARD_SURFACE_MIN_BRIGHTNESS = 115
CARD_SURFACE_MAX_SATURATION = 45
PLAY_VISIBILITY_STEP = 4
VISIBLE_CARD_PERCENT = 10
LOCAL_GREEN_PERCENT = 12
TABLE_GREEN_MIN_HITS = 3
TABLE_GREEN_PROBES = (
    (30, 250), (30, 420), (150, 420), (460, 75), (830, 130), (1120, 420), (1200, 420),
)
EDGE_CHECK_PX = 12
GAP_STOP_PX = 12
EXPAND_MARGIN_PX = 10
# CaptureProbeService
PLAY_WHITE_LIMIT = 190
CARD_WHITE_COLUMN_MIN_PIXELS = 8
# TurnBadge.kt
BADGE_Y_OFFSETS = (0, -12, -24, -30)
BADGE_HALF_WIDTH = 16
BADGE_HALF_HEIGHT = 12
BADGE_STEP = 2
# 录屏与手机画布的系统性偏移（圆标外接框中心实测，录屏 - 手机，单位画布像素）：
# 手机截屏里圆标中心在 PlayZone.badgeX/badgeY，录屏里左侧偏右约 16、右侧偏左约 12、整体偏上约 6，
# 取色窗只盖住圆标一半，红色占比够不到 43%。不改手机坐标，离线取色时把取点整体平移（Kotlin 守卫同样平移取点）。
BADGE_REC_SHIFT = {
    "TOP": (1, -6),
    "LEFT_TOP": (16, -6),
    "LEFT_BOTTOM": (16, -6),
    "RIGHT_TOP": (-12, -6),
    "RIGHT_BOTTOM": (-12, -6),
    "SELF": (0, 0),
}
# RoiFingerprint.kt
FP_COLUMNS = 40
FP_ROWS = 16
# dev 实测：5~7 张大牌/少于10张光效产生 13..60 的微扰，189 次真实 REPLACE_DIRECT 在 60 下全检出 (>=0.039)
FP_CHANNEL_DELTA = 60
FP_CHANGED_RATIO = 0.03

CARD = "CARD"
EMPTY = "EMPTY"
OCCLUDED = "OCCLUDED"


@dataclass(frozen=True)
class ZoneSpec:
    name: str
    outside: bool
    left_ratio: float
    right_ratio: float
    rank_y_ratio: float
    badge_x: int
    badge_y: int
    visibility_height: int
    expand: str  # NONE | LEFT | RIGHT
    expand_limit_ratio: float


# 顺序与 PlayZone 枚举一致。
SPECS = (
    ZoneSpec("TOP", True, 0.539, 0.602, 0.022, 780, 62, 70, "RIGHT", 1040.0 / 1280.0),
    ZoneSpec("LEFT_TOP", True, 0.148, 0.266, 0.156, 298, 130, 56, "RIGHT", 435.0 / 1280.0),
    ZoneSpec("LEFT_BOTTOM", True, 0.141, 0.281, 0.429, 298, 290, 70, "RIGHT", 560.0 / 1280.0),
    ZoneSpec("RIGHT_TOP", True, 0.750, 0.859, 0.156, 978, 130, 56, "LEFT", 795.0 / 1280.0),
    ZoneSpec("RIGHT_BOTTOM", True, 0.750, 0.859, 0.429, 978, 290, 70, "LEFT", 720.0 / 1280.0),
    ZoneSpec("SELF", False, 0.438, 0.563, 0.358, 638, 264, 28, "NONE", 0.0),
)
SPEC_BY_NAME = {spec.name: spec for spec in SPECS}
ZONE_NAMES = tuple(spec.name for spec in SPECS)


def round_half_up(value: float) -> int:
    """Kotlin Double.roundToInt（正数四舍五入，.5 向上）。"""
    return int(math.floor(value + 0.5))


def _left(spec: ZoneSpec, width: int = CANVAS_W) -> int:
    return round_half_up(width * spec.left_ratio)


def _right(spec: ZoneSpec, width: int = CANVAS_W) -> int:
    return round_half_up(width * spec.right_ratio)


def _rank_y(spec: ZoneSpec, height: int = CANVAS_H) -> int:
    return round_half_up(height * spec.rank_y_ratio)


def yolo_crop(spec: ZoneSpec) -> tuple[int, int, int, int]:
    """PlayZone.yoloCrop：(left, top, width, height)。"""
    left = _left(spec)
    crop_w = _right(spec) - left + 1
    crop_h = crop_w if not spec.outside else spec.visibility_height
    rank_y = _rank_y(spec)
    if not spec.outside:
        top = rank_y - crop_h * 43 // 100
    else:
        top = rank_y - crop_h // 2
    top = max(0, min(top, CANVAS_H - crop_h))
    return left, top, crop_w, crop_h


class Screen:
    """一帧截图（BGR，cv2 读出来的样子）；探针点取原始画面，裁剪区取 1280x578 画布。"""

    def __init__(self, bgr: np.ndarray):
        self.raw = bgr
        self.height, self.width = bgr.shape[:2]
        self._canvas: np.ndarray | None = None

    @property
    def canvas(self) -> np.ndarray:
        if self._canvas is None:
            if (self.width, self.height) == (CANVAS_W, CANVAS_H):
                self._canvas = self.raw
            else:
                # ScreenScale.canvasRegion：两个方向都是缩小时按面积平均，否则双线性。
                shrink = self.width >= CANVAS_W and self.height >= CANVAS_H
                interpolation = cv2.INTER_AREA if shrink else cv2.INTER_LINEAR
                self._canvas = cv2.resize(self.raw, (CANVAS_W, CANVAS_H), interpolation=interpolation)
        return self._canvas

    def rgb(self, xs, ys) -> np.ndarray:
        """画布坐标 -> 原始画面最近像素（x * 宽 / 1280，整数除法），返回 (n, 3) 的 R、G、B。"""
        xs = np.asarray(xs, dtype=np.int64)
        ys = np.asarray(ys, dtype=np.int64)
        sx = np.clip(xs * self.width // CANVAS_W, 0, self.width - 1)
        sy = np.clip(ys * self.height // CANVAS_H, 0, self.height - 1)
        return self.raw[sy, sx][:, ::-1].astype(np.int32)

    def grid_rgb(self, xs, ys) -> np.ndarray:
        """画布坐标网格（行 ys、列 xs）-> (len(ys), len(xs), 3) 的 R、G、B。"""
        xs = np.asarray(xs, dtype=np.int64)
        ys = np.asarray(ys, dtype=np.int64)
        sx = np.clip(xs * self.width // CANVAS_W, 0, self.width - 1)
        sy = np.clip(ys * self.height // CANVAS_H, 0, self.height - 1)
        return self.raw[np.ix_(sy, sx)][:, :, ::-1].astype(np.int32)


def is_table_visible(screen: Screen) -> bool:
    """7 点绿布投票，至少命中 3 个（green - red >= 50 过滤半透明通知栏遮罩）。"""
    xs = [p[0] for p in TABLE_GREEN_PROBES]
    ys = [p[1] for p in TABLE_GREEN_PROBES]
    px = screen.rgb(xs, ys)
    r, g, b = px[:, 0], px[:, 1], px[:, 2]
    green = (g >= 70) & (g - r >= 50) & (g - b >= 10)
    return int(green.sum()) >= TABLE_GREEN_MIN_HITS


def zone_visibility(screen: Screen, spec: ZoneSpec, step: int = PLAY_VISIBILITY_STEP) -> str:
    """PlayZone.zoneVisibility 三态：CARD / EMPTY / OCCLUDED。"""
    if spec.outside:
        left, top, crop_w, crop_h = yolo_crop(spec)
        right = left + crop_w - 1
        bottom = top + crop_h - 1
    else:
        left = _left(spec)
        right = _right(spec)
        top = _rank_y(spec)
        bottom = top + spec.visibility_height
    xs = np.arange(left, right + 1, step)
    ys = np.arange(top, bottom + 1, step)
    px = screen.grid_rgb(xs, ys)
    r, g, b = px[:, :, 0], px[:, :, 1], px[:, :, 2]
    high = np.maximum(np.maximum(r, g), b)
    low = np.minimum(np.minimum(r, g), b)
    surface = (high >= CARD_SURFACE_MIN_BRIGHTNESS) & (high - low <= CARD_SURFACE_MAX_SATURATION)
    samples = int(surface.size)
    matched = int(surface.sum())
    if spec.outside:
        dimmed_green = (g >= 35) & (g - r >= 10) & (g - b >= 5)
        green_matched = int((dimmed_green & ~surface).sum())
        if not (samples > 0 and green_matched * 100 >= samples * LOCAL_GREEN_PERCENT):
            return OCCLUDED
    return CARD if (samples > 0 and matched * 100 >= samples * VISIBLE_CARD_PERCENT) else EMPTY


def _white_columns(screen: Screen, top: int, bottom: int) -> np.ndarray:
    """CaptureProbeService.isCardWhiteColumn 对画布每一列 x 的结果（行 top 到 bottom-1，白点 >= 8 个）。"""
    xs = np.arange(CANVAS_W)
    ys = np.arange(top, bottom)
    px = screen.grid_rgb(xs, ys)
    white = px.min(axis=2) >= PLAY_WHITE_LIMIT
    return white.sum(axis=0) >= CARD_WHITE_COLUMN_MIN_PIXELS


def expanded_crop(screen: Screen, spec: ZoneSpec) -> tuple[int, int, int, int]:
    """PlayZone.expandedYoloCrop：长串牌沿延伸方向按白色牌面扩展，(left, top, width, height)。"""
    base_left, base_top, base_w, base_h = yolo_crop(spec)
    if spec.expand == "NONE":
        return base_left, base_top, base_w, base_h
    top = base_top
    bottom = base_top + base_h
    white = _white_columns(screen, top, bottom)
    limit_raw = round_half_up(CANVAS_W * spec.expand_limit_ratio)
    if spec.expand == "LEFT":
        limit = max(limit_raw, 0)
        edge_end = min(base_left + base_w, base_left + EDGE_CHECK_PX)
        if not any(white[x] for x in range(base_left, edge_end)):
            return base_left, base_top, base_w, base_h
        last_white = base_left
        gap = 0
        for x in range(base_left - 1, limit - 1, -1):
            if white[x]:
                last_white = x
                gap = 0
            else:
                gap += 1
                if gap >= GAP_STOP_PX:
                    break
        if last_white >= base_left:
            return base_left, base_top, base_w, base_h
        new_left = max(limit, last_white - EXPAND_MARGIN_PX)
        return new_left, base_top, base_left + base_w - new_left, base_h
    base_right = base_left + base_w
    limit = min(limit_raw, CANVAS_W)
    edge_start = max(base_left, base_right - EDGE_CHECK_PX)
    if not any(white[x] for x in range(edge_start, base_right)):
        return base_left, base_top, base_w, base_h
    last_white = base_right - 1
    gap = 0
    for x in range(base_right, limit):
        if white[x]:
            last_white = x
            gap = 0
        else:
            gap += 1
            if gap >= GAP_STOP_PX:
                break
    if last_white < base_right:
        return base_left, base_top, base_w, base_h
    new_right = min(limit, last_white + 1 + EXPAND_MARGIN_PX)
    return base_left, base_top, new_right - base_left, base_h


def turn_badge_shown(screen: Screen, spec: ZoneSpec, shift: tuple[int, int] = (0, 0)) -> bool:
    """TurnBadge.isShown：红底黄字“出牌”圆标，挂王时圆标上偏，依次试几个纵向偏移。

    shift=(dx, dy) 把取色窗整体平移；默认 (0, 0) 就是手机坐标。录屏回放才传 BADGE_REC_SHIFT[zone]。
    """
    dx, dy = shift
    xs = np.arange(spec.badge_x + dx - BADGE_HALF_WIDTH, spec.badge_x + dx + BADGE_HALF_WIDTH + 1, BADGE_STEP)
    for offset in BADGE_Y_OFFSETS:
        center_y = spec.badge_y + dy + offset
        ys = np.arange(center_y - BADGE_HALF_HEIGHT, center_y + BADGE_HALF_HEIGHT + 1, BADGE_STEP)
        px = screen.grid_rgb(xs, ys)
        r, g, b = px[:, :, 0], px[:, :, 1], px[:, :, 2]
        samples = int(r.size)
        red = (r >= 65) & (r - g >= 45) & (r - b >= 45) & (g * 2 <= r) & (b * 2 <= r)
        yellow = (r >= 85) & (g >= 70) & (r - b >= 40) & (g - b >= 30) & (r - g <= 75)
        if int(red.sum()) * 100 >= samples * 43 and int(yellow.sum()) * 5 >= samples:
            return True
    return False


def fingerprint(screen: Screen, spec: ZoneSpec) -> np.ndarray:
    """RoiFingerprint.of：yoloCrop 内 40x16 网格取点，(640, 3) 的 R、G、B。"""
    left, top, crop_w, crop_h = yolo_crop(spec)
    index = np.arange(FP_COLUMNS * FP_ROWS)
    xs = left + (crop_w - 1) * (index % FP_COLUMNS) // (FP_COLUMNS - 1)
    ys = top + (crop_h - 1) * (index // FP_COLUMNS) // (FP_ROWS - 1)
    return screen.rgb(xs, ys)


def fingerprint_still(previous: np.ndarray | None, current: np.ndarray) -> bool:
    """RoiFingerprint.isStill：任一通道差超过 12 的点不到 3% 就算静止。"""
    if previous is None or previous.shape != current.shape or current.size == 0:
        return False
    changed = int((np.abs(previous - current) > FP_CHANNEL_DELTA).any(axis=1).sum())
    return bool(np.float32(changed) / np.float32(len(current)) < np.float32(FP_CHANGED_RATIO))


def play_zone_input(screen: Screen, crop: tuple[int, int, int, int]) -> np.ndarray:
    """ScreenScale.playZoneInput：画布取区，等比缩放居中贴 320x320，四周填 114，返回 RGB uint8。"""
    left, top, width, height = crop
    region = screen.canvas[top:top + height, left:left + width][:, :, ::-1]
    scale = min(MODEL_SIZE / width, MODEL_SIZE / height)
    new_w = max(1, round(width * scale))
    new_h = max(1, round(height * scale))
    resized = cv2.resize(np.ascontiguousarray(region), (new_w, new_h), interpolation=cv2.INTER_LINEAR)
    out = np.full((MODEL_SIZE, MODEL_SIZE, 3), LETTERBOX_PAD, dtype=np.uint8)
    dx = (MODEL_SIZE - new_w) // 2
    dy = (MODEL_SIZE - new_h) // 2
    out[dy:dy + new_h, dx:dx + new_w] = resized
    return out


def fingerprint_hash(fp: np.ndarray) -> str:
    """指纹的 64 位 FNV-1a 哈希（逐点 0xRRGGBB），Kotlin 测试用同一公式。"""
    h = 0xCBF29CE484222325
    for r, g, b in fp.tolist():
        h ^= (r << 16) | (g << 8) | b
        h = (h * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return format(h, "016x")


def describe_frame(screen: Screen, previous: dict[str, np.ndarray] | None = None) -> tuple[dict, dict[str, np.ndarray]]:
    """一致性守卫用：一帧里五个外面出牌区的可见性、圆标、扩展后的裁剪框、指纹哈希，以及与上一帧比的静止。

    返回 (描述, 本帧各区指纹)；previous 是上一帧返回的指纹，没有就不给 still。
    """
    zones: dict[str, dict] = {}
    fingerprints: dict[str, np.ndarray] = {}
    for spec in SPECS:
        if not spec.outside:
            continue
        fp = fingerprint(screen, spec)
        fingerprints[spec.name] = fp
        entry = {
            "state": zone_visibility(screen, spec),
            "turn": turn_badge_shown(screen, spec),
            "crop": list(expanded_crop(screen, spec)),
            "fp": fingerprint_hash(fp),
        }
        if previous is not None:
            entry["still"] = fingerprint_still(previous[spec.name], fp)
        zones[spec.name] = entry
    return {"table_visible": is_table_visible(screen), "zones": zones}, fingerprints


def rank_tensor(rgb: np.ndarray) -> np.ndarray:
    """argbToRankTensor：RGB、0..1、NCHW。"""
    return np.ascontiguousarray(np.transpose(rgb.astype(np.float32) / 255.0, (2, 0, 1))[None])


# RankDecoder.refineNarrowLeftJoker 同口径参数
NARROW_JOKER_MIN_ASPECT = 1.85
NARROW_JOKER_MAX_WIDTH_RATIO = 0.72
NARROW_JOKER_TOP15_PERCENT = 15
NARROW_JOKER_TOP25_PERCENT = 25
NARROW_JOKER_TOP_PERCENT = 65
NARROW_JOKER_MIN_TOP25_RIGHT_INK = 4
NARROW_JOKER_MAX_TOP25_LEFT_RATIO_NUM = 1
NARROW_JOKER_MAX_TOP25_LEFT_RATIO_DEN = 3
NARROW_JOKER_MIN_INK_PIXELS = 8
NARROW_JOKER_MAX_BOTTOM_RED_FOR_SMALL = 2


def refine_narrow_left_joker(
    detections: list[tuple[int, float, float, float, float, float]],
    rgb320: np.ndarray,
    big_joker_id: int = 0,
    small_joker_id: int = 1,
) -> list[tuple[int, float, float, float, float, float]]:
    """与 RankDecoder.refineNarrowLeftJoker 完全一致：几何比例 + J 字顶部形态 + 上半部红黑墨迹 + 小王底边防红圆标侵入。"""
    if len(detections) < 2:
        return detections
    left_i = min(range(len(detections)), key=lambda i: detections[i][2])
    cls_id, score, cx, cy, w, h = detections[left_i]
    if cls_id in (big_joker_id, small_joker_id):
        return detections
    if w <= 0.0 or h / w < NARROW_JOKER_MIN_ASPECT:
        return detections
    max_other_w = max(detections[i][4] for i in range(len(detections)) if i != left_i)
    if w > NARROW_JOKER_MAX_WIDTH_RATIO * max_other_w:
        return detections

    x1 = max(0, min(MODEL_SIZE - 1, int(math.floor(cx - w / 2.0))))
    x2 = max(x1 + 1, min(MODEL_SIZE, int(math.ceil(cx + w / 2.0))))
    y1 = max(0, min(MODEL_SIZE - 1, int(math.floor(cy - h / 2.0))))
    y2 = max(y1 + 1, min(MODEL_SIZE, int(math.ceil(cy + h / 2.0))))
    bw = x2 - x1
    bh = y2 - y1
    y_top15 = min(MODEL_SIZE, y1 + max(1, bh * NARROW_JOKER_TOP15_PERCENT // 100))
    y_top25 = min(MODEL_SIZE, y1 + max(1, bh * NARROW_JOKER_TOP25_PERCENT // 100))
    y_top = min(MODEL_SIZE, y1 + max(1, bh * NARROW_JOKER_TOP_PERCENT // 100))
    x_mid = x1 + bw // 2

    patch = rgb320[y1:y2, x1:x2].astype(np.int32)
    r, g, b = patch[:, :, 0], patch[:, :, 1], patch[:, :, 2]
    is_red = (r >= 120) & (r - g >= 45) & (r - b >= 45)
    is_black = (r < 115) & (g < 115) & (b < 115)
    high = np.maximum(np.maximum(r, g), b)
    low = np.minimum(np.minimum(r, g), b)
    is_morph = is_red | ((high < 160) & (high - low <= 45))

    rows15 = y_top15 - y1
    max_top15_span = 0
    for ry in range(rows15):
        cols = np.flatnonzero(is_morph[ry])
        if cols.size > 0:
            max_top15_span = max(max_top15_span, int(cols[-1] - cols[0] + 1))
    if max_top15_span == 0 or max_top15_span * 2 > bw:
        return detections

    rows25 = y_top25 - y1
    mid_col = x_mid - x1
    top25_left = int(is_morph[:rows25, :mid_col].sum())
    top25_right = int(is_morph[:rows25, mid_col:].sum())
    if (
        top25_right < NARROW_JOKER_MIN_TOP25_RIGHT_INK
        or top25_left * NARROW_JOKER_MAX_TOP25_LEFT_RATIO_DEN
        > top25_right * NARROW_JOKER_MAX_TOP25_LEFT_RATIO_NUM
    ):
        return detections

    rows65 = y_top - y1
    red = int(is_red[:rows65].sum())
    black = int(is_black[:rows65].sum())
    bottom_red = int(is_red[rows65:].sum())
    if red >= NARROW_JOKER_MIN_INK_PIXELS and red >= black * 2:
        refined_cls = big_joker_id
    elif (
        black >= NARROW_JOKER_MIN_INK_PIXELS
        and (red <= 2 or black >= red * 2)
        and bottom_red <= NARROW_JOKER_MAX_BOTTOM_RED_FOR_SMALL
    ):
        refined_cls = small_joker_id
    else:
        return detections
    out = list(detections)
    out[left_i] = (refined_cls, score, cx, cy, w, h)
    return out
