# CardCounter 验收记录 — rebuild-event-pipeline P0-P2

验收日期：2026-10-03
验收人：主代理（子代理实现，主代理审查 + 测试验收）
方案依据：《CardCounter-实施计划与验收方案》v1.0（outputs/audit-20261003/）

| 项 | 值 |
|---|---|
| run-id | rebuild-20261003-01 |
| Git commit | `127d326934daa975148b0701736aadd84a8f3357`（P2）；P0=`0f847fb`、P1=`c763ba6` |
| 分支 | `rebuild-event-pipeline`（基线 remote main `dda6b451`），已推送 origin |
| APK | app-debug.apk，47,089,539 字节，SHA256 `0f582d4227eee4791b0a1a62dfa4e7bbd202b48bd98ae526a0643bd722120c03`（注：APK SHA256 每次构建都变，校验以字节大小为准） |
| 设备/系统 | 未涉及（离线阶段） |
| 模型/后端 | 未涉及（Phase 3 范围） |
| 数据集版本 | docs/rebuild/dataset-manifest.json（9 局 / 门槛 30，缺口 21） |

## 结果

| 项 | 结论 | 证据 |
|---|---|---|
| A0 构建 | **通过** | `assembleDebug` BUILD SUCCESSFUL，APK 47,089,539 字节 |
| A1 组件单测 | **通过** | `testDebugUnitTest` **87/87**（0 fail / 0 error），含 Phase 1 新增 22 例 reducer 测试、Phase 2 新增 12 例 storage 测试 |
| P0 交付物 | **通过** | docs/rebuild/ 四件套入库；manifest 逐视频 SHA256 实测（主代理抽查 1 例一致）；9局/ 与 source/ 确认为两批不同素材 |
| P1 账本契约 | **通过**（A1 级） | DispatchResult 四态语义；手牌点数合计≠牌边缘张数拒绝；超库存/守恒失败 → PAUSED 且原事件不进账；重复事件 DuplicateIgnored；`withoutRecoverablePause` 已删除，重启不解除暂停有测试锁定 |
| P2 单写者存储 | **通过**（A1 级） | SessionActor 单线程串行（并发测试无丢失）；VersionedEventLog CAS 旧版本写入被拒；诊断词表 OBSERVED/STABLE/ACCEPTED/REJECTED/RETRY/PAUSED/DUPLICATE；显式 Resume 仅 PAUSED→ACTIVE |
| A2 离线整局 | **未验证** | 需要 30 局标注数据 + offline runner（Phase 5） |
| A3 真机链路 | **未验证** | 需要 Mate 60 真机（Phase 6） |
| A4 整局放行 | **未验证** | 依赖 A2/A3 |

## 阻断问题（7.2 清单对照）

| 阻断项 | 状态 |
|---|---|
| 首出前产生正式出牌事件 | 规则已改：新会话直接可记录，外面出牌在确认手牌前照常记（横条先不扣自己的牌，确认后补扣），测试锁定；首出状态机细分属 Phase 4 |
| 误扣外部剩余 | 自己出的牌完全不进账（没有对应事件）；reducer 单测锁定外面剩余只受自己确认的手牌和外面出牌影响 |
| 同一静态牌组重复扣减 | DuplicateIgnored + evidenceId 兼作事件 id（测试锁定） |
| 事件未入账却日志 ACCEPTED | ACCEPTED 日志与 DispatchResult.Accepted 同源产生 |
| 识别失败静默丢失 | NO_CONSENSUS → RETRY 诊断记录保留证据 |
| 重启自动解除未确认暂停 | **已修复**（withoutRecoverablePause 删除 + 测试锁定） |
| 持久化后 replay 与实时不一致 | replayMatchesLiveState 测试锁定 |
| MNN/TFLite 后端不可观察 | **未处理**（Phase 3 范围） |
| 数量与 Rank 矛盾仍显示伪精确 | **已修复**（只接受点数完整的出牌；各家张数和外面总张数由点数向量推出，不再有独立数量账；手牌确认时点数合计必须等于牌边缘张数，对不上 → Rejected） |

## 主代理审查记录（子代理产出修正项）

1. P1 子代理漏改 2 处旧测试调用点（SelfHandCountTrackerTest / SessionStoreTest）→ 主代理修复。
2. JVM 单测 org.json 未 mock → 主代理加 `testImplementation("org.json:json:20240303")`。
3. P2 子代理在 CaptureProbeService 留下 5 处 `SessionRepository(this)` 临时实例做 phase 读（init 会旁路写盘，违反单写者）→ 主代理统一改为单一 `sessionLedger` 实例。

## 已知残留（不阻断 A1，已记录）

- 手牌确认前外面出牌照常记、横条不扣自己的牌；确认后补扣，任一点数超库存则守恒失败暂停，确认事件不进账。
- 自己出的牌不记、不算、不显示；本局手牌确认后不能再改，自动锁定改为手动确认属下一轮界面重写。
- `replay` 对程序级异常事件静默跳过（旧行为是整体 corrupted）；写入了 replaySkipsRejectedDuplicateAndIllegalEvents 测试固化现行为。

## 未验证项

- A2/A3/A4 全部（数据缺口 21 局；真机 14 场景未跑）。
- Phase 3（DetectorAdapter 统一视觉契约）、Phase 4（首出/六区状态机细分）未实施。

## 结论

**A0 + A1 通过（P0/P1/P2 三阶段按方案完成）**；A2 及以上不通过（未验证）。
不得宣称产品可用。下一步：Phase 3 视觉契约统一，或先补录数据集。
