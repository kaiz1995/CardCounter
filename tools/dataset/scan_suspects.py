"""全局扫描"一手被拆成多段 / 把非牌画面当成出牌"的特征。

同一区里：
- blank_alt   前后紧挨（间隔 0），其中一手 cards 留空、另一手有点数
- dup_cards   前后紧挨，cards 完全相同
- abab        X、Y、X 三手连续紧挨（Y 可以是留空），像一手被另一个画面打断
- same_notes  局内同一区有 2 手以上 notes 完全相同，且 notes 里带 needs_review/trim 等
              非单纯几何记录（单纯 `geom=1(w=46)` 对所有单张都一样，不算）

注意：同一区前后紧挨本身是正常的（够级的 REPLACE_DIRECT 就是新一手直接盖住上一手），
这里只列出可疑组合，要看画面才能下结论。

用法:
    python tools/dataset/scan_suspects.py [--truth-dir DIR] [--out CSV]
"""
from __future__ import annotations

import argparse
import csv
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402

PLAIN_NOTE = re.compile(r"^(clip片段; )?geom=\d+\(w=\d+\)$")


def load_rows(path: Path):
    with path.open(encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def scan_game(game_id: str, rows: list[dict]):
    hits = []
    by_zone: dict[str, list[dict]] = {}
    for row in rows:
        by_zone.setdefault(row["zone"], []).append(row)
    for zone, items in by_zone.items():
        items.sort(key=lambda r: int(r["appear_ms"]))
        for i in range(len(items) - 1):
            a, b = items[i], items[i + 1]
            if int(a["clear_ms"]) != int(b["appear_ms"]):
                continue
            a_blank = not a["cards"].strip()
            b_blank = not b["cards"].strip()
            if a_blank != b_blank:
                hits.append(("blank_alt", zone, [a, b]))
            elif not a_blank and a["cards"] == b["cards"]:
                hits.append(("dup_cards", zone, [a, b]))
            elif a_blank and b_blank:
                hits.append(("blank_alt", zone, [a, b]))
        for i in range(len(items) - 2):
            a, b, c = items[i], items[i + 1], items[i + 2]
            if (
                int(a["clear_ms"]) == int(b["appear_ms"])
                and int(b["clear_ms"]) == int(c["appear_ms"])
                and a["cards"] == c["cards"]
                and a["cards"].strip()
                and a["cards"] != b["cards"]
            ):
                hits.append(("abab", zone, [a, b, c]))
        groups: dict[str, list[dict]] = {}
        for row in items:
            note = row["notes"]
            if PLAIN_NOTE.match(note):
                continue
            groups.setdefault(note, []).append(row)
        for note, group in groups.items():
            if len(group) >= 2:
                hits.append(("same_notes", zone, group))
    return hits


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--truth-dir", default=str(ds.TRUTH_DIR))
    parser.add_argument("--out", default="")
    args = parser.parse_args()
    truth_dir = Path(args.truth_dir)
    payload = ds.load_games()
    all_hits = []
    for game in payload["games"]:
        path = truth_dir / f"{game['gameId']}.csv"
        if not path.exists():
            continue
        for kind, zone, rows in scan_game(game["gameId"], load_rows(path)):
            all_hits.append((game["gameId"], kind, zone, rows))
    counts: dict[str, int] = {}
    for game_id, kind, zone, rows in all_hits:
        counts[kind] = counts.get(kind, 0) + 1
        seqs = ",".join(r["event_seq"] for r in rows)
        cards = " | ".join((r["cards"] or "-") for r in rows)
        span = f"{rows[0]['appear_ms']}~{rows[-1]['clear_ms']}"
        print(f"{game_id} {kind:10s} {zone:12s} seq={seqs} t={span} cards={cards}")
    print("\n汇总:", counts or "无命中", f"（共 {len(all_hits)} 处）")
    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        with out.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.writer(handle)
            writer.writerow(["game_id", "kind", "zone", "seqs", "appear_ms", "clear_ms", "cards"])
            for game_id, kind, zone, rows in all_hits:
                writer.writerow(
                    [
                        game_id, kind, zone,
                        ",".join(r["event_seq"] for r in rows),
                        rows[0]["appear_ms"], rows[-1]["clear_ms"],
                        " | ".join((r["cards"] or "-") for r in rows),
                    ]
                )


if __name__ == "__main__":
    main()
