"""用人工真值抽查预标草稿：按 zone + 时间就近匹配，报表数/点数正确率。

用法:
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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--game", required=True)
    parser.add_argument("--truth", required=True)
    parser.add_argument("--start", type=int, required=True)
    parser.add_argument("--end", type=int, required=True)
    parser.add_argument("--window", type=int, default=1500)
    args = parser.parse_args()

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
