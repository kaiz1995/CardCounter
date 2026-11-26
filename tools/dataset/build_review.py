"""为每局生成纯本地静态核对页 D:\\CardCounterData\\review\\<gameId>.html。

每行一手牌：时间、出牌区、张数、点数、该手停稳时的区裁剪图（放大 3 倍）。
图片来自 draft_truth.py 写出的 crops/<gameId>/<seq>_<zone>.png，页面用相对路径引用，
不发任何网络请求。

用法:
    python tools/dataset/build_review.py [--games g102658-3]
"""
from __future__ import annotations

import argparse
import csv
import html
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402
import stockrules as sr  # noqa: E402

PAGE = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>{title} 预标核对</title>
<style>
  :root {{ color-scheme: dark; }}
  body {{ margin:0; padding:24px 32px 64px; background:#111417; color:#e6e6e6;
         font:14px/1.55 "Segoe UI", "Microsoft YaHei", sans-serif; }}
  h1 {{ font-size:20px; margin:0 0 4px; }}
  .meta {{ color:#9aa4ad; font-size:13px; margin-bottom:18px; }}
  table {{ border-collapse:collapse; width:100%; }}
  th, td {{ border-bottom:1px solid #262c31; padding:6px 10px; vertical-align:middle; }}
  th {{ position:sticky; top:0; background:#181d21; text-align:left; font-weight:600;
        font-size:12px; color:#9aa4ad; letter-spacing:.04em; }}
  td.time {{ font-variant-numeric:tabular-nums; white-space:nowrap; color:#8fc7ff; }}
  td.zone {{ white-space:nowrap; }}
  td.qty {{ text-align:right; font-variant-numeric:tabular-nums; }}
  td.cards {{ font-family:Consolas, monospace; }}
  td.tag {{ font-size:12px; }}
  .tag-NORMAL {{ color:#7f8b95; }}
  .tag-REPLACE_DIRECT {{ color:#f0b44c; }}
  .note {{ color:#6f7a84; font-size:12px; }}
  tr.stockover {{ background:#3a1a0e; outline:2px solid #ff7a1a; outline-offset:-2px; }}
  tr.stockover td.cards, tr.stockover td.qty {{ color:#ffd0a0; }}
  .flag.stock {{ background:#ff7a1a; color:#111; }}
  tr:target td {{ background:#4a3a00 !important; }}
  tr.review {{ background:#2a1416; }}
  tr.review td {{ border-bottom:1px solid #5a2a2e; }}
  tr.review td.cards, tr.review td.qty {{ color:#ffb4a8; }}
  .flag {{ display:inline-block; padding:1px 6px; margin-right:6px; border-radius:3px;
           background:#b3261e; color:#fff; font-size:11px; font-weight:600; }}
  .flag.blank {{ background:#6b4b00; }}
  img.crop {{ height:72px; image-rendering:pixelated; background:#0b0d0f;
              border:1px solid #2a3138; border-radius:2px; }}
  tr:hover td {{ background:#151a1e; }}
  .empty {{ color:#9aa4ad; padding:32px 0; }}
</style>
</head>
<body>
<h1>{title}</h1>
<div class="meta">{meta}</div>
{body}
</body>
</html>
"""


def fmt_time(ms: int) -> str:
    seconds, millis = divmod(int(ms), 1000)
    minutes, seconds = divmod(seconds, 60)
    return f"{minutes:02d}:{seconds:02d}.{millis:03d}"


def build_game(game: dict) -> Path | None:
    game_id = game["gameId"]
    truth_path = ds.TRUTH_DIR / f"{game_id}.csv"
    if not truth_path.exists():
        return None
    with truth_path.open(encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    crop_dir = ds.CROPS_DIR / game_id
    body_rows = []
    for row in rows:
        seq = int(row["event_seq"])
        zone = row["zone"]
        needs_review = "needs_review" in row["notes"]
        stock_over = "stock-over" in row["notes"]
        blank = not row["cards"].strip()
        image = crop_dir / f"{seq:03d}_{zone}.png"
        rel = f"../crops/{game_id}/{image.name}"
        if image.exists():
            img_html = f'<img class="crop" src="{html.escape(rel)}" alt="{zone}">'
        else:
            img_html = '<span class="note">无裁剪图</span>'
        flag_html = ""
        if stock_over:
            match = sr.STOCK_OVER.search(row["notes"])
            label = f"超库存 {match.group(1)} {match.group(2)}/{match.group(3)}" if match else "超库存"
            flag_html = f'<span class="flag stock">{html.escape(label)}</span>'
        elif needs_review:
            flag_html = '<span class="flag">待核对</span>'
        elif blank:
            flag_html = '<span class="flag blank">点数未识别</span>'
        body_rows.append(
            f'<tr id="seq-{seq}" class="{"stockover review" if stock_over else ("review" if needs_review or blank else "")}">'
            f'<td class="qty">{seq}</td>'
            f'<td class="time">{fmt_time(int(row["stable_ms"]))}</td>'
            f'<td class="zone">{html.escape(zone)}</td>'
            f'<td class="qty">{html.escape(row["quantity"])}</td>'
            f'<td class="cards">{flag_html}{html.escape(row["cards"] or "（未识别）")}</td>'
            f'<td>{img_html}</td>'
            f'<td class="tag tag-{html.escape(row["tag"])}">{html.escape(row["tag"])}</td>'
            f'<td class="note">{html.escape(row["notes"])}</td>'
            "</tr>"
        )
    if body_rows:
        body = (
            "<table><thead><tr>"
            "<th>#</th><th>停稳时间</th><th>区</th><th>张数</th><th>点数</th>"
            "<th>停稳裁剪</th><th>tag</th><th>notes</th>"
            "</tr></thead><tbody>"
            + "\n".join(body_rows)
            + "</tbody></table>"
        )
    else:
        body = '<div class="empty">本局没有预标出牌事件。</div>'
    meta = (
        f'{html.escape(game["video"])} · {fmt_time(game["startMs"])}–{fmt_time(game["endMs"])} '
        f'· split={game["split"]} · complete={str(game["complete"]).lower()} '
        f'· firstPlay={fmt_time(game["firstPlayMs"])} · 共 {len(rows)} 手 · review=AUTO'
        f' · 待核对 {sum(1 for row in rows if "needs_review" in row["notes"])} 手'
        f' · 其中超库存 {sum(1 for row in rows if "stock-over" in row["notes"])} 手'
    )
    page = PAGE.format(title=f"{game_id} 预标核对", meta=meta, body=body)
    out = ds.REVIEW_DIR / f"{game_id}.html"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(page, encoding="utf-8", newline="\n")
    return out


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--games", help="逗号分隔的 gameId，缺省全部")
    args = parser.parse_args()
    payload = ds.load_games()
    wanted = set(args.games.split(",")) if args.games else None
    count = 0
    for game in payload["games"]:
        if wanted is not None and game["gameId"] not in wanted:
            continue
        out = build_game(game)
        if out is None:
            print(f"{game['gameId']}: 跳过（无 truth CSV）")
            continue
        print(f"{game['gameId']}: {out}")
        count += 1
    print(f"生成 {count} 个核对页")
    import stock_report  # noqa: E402
    entries = stock_report.write_reports(payload)
    print(f"超库存清单: {len(entries)} 行 -> {ds.REVIEW_DIR / 'stock_over.csv'}")


if __name__ == "__main__":
    main()
