# CardCounter v2 实施方案（合成版，待拍板）

来源：主控草稿 + Gemini 4 Neon 独立方案，逐条对比后合成；分歧以代码和本机实测为准。
起点：分支 `rebuild/v2-base`（= `origin/rebuild-event-pipeline` b94c83b），87/87 单测通过，可打包。

## 1. 已核实的事实（2026-11-24）

- `9局/` 是 9 段 14~20 秒的开局手牌片段，0 局完整对局；`dataset-manifest.json` 里“1 段≈1 局”要更正。
- 完整对局只在 `source/` 的 6 段长录屏里（102658 887s、143651 867s、202234 315s、210429 246s、164333 222s、075639 202s，合计约 45 分钟）；具体含几局待切分核实。
- 整局真值为 0；现有真值只有 `truth_102658_516_536.csv` 一段 20 秒、35 手。
- `codex/optimization-20260913` 的 12 项修复挑入时 6 个文件冲突，只能手工移植。
- 已确认的未修缺陷：`FirstPlayBoundaryGate.update` 完全不用 `signals`（交贡/买牌期间手牌一稳就可能误锁首出）；`OverlayService.kt:51` 每次刷新都新建 `SessionRepository`（绕过单写者）。
- `isPublicRankBand(centerY < 0.85f)` 假设归一化坐标，而闭源 MNN 输出单位未核实，需实测。
- 本机：无 GPU；有 ultralytics、onnx、onnxruntime 1.26、MNN、cv2；没有 tensorflow / onnx2tf（导不出 TFLite）；没接手机。

## 2. 两份方案一致的部分（直接采用）

1. 唯一主干 = `rebuild-event-pipeline`，不再开平行大改分支；optimization 分支只手工移植有效修复。
2. 纯 Kotlin 部分抽成 `:core`（JVM 模块），手机和离线评测跑同一套识别、状态机和账本代码；`:core` 里写不进安卓依赖，由 Gradle 强制。
3. 先有整局离线评测，再改识别；没有评测数字对比，不合入任何识别/状态机改动。
4. 用游戏真实牌面素材程序合成带精确标注的数据，重训 15 类（按点数）专属轻量 YOLO，再加少量真实帧微调。
5. 加够级牌型规则约束：违规的一手不入账，进重试/暂停。
6. 模板、单座位特判、双层去抖只在新模型整局评测达标后再删。
7. 盲测局由主控保管，只在参数冻结后跑一次。
8. 每块独立 worktree，写文件范围互不重叠。

## 3. 采纳 Gemini 的部分

- 分波次编排和冻结接口在先（`CanonicalFrame`、`DetectorAdapter` 坐标统一归一化、`ZoneObservation`、整局真值 CSV、检测结果 JSONL 缓存）。
- 单独做一轮“旧模型整局基线 + 误差归因清单”，作为新模型的对照和补样本靶子。
- 训练前先跑 3 轮测速，再定数据规模。
- 验收铁律：先查改动是否越界；声称修了 bug 的，回滚那一行必须有测试变红。
- 状态机里处理“同一区未清空直接换牌”，真值单设 `REPLACE_DIRECT` 标签。

## 4. 不采纳的部分和理由

| Gemini 主张 | 不采纳理由 | 改为 |
|---|---|---|
| 新模型统一走 TFLite | 本机没有 tensorflow/onnx2tf，导不出 TFLite；ONNX 可直接导出，且 ONNX Runtime 在电脑（Java）和手机（Android）上都能跑，离线评测与真机是同一个模型、同一套后处理 | 手机端换成官方 ONNX Runtime Android；新模型达标后删除闭源 MNN `.so`、旧包名 `com.aijpq.pokerrecorder`、两份 10MB 旧模型和 tensorflow-lite 依赖 |
| 做 MNN→TFLite→DISABLED 双后端探针与降级链 | 两个旧后端最终都删，降级链没有事故依据 | 只保留 ONNX→DISABLED（进暂停）；旧模型只为跑基线记录契约 |
| 视频切局和真值初标交给 Gemini Hydrogen | Hydrogen 派活规则是不运行命令，切局要跑 ffmpeg/脚本；截图发云端识别要你同意 | 切局与真值初标交 Codex，在本机用旧模型 + 规则预标注，你只做核对 |
| 测试里 `20f` 证明坐标单位冲突 | 那是 NMS 单测，与单位无关，不算证据 | 坐标单位用本机 Python MNN 在固定截图上实测确定 |
| “APK 降到约 15MB”“约 10~14 局” | 推测，未核实 | 以实测为准，不作承诺 |

## 5. 分块与分工

| 块 | 内容 | 执行方 | 依赖 |
|---|---|---|---|
| W0 主干收口 | 手工移植有效修复（Overlay 单实例与隐藏态、handYoloBitmap 越界、删 `detectFormalPlays` 与 `observeSelfHandCount` 空转）；建 `:core`/`:eval` 骨架并迁入 domain/storage；冻结接口；加 GitHub 自动检查（单测 + 打包）；manifest 更正 | 主控 | 无 |
| W1 切局与真值 | `tools/` 切局审计脚本、整局切分与 dev/tuning/blind 分配、真值 CSV、核对页面（每手附裁剪图）；旧模型（Python MNN）跑检测 JSONL 并实测坐标单位 | Codex 会话 1 | W0 接口 |
| W2 离线评测 | `:eval` 命令行：读真值 + JSONL 或直接调 ONNX Runtime → `:core` 流水线 → 指标（精确/召回、漏/重/误扣、张数、点数混淆矩阵、首出误触发、暂停率、终局剩余误差），阻断项非零退出 | Codex 会话 2 | W0 |
| W3 合成数据与训练 | 从 `9局/` 与 dev 局抠 15 点数牌角，合成手牌区与六区样本（含过牌字样负样本），3 轮测速后训练 YOLO11n 320，导出 ONNX，按类报告（大王召回、A 误报） | Codex 会话 3 | 无，立即开始；严禁读 blind 局 |
| W4 够级牌型规则 | `GoujiPlayValidator` 及测试（同点 N 张、挂王/挂 2、库存上限） | Gemini 4 Hydrogen（只写不跑，测试主控跑） | W0 接口 |
| W5 拆上帝类 + 首出/六区状态机 | `OpeningStateMachine`（修忽略视觉信号）、`ZoneStateMachine`（并掉双层去抖）、`CandidateResolver`、`RecognitionPipeline` 进 `:core`；`CaptureProbeService` 只留采集与驱动（≤350 行） | Sonnet 5.5 | W0；W2 用于验收 |
| W6 旧模型整局基线 | 用 W1 真值 + W2 跑旧模型，出成绩单和误差归因 | 主控 | W1、W2、W5 |
| W7 新模型接入与减法 | ONNX Runtime Android 接入；删模板、特判、旧去抖、MNN/TFLite；新旧对比；盲测跑一次 | Sonnet 5.5 | W3、W5、W6 |
| W8 文档收口 | README 单一入口，旧计划/进度文档归档 | Codex | W7 |
| W9 真机验收 | Mate 60 上 14 场景 + 30 分钟长稳 + 证据包 | 你 + 主控 | W7 |

第一波并行：W1、W2、W3、W4（W0 完成后立即派）；第二波：W5、W6；第三波：W7、W8；最后 W9。

## 6. 验收标准

- 每块：派活单里写明文件范围和验收命令；主控读改动、亲自跑 `:core:test :eval:test :app:testDebugUnitTest :app:assembleDebug`；越界改动打回。
- 模型：真实验证裁剪上大王召回 ≥ 98%、A 误报 < 0.5%；但放行只看整局评测。
- 离线放行（A2，沿用原方案 7.2/7.3）：误扣 = 0、重复扣 = 0、首出误触发 = 0、未决事件静默丢失 = 0；召回 ≥ 95%、精确 ≥ 99%、张数 ≥ 99%、点数完全匹配 ≥ 95%；盲测局不参与调参。
- 真机（A3/A4）：原方案 Phase 6 的 14 个场景、30 分钟无崩溃，推理延迟实测后冻结。

## 7. 需要你做的

1. W1 产出首批整局真值后，花约半小时对着裁剪图核对。
2. 第二波期间在 Mate 60 上补录 15~20 局完整对局（尽量覆盖交贡/还贡、买 3 买 4、大小王、频繁过牌）。
3. 最后接上手机做真机验收。

## 8. 风险

- 完整局数不够（最大风险）：现有素材整局数未知，盲测局可能凑不够，A2 结论先标“初版”，补录后正式放行。
- 合成数据与真实录屏有差距：素材全部从真实录屏抠，并混入真实标注帧；以整局评测为准。
- 本机 CPU 训练时长未知（推测数小时）：先测速；若要租云端显卡，先报价再问你。
- ONNX Runtime 在 Mate 60 上的速度未实测（推测单帧几十毫秒内）：推理只在出牌区停稳时触发，频率低。
