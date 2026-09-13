# 记牌器自主优化总控（2026-09-13）

> 本文件是本次自主优化的**唯一总控入口**。目标、边界、验收标准、派发清单、审查纪律全部固化在此。
> 工作分支：`codex/optimization-20260913`（基线 `main` = `dda6b45`）
> 检查时间：2026-09-14 08:00

---

## 一、宏观目标

**让记牌器能够准确、及时地记录牌局。**

拆成三个可验证的子目标：

| # | 子目标 | 可验证形式 |
|---|---|---|
| G-A | **准确**：外面剩余数不出错 | 同一局内 `OutsideRemaining` 与实际不符的次数 = 0 |
| G-B | **及时**：一手牌结束到入账的延迟可接受 | 出牌动作完成 → 悬浮窗数字更新，延迟 ≤ 1.5s |
| G-C | **不丢不乱**：不重复入账、不静默丢牌 | 单局 `FORMAL_PLAY` 事件数 = 实际出牌手数 |

## 二、边界（不可越界）

**允许动：**
- `app/src/main/java/com/example/cardcounter/**` 全部源码
- `app/src/test/**` 单测
- 本文件、`plans/optimization-*.md`、`findings.md`、`progress.md`、`task_plan.md`

**禁止动：**
- ❌ `main` 分支（只在自己分支干活，验收后再由用户决定合并）
- ❌ `releases/**` 下的 APK / source.zip 二进制（除非明确要求重打包）
- ❌ `app/src/main/java/com/aijpq/pokerrecorder/service/MNNYoloDetector.java`（第三方来源，无源码可改）
- ❌ 模型文件 `poker_yolo11_320.mnn`、`app/src/main/assets/**`
- ❌ 视觉模板 PNG 资源（`res/drawable-nodpi/rank_*.png`）——识别精度问题的根因是模板/坐标标定，属独立课题，本次不动

**关键约束：本次优化不追求识别精度提升。** 识别层（模板匹配、YOLO 阈值、坐标标定）的调优需要真机真值集，当前只有 9 局样本，盲目调参违反已有教训（见 `findings.md` 2026-07-29 条目）。本次聚焦**架构缺陷、逻辑 bug、性能事故**——这些改完能让现有识别能力被正确使用。

## 三、验收门槛（每阶段必须全过）

1. `./gradlew testDebugUnitTest` — **全部通过，0 fail / 0 error**
2. `./gradlew assembleDebug` — **BUILD SUCCESSFUL**，产出 APK
3. 新增/修改的逻辑必须有对应单测（纯函数优先，不依赖 Android 框架）
4. `git diff` 审查：无调试残留、无 `println`、无注释掉的代码
5. 每个提交单独可回滚，提交信息说清「改了什么 + 为什么」

## 四、问题清单（12 项，全仓扫描确认）

### P0 — 直接导致记牌错误

| ID | 问题 | 位置 | 证据 |
|---|---|---|---|
| **P0-1** | `withinConservation()` 恒真失效 | `GameReducer.kt:119-123` | `outsidePlayedCount + outsideAdded + selfPlayedCount + (locked - selfPlayedCount) <= 198` — `selfPlayedCount` 被代数消掉，退化为 `outsidePlayedCount + outsideAdded + locked <= 198`。正常对局中自己手牌本就是从 198 里来的，此式**永不触发**。注释声称「替代 clamp」，实际不拦任何事。 |
| **P0-2** | `FirstPlayBoundaryGate` 忽略视觉信号 | `CaptureProbeStatus.kt:119-129` | `update(signals, hasStableHand)` 的 `signals` 参数**在函数体内从未引用**。开局边界实际只依赖 `hasStableHand`（稳定手牌样本 ≥2）。而 `openingSignals()` 每帧做绿色检测 + 720 次 `isWhite` 采样（`CaptureProbeService.kt:374-384`），**结果算出后丢弃**，纯浪费 CPU。 |
| **P0-3** | `detectYoloCards` 用 `minOf` 聚合置信度 | `CaptureProbeService.kt:652` | `detections.minOfOrNull { it.confidence }` 取**最小**置信度作为整批牌 confidence。12 张牌里 1 张 0.26 → 整批 0.26 → `canFastConfirmPlay`（要求 ≥0.60，`PlayZone.kt:138`）**永远返回 false**，快速确认路径失效。 |
| **P0-4** | `SessionRepository` 每帧重建 + 全量 JSON 读写 | `CaptureProbeService.kt:411,422,458,578,751` + `SessionRepository.kt:11-16` | 每次 `SessionRepository(this)` 构造都执行 `store.load()`（全量 JSON 解析 + `GameReducer.replay`）**并且立即 `store.save(state)`（全量序列化 + SharedPreferences 写入）**。`captureRoiEvents` 每 50ms 走一遍，`finalizeRoiEvent` 每区一次 → 每秒数十次磁盘 IO。 |

### P1 — 稳定性与正确性缺陷

| ID | 问题 | 位置 | 证据 |
|---|---|---|---|
| **P1-5** | `hidden` 状态未持久化，服务重启后丢失 | `OverlayService.kt:82-87` | `setHidden()` 只改 `counter`/`resetControl` 的 visibility 并改 `hideControl` 文字为「显示」，**`hideControl` 保持可见，功能正常**。（⚠️ 本条已修正：初判「隐藏后永久失联」为**误判**，详见 `optimization-stage3-20260913.md` 修正记录。）真实问题：`hidden` 只是内存字段，`OverlayService` 是 `START_STICKY`，被系统杀掉重启后 `hidden` 重置为 `false`，用户设的隐藏状态丢失。次要问题：`hideControl` 尺寸 68×68 显示 2 个 18f 汉字可能拥挤，需真机确认。 |
| **P1-6** | 清空即复位 `reported`，抖动可致重复入账 | `RoiPlayEventCache.kt:98-101` | `if (state.reported) { state.reported = false; return null }` — 清空采样达阈值立刻允许下一手。若牌桌动画出现「牌闪一下→被清→同牌再现」，会二次入账。`card-counter-arch-fix-plan-20260830.md` 方案 A 的残留风险。 |
| **P1-7** | `findAcrossRows` 跨行重复计数 | `HandRankMatcher.kt:149-162` | `find()` 内部**已调用** `selectDistinctMatches`（L133，单行内 dedup，`maxCount=40`），`findAcrossRows` 把多行结果 `flatMap` 后**再** dedup。同一张牌在 y=380 与 y=385 两行被分别匹配，若两行命中 x 相差 >`minGap=28` 则**逃过 dedup，重复计数**。这是「识别数量偏多」的代码级根因之一。 |
| **P1-8** | 两套坐标系并存，UI 框与识别 ROI 错位 | `OverlayLayout.kt:11-17` vs `PlayZone.kt:18-23` | `OverlayLayout.seatPanels` 用 1280×578 **绝对像素**；`PlayZone` 用**比例**。以 `TOP` 为例：`PlayZone` → `left=0.539×1280=690, right=0.602×1280=771`（宽 81，中心 730）；`OverlayLayout` → `x=439, w=350`（中心 614）。**中心差 116px**。用户看到的框和程序扫描的区不是同一个地方。 |
| **P1-9** | `canReachIoUThreshold` 名不副实 | `HandRankMatcher.kt:268-271` | 函数名与注释暗示 IoU（交并比），实际算 `min(sceneInk, templateInk) / max(...)`。作为**剪枝先验**更宽松可用，但命名会误导后续调参。 |

### P2 — 代码质量与隐患

| ID | 问题 | 位置 | 证据 |
|---|---|---|---|
| **P2-10** | `detectFormalPlays` 是死代码 | `CaptureProbeService.kt:451-508` | 全仓 grep：**仅定义，零调用**。58 行逻辑（含 `stableUnknownCount`、`isStableUnknownPlay` 的使用）永远不会执行。当前正式出牌路径是 `captureRoiEvents` → `finalizeRoiEvent`。 |
| **P2-11** | `handYoloBitmap` 数组越界隐患 | `CaptureProbeService.kt:691-707` | `IntArray(width * width)` 中 `width = HAND_YOLO_RIGHT - HAND_YOLO_LEFT = 870`，但写入索引 `(HAND_YOLO_PAD_TOP + y) * width + x` 中 `PAD_TOP=540`、`y: 0 until height=140` → 最大索引 `(540+139)*870+869 = 591779 < 870*870 = 756900`，**当前靠参数巧合不越界**。一旦 `HAND_YOLO_BOTTOM-TOP` 增大即 `ArrayIndexOutOfBounds`。另外 `height` 定义为 `BOTTOM - TOP` 但 `pixels` 用 `width*width` 分配，两者语义不一致。 |
| **P2-12** | `TfliteYoloDetector` + tensorflow-lite 依赖冗余 | `vision/TfliteYoloDetector.kt` + `build.gradle.kts:39` | 活跃后端是 `MnnYoloDetector`（`CaptureProbeService.kt:93`）。`TfliteYoloDetector` 仅被 `androidTest/DetectorSmokeTest.kt` 引用，`tensorflow-lite:2.17.0` 为生产依赖却只服务测试。**注意：删除需先确认 androidTest 是否仍需保留对比能力**，此项优先级最低，若无把握则仅记录不执行。 |

### 附：`OverlayLayout` 与 `PlayZone` 坐标系对齐参考数据

| 座位 | PlayZone 比例 → 1280×578 像素（left, right, rankY） | OverlayLayout.seatPanels（x, y, w, h） |
|---|---|---|
| TOP | 690, 771, 13 | 439, 91, 350, 53 |
| LEFT_TOP | 189, 340, 90 | 49, 154, 344, 52 |
| LEFT_BOTTOM | 180, 360, 248 | 44, 322, 349, 52 |
| RIGHT_TOP | 960, 1100, 90 | 868, 154, 344, 52 |
| RIGHT_BOTTOM | 960, 1100, 248 | 866, 322, 346, 52 |

## 五、实施方案（分 4 阶段，子代理分工）

### 阶段 1：会计与领域层修复（P0-1、P1-6、P1-9）
- **交付者**：子代理 A
- **范围**：`GameReducer.kt`、`RoiPlayEventCache.kt`、`HandRankMatcher.kt`（仅 L268-271 改名）
- **要求**：
  1. `withinConservation` 重写为**真正能触发**的不变量，或明确降级为 `require` + 注释说明它保护什么。必须写出能被单测触发的用例。
  2. `RoiPlayEventCache` 清空复位加**冷却**（清空后需 N 个稳定空样本才允许下一手），防抖动重复。
  3. `canReachIoUThreshold` 改名为语义正确的名字（如 `canReachInkRatio`）。
- **验收**：新增单测覆盖「守恒违反触发暂停」「同牌抖动只入账一次」；`testDebugUnitTest` 全绿。

### 阶段 2：识别路径修复（P0-2、P0-3、P1-7、P2-10、P2-11）
- **交付者**：子代理 B
- **范围**：`CaptureProbeService.kt`、`CaptureProbeStatus.kt`、`HandRankMatcher.kt`（`findAcrossRows`）
- **要求**：
  1. `FirstPlayBoundaryGate` 要么真正使用 `signals`，要么删除该参数并**移除 `openingSignals` 的无用计算**（若保留计算，必须让它参与判定）。
  2. `detectYoloCards` 置信度聚合改为**平均或中位数**，并加单测验证多牌场景。
  3. `findAcrossRows` 去重改为**先合并多行候选、再统一 dedup**，消除跨行重复。
  4. 删除 `detectFormalPlays` 死代码（及其专属未用成员），确认无测试依赖后执行。
  5. `handYoloBitmap` 修复：`pixels` 尺寸与索引语义对齐，加边界断言。
- **验收**：`testDebugUnitTest` 全绿；`assembleDebug` 成功；`git grep detectFormalPlays` 为空。

### 阶段 3：性能与 UI 修复（P0-4、P1-5、P1-8）
- **交付者**：子代理 C
- **详细规格**：见 `plans/optimization-stage3-20260913.md`（含精确测算数据与误判修正）
- **范围**：`SessionRepository.kt`、`CaptureProbeService.kt`（repository 使用点）、`OverlayService.kt`、`OverlayLayout.kt`、`OverlayLayoutTest.kt`
- **要求概要**：
  1. `SessionRepository`：`CaptureProbeService` 持有单实例 + `save` 节流 + 关键节点 `flush()`；`OverlayService`/`MainActivity` 保持新建实例（需读磁盘最新值）。
  2. `OverlayService`：持久化 `hidden` 状态（`SharedPreferences`），`onCreate` 读取并应用。
  3. `OverlayLayout`：**不做坐标重排**（无真机浮窗截图，MediaProjection 不捕获 overlay 层）。改为加**水平覆盖性测试**（面板须覆盖对应识别带）+ 注释说明待标定项。
- **验收**：`testDebugUnitTest` 全绿；新增覆盖性测试断言 5 个座位面板均水平覆盖其识别带。

### 阶段 4：收尾与交付（主代理）
- 全量回归：`testDebugUnitTest` + `assembleDebug`
- 汇总 diff、写验收报告 `plans/optimization-report-20260913.md`
- 更新 `findings.md` / `progress.md` / `task_plan.md`
- 产出 APK + SHA256，push 分支
- 主代理逐文件审查子代理产出，**不得只信摘要**

## 六、派发与审查纪律（吸取历史教训）

1. **审查必须全仓 grep**，不得只 grep 单文件再断言「死代码」。（此前 5 个误报的根因）
2. **下「未实现」结论前先扫全部分支 `git log`**。
3. 子代理交付必须包含：改动文件清单 + diff + 新增单测 + 自测命令与结果。
4. 主代理**独立重跑** `testDebugUnitTest`，不采信子代理的测试结论。
5. 每阶段完成后立即 push，规避本环境 git 写操作丢失 ref 的故障。
6. 破坏性操作（删文件、改公共接口）前先确认调用点全部覆盖。

## 七、本环境 git 操作约束（必须遵守）

- 分支/ref 创建用 **shell 直写** `printf "%s\n" "<40位hash>" > .git/refs/heads/<name>`
- 不用 `git worktree add -b`（依赖 ref 写入，必失败）
- push 前加 `env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy`
- 远端状态用 `git ls-remote` 或读 `FETCH_HEAD` 确认（`refs/remotes/` 会被清空）
