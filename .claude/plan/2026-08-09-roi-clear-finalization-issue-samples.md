# ROI事件缓存、清桌确认与问题样本留存——最小实施计划

日期：2026-08-09  
范围：只调整公开牌事件确认链；保留现有六区、YOLO11 TFLite/MNN、玩家座位、`GameReducer` 和浮窗统计。

## 目标与成功条件

1. 牌出现时只缓存 ROI 识别结果，不立即记牌。
2. 同一区确认清桌后，在现有识别线程异步归并缓存并生成一次事件。
3. 无法可靠确认、模型冲突或归约失败时不猜牌；只保存该区的小图与元数据。
4. 不保存整屏、不联网；缓存和本地样本都有硬上限。
5. 同一牌组清桌后再次出现，仍能生成新事件；短暂动画空帧不重复计数。

## 当前基线

- `CaptureProbeService` 已按 250 ms 单飞采样，并在 `rankHandler` 做 TFLite/MNN/模板识别。
- `StablePlayGate` 当前在牌仍显示时即返回事件；清空 6 个样本只负责重新布防。
- `PlayZone` 已绑定五个 `OutsideSeat`，事件可正确累计到玩家框。
- TFLite/MNN、28→15 Rank、白色牌体门、桌面绿色门、服务重启 prime 已存在，不重写。

## 最小状态机

每个 `PlayZone` 独立维护：

```text
EMPTY
  └─ 可见牌体 → OBSERVING（缓存候选）

OBSERVING
  ├─ 达到现有确认门 → ARMED（仍不写事件）
  ├─ 短暂空白 < 6 样本 → CLEARING → 牌恢复后回 OBSERVING/ARMED
  └─ 连续空白 6 样本 → FINALIZE

ARMED
  ├─ 新候选 → 继续缓存并重新选优
  └─ 连续空白 6 样本 → FINALIZE

FINALIZE
  ├─ 有可靠赢家 → dispatch 一次 → EMPTY
  └─ 无可靠赢家 → 保存问题 ROI，不写牌事件 → EMPTY

PRIMED（服务重启桌面已有牌）
  └─ 连续空白 6 样本 → EMPTY；期间永不生成事件
```

边界：`tableVisible=false` 代表离开游戏/遮挡，不等于清桌；冻结状态，不推进空白计数。新局重置、会话关闭或截屏停止时丢弃未提交缓存，绝不补猜。

## 缓存内容与判定

每区仅保留最近 8 次观察：

- `RankCounts`、检测来源（MNN/TFLite/模板）、最小置信度、采样时间。
- 一张“最佳候选”ROI 副本和一张“最后观察”ROI 副本；替换时立即回收旧 Bitmap。
- 不缓存整帧，不把 Bitmap 放进领域事件或会话持久化。

归并规则沿用现有门槛：

- 普通候选至少两次相同；多张牌且最小置信度 ≥ 0.60 可一次进入 `ARMED`，但仍等清桌才提交。
- 赢家先按相同 `RankCounts` 的样本数，再按最低/平均置信度选；平票或 YOLO/模板持续冲突视为问题样本。
- `visible=true + 无可靠 Rank` 只进入问题缓存；清桌后不生成空事件。
- reducer 拒绝（超库存、会话状态变化）时保留现有 PAUSE 安全门，并保存问题 ROI。

## 需要修改的文件

| 文件 | 最小改动 |
|---|---|
| `capture/PlayZone.kt` | 保留坐标/牌体判断；移除旧 `StablePlayGate` 的“牌面显示时立即接收”职责。 |
| `capture/CaptureProbeService.kt` | 所有六区把 `visible + 识别结果 + ROI` 喂入缓存；只处理缓存产出的 `FinalizedPlay`；重置/销毁时清缓存并回收 Bitmap。 |
| `capture/RoiPlayEventCache.kt`（新） | 纯 Kotlin 六区状态机、8样本环形缓存、prime、clear-finalize、generation 防止重置后的旧结果提交。 |
| `capture/IssueSampleStore.kt`（新） | 后台单线程保存 `roi.png + meta.json`；目录固定在 `filesDir/card_counter_issues/`，最多 50 组，超出删最旧。 |
| `capture/StablePlayGateTest.kt` | 改为清桌提交语义，或由新测试替代后删除旧门测试。 |
| `capture/RoiPlayEventCacheTest.kt`（新） | 覆盖状态机、选优、重启 prime、重置 generation。 |
| `capture/IssueSampleStoreTest.kt`（新） | 覆盖只写 ROI/JSON、50组上限和成对清理。 |

无需改：`GameEvent.kt`、`GameReducer.kt`、`SessionStore.kt`、玩家座位模型、浮窗 UI、MNN/TFLite 检测器。

## 分阶段实施

### P1：纯状态机

- 新建 `RoiPlayEventCache`，先只缓存识别元数据，不接 Bitmap/I/O。
- 把当前“即时 dispatch”改为“清桌后返回 `FinalizedPlay`”。
- 验证：牌稳定期间事件数为 0；第 6 个连续空样本后恰好 1；2 个空动画帧后牌恢复仍为 0。

### P2：接入服务

- 复用现有 `rankHandler` 完成 finalize；不新增判定线程，不阻塞 MediaProjection 回调。
- 保留 `SingleFlightGate`、250 ms 节流、桌面门、白色牌体门和 restart prime。
- `FinalizedPlay` 才构造 `SelfCardsPlayed/OutsideCardsPlayed(seat)` 并刷新浮窗。
- 验证：同一区同牌清桌再出可记录第二次；五个座位归因不变。

### P3：问题 ROI 留存

- 只在 `NO_CONSENSUS`、`MODEL_DISAGREEMENT`、`VISIBLE_WITHOUT_RANK`、`REDUCER_REJECTED` 保存。
- `meta.json` 仅含时间、区位、候选、置信度、后端、原因、App版本；不含整屏、账号或设备标识。
- 写盘放独立单线程；保存失败只记日志，不影响牌局。
- 验证：正常事件不产生文件；问题事件产生一组文件；第 51 组后仍仅保留最新 50 组。

### P4：离线与真机验收

- 现有录像/问题帧回放：人工标注的每次清桌对应 0或1 个事件，座位和 Rank 完全一致。
- Mate 60 完整牌局：错误扣牌 0、重复扣牌 0；清桌后提交延迟目标 ≤ 2 秒（6×250 ms 加一次推理余量）。
- 连续运行 10 分钟：无 Bitmap 增长、无推理队列堆积、MediaProjection 不掉线。
- 执行 `gradlew.bat test assembleDebug`；全部现有测试与新增测试通过后才替换当前即时门。

## 明确不做

- 不保存正常牌局截图、整屏录像或云端上传。
- 不新增数据库、协程框架、通用事件总线。
- 不调整 YOLO 模型、NMS、六区坐标和领域库存公式；这些需独立证据后再改。

