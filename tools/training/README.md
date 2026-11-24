# tools/training — 15-rank YOLO from real card art

Pure-Python tooling that turns the whitelisted recordings into a synthetic
rank dataset, trains `yolo11n` at 320, exports ONNX and evaluates on real
crops. Big data (sprites, datasets, runs) lives in `D:\CardCounterData\` and
never enters git.

## Hard rules

* `common.ensure_allowed` is the only way to open a video. It rejects the
  blind set (`202234`, `210429`) and the tuning set (`164333`) by name and
  rejects anything outside `9局/` + the six dev/clip files.
  `test_whitelist.py` proves the gate (10 cases, no video is decoded when the
  path is rejected).
* Class ids follow `docs/rebuild/DATA-FORMATS.md` §4 (`0 BIG_JOKER … 14 THREE`).
* Model contract: `[1,3,320,320]` float32 RGB `0..1`, letterbox pad `114/255`;
  output `[1,19,2100]`, no NMS, opset 17.

## Pipeline

```powershell
python tools/training/test_whitelist.py                 # gate proof
python tools/training/extract_sprites.py                # sprites + negatives + real hand frames
python tools/training/synthesize.py --train 5000 --val 800
python tools/training/synthesize.py --align             # stage-B list (real frames x15)
python tools/training/train.py speed                    # 3 epochs, CPU projection
python tools/training/train.py synth --epochs 22
python tools/training/train.py align --epochs 4
python tools/training/export.py --weights .../stage_b/weights/best.pt
python tools/training/eval_crops.py --onnx models/poker_rank15_yolo11n_320.onnx
```

## Where the labels come from

| zone | truth | how |
|---|---|---|
| hand | `g2-analysis/rank-ground-truth.md` | the game sorts the hand by rank, so card *k* in reading order has a known rank once the glyph positions are measured |
| play | none in the recordings | play-scale corners are resampled from the truth-labelled hand corners (measured 0.50x outside / 0.77x own seat); the old model's classes are **not** used, they were wrong on stacked plays |
| negatives | none needed | card-free patches (felt, overlays, buttons, borders) plus real empty play-zone crops |

Hand crops for validation come from the three 9局 clips that never produced a
single sprite or training tile (`145608`, `145637`, `145704`).
