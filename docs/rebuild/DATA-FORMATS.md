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
- `gameId` = `g<视频时间戳后 6 位>-<序号从 1 起>`，整个文件内不能重复。
- `split` 必填，只能是 `dev | tuning | blind | clip`（小写、无空格）；写错直接报错，防止盲测局绕过拦截。
- 除 `videoSha256`、`review`、`notes` 外的字段都必填，类型严格：时间是非负整数，`complete` 是布尔，`selfHand` 是字符串，不接受 `"true"`、`1.5`、`null` 这类近似值（`selfHandCount` 允许的 `null` 见下条）。
- `selfHandCount` 为正整数，或显式写 `null` 表示手牌张数未知（字段不能省略）。`selfHand` 合计小于它时视为点数不完整，离线评测不确认手牌（横条按未确认算），终局剩余牌标为不可算（`self_hand_unknown`）。
- `selfHandCount` 为 `null` 时 `selfHand` 必须写 `""`。离线评测同样不确认手牌，外面出牌照常记，终局剩余牌标为不可算（`self_hand_unknown`）。只有 `selfHand` 点数合计等于 `selfHandCount` 时才确认手牌；自己那一区的出牌不参与对齐，也不进账。
- 离线评测只回放 `[prePlayEndMs, endMs]` 内的观察帧；字段为 0 表示这一端不限。

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
- `event_seq` 为正整数且局内不重复；`appear_ms` 不能为负。
- 引号按 RFC 4180：双引号只能包裹整个字段，字段内的引号写成 `""`；未闭合的引号或未包裹字段里的引号都报错。
- 外面区有 `cards` 留空的行时，终局剩余牌标为不可算（`truth_ranks_incomplete`）。

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
- 一行只能有一个 JSON 对象；`ts_ms` 为非负整数，`table_visible` 为布尔，`zones` 必填且是对象（没有任何区时写 `{}`）。
- `zones`：键与 `PlayZone` 枚举同名；缺省的区表示该帧没有该区观察，评测不把它当成“该区没牌”，不推进清桌计数。
- `card_visible`、`still` 都必填：`card_visible` 白色牌面面积达到可见阈值（`hasVisibleCardArea`）；`still` 与上一次采样相比画面静止，只用于停稳判定。
- `dets`：该帧该区的检测框数组；省略或为 `null` 表示这一帧没做识别（对应 `ZoneObservation.detections = null`）。
- `rank`：`Rank` 枚举名；`conf` 为 0..1；`cx/cy/w/h` 是 320×320 模型输入画布上的 0..1 小数；`NaN` 等非有限数报错。
- 检测框视为已完成 NMS（IoU 0.45）与 `conf >= 0.25` 过滤，几何来自手机 `publicPlayBitmap` 的裁剪与 letterbox；评测工具不重复做 NMS 或阈值过滤。
