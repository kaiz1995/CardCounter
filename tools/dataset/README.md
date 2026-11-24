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
python tools/dataset/draft_truth.py        # -> datasets/truth/*.csv（+ crops）
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
- 张数：几何独立估算——牌扇宽度 `46 + (n-1)*14.3` px；`>=100px` 的宽扇形改用牌角墨迹列簇。

## 已知限制

- 旧模型坐标是 320 画布像素（非 0..1），见回执实测。
- app 的 `MnnYoloDetector` 把裁剪直接拉伸到 320x320；本工具按派活单改等比 letterbox。
- 交贡/还贡/买牌与正式出牌共用同一套出牌区，`firstPlayMs` 是启发式；有宣点按钮时用宣点结束时刻修正。
- 点数只看模型投票；张数由几何决定，两者不一致时按最常出现的 Rank 补齐/裁剪（notes 留痕）。
