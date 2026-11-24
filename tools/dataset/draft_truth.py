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
- 全局限量：4 副牌 198 张（Rank.kt）。同一局按时间累计，任何点数超过库存就把这一手超出
  的部分裁掉并标 needs_review。
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

# 4 副牌共 198 张，库存见 core/src/main/kotlin/com/example/cardcounter/domain/Rank.kt
STOCK = {
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
NORMAL_RANKS = tuple(
    rank for rank in ds.RANK_ORDER if rank not in ("BIG_JOKER", "SMALL_JOKER", "TWO")
)

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


def resolve_quantity(zone: str, model_total: int, meas, override):
    """按区的几何结果和模型票数定张数；返回 (张数, [notes], needs_review)。"""
    notes: list[str] = []
    if meas is None:
        return max(MIN_QUANTITY, model_total), ["crop-missing"], True

    width = meas["width"]
    geom = meas["by_width"]
    clusters = meas["clusters"]

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
    total = sum(voted.values())
    if total == quantity:
        return voted, ""
    note = f"model={total} geom={quantity}"
    if quantity <= 0:
        return {}, note
    if not voted:
        return {}, note
    if total < quantity:
        candidates = sorted(
            voted, key=lambda r: (-voted[r], ds.RANK_INDEX[r])
        )
        top = next(
            (r for r in candidates if voted[r] < STOCK.get(r, 16)),
            candidates[0],
        )
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
            merged = []
            for sig, samples in runs:
                voted = vote_counts(samples, zone)
                if not voted:
                    continue
                if merged and merged[-1]["voted"] == voted:
                    merged[-1]["samples"].extend(samples)
                    merged[-1]["end"] = samples[-1]["ts_ms"]
                else:
                    merged.append(
                        {
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


def merge_repeated_events(events, video: Path):
    """同一手被重复检出时合并：同区、同点数集合、首尾相接、扇宽一致。"""
    measured = []
    for event in events:
        frame = ds.frame_at(video, event["crop_ms"])
        if frame is not None:
            crop = ds.crop_zone(frame, event["zone"])
            event["crop"] = crop
            event["meas"] = measure(crop, event["zone"])
        else:
            event["crop"] = None
            event["meas"] = None
        measured.append(event)

    merged = []
    for zone in ds.ZONES:
        zone_events = [e for e in measured if e["zone"] == zone]
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
            zone_merged[-1] = keep
        merged.extend(zone_merged)
    merged.sort(key=lambda e: (e["appear_ms"], e["zone"]))
    return merged


def apply_stock(rows) -> None:
    """库存硬约束：某点数累计超库存时，从"最不可信"的那一手扣掉超出的张数。

    可信度排序：已标 needs_review 的优先，其次模型票数与几何差得最多的，再其次张数
    最大的。扣张时同步扣 quantity，保证 cards 合计与 quantity 一致。
    """
    def totals() -> dict:
        used = {rank: 0 for rank in STOCK}
        for row in rows:
            for rank, count in row["cards_vec"].items():
                used[rank] += count
        return used

    for _ in range(len(rows) * 2):
        used = totals()
        over = [rank for rank, count in used.items() if count > STOCK[rank]]
        if not over:
            break
        rank = over[0]
        excess = used[rank] - STOCK[rank]
        victims = [row for row in rows if row["cards_vec"].get(rank, 0) > 0]
        victims.sort(
            key=lambda row: (
                0 if row["needs_review"] else 1,
                -abs(row.get("model_total", 0) - row.get("geom", 0)),
                -row["cards_vec"].get(rank, 0),
                row["stable_ms"],
            )
        )
        victim = victims[0]
        removed = min(excess, victim["cards_vec"][rank])
        victim["cards_vec"][rank] -= removed
        if victim["cards_vec"][rank] == 0:
            del victim["cards_vec"][rank]
        victim["quantity"] -= removed
        victim["needs_review"] = True
        victim["notes"].append(
            f"needs_review; stock {rank} {used[rank]}/{STOCK[rank]}，本手 -{removed}"
        )
        if victim["quantity"] <= 0:
            victim["quantity"] = MIN_QUANTITY
            victim["cards_vec"] = {}
            victim["notes"].append("stock-exhausted; cards blank")

    total_quantity = sum(row["quantity"] for row in rows)
    if total_quantity > sum(STOCK.values()):
        raise SystemExit(f"全局张数 {total_quantity} 超过 198，需要人工复核")


def write_truth(game: dict, events) -> int:
    game_id = game["gameId"]
    video = ds.SOURCE_BASE / game["video"]
    out_path = ds.TRUTH_DIR / f"{game_id}.csv"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    crop_dir = ds.CROPS_DIR / game_id
    crop_dir.mkdir(parents=True, exist_ok=True)
    for stale in crop_dir.glob("*.png"):
        stale.unlink()

    events = merge_repeated_events(events, video)

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
            event["zone"], model_total, event.get("meas"), override
        )
        if override is not None and not any("manual=" in note for note in notes):
            notes.append(f"model={model_total} manual={quantity}")
        voted, mixed_note = collapse_mixed_ranks(voted)
        if mixed_note:
            notes.append(mixed_note)
        voted, adjust_note = adjust_to_quantity(voted, quantity)
        if adjust_note:
            notes.append(adjust_note)
        if event.get("merged_runs", 1) > 1:
            notes.append(f"merged_runs={event['merged_runs']}")
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

    apply_stock(rows)

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
    if changed:
        ds.save_games(payload)
        print(f"firstPlayMs 已夹到第一手之前: {', '.join(changed)}")
    print(f"共 {total} 手")


if __name__ == "__main__":
    main()
