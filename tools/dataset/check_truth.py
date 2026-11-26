"""真值硬门槛 + 人工真值抽查。

两种用法：

1) 整库硬门槛（complete=true 的局）：
    python tools/dataset/check_truth.py --all
   - 任何点数累计超过库存（Rank.kt 的 4 副牌库存）、或全局合计超过 198 张 -> 报错，
     并列出贡献该点数的可疑手（按可疑程度排序）。库存是硬规则：交贡/还贡/买牌只在 198 张
     内部转移；不检查“每家不超过 33 张”（交贡/买牌会让各家实际持牌数偏离 33）
   - 一手里出现两种普通点数（大王/小王/2 之外）-> 报错
   - 第一手 appear_ms 早于该局 firstPlayMs -> 报错
   - cards 合计与 quantity 不一致、stable<appear、clear<stable -> 报错
   有任何错误以非 0 退出。

2) 人工真值抽查（原有用法）：
    python tools/dataset/check_truth.py --game g102658-3 \
        --truth experiments/results/truth_102658_516_536.csv \
        --start 516000 --end 536500
人工真值列: event_id,time_s,zone,seat,cards,confidence（cards 里 2x5 = 两张 5）
"""
from __future__ import annotations

import argparse
import csv
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402

RANK_ALIAS = {
    "2": "TWO", "A": "ACE", "K": "KING", "Q": "QUEEN", "J": "JACK", "10": "TEN",
    "9": "NINE", "8": "EIGHT", "7": "SEVEN", "6": "SIX", "5": "FIVE", "4": "FOUR",
    "3": "THREE", "BIG_JOKER": "BIG_JOKER", "SMALL_JOKER": "SMALL_JOKER",
}

import stockrules as sr  # noqa: E402
from stockrules import NORMAL_RANKS, STOCK, TOTAL_STOCK  # noqa: E402  库存见 stockrules.py


def parse_cards(text: str):
    counts = {}
    for part in text.split("+"):
        part = part.strip()
        if not part:
            continue
        if "x" in part:
            # 人工真值写法是 张数xRank：2x5 = 两张 5，6xK = 六张 K
            qty, rank = part.split("x", 1)
            counts[RANK_ALIAS[rank]] = counts.get(RANK_ALIAS[rank], 0) + int(qty)
        else:
            counts[RANK_ALIAS[part]] = counts.get(RANK_ALIAS[part], 0) + 1
    return counts


def parse_vector(text: str):
    counts = {}
    for part in text.split(";"):
        if not part.strip():
            continue
        rank, qty = part.split(":")
        counts[rank] = counts.get(rank, 0) + int(qty)
    return counts


def check_game(game: dict) -> list[str]:
    """单局硬门槛；返回错误列表（空表示通过）。"""
    game_id = game["gameId"]
    csv_path = ds.TRUTH_DIR / f"{game_id}.csv"
    errors = []
    if not csv_path.exists():
        return [f"{game_id}: 缺真值文件 {csv_path}"]
    with csv_path.open(encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        return [f"{game_id}: 真值文件没有出牌行"]

    totals = {rank: 0 for rank in STOCK}
    total_quantity = 0
    first_play = int(game["firstPlayMs"])
    earliest = None
    for row in rows:
        seq = row["event_seq"]
        where = f"{game_id} #{seq} {row['zone']}"
        quantity = int(row["quantity"])
        total_quantity += quantity
        appear = int(row["appear_ms"])
        stable = int(row["stable_ms"])
        clear = int(row["clear_ms"])
        if appear > stable:
            errors.append(f"{where}: appear_ms {appear} 晚于 stable_ms {stable}")
        if clear < stable:
            errors.append(f"{where}: clear_ms {clear} 早于 stable_ms {stable}")
        cards = parse_vector(row["cards"])
        if cards and sum(cards.values()) != quantity:
            errors.append(
                f"{where}: cards 合计 {sum(cards.values())} 与 quantity {quantity} 不一致"
            )
        normals = [rank for rank in cards if rank in NORMAL_RANKS and cards[rank] > 0]
        if len(normals) > 1:
            errors.append(
                f"{where}: 一手出现两种普通点数 "
                + "+".join(f"{rank}:{cards[rank]}" for rank in normals)
            )
        for rank, count in cards.items():
            if rank in totals:
                totals[rank] += count
        earliest = appear if earliest is None else min(earliest, appear)

    for finding in sr.find_stock_over(rows):
        errors.append(
            f"{game_id}: {finding['rank']} 合计 {finding['total']}/{finding['stock']} 超库存；"
            f"可疑手 {len(finding['suspects'])} 手："
            + " | ".join(sr.describe_hand(row) for row in finding["suspects"][:8])
            + (" | …" if len(finding["suspects"]) > 8 else "")
            + "（完整清单见 D:\\CardCounterData\\review\\stock_over.csv）"
        )
    if total_quantity > TOTAL_STOCK:
        errors.append(
            f"{game_id}: 全局合计 {total_quantity}/{TOTAL_STOCK} 张"
        )
    if earliest is not None and earliest < first_play:
        errors.append(
            f"{game_id}: 第一手 appear_ms {earliest} 早于 firstPlayMs {first_play}"
        )
    return errors


def check_all() -> int:
    payload = ds.load_games()
    complete = [game for game in payload["games"] if game.get("complete")]
    errors = []
    print(f"检查 {len(complete)} 个 complete=true 的局")
    for game in complete:
        game_errors = check_game(game)
        errors.extend(game_errors)
        print(
            f"  {game['gameId']}: {'OK' if not game_errors else 'FAIL'}"
            + (f"（{len(game_errors)} 项）" if game_errors else "")
        )
    if errors:
        print(f"\n{len(errors)} 项错误：")
        for error in errors:
            print(f"  - {error}")
        return 1
    print("\n全部通过：库存、全局张数、单一点数、firstPlayMs 边界均无问题")
    return 0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true", help="整库硬门槛检查")
    parser.add_argument("--game")
    parser.add_argument("--truth")
    parser.add_argument("--start", type=int)
    parser.add_argument("--end", type=int)
    parser.add_argument("--window", type=int, default=1500)
    args = parser.parse_args()

    if args.all:
        raise SystemExit(check_all())
    missing = [
        name
        for name, value in (
            ("--game", args.game),
            ("--truth", args.truth),
            ("--start", args.start),
            ("--end", args.end),
        )
        if value is None
    ]
    if missing:
        parser.error("对照用法需要 " + " ".join(missing) + "，整库检查用 --all")

    truth = []
    with open(args.truth, encoding="utf-8-sig") as handle:
        for row in csv.DictReader(handle):
            t = int(round(float(row["time_s"]) * 1000))
            if args.start <= t <= args.end:
                truth.append(
                    {
                        "t": t,
                        "zone": row["zone"],
                        "cards": parse_cards(row["cards"]),
                        "raw": row["cards"],
                    }
                )

    pred = []
    with open(ds.TRUTH_DIR / f"{args.game}.csv", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if args.start <= int(row["stable_ms"]) <= args.end + args.window:
                pred.append(
                    {
                        "t": int(row["stable_ms"]),
                        "zone": row["zone"],
                        "quantity": int(row["quantity"]),
                        "cards": parse_vector(row["cards"]),
                        "seq": row["event_seq"],
                    }
                )

    used = set()
    matched = 0
    quantity_ok = 0
    cards_ok = 0
    print(f"{'truth':>8} {'zone':13s} {'cards':22s} -> {'pred':>8} {'q':>3} {'cards':28s} ok")
    for item in truth:
        best = None
        for i, p in enumerate(pred):
            if i in used or p["zone"] != item["zone"]:
                continue
            delta = abs(p["t"] - item["t"])
            if delta <= args.window and (best is None or delta < best[0]):
                best = (delta, i)
        if best is None:
            print(f"{item['t']:>8} {item['zone']:13s} {item['raw']:22s} -> MISS")
            continue
        i = best[1]
        used.add(i)
        p = pred[i]
        matched += 1
        q_ok = p["quantity"] == sum(item["cards"].values())
        c_ok = p["cards"] == item["cards"]
        quantity_ok += q_ok
        cards_ok += c_ok
        vector = ";".join(f"{r}:{p['cards'][r]}" for r in ds.RANK_ORDER if p["cards"].get(r))
        print(
            f"{item['t']:>8} {item['zone']:13s} {item['raw']:22s} -> {p['t']:>8} "
            f"{p['quantity']:>3} {vector:28s} {'Q' if q_ok else '-'}{'C' if c_ok else '-'}"
        )

    print(f"\n人工真值 {len(truth)} 手，匹配 {matched} 手")
    if matched:
        print(f"张数正确率: {quantity_ok}/{matched} = {quantity_ok / matched:.1%}")
        print(f"点数完全正确率: {cards_ok}/{matched} = {cards_ok / matched:.1%}")


if __name__ == "__main__":
    main()
