"""Consistency test for phone_vision.refine_narrow_left_joker against core/src/test/resources/joker_refine/cases.json."""
from __future__ import annotations

import json
from pathlib import Path
import sys

import cv2

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402
import phone_vision as pv  # noqa: E402


def main() -> None:
    res_dir = ds.REPO / "core" / "src" / "test" / "resources" / "joker_refine"
    cases = json.loads((res_dir / "cases.json").read_text(encoding="utf-8"))
    for item in cases:
        rgb = cv2.imread(str(res_dir / item["image"]), cv2.IMREAD_COLOR)[:, :, ::-1]
        raw_dets = [
            (
                ds.RANK_INDEX[d["rank"]],
                float(d["conf"]),
                float(d["cx"]),
                float(d["cy"]),
                float(d["w"]),
                float(d["h"]),
            )
            for d in item["rawDetections"]
        ]
        refined = pv.refine_narrow_left_joker(raw_dets, rgb)
        left = min(refined, key=lambda x: x[2])
        got_rank = ds.RANK_ORDER[left[0]]
        expected = item["expectedLeftRank"]
        assert got_rank == expected, f"{item['id']}: expected {expected}, got {got_rank}"
        print(f"OK {item['id']}: left={got_rank}")
    print(f"All {len(cases)} joker_refine cases matched between Python and Kotlin expectations.")


if __name__ == "__main__":
    main()
