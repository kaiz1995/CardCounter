"""库存规则与真值 notes 解析，draft_truth / check_truth / build_review / stock_report 共用。

库存（4 副牌 198 张）来自 core/src/main/kotlin/com/example/cardcounter/domain/Rank.kt。
交贡、还贡、买 3 买 4 只在 198 张内部转移，不改变任何点数的总数：某点数出牌合计超过库存
就一定有误识别。每家出牌合计没有 33 张的限制，这里不做“每家不超过 33 张”的检查。

库存只检查、不修改：超库存时不碰任何一手的 cards 和 quantity，只找出贡献该点数的可疑手。
"""
from __future__ import annotations

from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402

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
TOTAL_STOCK = sum(STOCK.values())
NORMAL_RANKS = tuple(
    rank for rank in ds.RANK_ORDER if rank not in ("BIG_JOKER", "SMALL_JOKER", "TWO")
)
# 贡献超库存点数的手里一手都没有可疑迹象时，退而列出张数最大的前几手
SUSPECT_FALLBACK = 3

MODEL_GEOM = re.compile(r"model=(\d+) geom=(\d+)")
STOCK_OVER = re.compile(r"stock-over (\w+) (\d+)/(\d+)")


def parse_vector(text: str) -> dict:
    counts: dict = {}
    for part in text.split(";"):
        if not part.strip():
            continue
        rank, qty = part.split(":")
        counts[rank] = counts.get(rank, 0) + int(qty)
    return counts


def fmt_time(ms: int) -> str:
    seconds, millis = divmod(int(ms), 1000)
    minutes, seconds = divmod(seconds, 60)
    return f"{minutes:02d}:{seconds:02d}.{millis:03d}"


def suspicion(notes: str):
    """按 notes 里的留痕给一手打可疑分：返回 (分数, [原因])。

    模型票数与几何不一致、trim（模型点数比几何多被裁掉）、宽扇形、mixed_ranks 修正过、牌距
    压缩靠牌角/模型兜底、被面板遮挡过的手都算可疑。笼统的 needs_review 标记不计分，避免
    库存标记反过来影响排序。
    """
    score = 0
    reasons: list[str] = []
    if "mixed_ranks" in notes:
        score += 3
        reasons.append("混了两种点数")
    if re.search(r"\btrim\b", notes):
        score += 3
        reasons.append("模型点数多于几何，被裁")
    seen = set()
    for match in MODEL_GEOM.finditer(notes):
        pair = (int(match.group(1)), int(match.group(2)))
        if pair in seen:
            continue
        seen.add(pair)
        diff = abs(pair[0] - pair[1])
        if diff:
            score += 2 * diff
            reasons.append(f"模型{pair[0]}张/几何{pair[1]}张")
    if "geom=wide" in notes:
        score += 2
        reasons.append("宽扇形")
    if "crop-missing" in notes:
        score += 2
        reasons.append("缺裁剪")
    if "-> corners" in notes or "-> model" in notes:
        score += 1
        reasons.append("牌距压缩")
    if "panel_covered" in notes:
        score += 1
        reasons.append("面板遮挡过")
    return score, reasons


def find_stock_over(rows):
    """rows: 带 event_seq/zone/stable_ms/cards/notes 的字典列表（CSV 行即可）。

    返回超库存的点数列表，每项 {rank,total,stock,suspects}；suspects 是贡献该点数的手，
    按可疑度从高到低排（同分张数多的在前，再按时间）；没有任何可疑迹象时取张数最大的前几手。
    """
    totals = {rank: 0 for rank in STOCK}
    parsed = []
    for row in rows:
        vector = parse_vector(row["cards"])
        for rank, count in vector.items():
            if rank in totals:
                totals[rank] += count
        parsed.append((row, vector))
    findings = []
    for rank in ds.RANK_ORDER:
        if totals[rank] <= STOCK[rank]:
            continue
        contributors = [(row, vector) for row, vector in parsed if vector.get(rank, 0) > 0]
        ranked = sorted(
            contributors,
            key=lambda item: (
                -suspicion(item[0]["notes"])[0],
                -item[1][rank],
                int(item[0]["stable_ms"]),
            ),
        )
        suspects = [row for row, _ in ranked if suspicion(row["notes"])[0] > 0]
        if not suspects:
            suspects = [row for row, _ in ranked[:SUSPECT_FALLBACK]]
        findings.append(
            {
                "rank": rank,
                "total": totals[rank],
                "stock": STOCK[rank],
                "suspects": suspects,
            }
        )
    return findings


def describe_hand(row) -> str:
    score, reasons = suspicion(row["notes"])
    why = "、".join(reasons) if reasons else "无可疑迹象"
    return (
        f"#{row['event_seq']} {row['zone']} {fmt_time(int(row['stable_ms']))} "
        f"cards={row['cards'] or '-'}（{why}）"
    )
