"""把 source/ 长录屏切成一局一局，写入 datasets/games.json。

信号（全部来自画面本身，不依赖 app 内部状态）：
- 发牌：自己手牌上方白带 450..475 行从空(<5%)跳到有牌(>=10%)并连续保持。
- 结算：中央大暗板 + 得分表蓝条（烧牌动画只有暗+红，不会命中）。
- 第一手正式出牌：六个出牌区"白区出现"事件的第一个密集簇（>=4 次、>=3 个区、8 秒内）。
  前置交贡/还贡/买牌也用同样的牌区显示，单帧无法与正式出牌区分，因此 firstPlayMs 是
  启发式边界；prePlayEndMs 取 firstPlayMs 前 1 秒，作为前置阶段结束的保守标记。

用法:
    python tools/dataset/cut_games.py [--only 102658] [--no-thumbs]
"""
from __future__ import annotations

import argparse
from datetime import date
from pathlib import Path
import sys

import cv2

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402

SAMPLE_MS = 500
DEAL_LOW = 0.05
DEAL_HIGH = 0.55
DEAL_LOW = 0.25
DEAL_LOW_SAMPLES = 2
BURST_WINDOW_MS = 8000
BURST_MIN_EVENTS = 4
BURST_MIN_ZONES = 3
EPISODE_MIN_MS = 300


def scan_video(path: Path, step_ms: int = SAMPLE_MS):
    """顺序解码采样；返回 ([(t_ms, hand, settle, {zone: white})], duration_ms)。

    Windows 上对长录屏反复 CAP_PROP_POS_MSEC seek 会落在同一个关键帧（实测 102658
    前 10 秒三次 seek 返回同一帧），因此这里逐帧 grab，只在目标时间点 retrieve。
    """
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise SystemExit(f"打不开视频: {path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    frame_count = cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0
    duration_ms = int(round(frame_count / fps * 1000)) if frame_count else 0
    samples = []
    index = 0
    next_t = 0
    while True:
        if not cap.grab():
            break
        # 容器时间戳（VFR 录屏用 index/fps 会漂移）
        t = cap.get(cv2.CAP_PROP_POS_MSEC)
        if index == 0 or t <= 0:
            t = index * 1000.0 / fps
        if t >= next_t:
            ok, frame = cap.retrieve()
            if not ok:
                break
            zones = {zone: ds.white_ratio(frame, zone) for zone in ds.ZONES}
            samples.append(
                {
                    "t": int(round(t)),
                    "hand": ds.hand_ratio(frame),
                    "settle": ds.settlement_visible(frame),
                    "zones": zones,
                    "xuan": ds.xuan_buttons_visible(frame),
                    "lobby": ds.lobby_visible(frame),
                    "dialog": ds.pregame_dialog_visible(frame),
                }
            )
            next_t += step_ms
            while next_t <= t:
                next_t += step_ms
        index += 1
    cap.release()
    return samples, duration_ms


def find_segments(samples, duration_ms: int):
    """按发牌/结算/大厅信号切出整局区间。

    状态机只认三件事：
    - 一手完整牌（手牌白带 >=50%）在空窗(>=1s)后出现 -> 开新局；
    - 结算画面 -> 关局（complete 取决于开头是否截断）；
    - 手牌清空期间出现大厅 -> 用户退出牌桌，按未完成关局；
    - 没抓到结算就再次出现整手牌 -> 上一局按未完成关局，同时开新局。
    """
    segments = []
    open_start = None
    low = 0
    started = False
    truncated = False
    lobby_run = 0
    just_closed_dialog = False
    first_nonidle_hand = None
    last_high_t = None

    def close(end_ms: int, complete: bool) -> None:
        nonlocal open_start, truncated, low, lobby_run, last_high_t
        segments.append(
            {
                "startMs": open_start,
                "endMs": int(min(max(open_start, end_ms), duration_ms)),
                "complete": complete and not truncated,
                "truncatedStart": truncated,
            }
        )
        open_start = None
        truncated = False
        low = 0
        lobby_run = 0
        last_high_t = None

    for sample in samples:
        t = sample["t"]
        hand = sample["hand"]
        idle = sample["lobby"] or sample["dialog"]
        if first_nonidle_hand is None and not idle:
            first_nonidle_hand = hand

        if open_start is None:
            if hand < DEAL_LOW:
                low += 1
            if idle:
                just_closed_dialog = bool(sample["dialog"])
                continue
            starts_mid_game = (
                not started
                and first_nonidle_hand is not None
                and first_nonidle_hand == hand
                and hand >= DEAL_HIGH
            )
            if hand >= DEAL_HIGH and (low >= DEAL_LOW_SAMPLES or just_closed_dialog or starts_mid_game):
                open_start = 0 if starts_mid_game else max(0, t - SAMPLE_MS)
                started = True
                truncated = starts_mid_game
                low = 0
                just_closed_dialog = False
                last_high_t = t
            else:
                just_closed_dialog = False
            continue

        if sample["settle"]:
            close(t, True)
            continue
        if sample["lobby"] and hand < DEAL_LOW:
            lobby_run += 1
            if lobby_run >= 2:
                close(last_high_t or (t - SAMPLE_MS), False)
            continue
        if hand < DEAL_LOW:
            low += 1
        else:
            if hand >= DEAL_HIGH and low >= 4:
                # 上一局没抓到结算就发下一局
                close(t - SAMPLE_MS, False)
                open_start = t - SAMPLE_MS
                low = 0
                truncated = False
                last_high_t = t
                continue
            low = 0
            last_high_t = t
        if sample["dialog"]:
            just_closed_dialog = False

    if open_start is not None:
        close(duration_ms, False)
    return segments


def zone_episodes(samples, start_ms: int, end_ms: int):
    """各出牌区白区出现区间 [(zone, start, end)]。"""
    episodes = []
    active = {}
    for sample in samples:
        t = sample["t"]
        zones = sample["zones"]
        if t < start_ms or t > end_ms:
            continue
        for zone in ds.ZONES:
            on = zones[zone] >= ds.WHITE_PRESENT_RATIO
            if on and zone not in active:
                active[zone] = t
            elif not on and zone in active:
                begin = active.pop(zone)
                if t - begin >= EPISODE_MIN_MS:
                    episodes.append((zone, begin, t))
    for zone, begin in active.items():
        if end_ms - begin >= EPISODE_MIN_MS:
            episodes.append((zone, begin, end_ms))
    episodes.sort(key=lambda item: item[1])
    return episodes


def first_play_ms(episodes):
    """第一个密集出牌簇的第一帧；找不到密集簇则退回第一个白区事件。"""
    if not episodes:
        return None
    for _zone, begin, _end in episodes:
        window = [e for e in episodes if begin <= e[1] <= begin + BURST_WINDOW_MS]
        if len(window) >= BURST_MIN_EVENTS and len({e[0] for e in window}) >= BURST_MIN_ZONES:
            return begin
    return episodes[0][1]


def thumb(video: Path, t_ms: int, out_path: Path) -> None:
    frame = ds.frame_at(video, t_ms)
    if frame is None:
        return
    small = cv2.resize(frame, (640, 289), interpolation=cv2.INTER_AREA)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(out_path), small)


def shoot_thumbs(video: Path, game: dict) -> None:
    for field in ("startMs", "prePlayEndMs", "firstPlayMs", "endMs"):
        value = game.get(field)
        if value is None:
            continue
        thumb(video, int(value), ds.REVIEW_DIR / f"{game['gameId']}_{field[:-2]}.png")


def build_long(group: str, name: str, split: str, thumbs: bool):
    path = ds.video_path(group, name)
    samples, duration_ms = scan_video(path)
    segments = find_segments(samples, duration_ms)
    games = []
    sha = None
    for i, seg in enumerate(segments, start=1):
        episodes = zone_episodes(samples, seg["startMs"], seg["endMs"])
        first_play = first_play_ms(episodes)
        xuan_times = [
            s["t"] for s in samples if s["xuan"] and seg["startMs"] <= s["t"] <= seg["endMs"]
        ]
        if first_play is None:
            first_play = seg["startMs"]
            pre_play_end = seg["startMs"]
        else:
            pre_play_end = max(seg["startMs"] + 2000, first_play - 1000)
        if xuan_times:
            # 宣点阶段在交贡/还贡之后、正式出牌之前，用它的结束时刻修正前置边界
            pre_play_end = max(pre_play_end, xuan_times[-1] + 500)
            after = [e for e in episodes if e[1] >= pre_play_end]
            first_play = after[0][1] if after else pre_play_end + 300
        if sha is None:
            sha = ds.sha256_file(path)
        game = {
            "gameId": ds.game_id(name, i),
            "video": f"{group}/{name}",
            "videoSha256": sha,
            "split": split,
            "startMs": seg["startMs"],
            "endMs": seg["endMs"],
            "prePlayEndMs": int(pre_play_end),
            "firstPlayMs": int(first_play),
            "complete": seg["complete"],
            "selfHand": "",
            "selfHandCount": None,
            "review": "AUTO",
            "notes": (
                f"自动切分；画布 {ds.CANON_W}x{ds.CANON_H}。"
                + (
                    "firstPlay 取宣点结束后的第一个白区事件；"
                    "prePlayEnd=宣点结束+0.5s。"
                    if xuan_times
                    else "firstPlay 为白区密集簇启发式，prePlayEnd=firstPlay-1000ms。"
                )
                + ("" if seg["complete"] else " 录屏只覆盖半局。")
                + (" 开头缺少发牌画面。" if seg["truncatedStart"] else "")
            ),
        }
        games.append(game)
        if thumbs:
            shoot_thumbs(path, game)
        print(
            f"  {game['gameId']} {seg['startMs']/1000:.1f}-{seg['endMs']/1000:.1f}s "
            f"complete={seg['complete']} firstPlay={first_play/1000:.1f}s "
            f"episodes={len(episodes)}"
        )
    return games


def build_clip(group: str, name: str):
    path = ds.video_path(group, name)
    duration = ds.video_duration_ms(path)
    return {
        "gameId": ds.game_id(name, 1),
        "video": f"{group}/{name}",
        "videoSha256": ds.sha256_file(path),
        "split": "clip",
        "startMs": 0,
        "endMs": duration,
        "prePlayEndMs": 0,
        "firstPlayMs": 0,
        "complete": False,
        "selfHand": "",
        "selfHandCount": None,
        "review": "AUTO",
        "notes": "开局/片段录制，不覆盖完整牌局；只登记，不做整局切分。",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", help="只处理文件名里包含该字符串的视频")
    parser.add_argument("--no-thumbs", action="store_true")
    args = parser.parse_args()

    ds.REVIEW_DIR.mkdir(parents=True, exist_ok=True)
    old = ds.load_games() if ds.GAMES_JSON.exists() else {"games": []}

    games = []
    for name, group, split in ds.LONG_VIDEOS:
        if args.only and args.only not in name:
            continue
        print(f"[cut] {group}/{name}")
        games.extend(build_long(group, name, split, not args.no_thumbs))
    for name, group in ds.CLIP_VIDEOS:
        if args.only and args.only not in name:
            continue
        game = build_clip(group, name)
        games.append(game)
        print(f"  {game['gameId']} clip {game['endMs']/1000:.1f}s")

    if args.only:
        touched = {g["video"] for g in games}
        merged = [g for g in old.get("games", []) if g["video"] not in touched]
        merged.extend(games)
        order = {
            f"{grp}/{nm}": i
            for i, (nm, grp, _split) in enumerate(ds.LONG_VIDEOS)
        }
        order.update(
            {f"{grp}/{nm}": i + 100 for i, (nm, grp) in enumerate(ds.CLIP_VIDEOS)}
        )
        merged.sort(key=lambda g: (order.get(g["video"], 999), g["startMs"]))
        games = merged

    payload = {
        "schemaVersion": 1,
        "generatedAt": date.today().isoformat(),
        "games": games,
    }
    ds.save_games(payload)
    print(f"写入 {ds.GAMES_JSON} 共 {len(games)} 条")


if __name__ == "__main__":
    main()
