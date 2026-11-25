"""Prove no held-out recording leaked into the training data.

Positive check: every source id found on disk must belong to the allowed training
sources (9局 train-only clips + the negative source videos). Negative check: no
blind / tuning / eval-hand / empty-zone-eval id may appear anywhere.
Structural checks, so nothing can slip past the id checks: every line of the
align list is a synthetic train image or a real hand frame, the sprite files on
disk are exactly the sprite index (each file name starts with its source id),
and both data.yaml files point at the expected folders and classes.
The report is printed and written to synth/_check/isolation_result.txt.
Exit code 1 on any violation.
"""

from __future__ import annotations

import json
import os
import re
import sys
from collections import Counter
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common as C  # noqa: E402

_ID = re.compile(r"_(\d{6})_\d+\.mp4$")
_SYNTH_TRAIN = re.compile(r"train_\d+\.png")
SPRITE_SCALES = ("hand", "outside", "self", "outside_top", "self_top")


def vid(name: str) -> str:
    m = _ID.search(name)
    return m.group(1) if m else name


def norm(path) -> str:
    return os.path.normcase(os.path.normpath(str(path)))


def prefixes(folder: Path) -> Counter:
    return Counter(f.split("_")[0] for f in os.listdir(folder))


def check_sprites(root: Path, entries: list[dict], problems: list[str]) -> None:
    for e in entries:
        if Path(e["path"]).name.split("_")[0] != e["source"]:
            problems.append(f"sprite {e['path']}: file name does not start with its source {e['source']}")
    indexed = {norm(root / e["path"]) for e in entries}
    on_disk = {norm(p) for scale in SPRITE_SCALES for p in (root / "sprites" / scale).rglob("*.png")}
    problems += [f"sprite file not in index: {p}" for p in sorted(on_disk - indexed)]
    problems += [f"sprite in index but missing on disk: {p}" for p in sorted(indexed - on_disk)]


def check_align_list(path: Path, problems: list[str]) -> tuple[int, Counter]:
    synth_dir = norm(C.SYNTH_DIR / "dataset" / "images" / "train")
    real_dir = norm(C.SYNTH_DIR / "real_hand" / "images")
    synthetic, real = 0, Counter()
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        folder, name = norm(Path(line).parent), Path(line).name
        if folder == synth_dir and _SYNTH_TRAIN.fullmatch(name):
            synthetic += 1
        elif folder == real_dir:
            real[name.split("_")[0]] += 1
        else:
            problems.append(f"align list line is neither a synthetic train image nor a real hand frame: {line}")
    return synthetic, real


def check_data_yaml(problems: list[str]) -> None:
    dataset = C.SYNTH_DIR / "dataset"
    align = C.SYNTH_DIR / "dataset_align"
    expected = {
        dataset / "data.yaml": {"path": dataset, "train": dataset / "images" / "train", "val": dataset / "images" / "val"},
        align / "data.yaml": {"path": align, "train": align / "train_list.txt", "val": dataset / "images" / "val"},
    }
    for file, want in expected.items():
        cfg = yaml.safe_load(file.read_text(encoding="utf-8"))
        base = Path(str(cfg.get("path", "")))
        for key, target in want.items():
            value = Path(str(cfg.get(key, "")))
            resolved = value if key == "path" or value.is_absolute() else base / value
            if norm(resolved) != norm(target):
                problems.append(f"{file}: {key} = {cfg.get(key)!r}, expected {target}")
        names = cfg.get("names", {})
        got = [names.get(i) for i in range(len(C.RANKS))] if isinstance(names, dict) else list(names)
        if cfg.get("nc") != len(C.RANKS) or got != list(C.RANKS):
            problems.append(f"{file}: classes differ from common.RANKS")


def main() -> int:
    root = C.DATA_ROOT
    allowed = {vid(p.name) for p in C.nine_games(train_only=True)} | {vid(n) for n in C.NEGATIVE_SOURCE_VIDEOS}
    forbidden = {vid(t + "_1.mp4"): "blind/tuning" for t in C.DENIED_TOKENS}
    forbidden.update({vid(n): "eval-hand" for n in C.EVAL_CLIPS})
    forbidden.update({vid(n): "eval-empty-zone" for n in C.EMPTY_ZONE_EVAL_VIDEOS})
    problems: list[str] = []

    entries = json.loads((root / "sprites" / "index.json").read_text(encoding="utf-8"))["entries"]
    check_sprites(root, entries, problems)
    found: dict[str, Counter] = {"sprite tiles": Counter(e["source"] for e in entries)}
    found["negatives"] = prefixes(root / "sprites" / "_neg")
    found["suit-only negatives"] = prefixes(root / "sprites" / "_pipneg")
    found["real hand frames"] = prefixes(root / "synth" / "real_hand" / "images")
    labels = root / "synth" / "real_hand" / "labels"
    if labels.is_dir():
        found["real hand labels"] = prefixes(labels)
    synthetic, found["align list (real)"] = check_align_list(
        root / "synth" / "dataset_align" / "train_list.txt", problems
    )
    check_data_yaml(problems)

    report: list[str] = []
    for kind, counter in found.items():
        report.append(f"{kind}: {dict(sorted(counter.items()))}")
        for src in counter:
            if src in forbidden:
                problems.append(f"LEAK {src} ({forbidden[src]}) in {kind}")
            elif src not in allowed:
                problems.append(f"NOT ALLOWED {src} in {kind}")
    report.append(f"sprite files checked against index: {len(entries)}")
    report.append(f"align list synthetic images: {synthetic}")
    report.append(f"allowed training sources: {sorted(allowed)}")
    report.append(f"forbidden ids checked: {dict(sorted(forbidden.items()))}")
    report += [f"  {p}" for p in problems]
    report.append("RESULT: " + ("CLEAN" if not problems else f"{len(problems)} violation(s)"))
    text = "\n".join(report)
    print(text)
    out = C.SYNTH_DIR / "_check" / "isolation_result.txt"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text + "\n", encoding="utf-8")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
