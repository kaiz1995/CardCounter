# 数据集工具（w1/dataset）

格式契约见 `docs/rebuild/DATA-FORMATS.md`；本目录只做"切局 -> 旧模型检测 -> 真值草稿 -> 核对页"。

大文件全部在仓库外 `D:\CardCounterData\`：

| 目录 | 内容 |
|---|---|
| `detections/<gameId>.jsonl` | 100ms 采样流（table_visible、六区 white_ratio/still、检测框） |
| `crops/<gameId>/<seq>_<zone>.png` | 每手停稳时的区裁剪（核对页原图） |
| `review/<gameId>.html` + `review/<gameId>_<边界>.png` | 人工核对页、切局边界缩略图 |

## 流程

```powershell
python tools/dataset/cut_games.py          # -> datasets/games.json（+ 边界缩略图）
python tools/dataset/old_model_detect.py   # -> D:\CardCounterData\detections/*.jsonl
python tools/dataset/draft_truth.py --clamp-first-play   # -> datasets/truth/*.csv（+ crops）
python tools/dataset/check_truth.py --all  # 库存/单一点数/firstPlayMs 硬门槛
python tools/dataset/build_review.py       # -> D:\CardCounterData\review/*.html
python tools/dataset/check_truth.py --game g102658-3 `
    --truth experiments/results/truth_102658_516_536.csv --start 516000 --end 536500
```

## 关键阈值

- 牌桌：`(500,200)`、`(800,200)` 两点都是牌桌绿（`CaptureProbeService.gameTableVisible`）。
- 白区：采样带内三通道都 `>=190` 的占比；`>=10%` 视为有牌（`playZoneVisibility`）。
- 发牌：自己手牌上沿（y 450..475）低饱和亮块 `>=55%`；开牌动画压暗时仍可分辨。
- 结算：中央大暗板 `>50%` + 得分表蓝条 `>3%`（烧牌动画只有暗+红，不会误判）。
- 停稳：同一区连续 3 帧检测签名不变（100ms/帧）。
- 张数：分区分开标定，模型只给点数。
  - 其他五区：`46 + (n-1)*14.3` px（102658 516-536s 人工真值标定）。
  - SELF：`68 + (n-1)*22` px。161x161 裁剪上实测 1-5 张对应 68 / 90 / 112 / 132-137 /
    156 px；旧参数 46/14.3 是其他区标定的，套到 SELF 会把 3 张算成 6 张、5 张算成
    10-15 张，这是历史草稿单局 204-227 张的主因。
  - 扇形被区裁掉或牌距压缩时宽度法失效，改数牌角墨迹列簇（按实测牌距聚合，剔除 "10"
    被拆成两列的假间隔）。SELF 6 张以上会撑满裁剪，看牌面右上角张数角标人工确认
    （`SELF_WIDE_MANUAL`）。
- 模型与几何差 1 张：按几何补齐；差 2 张以上：`notes` 写 `needs_review`，张数取较可信的
  一个（列簇与模型一致时用列簇，否则用模型）。review 列按契约只有 AUTO/CONFIRMED/FIXED，
  待核对靠 notes 标记，核对页把这行标红。
- 一手出现两种普通点数（大王/小王/2 之外）按够级规则不可能：取出现最多的点数，notes 记
  `mixed_ranks`。
- 库存：4 副 198 张（`Rank.kt`）。同一局按时间累计，某点数超库存时从最不可信的一手扣掉
  超出张数并标 needs_review；全局 198 张由 `check_truth.py --all` 卡死。
- 同一手被重复检出（出牌/闷牌动画打断白区、对话框遮住牌区）：同区、点数集合互相包含、
  首尾相接、扇宽一致的事件合并成一手，notes 记 `merged_runs`。

## 已知限制

- 旧模型坐标是 320 画布像素（非 0..1），见回执实测。
- app 的 `MnnYoloDetector` 把裁剪直接拉伸到 320x320；本工具按派活单改等比 letterbox。
- 交贡/还贡/买牌与正式出牌共用同一套出牌区，`firstPlayMs` 是启发式；有宣点按钮时用宣点结束时刻修正。
- 点数只看旧模型投票，个别手会多点/少点，库存卡住时从最不可信的一手扣张（notes 留痕）；
  点数完全看不清的行 cards 留空、quantity 保留。
- 第一手出现早于 `firstPlayMs` 时，`draft_truth.py --clamp-first-play` 把边界夹到它出现
  之前 100ms，并保证 `prePlayEndMs <= firstPlayMs`。
