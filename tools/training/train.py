"""Train yolo11n @320 on the synthesized rank data, then align on real frames.

Three subcommands keep the CPU budget honest:

  speed  3 epochs, measure seconds/epoch, print the projection for 3 hours
  synth  stage A: synthetic tiles only, early stopping
  align  stage B: short fine-tune with over-sampled real hand frames

All artefacts go to D:\\CardCounterData\\runs (never into git).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common as C  # noqa: E402

# torch._inductor calls getpass.getuser(), which needs USERNAME on Windows
# when the shell was started without a login environment.
os.environ.setdefault("USERNAME", os.environ.get("LOGNAME", "codex"))

RUNS = C.RUNS_DIR
BUDGET_S = 3 * 3600
# ultralytics pretrained weights live outside the repo (never in git); the first
# run downloads them to this path.
PRETRAINED = str(C.DATA_ROOT / "weights" / "yolo11n.pt")


def _yolo():
    from ultralytics import YOLO

    return YOLO


def _default_device() -> str:
    try:
        import torch

        if torch.cuda.is_available():
            return "0"
    except Exception:
        pass
    return "cpu"


def train(
    data: Path,
    epochs: int,
    project: str,
    name: str,
    weights: str = PRETRAINED,
    batch: int = 32,
    patience: int = 15,
    lr0: float = 0.01,
    workers: int = 8,
    device: str = "",
):
    YOLO = _yolo()
    model = YOLO(weights)
    target_device = device or _default_device()
    started = time.time()
    model.train(
        data=str(data),
        epochs=epochs,
        imgsz=320,
        batch=batch,
        device=target_device,
        workers=workers,
        project=str(RUNS / project),
        name=name,
        exist_ok=True,
        patience=patience,
        optimizer="SGD",
        lr0=lr0,
        lrf=0.05,
        warmup_epochs=1.0,
        cos_lr=True,
        amp=False,
        cache=False,
        plots=False,
        val=True,
        seed=0,
        deterministic=True,
        verbose=True,
        mosaic=1.0,
        scale=0.5,
        translate=0.1,
        degrees=0.0,
        fliplr=0.0,
        flipud=0.0,
        hsv_h=0.0,
        hsv_s=0.4,
        hsv_v=0.4,
        rect=False,
    )
    elapsed = time.time() - started
    best = RUNS / project / name / "weights" / "best.pt"
    return {"seconds": round(elapsed, 1), "best": str(best)}


def speed_test(data: Path, device: str = "") -> dict:
    result = train(data, epochs=3, project="speed", name="yolo11n_320", patience=0, device=device)
    per_epoch = result["seconds"] / 3
    result.update(
        {
            "seconds_per_epoch": round(per_epoch, 1),
            "epochs_in_3h": int((BUDGET_S - 600) // per_epoch),
        }
    )
    C.save_json(RUNS / "speed.json", result)
    print(json.dumps(result, indent=2))
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=["speed", "synth", "align"])
    parser.add_argument("--epochs", type=int, default=60)
    parser.add_argument("--batch", type=int, default=32)
    parser.add_argument("--weights", default="")
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--device", default="", help="e.g. 0 or cpu (default: auto CUDA if available)")
    args = parser.parse_args()

    if args.stage == "speed":
        speed_test(C.SYNTH_DIR / "dataset" / "data.yaml", device=args.device)
        return 0

    if args.stage == "synth":
        weights = args.weights or PRETRAINED
        result = train(
            C.SYNTH_DIR / "dataset" / "data.yaml",
            epochs=args.epochs,
            project="train",
            name="stage_a",
            weights=weights,
            batch=args.batch,
            workers=args.workers,
            device=args.device,
        )
    else:
        data = C.SYNTH_DIR / "dataset_align" / "data.yaml"
        if not data.is_file():
            raise SystemExit("run `synthesize.py --align` first to build the aligned set")
        result = train(
            data,
            epochs=args.epochs,
            project="train",
            name="stage_b",
            weights=args.weights or str(RUNS / "train" / "stage_a" / "weights" / "best.pt"),
            batch=args.batch,
            patience=max(8, args.epochs // 2),
            lr0=0.0008,
            workers=args.workers,
            device=args.device,
        )
    C.save_json(RUNS / f"{args.stage}_result.json", result)
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
