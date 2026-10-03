# BASELINE — Phase 0 基线冻结记录

生成日期：2026-10-03
依据文档：《CardCounter-实施计划与验收方案》第 2、5 节（Phase 0）

## 1. 代码基线

| 项 | 值 |
|---|---|
| 基线 commit | `dda6b451e03ac1db5fe372729a4a8b0c6d7b58e8` |
| 分支名 | `rebuild-event-pipeline`（从远程 main `dda6b451` 创建） |
| 来源仓库 | GitHub `kaiz1995/CardCounter` |
| 工作区状态 | 仅 `.gitignore` 有未提交修改（新增 `docs/*` + `!docs/rebuild/` 例外），代码文件干净 |
| APK SHA256 | `0f582d4227eee4791b0a1a62dfa4e7bbd202b48bd98ae526a0643bd722120c03`（P2 构建实测；SHA256 每次构建都变，校验以字节大小 47,089,539 为准） |

构建证据（APK SHA256）已由主代理验收补填，详见 `ACCEPTANCE-20261003.md`。

## 2. 代码模块清单

代码根：`app/src/main/java/com/example/cardcounter/`（单模块 app，未按方案 3.1 拆分为 Gradle 多模块，以下为包级边界）

| 包 | 文件 | 职责 | 与目标架构（3.1）对应 |
|---|---|---|---|
| `capture/` | CaptureProbeService.kt, CaptureProbeStatus.kt, HandCardPositionDetector.kt, HandRankMatcher.kt, IssueSampleStore.kt, PlayZone.kt, RoiPlayEventCache.kt, SelfHandCountTracker.kt | MediaProjection 采集服务、六区 ROI/几何、手牌位置与 Rank 匹配、问题样本存储 | 对应 capture/ + 部分识别逻辑（HandRankMatcher、RoiPlayEventCache 现混入识别职责） |
| `vision/` | MnnYoloDetector.kt, TfliteYoloDetector.kt, YoloDetection.kt, YoloLabelMap.kt | MNN/TFLite 两套 YOLO 检测器、检测结果与标签映射 | 对应 vision/，但无统一 DetectorAdapter 接口，两后端各自为政 |
| `recognition/` | （不存在） | — | 目标中的 OpeningStateMachine/ZoneStateMachine/CandidateResolver 尚未建立，状态机逻辑散落在 capture/ 内 |
| `domain/` | GameEvent.kt, GameReducer.kt, GameState.kt, OutsideSeat.kt, Rank.kt, RankCounts.kt | 事件、账本 reducer、状态、座位、Rank 库存（323 行，纯 Kotlin） | 对应 domain/，已满足纯度要求（见 ADR-001） |
| `persistence/` | SessionRepository.kt, SessionStore.kt | 基于 SharedPreferences 的会话持久化与恢复 | 对应 storage/，但无单写者、无版本号 |
| `service/` | OverlayService.kt | 悬浮窗编排服务 | 对应 ui/ 编排层 |
| `ui/` | CounterOverlayView.kt, OverlayLayout.kt | 悬浮窗展示 | 对应 ui/ OverlayPresenter |

## 3. 已知违规点（对照方案 7.2 阻断级清单）

| # | 违规点 | 代码位置 | 对应 7.2 阻断项 |
|---|---|---|---|
| 1 | 重启自动解除暂停：加载时过滤掉末尾的 `SessionPaused(RANK_UNSTABLE/BASELINE_FAILURE)` 事件 | `persistence/SessionStore.kt:27`、`SessionStore.kt:99`（`withoutRecoverablePause()`） | 重启自动解除未确认暂停 |
| 2 | quantity（cardCount）与 rankCounts 不校验一致：`cardCount` 默认取 `cards.total()`，无独立数量账，无一致性校验 | `domain/GameEvent.kt:17,24,31`；reducer 中无任何 quantity 与 Rank 向量比对逻辑 | 数量与 Rank 向量矛盾仍显示伪精确剩余 |
| 3 | 重复事件直接抛异常（`require(event.id !in ...)`），未按 DUPLICATE_IGNORED 语义处理 | `domain/GameReducer.kt:10` | 识别失败后静默丢失 / 事件安全（duplicate decrement 语义缺失） |
| 4 | SessionRepository 无单写者保护：无 synchronized/Mutex/Actor，`dispatch` 直接改 `state` 并覆写存储，多调用方可互相覆盖 | `persistence/SessionRepository.kt:14-28` | 持久化后 replay 与实时账本不一致（多实例覆盖风险） |

## 4. 数据资产基线

见 `docs/rebuild/dataset-manifest.json`（逐视频文件名、路径、字节数、SHA256、来源组、去重结论、三集合切分）。

- 样本总量：9 局（`9局/` 9 个 mp4），低于方案门槛 30 局，缺口 21 局。
- `source/` 目录 9 个 mp4 为原始长录制素材，与 `9局/` 的去重结论见 manifest。

## 5. 可追溯性

- 代码来源：GitHub `kaiz1995/CardCounter`，commit `dda6b451...`。
- 数据来源：本机 `记牌器开发/9局/` 与 `记牌器开发/source/`（主仓库目录，只读），哈希见 manifest。
- 模型文件哈希：待补（模型资产清单在 Phase 3 统一 DetectorAdapter 时落盘）。
