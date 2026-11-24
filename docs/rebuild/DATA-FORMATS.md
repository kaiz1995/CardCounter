# 数据格式契约（切局/真值/训练/离线评测共用）

改这里的格式必须同时改 `tools/` 与 `:eval` 的解析器。

## 1. 视频与集合划分

按录制日期整段划分，一段长录屏里的所有牌局归同一集合：

| 集合 | 视频（`记牌器开发/source/`） | 用途 |
|---|---|---|
| dev | SVID_20260810_102658_1、SVID_20260810_143651_1、SVID_20260811_075639_1 | 开发、训练素材、修 bug |
| tuning | SVID_20260813_164333_1 | 调阈值、选模型 |
| blind | SVID_20260815_202234_1、SVID_20260815_210429_1 | 只在参数冻结后跑一次终评；训练与调参脚本禁止读取 |
| clip | `9局/` 全部 9 段、source 下 SVID_20260727_201930_1、SVID_20260728_135222_1、SVID_20260811_214558_1 | 开局手牌片段，不是完整牌局；可用于训练素材 |

## 2. `datasets/games.json`

```json
{
  "schemaVersion": 1,
  "games": [
    {
      "gameId": "g102658-1",
      "video": "source/SVID_20260810_102658_1.mp4",
      "videoSha256": "...",
      "split": "dev",
      "startMs": 0,
      "endMs": 0,
      "prePlayEndMs": 0,
      "firstPlayMs": 0,
      "complete": true,
      "selfHand": "KING:6;QUEEN:4",
      "selfHandCount": 33,
      "review": "AUTO",
      "notes": ""
    }
  ]
}
```

- 所有时间为该视频内的绝对毫秒。`startMs` 发牌开始，`endMs` 结算画面出现，`prePlayEndMs` 交贡/还贡/买 3 买 4 全部结束，`firstPlayMs` 第一手正式出牌出现。
- `complete=false` 表示录屏只覆盖半局（开头或结尾被截）。
- `gameId` = `g<视频时间戳后 6 位>-<序号从 1 起>`。

## 3. 整局真值 `datasets/truth/<gameId>.csv`

UTF-8，无 BOM，表头固定：

```text
game_id,event_seq,zone,appear_ms,stable_ms,clear_ms,quantity,cards,tag,review,notes
```

- 只记正式首出之后的出牌；过牌不记行。
- `zone`：`TOP | LEFT_TOP | LEFT_BOTTOM | RIGHT_TOP | RIGHT_BOTTOM | SELF`（与 `PlayZone` 枚举同名）。
- `appear_ms` 牌首次出现；`stable_ms` 牌停稳；`clear_ms` 牌离开或被下一手替换。
- `quantity` 这一手的张数（必填）。
- `cards` 点数向量，`Rank` 枚举名加张数，分号分隔、按 `Rank` 枚举顺序：`BIG_JOKER:1;KING:6`。点数看不清时留空，但 `quantity` 仍必填。
- `tag`：`NORMAL | OCCLUDED | REPLACE_DIRECT | ANIMATION`（`REPLACE_DIRECT` = 同一区没清空就换成下一手）。
- `review`：`AUTO`（机器预标）| `CONFIRMED`（人工确认无误）| `FIXED`（人工改过）。

## 4. 模型契约（新模型）

- 类别 15 个，类别号 = `Rank` 枚举顺序：0 BIG_JOKER、1 SMALL_JOKER、2 TWO、3 ACE、4 KING、5 QUEEN、6 JACK、7 TEN、8 NINE、9 EIGHT、10 SEVEN、11 SIX、12 FIVE、13 FOUR、14 THREE。
- 输入 `[1,3,320,320]` float32，RGB，值域 0..1，等比缩放后 letterbox 居中，填充值 114/255。
- 输出 YOLO 原始头 `[1, 4+15, 2100]`（cx, cy, w, h 为 320 输入画布像素，后接 15 类分数），图内不做 NMS。
- 格式 ONNX（opset 17），电脑端用 ONNX Runtime Java、手机端用 ONNX Runtime Android 跑同一个文件。

## 5. 逐帧观察 JSONL（离线评测输入）

`:eval` 的 `--obs-dir` 下每局一个 `<gameId>.jsonl`，UTF-8，一行一个 `FrameObservation`：

```json
{"ts_ms":516146,"table_visible":true,"zones":{"TOP":{"card_visible":true,"still":true,"dets":[{"rank":"FOUR","conf":0.89,"cx":0.5,"cy":0.42,"w":0.12,"h":0.22}]}}}
```

- `ts_ms`：该视频内的绝对毫秒。行不要求按时间排序，评测工具会按 `ts_ms` 升序回放。
- `table_visible`：整张牌桌可见（手机 `gameTableVisible`）。
- `zones`：键与 `PlayZone` 枚举同名；缺省的区表示该帧没有该区观察。
- `card_visible`：白色牌面面积达到可见阈值（`hasVisibleCardArea`）；`still`：与上一次采样相比画面静止，只用于停稳判定。
- `dets`：该帧该区的检测框数组；省略或为 `null` 表示这一帧没做识别（对应 `ZoneObservation.detections = null`）。
- `rank`：`Rank` 枚举名；`conf` 为 0..1；`cx/cy/w/h` 是 320×320 模型输入画布上的 0..1 小数。
- 检测框视为已完成 NMS（IoU 0.45）与 `conf >= 0.25` 过滤，几何来自手机 `publicPlayBitmap` 的裁剪与 letterbox；评测工具不重复做 NMS 或阈值过滤。
