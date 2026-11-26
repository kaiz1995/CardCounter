"""手机手牌识别链路的 Python 对照实现，逐项对齐 Kotlin 代码。

对照来源（以 Kotlin 为准，不修改 phone_vision.py 与 RankDecoder.kt 以免改变 VISION_RULE_VERSION）：
* 手牌采样间隔、亮度指纹与防抖：app/.../capture/CaptureProbeService.kt
* 手牌取图区与边缘采样区：app/.../capture/HandRegions.kt
* 手牌张数检测：app/.../capture/HandCardPositionDetector.kt
* 手牌 870x870 画布与 320x320 双线性缩放：core/.../vision/ScreenScale.kt
* 多帧合并：core/.../capture/HandFrameMerger.kt
* 张数多帧投票：core/.../capture/HandPositionVoter.kt
* 自动锁定：core/.../domain/HandAutoLock.kt
* 出牌停稳缓存、圆标跟踪与开局门：core/.../capture/RoiPlayEventCache.kt / TurnBadge.kt / PlayZone.kt
"""
from __future__ import annotations

from dataclasses import dataclass
import math
from pathlib import Path
import sys

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402
import onnx_detect as od  # noqa: E402
import phone_vision as pv  # noqa: E402

# CaptureProbeService.kt
HAND_SAMPLE_INTERVAL_MS = 250
RANK_PROBE_INTERVAL_MS = 250
ROI_SAMPLE_INTERVAL_MS = 50
STABLE_CHANGE_PERCENT = 1.5
HAND_SIGNATURE_COLUMNS = 12
HAND_SIGNATURE_ROWS = 8
SHOULD_PROBE_MIN_STABLE = 3

# HandRegions.kt
HAND_YOLO_LEFT = 180
HAND_YOLO_RIGHT = 1050
HAND_YOLO_TOP = 350
HAND_YOLO_BOTTOM = 490
HAND_PAD_TOP = 540
HAND_CANVAS_SIZE = HAND_YOLO_RIGHT - HAND_YOLO_LEFT  # 870

SIGNATURE_LEFT_PERCENT = 5
SIGNATURE_RIGHT_PERCENT = 95
SIGNATURE_TOP_PERCENT = 58

EDGE_MIN_X = 150
EDGE_MAX_X = 1130
TOP_EDGE_ROWS = (420, 451)
BOTTOM_EDGE_ROWS = (456, 469)
BOTTOM_BORDER_ROWS = (452, 457)

# HandCardPositionDetector.kt
POS_EDGE_THRESHOLD = 40.0
POS_NEIGHBORHOOD = 6
POS_MIN_EDGE_GAP = 28
POS_TOP_CARD_WIDTH = 90
POS_TOP_CARD_PITCH = 44.7
POS_BOTTOM_CARD_WIDTH = 90
POS_BOTTOM_CARD_PITCH = 46.4
POS_SINGLE_GAP_MIN = 38
POS_SINGLE_GAP_MAX = 55
POS_TINY_GAP = 10
POS_AMBIGUOUS_MARGIN = 0.15
POS_BORDER_STEP = 15.0
POS_BORDER_INSET_NEAR = 2
POS_BORDER_INSET_FAR = 12

# HandFrameMerger.kt
HAND_MERGE_X_TOLERANCE = 7.0
HAND_MERGE_ROW_TOLERANCE = 8.0

# HandAutoLock.kt
HAND_LOCK_STABLE_FRAMES = 4

# Rank.kt 4副牌 198张库存（按 Rank 顺序）
INVENTORY_BY_RANK: dict[str, int] = {
    "BIG_JOKER": 4,
    "SMALL_JOKER": 4,
    "TWO": 16,
    "ACE": 16,
    "KING": 16,
    "QUEEN": 16,
    "JACK": 16,
    "TEN": 16,
    "NINE": 16,
    "EIGHT": 16,
    "SEVEN": 16,
    "SIX": 16,
    "FIVE": 16,
    "FOUR": 8,
    "THREE": 6,
}

# RoiPlayEventCache.kt / TurnBadge.kt / PlayZone.kt
ROI_MAX_FRAMES = 8
ROI_CLEAR_SAMPLES = 6
ROI_STABLE_SAMPLES = 15
ROI_COVERED_STILL_SAMPLES = 3
TURN_CONSECUTIVE = 2
TURN_LONG_CLEAR_MS = 1500
OPENING_RETRY_WINDOW_MS = 8000
MID_GAME_DEFAULT_CONSECUTIVE = 5

HAND_ALLOWED_SPLITS = ("dev", "tuning", "clip")
BLIND_GAME_IDS = ("g202234-1", "g210429-1")
BLIND_VIDEO_TOKENS = ("20260815_202234", "20260815_210429")


def assert_hand_allowed(game: dict) -> None:
    """blind 局和盲测视频一律硬拦截。"""
    if (
        game["split"] not in HAND_ALLOWED_SPLITS
        or game["gameId"] in BLIND_GAME_IDS
        or any(token in game["video"] for token in BLIND_VIDEO_TOKENS)
    ):
        raise SystemExit(
            f"拒绝处理 {game['gameId']}（split={game['split']}）：blind 局禁止查看视频、观察或写真值"
        )


def counts_to_str(counts: dict[str, int]) -> str:
    """按 Rank 枚举顺序序列化成 `RANK:N;...`，与 TruthCsv.parseCards 一致。"""
    parts = [f"{r}:{counts[r]}" for r in ds.RANK_ORDER if counts.get(r, 0) > 0]
    return ";".join(parts)


def zero_counts() -> dict[str, int]:
    return {r: 0 for r in ds.RANK_ORDER}


def counts_total(counts: dict[str, int]) -> int:
    return sum(counts.values())


def fits_within(counts: dict[str, int], limit: dict[str, int]) -> bool:
    return all(counts.get(r, 0) <= limit.get(r, 0) for r in ds.RANK_ORDER)


def counts_from_detections(detections: list[tuple[int, float, float, float, float, float]]) -> dict[str, int]:
    counts = zero_counts()
    for cls_id, *_ in detections:
        rank = ds.RANK_ORDER[cls_id]
        counts[rank] += 1
    return counts


def hand_signature(bgr: np.ndarray) -> np.ndarray:
    """CaptureProbeService.handSignature：直接按原图尺寸取 12x8 亮度指纹（整数运算）。"""
    h, w = bgr.shape[:2]
    left = w * SIGNATURE_LEFT_PERCENT // 100
    right = w * SIGNATURE_RIGHT_PERCENT // 100
    top = h * SIGNATURE_TOP_PERCENT // 100
    cols = np.arange(HAND_SIGNATURE_COLUMNS, dtype=np.int64)
    rows = np.arange(HAND_SIGNATURE_ROWS, dtype=np.int64)
    xs = left + (right - left - 1) * cols // (HAND_SIGNATURE_COLUMNS - 1)
    ys = top + (h - top - 1) * rows // (HAND_SIGNATURE_ROWS - 1)
    px = bgr[np.ix_(ys, xs)].astype(np.int32)
    b, g, r = px[:, :, 0], px[:, :, 1], px[:, :, 2]
    luma = (r * 30 + g * 59 + b * 11) // 100
    return luma.reshape(-1)


def hand_change_percent(before: np.ndarray | None, after: np.ndarray) -> float | None:
    """CaptureProbeStatus.handChangePercent。"""
    if before is None or before.shape != after.shape:
        return None
    diff_sum = int(np.abs(before - after).sum())
    return diff_sum / (after.size * 255.0) * 100.0


def should_probe_hand(hand_locked: bool, on_table: bool, stable_hand_samples: int) -> bool:
    """PlayZone.shouldProbeHand。"""
    return (not hand_locked) and on_table and stable_hand_samples >= SHOULD_PROBE_MIN_STABLE


def hand_canvas_from_screen(screen: pv.Screen) -> np.ndarray:
    """ScreenScale.handCanvas：1280x578 画布 [180:1050, 350:490] 放到 870x870 黑底第 540 行起（RGB uint8）。"""
    strip = screen.canvas[HAND_YOLO_TOP:HAND_YOLO_BOTTOM, HAND_YOLO_LEFT:HAND_YOLO_RIGHT][:, :, ::-1]
    canvas = np.zeros((HAND_CANVAS_SIZE, HAND_CANVAS_SIZE, 3), dtype=np.uint8)
    h = HAND_YOLO_BOTTOM - HAND_YOLO_TOP
    canvas[HAND_PAD_TOP:HAND_PAD_TOP + h, :HAND_CANVAS_SIZE] = strip
    return canvas


def hand_input_320(hand_canvas: np.ndarray) -> np.ndarray:
    """ScreenScale.handInput：870x870 双线性缩放到 320x320（RGB uint8）。"""
    return cv2.resize(
        np.ascontiguousarray(hand_canvas),
        (pv.MODEL_SIZE, pv.MODEL_SIZE),
        interpolation=cv2.INTER_LINEAR,
    )


def detect_hand_boxes(
    detector: od.RankDetector, screen: pv.Screen
) -> list[tuple[int, float, float, float, float, float]]:
    """手机同口径手牌 YOLO 检测（refineOutsideJoker=False）。"""
    canvas870 = hand_canvas_from_screen(screen)
    rgb320 = hand_input_320(canvas870)
    return detector.detect(rgb320)


# ---- HandCardPositionDetector ----


def _luma_snapshot(bgr: np.ndarray) -> np.ndarray:
    """HandCardPositionDetector.snapshot：对三条采样带在原图上按整数比例取最近像素的灰度。"""
    h, w = bgr.shape[:2]
    luma = np.zeros((pv.CANVAS_H, pv.CANVAS_W), dtype=np.int32)
    x0 = EDGE_MIN_X - 1
    x1 = EDGE_MAX_X + 1
    xs = np.arange(x0, x1, dtype=np.int64)
    sx = xs * w // pv.CANVAS_W
    for y_start, y_end in (TOP_EDGE_ROWS, BOTTOM_EDGE_ROWS, BOTTOM_BORDER_ROWS):
        ys = np.arange(y_start, y_end, dtype=np.int64)
        sy = ys * h // pv.CANVAS_H
        patch = bgr[np.ix_(sy, sx)].astype(np.int32)
        b, g, r = patch[:, :, 0], patch[:, :, 1], patch[:, :, 2]
        luma[y_start:y_end, x0:x1] = (r * 30 + g * 59 + b * 11) // 100
    return luma


def _edge_positions(luma: np.ndarray, row_range: tuple[int, int]) -> list[int]:
    y0, y1 = row_range
    row_count = y1 - y0
    band = luma[y0:y1, EDGE_MIN_X - 1:EDGE_MAX_X + 1]
    diffs = np.abs(band[:, 1:] - band[:, :-1]).sum(axis=0)
    scores = diffs.astype(np.float64) / float(row_count)

    peaks: list[tuple[int, float]] = []
    n = len(scores)
    for idx in range(n):
        sc = float(scores[idx])
        if sc < POS_EDGE_THRESHOLD:
            continue
        lo = max(0, idx - POS_NEIGHBORHOOD)
        hi = min(n, idx + POS_NEIGHBORHOOD + 1)
        if np.any(scores[lo:hi] > sc):
            continue
        peaks.append((EDGE_MIN_X + idx, sc))

    peaks.sort(key=lambda item: -item[1])
    selected: list[int] = []
    for cand_x, _ in peaks:
        if all(abs(sx - cand_x) > POS_MIN_EDGE_GAP for sx in selected):
            selected.append(cand_x)
    return selected


def _border_step(luma: np.ndarray, from_x: int, until_x: int) -> float:
    x_min = EDGE_MIN_X - 1
    x_max = EDGE_MAX_X + 1
    x0 = max(x_min, min(x_max, from_x))
    x1 = max(x0, min(x_max, until_x))
    if x1 <= x0:
        return 0.0
    above = float(luma[BOTTOM_BORDER_ROWS[0], x0:x1].mean())
    brightest = max(
        float(luma[y, x0:x1].mean())
        for y in range(BOTTOM_BORDER_ROWS[0] + 2, BOTTOM_BORDER_ROWS[1])
    )
    return brightest - above


def _has_own_top_border(luma: np.ndarray, bottom_edges: list[int]) -> bool:
    if len(bottom_edges) < 2:
        return False
    left = min(bottom_edges)
    right = max(bottom_edges)
    return (
        _border_step(luma, left + POS_BORDER_INSET_NEAR, left + POS_BORDER_INSET_FAR) >= POS_BORDER_STEP
        and _border_step(luma, right - POS_BORDER_INSET_FAR, right - POS_BORDER_INSET_NEAR) >= POS_BORDER_STEP
    )


def _count_by_gaps(selected: list[int], card_width: int, card_pitch: float) -> int | None:
    if len(selected) < 2:
        return None
    s = sorted(selected)
    if s[-1] - s[0] < card_width:
        return None
    last_left = s[-1] - card_width
    lefts = [x for x in s if x < last_left] + [last_left]
    count = 1
    unusual = 0
    for left, nxt in zip(lefts, lefts[1:]):
        gap = nxt - left
        if gap <= POS_TINY_GAP or (POS_SINGLE_GAP_MIN <= gap <= POS_SINGLE_GAP_MAX):
            count += pv.round_half_up(unusual / card_pitch) + (1 if gap > POS_TINY_GAP else 0)
            unusual = 0
        else:
            unusual += gap
    count += pv.round_half_up(unusual / card_pitch)
    return count if count > 0 else None


def _row_count_bottom(selected: list[int], card_width: int, card_pitch: float) -> int | None:
    if len(selected) < 2:
        return None
    left = min(selected)
    right = max(selected)
    if right - left < card_width:
        return None
    raw = (right - card_width - left) / card_pitch + 1.0
    nearest = pv.round_half_up(raw)
    if abs(raw - nearest) > 0.5 - POS_AMBIGUOUS_MARGIN:
        lower = int(math.floor(raw))
        by_edges = len(selected) - 1
        count = lower if abs(lower - by_edges) < abs(lower + 1 - by_edges) else lower + 1
    else:
        count = nearest
    return count if count > 0 else None


def detect_hand_positions(bgr: np.ndarray) -> tuple[tuple[int | None, int | None], int | None]:
    """HandCardPositionDetector.rowCounts + detect：返回 ((top, bottom), total)。"""
    luma = _luma_snapshot(bgr)
    top_edges = _edge_positions(luma, TOP_EDGE_ROWS)
    bottom_edges = _edge_positions(luma, BOTTOM_EDGE_ROWS)
    top_count = _count_by_gaps(top_edges, POS_TOP_CARD_WIDTH, POS_TOP_CARD_PITCH)
    bottom_count = (
        _row_count_bottom(bottom_edges, POS_BOTTOM_CARD_WIDTH, POS_BOTTOM_CARD_PITCH)
        if _has_own_top_border(luma, bottom_edges)
        else None
    )
    if top_count is None or bottom_count is None or top_count <= 0 or bottom_count <= 0:
        total = None
    elif 0 <= bottom_count - top_count <= 1:
        total = top_count + bottom_count
    else:
        total = None
    return (top_count, bottom_count), total


# ---- HandFrameMerger / HandPositionVoter / HandAutoLock ----


@dataclass
class HandMergeResult:
    single: dict[str, int]
    merged: dict[str, int]
    frame_detections: list[tuple[int, float, float, float, float, float]]
    slot_detections: list[tuple[int, float, float, float, float, float]]
    frames: int
    fell_back: bool


class HandFrameMerger:
    """对应 core/.../capture/HandFrameMerger.kt。"""

    def __init__(
        self,
        inventory: dict[str, int] | None = None,
        x_tolerance: float = HAND_MERGE_X_TOLERANCE,
        row_tolerance: float = HAND_MERGE_ROW_TOLERANCE,
    ):
        self.inventory = inventory or INVENTORY_BY_RANK
        self.x_tolerance = x_tolerance
        self.row_tolerance = row_tolerance
        self.slots: list[list] = []  # [detection]
        self.scene: int | None = None
        self.frames = 0

    def reset(self) -> None:
        self.slots.clear()
        self.scene = None
        self.frames = 0

    def add(
        self,
        scene: int,
        detections: list[tuple[int, float, float, float, float, float]],
    ) -> HandMergeResult:
        if self.scene != scene:
            self.reset()
            self.scene = scene
        self._absorb(detections)
        self.frames += 1
        single = counts_from_detections(detections)
        slot_dets = [s[0] for s in self.slots]
        merged = counts_from_detections(slot_dets)
        if fits_within(merged, self.inventory):
            return HandMergeResult(single, merged, detections, slot_dets, self.frames, False)
        self.slots.clear()
        self.frames = 1
        self._absorb(detections)
        return HandMergeResult(single, single, detections, list(detections), self.frames, True)

    def _absorb(self, detections: list[tuple[int, float, float, float, float, float]]) -> None:
        claimed: set[int] = set()
        for det in sorted(detections, key=lambda d: -d[1]):
            _, conf, cx, cy, _, _ = det
            best_idx = None
            best_dx = float("inf")
            for idx, slot in enumerate(self.slots):
                if idx in claimed:
                    continue
                _, _, scx, scy, _, _ = slot[0]
                if abs(scy - cy) <= self.row_tolerance and abs(scx - cx) <= self.x_tolerance:
                    dx = abs(scx - cx)
                    if dx < best_dx:
                        best_dx = dx
                        best_idx = idx
            if best_idx is None:
                claimed.add(len(self.slots))
                self.slots.append([det])
            else:
                claimed.add(best_idx)
                if conf > self.slots[best_idx][0][1]:
                    self.slots[best_idx][0] = det


class HandPositionVoter:
    """对应 core/.../capture/HandPositionVoter.kt。"""

    def __init__(self) -> None:
        self.scene: int | None = None
        self.votes: dict[int, int] = {}
        self.last_seen: dict[int, int] = {}
        self.tick = 0

    def reset(self) -> None:
        self.scene = None
        self.votes.clear()
        self.last_seen.clear()
        self.tick = 0

    def add(self, scene: int, count: int | None) -> int | None:
        if self.scene != scene:
            self.reset()
            self.scene = scene
        if count is not None:
            self.votes[count] = self.votes.get(count, 0) + 1
            self.tick += 1
            self.last_seen[count] = self.tick
        if not self.votes:
            return None
        return max(self.votes.keys(), key=lambda k: (self.votes[k], self.last_seen[k]))


@dataclass(frozen=True)
class HandReading:
    cards: tuple[tuple[str, int], ...]  # 按 RANK_ORDER 的不可变表示，便于相等比较
    position_count: int | None

    @classmethod
    def from_dict(cls, cards: dict[str, int], position_count: int | None) -> "HandReading":
        return cls(tuple((r, cards.get(r, 0)) for r in ds.RANK_ORDER), position_count)

    def as_dict(self) -> dict[str, int]:
        return dict(self.cards)


class HandAutoLock:
    """对应 core/.../domain/HandAutoLock.kt。"""

    def __init__(self, stable_frames: int = HAND_LOCK_STABLE_FRAMES) -> None:
        self.stable_frames = stable_frames
        self.latest: HandReading | None = None
        self.streak = 0
        self.qualified: tuple[dict[str, int], int] | None = None
        self.qualified_at_ms: int | None = None
        self.locked = False

    def observe(self, reading: HandReading, ts_ms: int | None = None) -> None:
        if self.locked:
            return
        self.streak = self.streak + 1 if reading == self.latest else 1
        self.latest = reading
        if self.streak < self.stable_frames or not self._is_qualified(reading):
            return
        pos = reading.position_count
        if pos is None:
            return
        cards_dict = reading.as_dict()
        if self.qualified is not None:
            curr_cards, _ = self.qualified
            if cards_dict != curr_cards and fits_within(cards_dict, curr_cards):
                return
        self.qualified = (cards_dict, pos)
        self.qualified_at_ms = ts_ms

    def lock(self) -> tuple[dict[str, int], int] | None:
        self.locked = True
        return self.qualified

    def reset(self) -> None:
        self.latest = None
        self.streak = 0
        self.qualified = None
        self.qualified_at_ms = None
        self.locked = False

    @staticmethod
    def _is_qualified(reading: HandReading) -> bool:
        pos = reading.position_count
        if pos is None or pos <= 0:
            return False
        cards_dict = reading.as_dict()
        return counts_total(cards_dict) == pos and fits_within(cards_dict, INVENTORY_BY_RANK)


# ---- 开局门（与 CaptureProbeService + RoiPlayEventCache + TurnTracker + OpeningPlayGate 对齐） ----


class _ZoneCacheState:
    def __init__(self) -> None:
        self.frames: list[np.ndarray] = []
        self.pending: list[np.ndarray] = []
        self.pending_still = 0
        self.pending_visible_samples = 0
        self.current_visible_samples = 0
        self.last: np.ndarray | None = None
        self.empty_samples = 0
        self.still_count = 0
        self.delivered = False
        self.cleared = False


class OpeningGateTracker:
    """只追踪开局门何时打开（OUTSIDE 区出牌圆标连续 2 次且桌面已空、无待兜底牌）。"""

    def __init__(self) -> None:
        self.outside_names = [s.name for s in pv.SPECS if s.outside]
        self.cache = {name: _ZoneCacheState() for name in self.outside_names}
        self.consecutive = {name: 0 for name in self.outside_names}
        self.fired = {name: False for name in self.outside_names}
        self.mid_pending = True
        self.mid_consecutive = 0
        self.mid_skipped = False
        self.entered_play_phase = False
        self.opened_at_ms: int | None = None
        self.opened_zone: str | None = None

    def observe_50ms(self, screen: pv.Screen, ts_ms: int) -> bool:
        """返回本次采样是否刚刚打开开局门。"""
        table_visible = pv.is_table_visible(screen)
        zone_states = (
            {s.name: pv.zone_visibility(screen, s) for s in pv.SPECS if s.outside}
            if table_visible
            else {}
        )

        # MidGameOpenDetector
        if self.mid_pending:
            if not table_visible:
                self.mid_consecutive = 0
            else:
                any_card = any(zone_states[n] == pv.CARD for n in self.outside_names)
                if any_card:
                    self.mid_consecutive += 1
                    if self.mid_consecutive >= MID_GAME_DEFAULT_CONSECUTIVE:
                        self.mid_pending = False
                        self.mid_skipped = True
                elif all(zone_states[n] == pv.EMPTY for n in self.outside_names):
                    self.mid_pending = False
                    self.mid_consecutive = 0
                else:
                    self.mid_consecutive = 0
        if self.mid_skipped:
            return False

        opened_now = False
        for spec in pv.SPECS:
            if not spec.outside:
                continue
            name = spec.name
            state_vis = zone_states.get(name)
            zone_table_visible = table_visible and state_vis != pv.OCCLUDED
            visible = zone_table_visible and state_vis == pv.CARD
            fp = pv.fingerprint(screen, spec) if visible else None
            self._cache_observe(name, zone_table_visible, visible, fp)

            shown = zone_table_visible and pv.turn_badge_shown(screen, spec, pv.BADGE_REC_SHIFT[name])
            has_undelivered = self._has_undelivered(name)
            zone_empty = zone_table_visible and (not visible) and (not has_undelivered)

            if not zone_table_visible or not shown or not zone_empty:
                self.consecutive[name] = 0
                self.fired[name] = False
            else:
                self.consecutive[name] += 1
                if self.consecutive[name] >= TURN_CONSECUTIVE and not self.fired[name]:
                    self.fired[name] = True
                    if not self.entered_play_phase:
                        self.entered_play_phase = True
                        self.opened_at_ms = ts_ms
                        self.opened_zone = name
                        opened_now = True
        return opened_now

    def _fallback_source(self, st: _ZoneCacheState, min_still: int) -> list[np.ndarray] | None:
        if st.delivered:
            return None
        if st.still_count > 0:
            return st.frames if st.still_count >= min_still else None
        if st.pending:
            return st.pending if st.pending_still >= min_still else None
        return None

    def _has_undelivered(self, name: str) -> bool:
        return self._fallback_source(self.cache[name], 1) is not None

    def _clear_state(self, st: _ZoneCacheState) -> None:
        st.frames.clear()
        st.pending.clear()
        st.pending_still = 0
        st.pending_visible_samples = 0
        st.current_visible_samples = 0
        st.last = None
        st.empty_samples = 0
        st.still_count = 0
        st.delivered = False
        st.cleared = False

    def _cache_observe(
        self, name: str, table_visible: bool, card_visible: bool, fp: np.ndarray | None
    ) -> None:
        st = self.cache[name]
        if not table_visible:
            if self._fallback_source(st, ROI_COVERED_STILL_SAMPLES) is not None:
                self._clear_state(st)
            return
        if card_visible:
            st.empty_samples = 0
            st.cleared = False
            if fp is None:
                return
            prev = st.last
            st.last = fp
            if prev is not None and pv.fingerprint_still(prev, fp):
                if st.delivered:
                    return
                if st.pending:
                    st.pending.clear()
                    st.pending_still = 0
                    st.pending_visible_samples = 0
                if len(st.frames) >= ROI_MAX_FRAMES:
                    st.frames.pop(0)
                st.frames.append(fp)
                st.still_count += 1
                st.current_visible_samples += 1
                if st.still_count >= ROI_STABLE_SAMPLES:
                    st.frames.clear()
                    st.still_count = 0
                    st.current_visible_samples = 0
                    st.delivered = True
                return
            if not st.delivered and st.still_count > 0:
                st.pending = list(st.frames)
                st.pending_still = st.still_count
                st.pending_visible_samples = st.current_visible_samples
                st.current_visible_samples = 1
            else:
                st.current_visible_samples = 1 if st.delivered else st.current_visible_samples + 1
            st.frames = [fp]
            st.still_count = 0
            st.delivered = False
            return
        st.last = None
        st.empty_samples = min(st.empty_samples + 1, ROI_CLEAR_SAMPLES)
        if st.empty_samples < ROI_CLEAR_SAMPLES:
            return
        if self._fallback_source(st, 1) is not None:
            self._clear_state(st)
        st.cleared = True
