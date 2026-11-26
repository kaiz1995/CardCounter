"""把 100ms 检测流压成整局真值草稿 datasets/truth/<gameId>.csv。

规则: 白区出现 -> 连续停稳 -> 多帧投票 -> 清空
- 白区出现: white_ratio >= 6%
- 停稳: 连续 3 帧检测签名不变
- 投票: 停稳窗口内每帧检测按 Rank 计数，取逐 Rank 中位数
- 清空: white_ratio < 3% 连续 5 帧（500ms）
- 同一区没清空就换下一手 -> REPLACE_DIRECT
- 同区、同点数集合、首尾相接、扇宽一致的连续事件 = 同一手被重复检出（出牌/闷牌动画把
  白区打断，或对话框遮住牌区），合并成一手，取模型票数最多的一次。

张数按区分别做几何标定，模型只提供点数：
- 其他五区沿用 46px 单张 / 14.3px 牌距（`102658` 516-536s 人工真值标定）。
- SELF 区牌更大、扇形更宽，单独量：单张 68px、牌距 22px（标定记录见 README）。
- 张数 = 1 + round((扇宽 - 单张) / 牌距)；扇形被区裁剪或牌距压缩时宽度法失效，改数牌角
  墨迹列簇。
- 模型与几何差 1 张：几何可信时按几何补齐；差 2 张以上：notes 写 needs_review，张数取
  两者里较可信的一个（列簇与模型一致时用列簇，否则用模型），review 列按格式契约仍填 AUTO。
- 一手里出现两种普通点数（大王/小王/2 之外的 Rank）按够级规则不可能：按出现最多的点数
  处理，notes 留痕 mixed_ranks。
- 库存：4 副牌 198 张（Rank.kt）只检查、不修改。complete=true 的局里某点数合计超库存时，把
  贡献它的可疑手标 needs_review，notes 写 `stock-over <RANK> <合计>/<库存>`；任何一手的 cards
  和 quantity 都不因库存而改动（stock_report.py 出待核对清单，check_truth.py --all 当硬错误）。
- 补张：模型点数比张数少时，差的张数补给这一手里票数最多的普通点数；一手里没有普通点数
  （只看到大小王）就不拿大小王去凑，cards 留空、张数仍按几何，notes 记模型看到的点数。
- 出牌记录面板（青色描边的深色面板）盖住出牌区时，区裁剪近黑像素占比 >= 25%，区里看到的是
  面板里的小牌，不是桌面出牌：这类事件整个排除，记入 review/panel_dropped.csv；面板前后是
  同一手（点数集合相同、扇宽一致、期间区从未空过）的合并成一手，notes 记 panel_covered。
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import sys

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402

OCC_ON = 0.06
OCC_OFF = 0.03
CLEAR_SAMPLES = 5
STABLE_SAMPLES = 3
MIN_SCORE = 0.30
MIN_QUANTITY = 1

import stockrules as sr  # noqa: E402
from stockrules import NORMAL_RANKS, STOCK  # noqa: E402  库存与 notes 规则见 stockrules.py

PANEL_DARK = 0.25  # 区裁剪里最亮通道 <50 的像素占比 >= 此值 = 出牌记录面板盖住
PANEL_GAP_STEP_MS = 500

# (单张宽度, 牌距)。SELF 在 161x161 裁剪上实测：68 / 90 / 112 / 132-137 / 156 对应 1-5 张。
ZONE_GEOM = {"SELF": (68.0, 22.0)}
DEFAULT_GEOM = (46.0, 14.3)

# 宽扇形（6 张以上）撑满 SELF 裁剪，宽度法无法分辨张数；这几手人工读牌面右上角张数角标
# 确认（裁剪图与成片时间已复核），键为 (gameId, 停稳毫秒)，值为张数。
SELF_WIDE_MANUAL = {
    ("g102658-2", 352216): 6,
    ("g102658-4", 704130): 6,
    ("g102658-5", 796809): 6,
    ("g143651-4", 674619): 7,
    ("g202234-1", 257217): 7,
    ("g210429-1", 231012): 6,
}


def load_stream(game_id: str):
    path = ds.DETECTIONS_DIR / f"{game_id}.jsonl"
    if not path.exists():
        raise SystemExit(f"缺少检测流: {path}")
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                yield json.loads(line)


def card_mask(crop: np.ndarray) -> np.ndarray:
    """低饱和亮块：牌面白/灰白；牌桌绿、红色花色都被排除。"""
    data = crop.astype(np.int16)
    high = data.max(axis=2)
    low = data.min(axis=2)
    return ((high - low) <= 45) & (high >= 110)


def fan_box(crop: np.ndarray):
    """返回 (top, bottom, left, right)，白色牌扇的包围盒；找不到返回 None。"""
    if crop.size == 0:
        return None
    mask = card_mask(crop)
    rows = mask.mean(axis=1)
    fan_rows = np.where(rows > 0.15)[0]
    if len(fan_rows) < 3:
        return None
    top, bottom = int(fan_rows.min()), int(fan_rows.max())
    cols = mask[top:bottom + 1].mean(axis=0)
    fan_cols = np.where(cols > 0.15)[0]
    if len(fan_cols) < 2:
        return None
    return top, bottom, int(fan_cols.min()), int(fan_cols.max())


def ink_columns(crop: np.ndarray, box, frac: float = 0.40, threshold: float = 0.12):
    """牌角墨迹列：扇形上半部的暗/高饱和列，按 >=2px 空白分组。"""
    top, bottom, left, right = box
    height = max(2, int((bottom - top + 1) * frac))
    region = crop[top:top + height, left:right + 1].astype(np.int16)
    high = region.max(axis=2)
    low = region.min(axis=2)
    ink = (high < 115) | ((high - low) > 55)
    profile = ink.mean(axis=0)
    runs = []
    start = None
    run = 0
    for index, value in enumerate(profile):
        if value >= threshold:
            if start is None:
                start = index
            run += 1
        else:
            if run > 0:
                if run >= 2:
                    runs.append((start, index - 1))
                start = None
                run = 0
    if run > 0:
        runs.append((start, len(profile) - 1))
    return runs


def ink_clusters(crop: np.ndarray, box, frac: float = 0.40, threshold: float = 0.12):
    """牌角墨迹列簇数：扇形上半部的暗/高饱和列，按 >=2px 空白分组。"""
    return len(ink_columns(crop, box, frac, threshold))


def corner_count(crop: np.ndarray, box, expected_pitch: float):
    """牌角列簇按牌距聚合：返回 (张数, 实测牌距)。

    牌角墨迹列可能把一个 "10" 拆成两列（1 和 0），所以先剔除明显小于牌距的间隔，
    再用相邻列起点距离 + 首末跨度推张数。这样牌距被压缩（5 张以上挤进小裁剪）时仍可用。
    """
    runs = ink_columns(crop, box)
    if not runs:
        return 0, None
    if len(runs) == 1:
        return 1, None
    starts = [start for start, _end in runs]
    diffs = sorted(starts[i + 1] - starts[i] for i in range(len(starts) - 1))
    usable = [d for d in diffs if d >= 0.55 * expected_pitch]
    if not usable:
        return len(runs), None
    pitch = usable[len(usable) // 2]
    span = starts[-1] - starts[0]
    return 1 + int(round(span / pitch)), pitch


def measure(crop: np.ndarray, zone: str):
    """量张数：返回宽度法结果、是否被区裁掉、牌角墨迹列簇数。"""
    box = fan_box(crop)
    if box is None:
        return None
    top, bottom, left, right = box
    width = right - left + 1
    single, pitch = ZONE_GEOM.get(zone, DEFAULT_GEOM)
    by_width = 1 + max(0, int(round((width - single) / pitch)))
    clipped = left <= 0 or right >= crop.shape[1] - 1
    corners, measured_pitch = corner_count(crop, box, pitch)
    return {
        "width": width,
        "left": left,
        "right": right,
        "crop_w": int(crop.shape[1]),
        "by_width": max(0, min(by_width, 30)),
        "clipped": bool(clipped),
        "clusters": ink_clusters(crop, box),
        "corners": corners,
        "pitch": measured_pitch,
    }


def resolve_quantity(zone: str, model_total: int, meas, override, model_ranks=None):
    """按区的几何结果和模型票数定张数；返回 (张数, [notes], needs_review)。"""
    notes: list[str] = []
    if meas is None:
        return max(MIN_QUANTITY, model_total), ["crop-missing"], True

    width = meas["width"]
    geom = meas["by_width"]
    clusters = meas["clusters"]

    # 牌旁的「挂大王/挂小王」黄色气泡、红色出牌徽章、飞牌动画都是白/亮色块，会把扇宽撑大 2~5 张：
    # 非 SELF 区几何比模型多 2 张以上时，以模型票数为准。人工看画面核对过的 7 手
    # （g202234-1 #53/#72、g210429-1 #49、g102658-1 #78、g102658-4 #86、g143651-1 #58、
    # g135222-1 #2）模型张数全部正确、几何全部偏大，没有反例。
    if (
        zone != "SELF"
        and override is None
        and model_total >= MIN_QUANTITY
        and geom - model_total >= 2
    ):
        notes.append(f"geom-over; model={model_total} geom={geom}(w={width})")
        return model_total, notes, False

    if zone == "SELF":
        if override is not None:
            notes.append(f"geom=wide(w={width}) manual={override}")
            return override, notes, False
        if not meas["clipped"] or width <= 157:
            notes.append(f"geom={geom}(w={width})")
            if abs(geom - model_total) >= 2:
                notes.append("needs_review")
                return geom, notes, True
            return geom, notes, False
        notes.append(
            f"needs_review; geom=wide(w={width}) model={model_total} clusters={clusters}"
        )
        return max(MIN_QUANTITY, model_total), notes, True

    # 其他五区：牌距压缩（5 张以上挤进小裁剪）时宽度法会低估，靠牌角列簇/模型兜底
    compressed = meas["clipped"] or width >= meas["crop_w"] - 6
    if not compressed and geom >= MIN_QUANTITY:
        notes.append(f"geom={geom}(w={width})")
        if abs(geom - model_total) >= 2:
            notes.append(f"needs_review; model={model_total} geom={geom}")
            return geom, notes, True
        return geom, notes, False

    corners = meas["corners"]
    pitch = meas["pitch"]
    if corners >= MIN_QUANTITY and abs(corners - model_total) <= 1:
        resolved, source = corners, f"corners(pitch={pitch})"
    else:
        resolved, source = max(MIN_QUANTITY, model_total), "model"
    notes.append(
        f"needs_review; model={model_total} geom={geom}(w={width}) "
        f"clusters={clusters} corners={corners} -> {source}"
    )
    return resolved, notes, True


def sample_counts(sample, zone: str, min_score: float = MIN_SCORE):
    counts = {}
    for det in sample["dets"].get(zone, []):
        if det["score"] >= min_score:
            counts[det["rank"]] = counts.get(det["rank"], 0) + 1
    return counts


def vote_counts(stable_samples, zone: str):
    """逐 Rank 取中位数；只保留至少半数帧出现的 Rank。"""
    per_rank = {}
    for sample in stable_samples:
        counts = sample_counts(sample, zone)
        for rank in ds.RANK_ORDER:
            per_rank.setdefault(rank, []).append(counts.get(rank, 0))
    voted = {}
    for rank, values in per_rank.items():
        ordered = sorted(values)
        median = ordered[len(ordered) // 2]
        present = sum(1 for v in values if v > 0)
        if median > 0 and present * 2 >= len(values):
            voted[rank] = int(median)
    return voted


def collapse_mixed_ranks(voted: dict):
    """一手不可能同时有两种普通点数：按出现最多的那个点数处理。"""
    normals = [rank for rank in voted if rank in NORMAL_RANKS and voted[rank] > 0]
    if len(normals) < 2:
        return voted, None
    dominant = max(normals, key=lambda rank: (voted[rank], -ds.RANK_INDEX[rank]))
    dropped = ";".join(f"{rank}:{voted[rank]}" for rank in normals if rank != dominant)
    for rank in normals:
        if rank != dominant:
            del voted[rank]
    return voted, f"mixed_ranks={dropped}->{dominant}"


def adjust_to_quantity(voted: dict, quantity: int):
    """把模型点数向量对齐到几何/列簇定出的张数；只在这一手自己的证据之间取舍，不看库存。

    模型比张数多：从票数最少的点数里裁（trim）。模型比张数少：差的张数补给这一手里票数最多
    的普通点数；没有普通点数时，只有一种 2 才补 2（可能是纯 2 的一手），其余情况（只看到大小
    王）cards 留空，张数仍按几何，模型看到的点数写进 notes。
    """
    total = sum(voted.values())
    if total == quantity:
        return voted, ""
    note = f"model={total} geom={quantity}"
    if quantity <= 0 or not voted:
        return {}, note
    if total < quantity:
        normals = [r for r in voted if r in NORMAL_RANKS]
        pool = normals or (["TWO"] if list(voted) == ["TWO"] else [])
        if not pool:
            seen = ";".join(f"{r}:{voted[r]}" for r in ds.RANK_ORDER if r in voted)
            return {}, note + f" no-normal-rank (model_ranks={seen})"
        top = max(pool, key=lambda r: (voted[r], -ds.RANK_INDEX[r]))
        voted[top] += quantity - total
        return voted, note + f" +{quantity - total}{top}"
    while total > quantity:
        low = min(voted, key=lambda r: (voted[r], -ds.RANK_INDEX[r]))
        voted[low] -= 1
        total -= 1
        if voted[low] == 0:
            del voted[low]
    return voted, note + " trim"


def cards_string(voted: dict) -> str:
    return ";".join(
        f"{rank}:{voted[rank]}"
        for rank in ds.RANK_ORDER
        if voted.get(rank, 0) > 0
    )


def signature(sample, zone: str):
    """同一帧的 Rank 多重集合（低分框丢弃）。"""
    ranks = [
        det["rank"]
        for det in sample["dets"].get(zone, [])
        if det["score"] >= MIN_SCORE
    ]
    return tuple(sorted(ranks))


def position_key(sample, zone: str):
    """同一帧按 x 坐标排序的 (点数, 检测标签)；标签带红黑色（B6/R6），signature 丢掉的就是这个。"""
    dets = [d for d in sample["dets"].get(zone, []) if d["score"] >= MIN_SCORE]
    dets.sort(key=lambda d: d["cx"])
    return tuple((d["rank"], d["label"]) for d in dets)


def is_color_flip(a, b) -> bool:
    """同区换手但点数多重集合不变：每个位置的点数都没变、只有红黑色变了。
    某个位置点数变了（如 B4/R4/R3/R4 -> B4/R4/R4/R3）是误检抖动，不算换手。"""
    if not a or not b or len(a) != len(b):
        return False
    if any(x[0] != y[0] for x, y in zip(a, b)):
        return False
    return any(x[1] != y[1] for x, y in zip(a, b))


def split_color_runs(runs, zone: str):
    """稳定段内部按红黑色换手拆开：新的位置标签序列连续稳定 >= STABLE_SAMPLES 个采样，且和前一个稳定
    标签序列是 is_color_flip（每个位置点数不变、只有颜色变）时，在新序列首帧处切开。
    单帧/两帧的标签抖动不够 STABLE_SAMPLES，不会切。没有换色的段原样返回。
    返回 (sig, samples, forced)，forced=True 表示这一段是拆出来的后半，后面合并步骤不得并回去。"""
    out = []
    for sig, samples in runs:
        segs = []
        for sample in samples:
            key = position_key(sample, zone)
            if segs and segs[-1][0] == key:
                segs[-1][1].append(sample)
            else:
                segs.append([key, [sample]])
        solid = [i for i, (key, group) in enumerate(segs) if key and len(group) >= STABLE_SAMPLES]
        cuts = [b for a, b in zip(solid, solid[1:]) if is_color_flip(segs[a][0], segs[b][0])]
        if not cuts:
            out.append((sig, samples, False))
            continue
        start = 0
        for n, cut in enumerate(cuts + [len(segs)]):
            part = [s for _, group in segs[start:cut] for s in group]
            out.append((sig, part, n > 0))
            start = cut
    return out


def build_events(stream, first_play_ms: int):
    """白区出现 -> 内容连续稳定 -> 投票 -> 清空；同区未清空直接换手记 REPLACE_DIRECT。"""
    events = []
    for zone in ds.ZONES:
        episode = []
        empty_run = 0

        def close(episode, zone=zone):
            if not episode:
                return
            runs = []
            current = []
            current_sig = None
            for sample in episode:
                occ = sample["zones"][zone]["white_ratio"] >= OCC_ON
                sig = signature(sample, zone)
                stable = occ and (sig or sample["zones"][zone]["still"])
                if stable and (sig == current_sig or not current):
                    current.append(sample)
                    current_sig = sig
                elif stable and sig != current_sig:
                    if len(current) >= STABLE_SAMPLES:
                        runs.append((current_sig, current))
                    current = [sample]
                    current_sig = sig
                else:
                    if len(current) >= STABLE_SAMPLES:
                        runs.append((current_sig, current))
                    current = []
                    current_sig = None
            if len(current) >= STABLE_SAMPLES:
                runs.append((current_sig, current))
            if not runs:
                return
            runs = split_color_runs(runs, zone)
            merged = []
            for sig, samples, forced in runs:
                voted = vote_counts(samples, zone)
                if not voted:
                    continue
                if merged and merged[-1]["voted"] == voted and not forced:
                    merged[-1]["samples"].extend(samples)
                    merged[-1]["end"] = samples[-1]["ts_ms"]
                else:
                    merged.append(
                        {
                            "color_split": forced,
                            "voted": voted,
                            "samples": list(samples),
                            "start": samples[0]["ts_ms"],
                            "crop_ms": samples[len(samples) // 2]["ts_ms"],
                            "end": samples[-1]["ts_ms"],
                        }
                    )
            for i, item in enumerate(merged):
                appear = episode[0]["ts_ms"] if i == 0 else item["start"]
                clear = (
                    merged[i + 1]["start"]
                    if i + 1 < len(merged)
                    else episode[-1]["ts_ms"]
                )
                events.append(
                    {
                        "zone": zone,
                        "appear_ms": appear,
                        "stable_ms": item["start"],
                        "crop_ms": item["crop_ms"],
                        "clear_ms": clear,
                        "voted": item["voted"],
                        "tag": "REPLACE_DIRECT" if i > 0 else "NORMAL",
                        "color_split": bool(item["color_split"]),
                    }
                )

        for sample in stream:
            ratio = sample["zones"][zone]["white_ratio"]
            if not episode:
                if ratio >= OCC_ON:
                    episode = [sample]
                    empty_run = 0
            else:
                if ratio < OCC_OFF:
                    empty_run += 1
                    if empty_run >= CLEAR_SAMPLES:
                        close(episode)
                        episode = []
                        empty_run = 0
                else:
                    empty_run = 0
                    episode.append(sample)
        close(episode)
    events = [e for e in events if e["stable_ms"] >= first_play_ms]
    events.sort(key=lambda e: (e["appear_ms"], e["zone"]))
    return events


def panel_covered(crop) -> bool:
    """区裁剪被出牌记录面板盖住：面板是近黑底（最亮通道 <50 的像素占 70% 上下）。"""
    if crop is None or crop.size == 0:
        return False
    return float((crop.max(axis=2) < 50).mean()) >= PANEL_DARK


def gap_has_clear(video: Path, zone: str, start_ms: int, end_ms: int) -> bool:
    """start..end 之间区是否真的空过：连续两个采样（500ms 一次）都是空牌桌。
    面板压暗、有牌都不算空。"""
    empty_run = 0
    t = start_ms + PANEL_GAP_STEP_MS
    while t < end_ms:
        frame = ds.frame_at(video, t)
        if frame is not None:
            crop = ds.crop_zone(frame, zone)
            if ds.white_ratio(frame, zone) < OCC_OFF and not panel_covered(crop):
                empty_run += 1
                if empty_run >= 2:
                    return True
            else:
                empty_run = 0
        t += PANEL_GAP_STEP_MS
    return False


def gap_covered(video: Path, zone: str, start_ms: int, end_ms: int) -> bool:
    """start..end 之间区被动画/面板压暗：至少一个采样 panel_covered（白区为 0 时没有面板事件，靠这个判断）。"""
    t = start_ms + PANEL_GAP_STEP_MS
    while t < end_ms:
        frame = ds.frame_at(video, t)
        if frame is not None and panel_covered(ds.crop_zone(frame, zone)):
            return True
        t += PANEL_GAP_STEP_MS
    return False


def bridge_panels(zone_events, video: Path):
    """排除面板覆盖的事件；面板/烧牌动画前后同一手的合并。返回 (保留的事件, 被排除的面板事件)。"""
    kept: list[dict] = []
    dropped: list[dict] = []
    between: list[dict] = []
    for event in zone_events:
        if event.get("panel"):
            between.append(event)
            continue
        prev = kept[-1] if kept else None
        bridged = (
            prev is not None
            and not event.get("color_split")
            and set(prev["voted"]) == set(event["voted"])
            and prev["meas"] is not None
            and event["meas"] is not None
            and abs(prev["meas"]["width"] - event["meas"]["width"]) <= 3
            and (between or gap_covered(video, event["zone"], prev["clear_ms"], event["appear_ms"]))
            and not gap_has_clear(video, event["zone"], prev["clear_ms"], event["appear_ms"])
        )
        if bridged:
            prev["clear_ms"] = max(prev["clear_ms"], event["clear_ms"])
            prev["merged_runs"] = prev.get("merged_runs", 1) + event.get("merged_runs", 1)
            prev["panel_covered"] = prev.get("panel_covered", 0) + max(len(between), 1)
        else:
            if prev is not None and between:
                prev["panel_skipped"] = prev.get("panel_skipped", 0) + len(between)
            kept.append(event)
        dropped.extend(between)
        between = []
    dropped.extend(between)
    return kept, dropped


def merge_repeated_events(events, video: Path):
    """同一手被重复检出时合并：同区、同点数集合、首尾相接、扇宽一致。"""
    measured = []
    for event in events:
        frame = ds.frame_at(video, event["crop_ms"])
        if frame is not None:
            crop = ds.crop_zone(frame, event["zone"])
            event["crop"] = crop
            event["meas"] = measure(crop, event["zone"])
            event["panel"] = panel_covered(crop)
        else:
            event["crop"] = None
            event["meas"] = None
            event["panel"] = False
        measured.append(event)

    merged = []
    dropped_all: list[dict] = []
    for zone in ds.ZONES:
        zone_events = [e for e in measured if e["zone"] == zone]
        zone_events, dropped = bridge_panels(zone_events, video)
        dropped_all.extend(dropped)
        zone_merged: list[dict] = []
        for event in zone_events:
            prev = zone_merged[-1] if zone_merged else None
            prev_ranks = set(prev["voted"]) if prev else set()
            cur_ranks = set(event["voted"])
            same_play = (
                prev is not None
                and prev_ranks
                and cur_ranks
                and (prev_ranks <= cur_ranks or cur_ranks <= prev_ranks)
                and prev["clear_ms"] == event["appear_ms"]
                and not event.get("color_split")
                and event["stable_ms"] - prev["stable_ms"] <= 6000
                and prev["meas"] is not None
                and event["meas"] is not None
                and abs(prev["meas"]["width"] - event["meas"]["width"]) <= 3
            )
            if not same_play:
                zone_merged.append(event)
                continue
            keep = (
                event
                if sum(event["voted"].values()) >= sum(prev["voted"].values())
                else prev
            )
            keep["appear_ms"] = min(prev["appear_ms"], event["appear_ms"])
            keep["stable_ms"] = min(prev["stable_ms"], event["stable_ms"])
            keep["clear_ms"] = max(prev["clear_ms"], event["clear_ms"])
            keep["merged_runs"] = prev.get("merged_runs", 1) + 1
            keep["tag"] = prev["tag"]
            for key in ("panel_covered", "panel_skipped"):
                if prev.get(key) or event.get(key):
                    keep[key] = prev.get(key, 0) + event.get(key, 0)
            zone_merged[-1] = keep
        merged.extend(zone_merged)
    merged.sort(key=lambda e: (e["appear_ms"], e["zone"]))
    return merged, dropped_all


PANEL_DROPPED: list[dict] = []


def log_panel_dropped(game_id: str, dropped) -> None:
    for event in dropped:
        PANEL_DROPPED.append(
            {
                "game_id": game_id,
                "zone": event["zone"],
                "appear_ms": event["appear_ms"],
                "stable_ms": event["stable_ms"],
                "clear_ms": event["clear_ms"],
                "model_cards": cards_string(event["voted"]),
            }
        )
    if dropped:
        print(
            f"  {game_id}: 面板盖住、排除 {len(dropped)} 个事件: "
            + ", ".join(f"{e['zone']}@{e['stable_ms']}" for e in dropped)
        )


def write_panel_dropped(games_run: set) -> None:
    path = ds.REVIEW_DIR / "panel_dropped.csv"
    fields = ["game_id", "zone", "appear_ms", "stable_ms", "clear_ms", "model_cards"]
    old = []
    if path.exists():
        with path.open(encoding="utf-8-sig") as handle:
            old = [row for row in csv.DictReader(handle) if row["game_id"] not in games_run]
    ds.REVIEW_DIR.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(old + PANEL_DROPPED)


def mark_stock_over(game: dict, rows) -> None:
    """库存只检查、不改数：绝不修改任何一手的 cards 和 quantity。

    complete=true 的局里某点数合计超库存时，把贡献它的可疑手标 needs_review，notes 写
    `stock-over <RANK> <合计>/<库存>`。真正的核对清单由 stock_report.py 出，check_truth.py 报错。
    """
    total_quantity = sum(row["quantity"] for row in rows)
    if total_quantity > sr.TOTAL_STOCK:
        print(f"  {game['gameId']}: 全局合计 {total_quantity}/{sr.TOTAL_STOCK} 张，超过 198")
    if not game.get("complete"):
        return
    view = [
        {
            "event_seq": str(row["seq"]),
            "zone": row["zone"],
            "stable_ms": str(row["stable_ms"]),
            "cards": cards_string(row["cards_vec"]),
            "notes": "; ".join(row["notes"]),
        }
        for row in rows
    ]
    for finding in sr.find_stock_over(view):
        print(
            f"  {game['gameId']}: {finding['rank']} 合计 {finding['total']}/{finding['stock']} 超库存，"
            f"标记 {len(finding['suspects'])} 手待核对"
        )
        for suspect in finding["suspects"]:
            row = rows[int(suspect["event_seq"]) - 1]
            row["needs_review"] = True
            row["notes"].append(
                f"needs_review; stock-over {finding['rank']} {finding['total']}/{finding['stock']}"
            )


def write_truth(game: dict, events) -> int:
    game_id = game["gameId"]
    video = ds.SOURCE_BASE / game["video"]
    out_path = ds.TRUTH_DIR / f"{game_id}.csv"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    crop_dir = ds.CROPS_DIR / game_id
    crop_dir.mkdir(parents=True, exist_ok=True)
    for stale in crop_dir.glob("*.png"):
        stale.unlink()

    events, dropped = merge_repeated_events(events, video)
    log_panel_dropped(game_id, dropped)

    rows = []
    used_manual = set()
    for event in events:
        crop = event.get("crop")
        voted = dict(event["voted"])
        model_total = sum(voted.values())
        override = None
        if event.get("meas") and event["meas"]["width"] >= 159:
            match = manual_quantity(game_id, event["stable_ms"], used_manual)
            if match is not None:
                used_manual.add(match[0])
                override = match[1]
        quantity, notes, needs_review = resolve_quantity(
            event["zone"], model_total, event.get("meas"), override, set(voted)
        )
        if override is not None and not any("manual=" in note for note in notes):
            notes.append(f"model={model_total} manual={quantity}")
        voted, mixed_note = collapse_mixed_ranks(voted)
        if mixed_note:
            notes.append(mixed_note)
        voted, adjust_note = adjust_to_quantity(voted, quantity)
        if adjust_note:
            notes.append(adjust_note)
        if event.get("color_split"):
            notes.append("color_split")
        if event.get("merged_runs", 1) > 1:
            notes.append(f"merged_runs={event['merged_runs']}")
        if event.get("panel_covered"):
            notes.append(f"panel_covered={event['panel_covered']}")
        if event.get("panel_skipped"):
            notes.append(f"panel_skipped={event['panel_skipped']}")
        if not voted:
            if not any("needs_review" in note for note in notes):
                notes.append("needs_review")
            notes.append("cards-blank")
            needs_review = True
        if game["split"] == "clip":
            notes.insert(0, "clip片段")
        rows.append(
            {
                "seq": len(rows) + 1,
                "zone": event["zone"],
                "appear_ms": event["appear_ms"],
                "stable_ms": event["stable_ms"],
                "clear_ms": event["clear_ms"],
                "quantity": quantity,
                "cards_vec": voted,
                "tag": event["tag"],
                "notes": notes,
                "needs_review": needs_review,
                "filled": quantity > model_total,
                "model_total": model_total,
                "geom": event["meas"]["by_width"] if event.get("meas") else quantity,
            }
        )
        if crop is not None:
            cv2.imwrite(
                str(crop_dir / f"{len(rows):03d}_{event['zone']}.png"), crop
            )

    mark_stock_over(game, rows)

    with out_path.open("w", encoding="utf-8", newline="\n") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "game_id", "event_seq", "zone", "appear_ms", "stable_ms",
                "clear_ms", "quantity", "cards", "tag", "review", "notes",
            ]
        )
        for row in rows:
            writer.writerow(
                [
                    game_id,
                    row["seq"],
                    row["zone"],
                    row["appear_ms"],
                    row["stable_ms"],
                    row["clear_ms"],
                    row["quantity"],
                    cards_string(row["cards_vec"]),
                    row["tag"],
                    "AUTO",
                    "; ".join(row["notes"]),
                ]
            )
    return len(rows)


def manual_quantity(game_id: str, stable_ms: int, used: set):
    """宽扇形人工读数：同一局里取时间最近的条目（合并后停稳时刻可能提前几百毫秒）。"""
    best = None
    for key, quantity in SELF_WIDE_MANUAL.items():
        manual_game, manual_ms = key
        if manual_game != game_id or key in used:
            continue
        delta = abs(manual_ms - stable_ms)
        if delta <= 1500 and (best is None or delta < best[0]):
            best = (delta, quantity, key)
    return (best[2], best[1]) if best else None


def first_play_clamp(stream, game: dict):
    """第一手出现早于 firstPlayMs 时，把边界夹到它出现之前 100ms。"""
    first_play = int(game["firstPlayMs"])
    events = build_events(stream, 0)
    # 只看紧邻边界的这一手；更早的白区事件属于交贡/还贡，不参与夹取
    early = [
        e
        for e in events
        if first_play - 250 <= e["appear_ms"] < first_play
    ]
    if not early:
        return None
    earliest = min(e["appear_ms"] for e in early)
    new_first = max(int(game["startMs"]), earliest - 100)
    new_pre = min(int(game.get("prePlayEndMs") or 0), new_first)
    if new_pre <= 0:
        new_pre = max(int(game["startMs"]), new_first - 1000)
    return new_first, new_pre, earliest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--games", help="逗号分隔的 gameId，缺省全部")
    parser.add_argument(
        "--clamp-first-play",
        action="store_true",
        help="第一手早于 firstPlayMs 时写回 datasets/games.json",
    )
    args = parser.parse_args()

    payload = ds.load_games()
    wanted = set(args.games.split(",")) if args.games else None
    total = 0
    changed = []
    for game in payload["games"]:
        if wanted is not None and game["gameId"] not in wanted:
            continue
        stream = list(load_stream(game["gameId"]))
        if args.clamp_first_play:
            clamp = first_play_clamp(stream, game)
            if clamp is not None:
                new_first, new_pre, earliest = clamp
                game["notes"] = (
                    game.get("notes", "")
                    + f" firstPlayMs {game['firstPlayMs']}->{new_first}"
                    f"（第一手 {earliest}ms 出现，夹到出现之前）；prePlayEndMs={new_pre}。"
                )
                game["firstPlayMs"] = new_first
                game["prePlayEndMs"] = new_pre
                changed.append(game["gameId"])
        events = build_events(stream, int(game["firstPlayMs"]))
        count = write_truth(game, events)
        total += count
        print(f"{game['gameId']}: {count} 手 -> {ds.TRUTH_DIR / (game['gameId'] + '.csv')}")
    write_panel_dropped({g["gameId"] for g in payload["games"] if wanted is None or g["gameId"] in wanted})
    if changed:
        ds.save_games(payload)
        print(f"firstPlayMs 已夹到第一手之前: {', '.join(changed)}")
    print(f"共 {total} 手")


if __name__ == "__main__":
    main()
