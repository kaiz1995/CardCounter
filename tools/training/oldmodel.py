"""Python runner for the shipped 28-class MNN model (local only, no network).

Preprocessing mirrors MnnYoloDetector.detect(): the crop is squashed to 320x320
(Bitmap.createScaledBitmap), RGB, float 0..1, NCHW. Output is [1, 32, 2100]
with cx, cy, w, h in 320-pixels followed by 28 class scores.

Only used to (a) locate real play-zone card corners and (b) provide the old
model baseline numbers. It never labels the new model's training targets.
"""

from __future__ import annotations

import cv2
import numpy as np

from common import MODEL_INPUT, OLD_LABELS, ROOT, old_label_to_rank

MODEL_PATH = ROOT / "app" / "src" / "main" / "assets" / "poker_yolo11_320.mnn"
NUM_CLASSES = len(OLD_LABELS)


def nms(boxes: np.ndarray, scores: np.ndarray, iou_threshold: float = 0.45) -> list[int]:
    order = scores.argsort()[::-1]
    keep: list[int] = []
    while order.size:
        index = int(order[0])
        keep.append(index)
        if order.size == 1:
            break
        rest = order[1:]
        xx1 = np.maximum(boxes[index, 0], boxes[rest, 0])
        yy1 = np.maximum(boxes[index, 1], boxes[rest, 1])
        xx2 = np.minimum(boxes[index, 2], boxes[rest, 2])
        yy2 = np.minimum(boxes[index, 3], boxes[rest, 3])
        inter = np.clip(xx2 - xx1, 0, None) * np.clip(yy2 - yy1, 0, None)
        area_index = (boxes[index, 2] - boxes[index, 0]) * (boxes[index, 3] - boxes[index, 1])
        area_rest = (boxes[rest, 2] - boxes[rest, 0]) * (boxes[rest, 3] - boxes[rest, 1])
        iou = inter / np.maximum(area_index + area_rest - inter, 1e-6)
        order = rest[iou <= iou_threshold]
    return keep


def preprocess(crop_bgr: np.ndarray) -> np.ndarray:
    image = cv2.resize(crop_bgr, (MODEL_INPUT, MODEL_INPUT), interpolation=cv2.INTER_LINEAR)
    rgb = image[:, :, ::-1].astype(np.float32) / 255.0
    return np.ascontiguousarray(rgb.transpose(2, 0, 1)[None])


def decode(raw: np.ndarray, width: int, height: int, conf: float, iou: float) -> list[dict]:
    scores = raw[4:]
    best = scores.max(axis=0)
    class_ids = scores.argmax(axis=0)
    mask = best >= conf
    if not mask.any():
        return []
    cx, cy, bw, bh = raw[0][mask], raw[1][mask], raw[2][mask], raw[3][mask]
    sx, sy = width / MODEL_INPUT, height / MODEL_INPUT
    boxes = np.stack(
        [(cx - bw / 2) * sx, (cy - bh / 2) * sy, (cx + bw / 2) * sx, (cy + bh / 2) * sy], axis=1
    )
    keep = nms(boxes, best[mask], iou)
    results = []
    for index in keep:
        label = OLD_LABELS[int(class_ids[mask][index])]
        results.append(
            {
                "label": label,
                "rank": old_label_to_rank(label),
                "conf": float(best[mask][index]),
                "box": tuple(float(v) for v in boxes[index]),
            }
        )
    return results


class OldDetector:
    """MNN session wrapper. Import of MNN happens here so other tools stay pure."""

    def __init__(self, threads: int = 6):
        import MNN

        self._mnn = MNN
        self._interpreter = MNN.Interpreter(str(MODEL_PATH))
        self._session = self._interpreter.createSession({"numThread": threads, "backend": "CPU"})
        outputs = self._interpreter.getSessionOutputAll(self._session)
        self._out_name = "output0" if "output0" in outputs else next(iter(outputs))

    def raw(self, crop_bgr: np.ndarray) -> np.ndarray:
        MNN = self._mnn
        tensor_in = preprocess(crop_bgr)
        session = self._session
        inp = self._interpreter.getSessionInput(session)
        inp.copyFrom(
            MNN.Tensor(
                [1, 3, MODEL_INPUT, MODEL_INPUT],
                MNN.Halide_Type_Float,
                tensor_in,
                MNN.Tensor_DimensionType_Caffe,
            )
        )
        self._interpreter.runSession(session)
        out = MNN.Tensor(
            [1, 4 + NUM_CLASSES, 2100], MNN.Halide_Type_Float, MNN.Tensor_DimensionType_Caffe
        )
        self._interpreter.getSessionOutputAll(session)[self._out_name].copyToHostTensor(out)
        return np.asarray(out.getData(), dtype=np.float32).reshape(4 + NUM_CLASSES, 2100)

    def detect(self, crop_bgr: np.ndarray, conf: float = 0.25, iou: float = 0.45) -> list[dict]:
        return decode(self.raw(crop_bgr), crop_bgr.shape[1], crop_bgr.shape[0], conf, iou)
