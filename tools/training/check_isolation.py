"""Prove no held-out recording (dev/tuning/blind/eval) leaked into the training data.

Positive check: every source id found on disk must belong to the allowed training
sources (6 9局 train-only clips). Negative check: no dev (102658/143651/075639) /
tuning (164333) / blind (202234/210429) / eval-hand / eval-clip id may appear in
any category (sprite tiles, negatives, turn badges, suit-only negatives, real hand
frames/labels, align list, or NEGATIVE_SOURCE_VIDEOS config).
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
REMEDIATE_HINT = (
    "请检查并从 common.NEGATIVE_SOURCE_VIDEOS / extract_sprites.py 移除非训练视频来源，"
    "然后重跑 extract_sprites.py 与 synthesize.py（及 --align）重建素材"
)


def vid(name: str) -> str:
    m = _ID.search(name)
    return m.group(1) if m else name


def norm(path) -> str:
    return os.path.normcase(os.path.normpath(str(path)))


def build_forbidden_map() -> dict[str, str]:
    forbidden: dict[str, str] = {vid(t + "_1.mp4"): "blind/tuning" for t in C.DENIED_TOKENS}
    for n in C.DEV_VIDEOS:
        forbidden[vid(n)] = "dev"
    for n in C.EMPTY_ZONE_EVAL_VIDEOS:
        forbidden.setdefault(vid(n), "dev/eval-empty-zone")
    for n in C.EVAL_CLIPS:
        forbidden[vid(n)] = "eval-hand"
    for n in C.CLIP_VIDEOS:
        forbidden[vid(n)] = "eval-clip"
    return forbidden


def check_source_item(
    kind: str,
    file_desc: str,
    src: str,
    allowed: set[str],
    forbidden: dict[str, str],
    problems: list[str],
) -> None:
    for fid, label in sorted(forbidden.items()):
        if src == fid or fid in file_desc:
            problems.append(
                f"LEAK [{kind}] 文件={file_desc} 来源={fid}({label})；{REMEDIATE_HINT}"
            )
            return
    if src not in allowed:
        problems.append(
            f"NOT ALLOWED [{kind}] 文件={file_desc} 来源={src}（不在允许列表 {sorted(allowed)} 内）；{REMEDIATE_HINT}"
        )


def check_sprites(
    root: Path,
    entries: list[dict],
    allowed: set[str],
    forbidden: dict[str, str],
    problems: list[str],
) -> Counter:
    counter: Counter = Counter()
    for e in entries:
        rel_path = e["path"]
        file_name = Path(rel_path).name
        file_src = file_name.split("_")[0]
        src = e["source"]
        if file_src != src:
            problems.append(
                f"MISMATCH [sprite tiles] 文件={rel_path} 文件名前缀={file_src} 与 index source={src} 不一致；{REMEDIATE_HINT}"
            )
        counter[src] += 1
        check_source_item("sprite tiles", str(root / rel_path), src, allowed, forbidden, problems)

    indexed = {norm(root / e["path"]) for e in entries}
    on_disk_paths = [p for scale in SPRITE_SCALES for p in (root / "sprites" / scale).rglob("*.png")]
    on_disk = {norm(p) for p in on_disk_paths}
    for p in sorted(on_disk - indexed):
        problems.append(f"UNINDEXED [sprite tiles] 磁盘存在未索引文件={p}；{REMEDIATE_HINT}")
    for p in sorted(indexed - on_disk):
        problems.append(f"MISSING [sprite tiles] index 记录缺失文件={p}；{REMEDIATE_HINT}")
    for p in sorted(on_disk_paths):
        check_source_item("sprite tiles (disk)", str(p), p.name.split("_")[0], allowed, forbidden, problems)
    return counter


def check_folder_items(
    kind: str,
    paths: list[Path],
    allowed: set[str],
    forbidden: dict[str, str],
    problems: list[str],
) -> Counter:
    counter: Counter = Counter()
    for p in sorted(paths):
        src = p.name.split("_")[0]
        counter[src] += 1
        check_source_item(kind, str(p), src, allowed, forbidden, problems)
    return counter


def check_align_list(
    path: Path,
    allowed: set[str],
    forbidden: dict[str, str],
    problems: list[str],
) -> tuple[int, Counter]:
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
            src = name.split("_")[0]
            real[src] += 1
            check_source_item("align list (real)", line, src, allowed, forbidden, problems)
        else:
            problems.append(
                f"INVALID [align list] 非法条目={line}（既不是合成训练集也不是 real_hand 帧）；{REMEDIATE_HINT}"
            )
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


def run_check(root: Path = C.DATA_ROOT) -> tuple[int, str]:
    forbidden = build_forbidden_map()
    problems: list[str] = []
    for neg_video in C.NEGATIVE_SOURCE_VIDEOS:
        src = vid(neg_video)
        if src in forbidden:
            problems.append(
                f"LEAK [config common.NEGATIVE_SOURCE_VIDEOS] 视频={neg_video} 来源={src}({forbidden[src]})；{REMEDIATE_HINT}"
            )
    allowed = {vid(p.name) for p in C.nine_games(train_only=True)} | (
        {vid(n) for n in C.NEGATIVE_SOURCE_VIDEOS} - set(forbidden)
    )

    entries = json.loads((root / "sprites" / "index.json").read_text(encoding="utf-8"))["entries"]
    found: dict[str, Counter] = {
        "sprite tiles": check_sprites(root, entries, allowed, forbidden, problems)
    }

    neg_all = sorted((root / "sprites" / "_neg").glob("*"))
    neg_regular = [p for p in neg_all if "_badge_" not in p.name]
    neg_badges = [p for p in neg_all if "_badge_" in p.name]
    found["negatives"] = check_folder_items("negatives", neg_regular, allowed, forbidden, problems)
    found["turn badges"] = check_folder_items("turn badges", neg_badges, allowed, forbidden, problems)
    found["suit-only negatives"] = check_folder_items(
        "suit-only negatives", sorted((root / "sprites" / "_pipneg").glob("*")), allowed, forbidden, problems
    )
    found["real hand frames"] = check_folder_items(
        "real hand frames", sorted((root / "synth" / "real_hand" / "images").glob("*")), allowed, forbidden, problems
    )
    labels = root / "synth" / "real_hand" / "labels"
    if labels.is_dir():
        found["real hand labels"] = check_folder_items(
            "real hand labels", sorted(labels.glob("*")), allowed, forbidden, problems
        )
    synthetic, found["align list (real)"] = check_align_list(
        root / "synth" / "dataset_align" / "train_list.txt", allowed, forbidden, problems
    )
    check_data_yaml(problems)

    report: list[str] = []
    for kind, counter in found.items():
        report.append(f"{kind}: {dict(sorted(counter.items()))}")
    report.append(f"sprite files checked against index: {len(entries)}")
    report.append(f"align list synthetic images: {synthetic}")
    report.append(f"allowed training sources: {sorted(allowed)}")
    report.append(f"forbidden ids checked: {dict(sorted(forbidden.items()))}")
    report += [f"  {p}" for p in problems]
    report.append("RESULT: " + ("CLEAN" if not problems else f"{len(problems)} violation(s)"))
    text = "\n".join(report)
    return (1 if problems else 0), text


def main() -> int:
    code, text = run_check(C.DATA_ROOT)
    print(text)
    out = C.SYNTH_DIR / "_check" / "isolation_result.txt"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text + "\n", encoding="utf-8")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
