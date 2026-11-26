"""超库存待核对清单：D:\\CardCounterData\\review\\stock_over.csv 和 stock_over.html。

只统计 complete=true 的局。某点数合计超过库存时，列出贡献该点数的可疑手（按可疑程度排序）：
局、点数、合计/库存、event_seq、区、时间、当前 cards，并链接到对应局核对页上的那一行。
由 build_review.py 在生成核对页之后调用，也可单独运行：
    python tools/dataset/stock_report.py
"""
from __future__ import annotations

import csv
import html
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402
import stockrules as sr  # noqa: E402

FIELDS = [
    "game_id", "rank", "total", "stock", "order", "event_seq", "zone",
    "appear_ms", "stable_ms", "clear_ms", "time", "quantity", "cards",
    "suspicion", "reasons", "link", "notes",
]

PAGE = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>超库存待核对清单</title>
<style>
  :root {{ color-scheme: dark; }}
  body {{ margin:0; padding:24px 32px 64px; background:#111417; color:#e6e6e6;
         font:14px/1.55 "Segoe UI", "Microsoft YaHei", sans-serif; }}
  h1 {{ font-size:20px; margin:0 0 4px; }}
  .meta {{ color:#9aa4ad; font-size:13px; margin-bottom:18px; }}
  table {{ border-collapse:collapse; width:100%; }}
  th, td {{ border-bottom:1px solid #262c31; padding:6px 10px; vertical-align:middle; }}
  th {{ position:sticky; top:0; background:#181d21; text-align:left; font-weight:600;
        font-size:12px; color:#9aa4ad; }}
  tr.group td {{ background:#2b2110; color:#ffcc66; font-weight:600; border-bottom:1px solid #7a5a12; }}
  td.num {{ text-align:right; font-variant-numeric:tabular-nums; }}
  td.time {{ white-space:nowrap; color:#8fc7ff; font-variant-numeric:tabular-nums; }}
  td.cards {{ font-family:Consolas, monospace; }}
  td.why {{ color:#ffb4a8; font-size:12px; }}
  a {{ color:#8fc7ff; }}
  tr:hover td {{ background:#151a1e; }}
</style>
</head>
<body>
<h1>超库存待核对清单</h1>
<div class="meta">{meta}</div>
{body}
</body>
</html>
"""


def collect(payload: dict) -> list[dict]:
    entries: list[dict] = []
    for game in payload["games"]:
        if not game.get("complete"):
            continue
        game_id = game["gameId"]
        path = ds.TRUTH_DIR / f"{game_id}.csv"
        if not path.exists():
            continue
        with path.open(encoding="utf-8") as handle:
            rows = list(csv.DictReader(handle))
        for item in sr.find_stock_over(rows):
            for order, row in enumerate(item["suspects"], 1):
                score, reasons = sr.suspicion(row["notes"])
                entries.append(
                    {
                        "game_id": game_id,
                        "rank": item["rank"],
                        "total": item["total"],
                        "stock": item["stock"],
                        "order": order,
                        "event_seq": row["event_seq"],
                        "zone": row["zone"],
                        "appear_ms": row["appear_ms"],
                        "stable_ms": row["stable_ms"],
                        "clear_ms": row["clear_ms"],
                        "time": sr.fmt_time(int(row["stable_ms"])),
                        "quantity": row["quantity"],
                        "cards": row["cards"] or "（未识别）",
                        "suspicion": score,
                        "reasons": "、".join(reasons) if reasons else "无可疑迹象（按张数列出）",
                        "link": f"{game_id}.html#seq-{row['event_seq']}",
                        "notes": row["notes"],
                    }
                )
    return entries


def write_reports(payload: dict) -> list[dict]:
    entries = collect(payload)
    ds.REVIEW_DIR.mkdir(parents=True, exist_ok=True)
    with (ds.REVIEW_DIR / "stock_over.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(entries)

    groups: dict[tuple, list[dict]] = {}
    for entry in entries:
        groups.setdefault((entry["game_id"], entry["rank"], entry["total"], entry["stock"]), []).append(entry)
    body_rows = []
    for (game_id, rank, total, stock), items in groups.items():
        body_rows.append(
            f'<tr class="group"><td colspan="8">{html.escape(game_id)} · {html.escape(rank)} '
            f"合计 {total}/{stock}（超 {total - stock}）· 可疑手 {len(items)} 手</td></tr>"
        )
        for item in items:
            body_rows.append(
                "<tr>"
                f'<td class="num">{item["order"]}</td>'
                f'<td><a href="{html.escape(item["link"])}">#{html.escape(str(item["event_seq"]))}</a></td>'
                f'<td>{html.escape(item["zone"])}</td>'
                f'<td class="time">{html.escape(item["time"])}</td>'
                f'<td class="num">{html.escape(str(item["quantity"]))}</td>'
                f'<td class="cards">{html.escape(item["cards"])}</td>'
                f'<td class="why">{html.escape(item["reasons"])}（{item["suspicion"]} 分）</td>'
                f'<td class="why">{html.escape(item["notes"])}</td>'
                "</tr>"
            )
    if body_rows:
        body = (
            "<table><thead><tr><th>序</th><th>手（链接到核对页）</th><th>区</th><th>停稳时间</th>"
            "<th>张数</th><th>当前 cards</th><th>可疑原因</th><th>notes</th></tr></thead><tbody>"
            + "\n".join(body_rows)
            + "</tbody></table>"
        )
    else:
        body = '<div class="meta">没有点数超库存。</div>'
    meta = (
        f"complete=true 的局里，某点数出牌合计超过库存（Rank.kt）。共 {len(groups)} 个（局, 点数）、"
        f"{len(entries)} 行可疑手；同一点数下按可疑程度从高到低排。"
    )
    (ds.REVIEW_DIR / "stock_over.html").write_text(
        PAGE.format(meta=html.escape(meta), body=body), encoding="utf-8", newline="\n"
    )
    return entries


def main() -> None:
    entries = write_reports(ds.load_games())
    print(f"stock_over: {len(entries)} 行 -> {ds.REVIEW_DIR / 'stock_over.csv'}")


if __name__ == "__main__":
    main()
