"""Python runner for the shipped 28-class MNN model (local only, no network).

Preprocessing mirrors the phone, which feeds the model two different ways:

* play zones: CaptureProbeService.publicPlayBitmap() scales the crop
  proportionally, centres it on a 320x320 canvas filled with 0xff727272 (=114)
  and hands that to MnnYoloDetector (already 320x320, so no further resize).
  ``detect_letterbox`` reproduces this and maps boxes back to crop pixels.
* hand zone: handYoloBitmap() is a square 870x870 canvas, which
  MnnYoloDetector squashes to 320x320 (Bitmap.createScaledBitmap).
  ``detect`` reproduces this (square input, so squash == proportional).

Output is [1, 32, 2100] with cx, cy, w, h in 320-pixels followed by 28 class
scores; NMS is class-agnostic, like the phone-side detectors.

Only used to (a) locate real play-zone card corners and (b) provide the old
model baseline numbers. It never labels the new model's training targets.
"""

from __future__ import annotations

import cv2
import numpy as np

from common import LETTERBOX_PAD, MODEL_INPUT, OLD_LABELS, ROOT, letterbox, old_label_to_rank, unletterbox_boxes

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


def to_tensor(image_320_bgr: np.ndarray) -> np.ndarray:
    rgb = image_320_bgr[:, :, ::-1].astype(np.float32) / 255.0
    return np.ascontiguousarray(rgb.transpose(2, 0, 1)[None])


def preprocess(image_bgr: np.ndarray) -> np.ndarray:
    """Squash to 320x320 (phone hand-zone path; the 870x870 canvas is square)."""

    image = cv2.resize(image_bgr, (MODEL_INPUT, MODEL_INPUT), interpolation=cv2.INTER_LINEAR)
    return to_tensor(image)


def preprocess_letterbox(crop_bgr: np.ndarray):
    """Phone play-zone path: proportional resize + centred 114-grey padding."""

    image, scale, dx, dy = letterbox(crop_bgr, MODEL_INPUT, LETTERBOX_PAD)
    return to_tensor(image), scale, dx, dy


def decode_canvas(raw: np.ndarray, conf: float, iou: float) -> list[dict]:
    """Decode the raw head into detections in 320-canvas pixels (class-agnostic NMS)."""

    scores = raw[4:]
    best = scores.max(axis=0)
    class_ids = scores.argmax(axis=0)
    mask = best >= conf
    if not mask.any():
        return []
    cx, cy, bw, bh = raw[0][mask], raw[1][mask], raw[2][mask], raw[3][mask]
    boxes = np.stack([cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2], axis=1)
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


def decode(raw: np.ndarray, width: int, height: int, conf: float, iou: float) -> list[dict]:
    """Squash-path decode: canvas boxes scaled back by width/320, height/320."""

    sx, sy = width / MODEL_INPUT, height / MODEL_INPUT
    out = []
    for det in decode_canvas(raw, conf, iou):
        x0, y0, x1, y1 = det["box"]
        det = dict(det)
        det["box"] = (x0 * sx, y0 * sy, x1 * sx, y1 * sy)
        out.append(det)
    return out


def decode_letterbox(raw: np.ndarray, scale: float, dx: int, dy: int, conf: float, iou: float) -> list[dict]:
    """Letterbox-path decode: undo padding (minus dx/dy) and scale (divide) -> crop pixels."""

    out = []
    for det in decode_canvas(raw, conf, iou):
        box = unletterbox_boxes([det["box"]], scale, dx, dy)[0]
        det = dict(det)
        det["box"] = tuple(float(v) for v in box)
        out.append(det)
    return out


class OldDetector:
    """MNN session wrapper. Import of MNN happens here so other tools stay pure."""

    def __init__(self, threads: int = 6):
        import MNN

        self._mnn = MNN
        self._interpreter = MNN.Interpreter(str(MODEL_PATH))
        self._session = self._interpreter.createSession({"numThread": threads, "backend": "CPU"})
        outputs = self._interpreter.getSessionOutputAll(self._session)
        self._out_name = "output0" if "output0" in outputs else next(iter(outputs))

    def run_tensor(self, tensor_in: np.ndarray) -> np.ndarray:
        """Pure inference on an already-built [1,3,320,320] float32 tensor."""

        MNN = self._mnn
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

    def raw(self, image_bgr: np.ndarray) -> np.ndarray:
        return self.run_tensor(preprocess(image_bgr))

    def detect(self, image_bgr: np.ndarray, conf: float = 0.25, iou: float = 0.45) -> list[dict]:
        """Square-canvas (hand zone) path; boxes in input-image pixels."""

        return decode(self.raw(image_bgr), image_bgr.shape[1], image_bgr.shape[0], conf, iou)

    def detect_letterbox(self, crop_bgr: np.ndarray, conf: float = 0.25, iou: float = 0.45) -> list[dict]:
        """Play-zone path exactly as the phone does it; boxes in crop pixels."""

        tensor, scale, dx, dy = preprocess_letterbox(crop_bgr)
        return decode_letterbox(self.run_tensor(tensor), scale, dx, dy, conf, iou)
