# MODEL-V2 — 15 点数轻量模型（真实牌面合成数据训练）

日期：2026-11-24　分支：`w3/training`　基线：`bae6b84`

## 1. 产物

| 项 | 值 |
|---|---|
| 权重 | `D:\CardCounterData\runs\train\stage_b\weights\best.pt`（不入库） |
| ONNX | `models/poker_rank15_yolo11n_320.onnx`（10,450,050 字节，**10.0 MiB > 8 MB，按约定不入 git**） |
| SHA256 | `4c14427fab8aea5c7424260d3a3dc70ae8985c2ca372e0c292bd58cfc25321ea` |
| ONNX opset | 17（单一 import） |
| 输入 | `images`：`[1,3,320,320]` `tensor(float)` |
| 输出 | `output0`：`[1,19,2100]` `tensor(float)`，图内无 NMS |
| 类别 | 15，类别号 = `Rank` 枚举顺序（`0 BIG_JOKER … 14 THREE`，见 DATA-FORMATS §4） |

输入/输出形状由 `tools/training/export.py` 用 onnxruntime 实测校验（不是读配置），探针推理输出全部有限，
cx 范围 3.07–317.4 px，说明坐标确实是 320 画布像素。

## 2. 素材与合成集

素材全部来自白名单录像（blind 202234/210429、tuning 164333 由 `common.ensure_allowed` 硬拒绝，
`tools/training/test_whitelist.py` 10 条用例覆盖）。真值来源只有一种：`g2-analysis/rank-ground-truth.md`
的逐点数张数向量 + 牌局按点数排序 —— 点数已知，只需在帧上定位牌角，所以合成标签是精确的。

### 2.1 牌角 sprites

| 点数 | 手牌区 | 出牌区(outside) | 出牌区(self) | 点数 | 手牌区 | 出牌区(outside) | 出牌区(self) |
|---|---|---|---|---|---|---|---|
| BIG_JOKER | 12 | 6 | 6 | NINE | 33 | 24 | 27 |
| SMALL_JOKER | 12 | 4 | 7 | EIGHT | 25 | 18 | 20 |
| TWO | 43 | 30 | 31 | SEVEN | 28 | 19 | 19 |
| ACE | 25 | 24 | 23 | SIX | 48 | 34 | 40 |
| KING | 31 | 24 | 24 | FIVE | 30 | 25 | 25 |
| QUEEN | 38 | 32 | 34 | FOUR | 25 | 21 | 21 |
| JACK | 36 | 32 | 32 | THREE | 9 | 9 | 9 |
| TEN | 48 | 41 | 42 | | | | |

合计 1146 块（手牌 443 + 出牌 703）。负样本另有 304 张无牌图块（牌桌、水印、头像、按钮、计数板、边框）
与 120 张真实空出牌区裁剪。每类拼图在 `D:\CardCounterData\sprites\_mosaic\<RANK>.png`，已逐张人眼抽查。

实测牌面尺度：手牌点数位约 27×30 px、横向间距 42 px；出牌区点数字高 15 px（其他座位）/ 23 px（自己），
牌面宽度 44 px / 68 px，叠放间距实测 outside 14.3–14.5 px、self 21.8 px（`sprites/summary.json`）。

**假设（重要）**：出牌区 sprites 不是从真实出牌帧上打标签，而是把真值手牌牌角按 0.50（outside）/0.77（self）
重采样后贴到按实测尺寸生成的白色牌面上。原因是旧模型在叠放牌上的类别不可用：用它给真实出牌区打标签后，
`TEN` 目录里装的是 9、J、K，`ACE` 目录里装的是 4、K、2、A（拼图证据见 `sprites/_mosaic`），
而 10×15 px 的点数位用模板匹配也分不开（6 只排到 0.30–0.34 相似度）。

### 2.2 合成集

`synthesize.py` 复刻 app 的裁剪：出牌区 1–12 张同点数叠放（含少量挂大小王/挂 2）、手牌区两排 12–18 张
密集叠放后再做 870×870 画布 + 320 缩放（画布其余部分是黑的，与 `handYoloBitmap` 一致）、纯负样本。
增广含亮度/对比 ±14%、JPEG 55–95、模糊、噪声、半透明淡入帧（15%）。

| 集合 | 图像 | 框 |
|---|---|---|
| train | 5000 | 57,012 |
| val | 800 | 8,404 |

训练集每类框 3,611–4,614，分布均衡。真值手牌帧另有 17 张（来自 6 个训练 clip），只在 stage B 以 ×15 过采样加入。

## 3. 训练

机器：Intel Ultra 7 155H（16 核 22 线程），CPU-only，32 GB，无 GPU 包。

| 阶段 | 轮数 | 用时 | val mAP50 | val mAP50-95 |
|---|---|---|---|---|
| 测速（yolo11n, imgsz=320, batch=32） | 3 | 958.6 s（319.5 s/epoch） | 0.711 | 0.487 |
| stage A 合成集 | 12 | 4528.9 s（75.5 min） | 0.880 | 0.681 |
| stage B +真实手牌帧 ×15 | 3 | 1168.2 s（19.5 min） | 0.875 | 0.667 |

测速投影 3 小时内只能跑 31 epoch。首次按 22 epoch 启动，实测 390–440 s/epoch（同机另有进程占用），
第 3 epoch 后投影会超过 3 小时，因此按约定降轮数：用该 3 epoch 的 `best.pt` 续训 12 epoch，再对齐 3 epoch。
训练实际总时长约 116 min（含被中止的 21 min），未超 3 小时预算。batch=64 实测更慢（399.7 s/epoch），未采用。

## 4. 前后处理（与 DATA-FORMATS §4 一致）

* 出牌区：`PlayZone.yoloCrop` 裁剪 → 等比缩放到长边 320 → 居中补 114 像素。
* 手牌区：`handYoloBitmap`（x 180–1050、y 350–490 贴到 870×870 画布 y=540 起）→ 直接缩放到 320×320。
* 归一化：RGB、`/255`、NCHW float32。
* 解码：对 2100 个 anchor 取 15 类最大分，`conf ≥ 0.25` 后做类别内 NMS（IoU 0.45）。图内不含 NMS。

## 5. 真实裁剪评测（白名单、未参与训练）

真值：`145637`（32 张）与 `145704`（34 张）两个 clip 的开局手牌，按真值向量 + 帧上实测牌角位置对齐，
不使用任何模型输出当真值；取真值标注时刻前 0.8 s 到后 0.5 s 内的 240 帧/clip，共 **480 张真实手牌裁剪**
（再晚画面就开始进贡/首出，手牌已经变了）。
匹配：同类且 (IoU ≥ 0.3 或中心距 ≤ 半个点数位)，conf 0.25。

| 点数 | 新 TP | 新 FP | 新 FN | 新 P | 新 R | 旧 P | 旧 R |
|---|---|---|---|---|---|---|---|
| BIG_JOKER | 240 | 0 | 0 | 1.000 | 1.000 | 1.000 | 1.000 |
| SMALL_JOKER | 480 | 0 | 0 | 1.000 | 1.000 | 1.000 | 1.000 |
| TWO | 1440 | 0 | 0 | 1.000 | 1.000 | 0.857 | 1.000 |
| ACE | 2400 | 0 | 0 | 1.000 | 1.000 | 1.000 | 1.000 |
| KING | 960 | 0 | 0 | 1.000 | 1.000 | 1.000 | 1.000 |
| QUEEN | 1200 | 0 | 0 | 1.000 | 1.000 | 1.000 | 1.000 |
| JACK | 960 | 0 | 0 | 1.000 | 1.000 | 1.000 | 1.000 |
| TEN | 960 | 0 | 0 | 1.000 | 1.000 | 1.000 | 0.750 |
| NINE | 480 | 0 | 0 | 1.000 | 1.000 | 1.000 | 0.500 |
| EIGHT | 720 | 0 | 0 | 1.000 | 1.000 | 1.000 | 0.667 |
| SEVEN | 1440 | 0 | 0 | 1.000 | 1.000 | 1.000 | 0.833 |
| SIX | 1680 | 0 | 0 | 1.000 | 1.000 | 1.000 | 1.000 |
| FIVE | 1920 | 0 | 0 | 1.000 | 1.000 | 1.000 | 0.830 |
| FOUR | 480 | 0 | 0 | 1.000 | 1.000 | 1.000 | 0.500 |
| THREE | 480 | 0 | 0 | 1.000 | 1.000 | 1.000 | 1.000 |

* 大王召回 **100%**，小王召回 **100%**（新）／1.000、1.000（旧）。
* A 误报率 **0%**（2400 个 A 全中、0 个误报）；旧模型在同样本上也是 1.000，即这批数据里没有复现出 A 误报。
* 单张 CPU 推理（onnxruntime 单线程 / MNN 单线程，同机）：新 **21.0 ms**（p95 35.4），旧 **26.9 ms**（p95 30.2）。
* **样本量限制**：480 张裁剪来自 2 手固定手牌，帧与帧高度相关，有效的独立样本是 2 手。这里的 1.000 只说明
  「这两手牌在整段稳定窗口内没有读错」，不等于全牌局泛化。

`145608` 被排除：真值向量写的是 2 张大王 + 1 张小王，但 10 s 后画面只剩 1 大 1 小（34→33 张），
用它计分会把「真值与画面不符」算成模型错误。

### 5.1 出牌区 spot check（真实 app 截图，2688×1216 → 1280×578）

| 截图 | 区 | 期望 | 新模型 | 旧模型 |
|---|---|---|---|---|
| `current_a_right_bottom.png` | RIGHT_BOTTOM | A | A×2（无 A 误报） | A×2 |
| `current_left_bottom_7x4.png` | LEFT_BOTTOM | 7×4 | 7×4 ✅ | 7×5（多 1） |
| `current_right_top_7x2_overcount.png` | RIGHT_TOP | 7×2 | 7×2 ✅ | 7×1（少 1） |
| `current_pass_and_sixes.png` | SELF | 过牌 | 0 检测 ✅ | 0 检测 ✅ |

### 5.2 空出牌区误报

200 张真实出牌区裁剪（牌面白色像素 < 2%，与两个模型无关的判据）：新 0 张有检测、旧 0 张有检测。
说明这一批空区没有误报，但也没有复现历史上的 A 过报 —— 历史问题更可能出在叠放牌的类别判定，而不是空区。

## 6. 已知限制 / 下一步

1. 出牌区 sprites 是重采样而非真实出牌帧，叠放牌的「半遮挡点数字形」没有被真实样本覆盖；真机叠放牌
   （4 张以上）的召回只能靠现场验证。
2. 手牌区在 320 输入里点数位只有约 10×11 px，能读出这两手牌不代表换桌/换皮肤也稳。
3. 真实评测只有 2 手牌、且来自训练素材同源的 UI，`145608` 因真值与画面不符被剔除；要更硬的结论需要
   多局盲测（blind 20260815 两个文件尚未使用，可在参数冻结后跑一次）。
4. ONNX 10.0 MiB 超过 8 MiB 入库阈值；需要入库时可再出 fp16 变体（会改变输入 dtype，需同步契约）。

## 7. 复现

```powershell
python tools/training/test_whitelist.py
python tools/training/extract_sprites.py
python tools/training/synthesize.py --train 5000 --val 800
python tools/training/synthesize.py --align
python tools/training/train.py speed
python tools/training/train.py synth --epochs 12
python tools/training/train.py align --epochs 3
python tools/training/export.py
python tools/training/eval_crops.py --onnx models/poker_rank15_yolo11n_320.onnx
```

大数据全部落在 `D:\CardCounterData\`（`sprites/`、`synth/`、`runs/`），不入 git。
评测明细：`runs/eval/eval.json`；导出事实：`runs/export.json`；测速：`runs/speed.json`。
