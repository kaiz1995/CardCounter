"""把 100ms 检测流压成整局真值草稿 datasets/truth/<gameId>.csv。

规则: 白区出现 -> 连续停稳 -> 多帧投票 -> 清空
- 白区出现: white_ratio >= 6%
- 停稳: 连续 4 帧 still=true（400ms）
- 投票: 停稳窗口内每帧检测按 Rank 计数，取逐 Rank 中位数
- 清空: white_ratio < 3% 连续 5 帧（500ms）
- 同一区没清空就换下一手 -> REPLACE_DIRECT

张数用几何方法独立估算（白色扇形宽度 + 牌角墨迹列簇），模型只提供点数；
两者不一致时以几何张数为准，点数向量按最常出现的 Rank 补齐/裁剪，并在 notes 留痕。
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


def ink_clusters(crop: np.ndarray, box, frac: float = 0.40, threshold: float = 0.12):
    """牌角墨迹列簇数：扇形上半部的暗/高饱和列，按 >=2px 空白分组。"""
    top, bottom, left, right = box
    height = max(2, int((bottom - top + 1) * frac))
    region = crop[top:top + height, left:right + 1].astype(np.int16)
    high = region.max(axis=2)
    low = region.min(axis=2)
    ink = (high < 115) | ((high - low) > 55)
    profile = ink.mean(axis=0)
    clusters = 0
    run = 0
    for value in profile:
        if value >= threshold:
            run += 1
        else:
            if run > 0:
                clusters += 1
                run = 0
    if run > 0:
        clusters += 1
    return clusters


def estimate_quantity(crop: np.ndarray) -> int:
    """几何数张：牌扇宽度 -> 张数；宽扇形（>=100px）改用牌角墨迹簇数。

    标定（102658 人工真值 516-536s）：单张 45-46px，每多一张 +14.3px，
    2 张 60px、3 张 75px、4 张 89px；7-8 张时牌距压缩，宽度法失效，
    改用牌角墨迹列簇（4 张=4 簇，8 张=8 簇）。扇形被区域裁掉时补 1 张。
    """
    box = fan_box(crop)
    if box is None:
        return 0
    top, bottom, left, right = box
    fan_w = right - left + 1
    clipped = left <= 0 or right >= crop.shape[1] - 1
    if fan_w < 100:
        estimate = 1 + max(0, int(round((fan_w - 46) / 14.3)))
        if clipped and fan_w >= 70:
            estimate += 1
    else:
        estimate = ink_clusters(crop, box)
    return max(0, min(int(estimate), 30))


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
            (r for r in candidates if voted[r] < (4 if "JOKER" in r else 16)),
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
            # 连续相同检测签名 / 连续停稳 的窗口
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


def write_truth(game: dict, events) -> int:
    game_id = game["gameId"]
    video = ds.SOURCE_BASE / game["video"]
    out_path = ds.TRUTH_DIR / f"{game_id}.csv"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    crop_dir = ds.CROPS_DIR / game_id
    crop_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for seq, event in enumerate(events, start=1):
        frame = ds.frame_at(video, event["crop_ms"])
        if frame is None:
            quantity = sum(event["voted"].values())
            geom_note = "crop-missing"
        else:
            crop = ds.crop_zone(frame, event["zone"])
            cv2.imwrite(str(crop_dir / f"{seq:03d}_{event['zone']}.png"), crop)
            quantity = estimate_quantity(crop)
            geom_note = f"geom={quantity}"
            model_total = sum(event["voted"].values())
            if quantity * 2 < model_total and model_total >= 4:
                # 扇形被区裁剪切断时几何会严重低估；退回模型票数
                quantity = model_total
                geom_note += f"(clipped->model {model_total})"
            if quantity < MIN_QUANTITY:
                quantity = model_total
                geom_note = "geom=0"
        voted, note = adjust_to_quantity(dict(event["voted"]), quantity)
        if game["split"] == "clip":
            geom_note = ("clip片段; " + geom_note).strip("; ")
        rows.append(
            [
                game_id,
                seq,
                event["zone"],
                event["appear_ms"],
                event["stable_ms"],
                event["clear_ms"],
                quantity,
                cards_string(voted),
                event["tag"],
                "AUTO",
                "; ".join(x for x in (geom_note, note) if x),
            ]
        )
    with out_path.open("w", encoding="utf-8", newline="\n") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "game_id", "event_seq", "zone", "appear_ms", "stable_ms",
                "clear_ms", "quantity", "cards", "tag", "review", "notes",
            ]
        )
        writer.writerows(rows)
    return len(rows)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--games", help="逗号分隔的 gameId，缺省全部")
    args = parser.parse_args()

    payload = ds.load_games()
    wanted = set(args.games.split(",")) if args.games else None
    total = 0
    for game in payload["games"]:
        if wanted is not None and game["gameId"] not in wanted:
            continue
        stream = list(load_stream(game["gameId"]))
        events = build_events(stream, int(game["firstPlayMs"]))
        count = write_truth(game, events)
        total += count
        print(f"{game['gameId']}: {count} 手 -> {ds.TRUTH_DIR / (game['gameId'] + '.csv')}")
    print(f"共 {total} 手")


if __name__ == "__main__":
    main()
