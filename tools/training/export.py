"""Export the trained model to ONNX and verify the DATA-FORMATS contract.

Contract (docs/rebuild/DATA-FORMATS.md section 4):
  input  [1, 3, 320, 320] float32, RGB, 0..1, letterbox pad 114/255
  output [1, 4 + 15, 2100]  (cx, cy, w, h in 320px + 15 class scores, no NMS)
  opset 17

Writes docs/rebuild/MODEL-V2.md facts (sha256, bytes, shapes) as JSON so the
doc stays in sync with the artefact.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

os.environ.setdefault("USERNAME", os.environ.get("LOGNAME", "codex"))

sys.path.insert(0, str(Path(__file__).resolve().parent))

import numpy as np  # noqa: E402

import common as C  # noqa: E402


def export(weights: Path, out_path: Path, opset: int = 17) -> Path:
    from ultralytics import YOLO

    model = YOLO(str(weights))
    produced = model.export(
        format="onnx",
        imgsz=C.MODEL_INPUT,
        opset=opset,
        nms=False,
        simplify=False,
        dynamic=False,
        half=False,
        batch=1,
    )
    produced = Path(produced)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    if produced.resolve() != out_path.resolve():
        out_path.write_bytes(produced.read_bytes())
    return out_path


def verify(path: Path) -> dict:
    import onnxruntime as ort

    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    inputs = session.get_inputs()
    outputs = session.get_outputs()
    input_shape = list(inputs[0].shape)
    output_shape = list(outputs[0].shape)
    expected_in = [1, 3, C.MODEL_INPUT, C.MODEL_INPUT]
    expected_out = [1, 4 + len(C.RANKS), 2100]
    if input_shape != expected_in:
        raise SystemExit(f"input shape {input_shape} != {expected_in}")
    if output_shape != expected_out:
        raise SystemExit(f"output shape {output_shape} != {expected_out}")

    rng = np.random.default_rng(0)
    probe = rng.random((1, 3, C.MODEL_INPUT, C.MODEL_INPUT), dtype=np.float32)
    raw = session.run(None, {inputs[0].name: probe})[0]
    if raw.shape != tuple(expected_out):
        raise SystemExit(f"runtime output {raw.shape} != {expected_out}")
    finite = bool(np.isfinite(raw).all())
    cx_range = (float(raw[0, 0].min()), float(raw[0, 0].max()))
    score_range = (float(raw[0, 4:].min()), float(raw[0, 4:].max()))

    import onnx

    graph = onnx.load(str(path))
    opset = [op.version for op in graph.opset_import]
    return {
        "path": str(path),
        "sha256": C.sha256_file(path),
        "bytes": path.stat().st_size,
        "opset": opset,
        "input": {"name": inputs[0].name, "shape": input_shape, "dtype": inputs[0].type},
        "output": {"name": outputs[0].name, "shape": output_shape, "dtype": outputs[0].type},
        "finite": finite,
        "cx_range": [round(v, 2) for v in cx_range],
        "score_range": [round(v, 4) for v in score_range],
        "nms_in_graph": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", default=str(C.RUNS_DIR / "train" / "stage_b" / "weights" / "best.pt"))
    parser.add_argument("--out", default=str(C.ROOT / "models" / "poker_rank15_yolo11n_320.onnx"))
    parser.add_argument("--opset", type=int, default=17)
    args = parser.parse_args()

    weights = Path(args.weights)
    if not weights.is_file():
        raise SystemExit(f"weights not found: {weights}")
    out_path = Path(args.out)
    path = export(weights, out_path, args.opset)
    facts = verify(path)
    facts["weights"] = str(weights)
    facts["imgsz"] = C.MODEL_INPUT
    facts["classes"] = C.RANKS
    C.save_json(C.RUNS_DIR / "export.json", facts)
    print(json.dumps(facts, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
