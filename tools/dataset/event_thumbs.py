"""按真值行的 appear/stable/clear 时刻从录屏截缩略图，供人工核对一段可疑事件。

每个事件一张拼图：三列（appear / stable / clear），每列上面是整帧缩小图（框出该区），
下面是该区裁剪放大 3 倍。只读本地录屏，图片写到 D:\\CardCounterData\\review\\ 下，不联网。

用法:
    python tools/dataset/event_thumbs.py --game g202234-1 --seqs 57-63,80,85 --out g202234-1_rb
"""
from __future__ import annotations

import argparse
import csv
from pathlib import Path
import sys

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402

FRAME_W = 480
CROP_SCALE = 3


def parse_seqs(text: str) -> list[int]:
    seqs: list[int] = []
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            lo, hi = part.split("-", 1)
            seqs.extend(range(int(lo), int(hi) + 1))
        else:
            seqs.append(int(part))
    return seqs


def label(img: np.ndarray, text: str) -> np.ndarray:
    out = img.copy()
    cv2.rectangle(out, (0, 0), (out.shape[1], 18), (0, 0, 0), -1)
    cv2.putText(out, text, (4, 13), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1, cv2.LINE_AA)
    return out


def column(video: Path, zone: str, t_ms: int, name: str) -> np.ndarray:
    frame = ds.frame_at(video, t_ms)
    if frame is None:
        frame = np.zeros((ds.CANON_H, ds.CANON_W, 3), np.uint8)
    left, top, w, h = ds.zone_rect(zone, frame.shape[1], frame.shape[0])
    boxed = frame.copy()
    cv2.rectangle(boxed, (left, top), (left + w, top + h), (0, 0, 255), 2)
    scale = FRAME_W / boxed.shape[1]
    small = cv2.resize(boxed, (FRAME_W, int(boxed.shape[0] * scale)), interpolation=cv2.INTER_AREA)
    crop = ds.crop_zone(frame, zone)
    big = cv2.resize(
        crop, (crop.shape[1] * CROP_SCALE, crop.shape[0] * CROP_SCALE), interpolation=cv2.INTER_NEAREST
    )
    pad = np.zeros((big.shape[0], FRAME_W, 3), np.uint8)
    pad[:, : min(FRAME_W, big.shape[1])] = big[:, :FRAME_W]
    return np.vstack([label(small, f"{name} {t_ms}ms"), pad])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--game", required=True)
    parser.add_argument("--seqs", required=True, help="如 57-63,80,85")
    parser.add_argument("--out", required=True, help="review 下的子目录名")
    args = parser.parse_args()

    game = next(g for g in ds.load_games()["games"] if g["gameId"] == args.game)
    video = ds.SOURCE_BASE / game["video"]
    with (ds.TRUTH_DIR / f"{args.game}.csv").open(encoding="utf-8") as handle:
        rows = {int(r["event_seq"]): r for r in csv.DictReader(handle)}
    out_dir = ds.REVIEW_DIR / args.out
    out_dir.mkdir(parents=True, exist_ok=True)
    for seq in parse_seqs(args.seqs):
        row = rows.get(seq)
        if row is None:
            print(f"seq {seq}: 不存在")
            continue
        zone = row["zone"]
        cols = [
            column(video, zone, int(row["appear_ms"]), "appear"),
            column(video, zone, int(row["stable_ms"]), "stable"),
            column(video, zone, int(row["clear_ms"]), "clear"),
        ]
        sheet = np.hstack(cols)
        path = out_dir / f"{seq:03d}_{zone}_{row['stable_ms']}.png"
        cv2.imwrite(str(path), sheet)
        print(f"seq {seq} {zone} q={row['quantity']} cards={row['cards'] or '-'} -> {path}")


if __name__ == "__main__":
    main()
