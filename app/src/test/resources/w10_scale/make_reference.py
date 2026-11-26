"""生成 w10_scale 的测试数据：原始整屏截图里手牌和一个出牌区用到的那一小块 + 训练/评测端（cv2）的预处理结果。

用法：python make_reference.py <整屏截图.png>      （例如 D:/GITHUB仓库/CardCounter/tmp_adb/screen_three4.png）

做法和 tools/training/common.py 一致（git show w3/training:tools/training/common.py）：
  整屏 cv2.INTER_AREA 缩到 1280x578 -> 手牌条带 (180,350)-(1050,490) 放进 870x870 黑底第 540 行起 -> cv2.INTER_LINEAR 到 320x320；
  出牌区 RIGHT_BOTTOM 取 (960,213) 141x70 -> 等比 INTER_LINEAR、边长 round()、居中整数偏移、四周填 114。
只保存缩放用得到的那几行几列的原始像素（上下左右各留 2 像素余量），不保存整屏，体积小。
输出：source_hand.rgb.gz / source_zone.rgb.gz（原始 RGB 字节）、ref_hand.rgb.gz / ref_zone.rgb.gz（320x320 参考）、meta.json。
"""

import gzip
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np

CANVAS_W, CANVAS_H, MODEL, PAD = 1280, 578, 320, 114
HAND = (180, 350, 1050, 490)  # left, top, right, bottom（画布坐标）
HAND_PAD_TOP = 540
ZONE = (960, 213, 141, 70)  # RIGHT_BOTTOM: left, top, width, height（画布坐标）
MARGIN = 2


def read_rgb(path: Path) -> np.ndarray:
    bgr = cv2.imdecode(np.fromfile(str(path), np.uint8), cv2.IMREAD_COLOR)
    if bgr is None:
        raise SystemExit(f"读不出截图: {path}")
    return np.ascontiguousarray(bgr[:, :, ::-1])


def source_box(width: int, height: int, left: int, top: int, right: int, bottom: int):
    """画布上 [left,right)x[top,bottom) 用到的源像素范围。"""
    x0 = max(0, math.floor(left * width / CANVAS_W) - MARGIN)
    x1 = min(width, math.ceil(right * width / CANVAS_W) + MARGIN)
    y0 = max(0, math.floor(top * height / CANVAS_H) - MARGIN)
    y1 = min(height, math.ceil(bottom * height / CANVAS_H) + MARGIN)
    return x0, y0, x1 - x0, y1 - y0


def save_gz(path: Path, array: np.ndarray) -> None:
    with gzip.GzipFile(str(path), "wb", mtime=0) as handle:
        handle.write(np.ascontiguousarray(array, np.uint8).tobytes())


def letterbox(img: np.ndarray) -> np.ndarray:
    h, w = img.shape[:2]
    scale = min(MODEL / w, MODEL / h)
    new_w, new_h = max(1, round(w * scale)), max(1, round(h * scale))
    resized = cv2.resize(img, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
    out = np.full((MODEL, MODEL, 3), PAD, np.uint8)
    dx, dy = (MODEL - new_w) // 2, (MODEL - new_h) // 2
    out[dy:dy + new_h, dx:dx + new_w] = resized
    return out


def main(path: Path) -> None:
    out = Path(__file__).parent
    raw = read_rgb(path)
    height, width = raw.shape[:2]
    area = cv2.resize(raw, (CANVAS_W, CANVAS_H), interpolation=cv2.INTER_AREA)

    left, top, right, bottom = HAND
    strip = area[top:bottom, left:right]
    side = right - left
    canvas = np.zeros((side, side, 3), np.uint8)
    canvas[HAND_PAD_TOP:HAND_PAD_TOP + strip.shape[0]] = strip
    ref_hand = cv2.resize(canvas, (MODEL, MODEL), interpolation=cv2.INTER_LINEAR)

    zl, zt, zw, zh = ZONE
    ref_zone = letterbox(area[zt:zt + zh, zl:zl + zw])

    meta = {"screenWidth": width, "screenHeight": height, "hand": list(HAND), "zone": list(ZONE)}
    for name, box in (("hand", source_box(width, height, left, top, right, bottom)),
                      ("zone", source_box(width, height, zl, zt, zl + zw, zt + zh))):
        x, y, w, h = box
        save_gz(out / f"source_{name}.rgb.gz", raw[y:y + h, x:x + w])
        meta[f"{name}Source"] = [x, y, w, h]
    save_gz(out / "ref_hand.rgb.gz", ref_hand)
    save_gz(out / "ref_zone.rgb.gz", ref_zone)
    (out / "meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(json.dumps(meta))


if __name__ == "__main__":
    main(Path(sys.argv[1]))
