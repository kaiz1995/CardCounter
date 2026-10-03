# ADR-001：模块边界与依赖方向

日期：2026-10-03
状态：已接受
关联：方案第 3.1/3.2 节；BASELINE.md

## 决策

采用方案 3.1 节定义的模块边界：

```text
capture/    FrameSource、FrameSampler、GameGeometry —— 仅负责获得帧
vision/     DetectorAdapter、MNN/TFLite/Template 实现、Observation
recognition/ OpeningStateMachine、ZoneStateMachine、CandidateResolver
domain/     LedgerCommand、LedgerReducer、LedgerState —— 纯 Kotlin
storage/    SessionActor（单写者）、SessionStore（原子追加/版本检查/恢复）
ui/         OverlayPresenter —— 只显示可信数据或不可用状态
```

依赖方向（箭头指向被依赖方）：

```text
domain ← recognition ← vision/capture
domain ← storage
domain ← ui presenter
Android Service → capture/recognition/application
```

**Domain 禁止依赖**：Android SDK、Bitmap、ImageReader、SharedPreferences、MNN、TFLite。

## 现状验证（基线 commit dda6b451）

执行命令：

```bash
grep -l "android\.\|Bitmap\|ImageReader\|SharedPreferences\|MNN\|TFLite" \
  app/src/main/java/com/example/cardcounter/domain/*.kt
```

**结果：无匹配（exit code 1）。** `domain/` 下 6 个文件（GameEvent、GameReducer、GameState、OutsideSeat、Rank、RankCounts，共 323 行）均为纯 Kotlin，无 Android/视觉/存储依赖。现状 domain 已纯净，符合目标。

补充说明：`persistence/SessionRepository.kt:3` 依赖 `android.content.Context` 属预期——storage 层需要 Context 访问 SharedPreferences，仅要求其不向 domain 泄漏。

## 现状与目标的差距清单

| # | 差距 | 现状位置 | 目标 | 修复阶段 |
|---|---|---|---|---|
| 1 | `recognition/` 包不存在 | 状态机逻辑散落在 `capture/`（HandCardPositionDetector、RoiPlayEventCache 等） | 独立 recognition 层：Opening/Zone 状态机、多帧投票 | P4 |
| 2 | vision 无统一 DetectorAdapter | `vision/MnnYoloDetector.kt` 与 `TfliteYoloDetector.kt` 各自为政 | 统一接口 + 能力探针 MNN→TFLite→DISABLED | P3 |
| 3 | capture 混入识别职责 | `capture/HandRankMatcher.kt`、`capture/SelfHandCountTracker.kt` | capture 只负责帧与几何，识别逻辑移出 | P3/P4 |
| 4 | storage 无单写者 | `persistence/SessionRepository.kt` 直接可变 state，无 Actor | SessionActor 串行化 + 版本号写入 | P2 |
| 5 | 持久化用 SharedPreferences 存事件列表 | `persistence/SessionStore.kt`（含重启自动解除暂停缺陷，见 BASELINE.md §3） | 原子追加/版本检查/恢复，暂停状态持久保留 | P1/P2 |
| 6 | ui presenter 未隔离 | `ui/CounterOverlayView.kt` 直接由 service 驱动 | OverlayPresenter 只显示可信数据或不可用状态 | P4/P6 |

## 后果

- 短期：现有包结构保留，`persistence` 视为 `storage` 的过渡实现，先修行为缺陷（P1/P2），再逐步改名与挪动文件。
- 长期：若引入 Gradle 多模块，按上表包级边界直接映射，依赖方向用模块依赖图强制。
