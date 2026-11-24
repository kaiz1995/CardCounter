"""Prove no held-out recording leaked into sprites, negatives, real hand frames or the align list.

Positive check: every source id found on disk must belong to the allowed training
sources (9局 train-only clips + the negative source videos). Negative check: no
blind / tuning / eval-hand / empty-zone-eval id may appear anywhere. Exit code 1 on any hit.
"""

from __future__ import annotations

import json
import os
import re
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common as C  # noqa: E402

_ID = re.compile(r"_(\d{6})_\d+\.mp4$")


def vid(name: str) -> str:
    m = _ID.search(name)
    return m.group(1) if m else name


def main() -> int:
    root = C.DATA_ROOT
    allowed = {vid(p.name) for p in C.nine_games(train_only=True)} | {vid(n) for n in C.NEGATIVE_SOURCE_VIDEOS}
    forbidden = {vid(t + "_1.mp4"): "blind/tuning" for t in C.DENIED_TOKENS}
    forbidden.update({vid(n): "eval-hand" for n in C.EVAL_CLIPS})
    forbidden.update({vid(n): "eval-empty-zone" for n in C.EMPTY_ZONE_EVAL_VIDEOS})
    found: dict[str, Counter] = {}
    index = json.loads((root / "sprites" / "index.json").read_text(encoding="utf-8"))
    found["sprite tiles"] = Counter(e["source"] for e in index["entries"])
    found["negatives"] = Counter(f.split("_")[0] for f in os.listdir(root / "sprites" / "_neg"))
    found["suit-only negatives"] = Counter(f.split("_")[0] for f in os.listdir(root / "sprites" / "_pipneg"))
    found["real hand frames"] = Counter(f.split("_")[0] for f in os.listdir(root / "synth" / "real_hand" / "images"))
    lines = (root / "synth" / "dataset_align" / "train_list.txt").read_text(encoding="utf-8").split()
    found["align list (real)"] = Counter(os.path.basename(x).split("_")[0] for x in lines if "real_hand" in x)
    bad = 0
    for kind, counter in found.items():
        print(f"{kind}: {dict(sorted(counter.items()))}")
        for src in counter:
            if src in forbidden:
                print(f"  LEAK {src} ({forbidden[src]})")
                bad += 1
            elif src not in allowed:
                print(f"  NOT ALLOWED {src}")
                bad += 1
    print("allowed training sources:", sorted(allowed))
    print("forbidden ids checked:", dict(sorted(forbidden.items())))
    print("RESULT:", "CLEAN" if bad == 0 else f"{bad} violation(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
