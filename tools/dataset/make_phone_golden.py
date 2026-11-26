"""从录屏挑 20 帧做“手机同口径”一致性守卫的测试资源。

产物（core/src/test/resources/phone_golden/）：
* f01.png ... f20.png：1280x578 原始帧，只保留判定要用的区域（出牌区条带、圆标窗口、7 个牌桌探针点），其余涂黑；
* expected.json：Python（phone_vision.py）在这批帧上算出的牌桌可见、各区三态、圆标、扩展后裁剪框、指纹哈希、静止。

:core 的 PhoneGoldenFramesTest 用 Kotlin 同一套函数算同一批帧，逐项必须零差异。
挑帧只用 dev 局（g102658-3、g075639-9）的 detections_v2 观察挑出各类场景：空桌、有牌、长串扩展、遮挡、
圆标亮、牌桌被挡、静止/运动；不碰 blind 和 tuning。

用法（先跑过 onnx_detect.py）:
    python tools/dataset/make_phone_golden.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402
import phone_vision as pv  # noqa: E402

OUT_DIR = ds.REPO / "core" / "src" / "test" / "resources" / "phone_golden"
DETECTIONS = ds.DATA_ROOT / "detections_v2"
GAMES = ("g102658-3", "g075639-9", "g102658-1", "g143651-4", "g102658-5")
# 长串扩展：g102658-3 里只有右上 KING:7 一手能触发，所以向右（左下、顶家）和向左（右上、右下）各挑一处
# dev 其它局里裁剪框真正被扩展过的时刻（已在录屏里逐帧扫过）。左上在 dev 里没有触发过扩展的帧。
EXPAND_PICKS = (
    ("g102658-1", "LEFT_BOTTOM", 177500),
    ("g102658-1", "TOP", 210400),
    ("g143651-4", "RIGHT_TOP", 636700),
    ("g102658-5", "RIGHT_BOTTOM", 863400),
)
ALLOWED_GAMES = {"dev"}


def load_rows(game_id: str) -> list[dict]:
    path = DETECTIONS / f"{game_id}.jsonl"
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def zone_state(zone: dict) -> str:
    if zone.get("occluded"):
        return pv.OCCLUDED
    return pv.CARD if zone["card_visible"] else pv.EMPTY


def spread(candidates: list[int], count: int, taken: set[int], gap: int = 60) -> list[int]:
    """从候选行号里按分位均匀挑 count 个，彼此相隔至少 gap 行，也避开已挑过的。"""
    chosen: list[int] = []
    if not candidates:
        return chosen
    for k in range(count):
        target = candidates[min(len(candidates) - 1, int((k + 0.5) * len(candidates) / count))]
        order = sorted(candidates, key=lambda i: abs(i - target))
        for index in order:
            if all(abs(index - other) >= gap for other in chosen + sorted(taken)):
                chosen.append(index)
                break
    return chosen


def select() -> list[dict]:
    """返回 20 帧的挑选清单：{id, game, ts, label}；成对的“上一帧”紧挨在后一帧前面并带 prev。"""
    main = load_rows("g102658-3")
    occ = load_rows("g075639-9")
    taken: set[int] = set()
    picks: list[tuple[str, int, str, int | None]] = []  # (game, row, label, prev_row)

    def rows_where(rows, predicate):
        return [i for i, r in enumerate(rows) if i > 0 and predicate(r)]

    empty = rows_where(main, lambda r: r["table_visible"] and r["zones"] and all(
        zone_state(z) == pv.EMPTY and not z["turn"] for z in r["zones"].values()))
    cards = rows_where(main, lambda r: r["table_visible"] and sum(
        zone_state(z) == pv.CARD for z in r["zones"].values()) >= 3)
    long_run = rows_where(main, lambda r: any(len(z.get("dets") or []) >= 7 for z in r["zones"].values()))
    badge = rows_where(main, lambda r: any(z["turn"] for z in r["zones"].values()))
    hidden = rows_where(main, lambda r: not r["table_visible"])
    still_pair = rows_where(main, lambda r: sum(
        z.get("still") is True and zone_state(z) == pv.CARD for z in r["zones"].values()) >= 2)
    moving_pair = rows_where(main, lambda r: any(
        zone_state(z) == pv.CARD and z.get("still") is False for z in r["zones"].values()))
    occluded = rows_where(occ, lambda r: r["table_visible"] and any(
        z.get("occluded") for z in r["zones"].values()))

    def zones_with_turn(index: int) -> set[str]:
        return {name for name, z in main[index]["zones"].items() if z["turn"]}

    for index in spread(empty, 2, taken):
        picks.append(("g102658-3", index, "空桌", None)); taken.add(index)
    for index in spread(cards, 2, taken):
        picks.append(("g102658-3", index, "有牌", None)); taken.add(index)
    for game, zone_name, target in EXPAND_PICKS:
        game_rows = load_rows(game)
        index = min(range(len(game_rows)), key=lambda i: abs(game_rows[i]["ts_ms"] - target))
        picks.append((game, index, f"长串扩展 {zone_name}", None))
    # 每一家各挑一帧圆标亮着的（挂王时圆标上偏的情形也在其中，能碰上多少算多少）。
    for name in ("TOP", "LEFT_TOP", "LEFT_BOTTOM", "RIGHT_TOP", "RIGHT_BOTTOM"):
        rows_of_zone = [i for i in badge if main[i]["zones"].get(name, {}).get("turn")]
        for index in spread(rows_of_zone, 1, taken, gap=20):
            picks.append(("g102658-3", index, f"圆标亮 {name}", None)); taken.add(index)
    for index in spread(hidden, 1, taken, gap=1):
        picks.append(("g102658-3", index, "牌桌被挡", None)); taken.add(index)
    for index in spread(occluded, 2, set(), gap=60):
        picks.append(("g075639-9", index, "遮挡", None));
    for index in spread(still_pair, 1, taken, gap=60):
        picks.append(("g102658-3", index, "静止对", index - 1)); taken.add(index)
    for index in spread(moving_pair, 1, taken, gap=60):
        picks.append(("g102658-3", index, "运动对", index - 1)); taken.add(index)

    rows_by_game = {game_id: load_rows(game_id) for game_id in GAMES}
    plan: list[dict] = []
    for game, index, label, prev in picks:
        rows = rows_by_game[game]
        if prev is not None:
            plan.append({"game": game, "ts": rows[prev]["ts_ms"], "label": label + "·前一帧", "prev": None})
        plan.append({
            "game": game, "ts": rows[index]["ts_ms"], "label": label,
            "prev": rows[prev]["ts_ms"] if prev is not None else None,
            "badge": label.split(" ")[1] if label.startswith("圆标亮 ") else None,
            "expand": label.split(" ")[1] if label.startswith("长串扩展 ") else None,
        })
    for number, item in enumerate(plan, start=1):
        item["id"] = f"f{number:02d}"
    return plan


def grab(plan: list[dict]) -> dict[tuple[str, int], np.ndarray]:
    """顺序解码录屏，和 onnx_detect 同一套时间戳取法，取出计划里要的帧。"""
    games = {g["gameId"]: g for g in ds.load_games()["games"]}
    found: dict[tuple[str, int], np.ndarray] = {}
    for game_id in GAMES:
        game = games[game_id]
        if game["split"] not in ALLOWED_GAMES:
            raise SystemExit(f"{game_id} 不是 dev 局，拒绝")
        wanted = {item["ts"] for item in plan if item["game"] == game_id}
        cap = cv2.VideoCapture(str(ds.SOURCE_BASE / game["video"]))
        fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        index = 0
        last = max(wanted)
        while wanted - {ts for (g, ts) in found if g == game_id}:
            if not cap.grab():
                break
            t = cap.get(cv2.CAP_PROP_POS_MSEC)
            if index == 0 or t <= 0:
                t = index * 1000.0 / fps
            index += 1
            ts = int(round(t))
            if ts in wanted and (game_id, ts) not in found:
                ok, frame = cap.retrieve()
                if ok:
                    found[(game_id, ts)] = frame
            if t > last + 1000:
                break
        cap.release()
    missing = [item for item in plan if (item["game"], item["ts"]) not in found]
    if missing:
        raise SystemExit(f"没取到帧: {[(m['game'], m['ts']) for m in missing]}")
    return found


def keep_rects() -> list[tuple[int, int, int, int]]:
    """判定会读到的画布区域 (x0, y0, x1, y1)，右下开区间。"""
    rects: list[tuple[int, int, int, int]] = []
    for spec in pv.SPECS:
        if not spec.outside:
            continue
        left, top, width, height = pv.yolo_crop(spec)
        limit = pv.round_half_up(pv.CANVAS_W * spec.expand_limit_ratio)
        x0 = max(0, min(left, limit))
        x1 = min(pv.CANVAS_W, max(left + width, limit))
        rects.append((x0, top, x1, top + height))
        bx, by = spec.badge_x, spec.badge_y
        rects.append((
            bx - pv.BADGE_HALF_WIDTH, by + min(pv.BADGE_Y_OFFSETS) - pv.BADGE_HALF_HEIGHT,
            bx + pv.BADGE_HALF_WIDTH + 1, by + max(pv.BADGE_Y_OFFSETS) + pv.BADGE_HALF_HEIGHT + 1,
        ))
    for x, y in pv.TABLE_GREEN_PROBES:
        rects.append((x, y, x + 1, y + 1))
    return rects


def mask(frame: np.ndarray) -> np.ndarray:
    out = np.zeros_like(frame)
    for x0, y0, x1, y1 in keep_rects():
        out[y0:y1, x0:x1] = frame[y0:y1, x0:x1]
    return out


def main() -> None:
    plan = select()
    if len(plan) != 20:
        raise SystemExit(f"守卫要 20 帧，实际挑出 {len(plan)} 帧")
    frames = grab(plan)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for old in OUT_DIR.glob("f*.png"):
        old.unlink()
    expected = {"canvas": [pv.CANVAS_W, pv.CANVAS_H], "frames": []}
    by_ts: dict[tuple[str, int], tuple[dict, dict]] = {}
    for item in plan:
        original = frames[(item["game"], item["ts"])]
        if item.get("badge"):
            # 录屏里圆标相对手机坐标有系统性偏移：整帧反向平移回手机坐标，
            # Python 与 Kotlin 在同一张帧上用手机取色窗比对，手机代码不用动。
            dx, dy = pv.BADGE_REC_SHIFT[item["badge"]]
            original = cv2.warpAffine(
                original, np.float32([[1, 0, -dx], [0, 1, -dy]]),
                (original.shape[1], original.shape[0]), borderMode=cv2.BORDER_REPLICATE,
            )
        if original.shape[:2] != (pv.CANVAS_H, pv.CANVAS_W):
            raise SystemExit(f"{item['id']} 分辨率 {original.shape[:2]} 不是 1280x578")
        masked = mask(original)
        # 涂黑不能改变任何判定：原始帧和涂黑帧的描述必须完全相同。
        previous_fp = by_ts[(item["game"], item["prev"])][1] if item["prev"] is not None else None
        full, _ = pv.describe_frame(pv.Screen(original), previous_fp)
        description, fingerprints = pv.describe_frame(pv.Screen(masked), previous_fp)
        if full != description:
            raise SystemExit(f"{item['id']} 涂黑后判定变了，保留区域不够")
        if item.get("badge") and not description["zones"][item["badge"]]["turn"]:
            raise SystemExit(f"{item['id']} 平移后 {item['badge']} 的圆标仍没认出来")
        if item.get("expand"):
            base_crop = list(pv.yolo_crop(pv.SPEC_BY_NAME[item["expand"]]))
            if description["zones"][item["expand"]]["crop"] == base_crop:
                raise SystemExit(f"{item['id']} {item['expand']} 的裁剪框没有被扩展，挑帧时刻不对")
        by_ts[(item["game"], item["ts"])] = (description, fingerprints)
        cv2.imwrite(str(OUT_DIR / f"{item['id']}.png"), masked, [cv2.IMWRITE_PNG_COMPRESSION, 9])
        entry = {"id": item["id"], "source": f"{item['game']}@{item['ts']}ms", "label": item["label"]}
        if item["prev"] is not None:
            entry["prev"] = next(p["id"] for p in plan if p["game"] == item["game"] and p["ts"] == item["prev"])
        entry.update(description)
        expected["frames"].append(entry)
    (OUT_DIR / "expected.json").write_text(
        json.dumps(expected, ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
    )
    total = sum(p.stat().st_size for p in OUT_DIR.glob("f*.png"))
    states: dict[str, int] = {}
    for frame in expected["frames"]:
        for zone in frame["zones"].values():
            states[zone["state"]] = states.get(zone["state"], 0) + 1
    expanded = sum(
        1 for frame in expected["frames"] for name, zone in frame["zones"].items()
        if zone["crop"] != list(pv.yolo_crop(pv.SPEC_BY_NAME[name]))
    )
    print(f"{len(plan)} 帧，PNG 合计 {total / 1024:.0f} KB -> {OUT_DIR}")
    print(f"三态计数 {states}；圆标亮 {sum(z['turn'] for f in expected['frames'] for z in f['zones'].values())} 区次；"
          f"扩展过的裁剪 {expanded} 区次；牌桌不可见 {sum(not f['table_visible'] for f in expected['frames'])} 帧")
    for item in plan:
        print(item["id"], item["label"], f"{item['game']}@{item['ts']}")


if __name__ == "__main__":
    main()
