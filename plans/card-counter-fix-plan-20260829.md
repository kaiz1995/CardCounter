# 记牌器真机缺陷修复实施方案(2026-08-29)

分支:codex/positive-accumulate(基线 1204b08)。此方案由主代理依据 08-29 真机对局证据制定,实现由子代理按此规格完成,主代理审查。

## 一、真机证据与根因(已核实)

08-29 一局(15:03:23 锁 H0 至 15:06:39 最后一手):
1. **出完即整桌停摆**:15:06:39 后所有区域不再产生 ROI_FINALIZED/FORMAL_PLAY,但 FRAME_RATE 持续 60fps、无 SESSION_PAUSED。外面五家继续出牌(用户确认),账目 OUT 122 张 vs 理论 165,缺口 43 张全在停摆段。根因:`gameTableVisible()` 只检查 (500,200)/(800,200) 两个 1280x578 画布坐标是否"桌面绿";自己走科出完后该区域画面变化,两点不绿 → 判定整桌消失 → 全部区域停止观察。
2. **H0 识别漏 5 张**:SELF_HAND_LOCKED total=28,用户确认开局实际 33。且 H0 中 NINE/FOUR/THREE 全为 0,33 张手牌完全不含 3/4/9 概率极低 → rank 识别漏检。上一轮把 isValidBaseline 的"positionCount 与牌面张数一致性校验"整体删除,导致 28 张的漏检结果也被当作 H0 锁定,外面剩余从一开始虚高 5 张。
3. **大小王混淆**:H0=大王1/小王2,局内 SELF 出小王2、OUT 出小王2,大王全程 0 条记录;大小王为历史弱项。
4. **6 次 OUT_OF_STOCK 拒记**:PLAY_REJECTED 日志不记录被拒牌面,无法归因 → 审计缺口。

## 二、修复范围(三个文件改动 + 测试)

### 修复 1:桌面判定改为"多点投票 + 出牌活动豁免"(对应停摆)

文件:`app/src/main/java/com/example/cardcounter/capture/CaptureProbeService.kt`

- 新增内部纯函数(放同文件或 CaptureProbeStatus.kt 均可,必须 internal 可单测):
  `internal fun isTableVisibleBySamples(greenPixels: Int, totalPixels: Int, hasRecentActivity: Boolean): Boolean = hasRecentActivity || greenPixels * 10 >= totalPixels * 6`
  (即绿点占比 >= 60%,或最近有出牌活动则视为可见)。
- 将 `gameTableVisible(image)` 替换为多点采样:在 1280x578 画布坐标取至少 8 个采样点,覆盖中央桌面区域(避开顶部横条 y<120、避开自己手牌区 y>430;示例坐标可含 (480,170) (640,170) (800,170) (420,240) (640,240) (860,240) (520,310) (760,310))。统计其中满足 `isTableGreen` 的点数。
- 活动豁免:service 维护 `lastPlayRecordedAt`(每次 FORMAL_PLAY 成功入账时更新为当前 elapsedRealtime),`gameTableVisible(image, now)` = `isTableVisibleBySamples(greenCount, sampleCount, now - lastPlayRecordedAt <= 5000)`。
- 保持其它逻辑不变(captureRoiEvents 调用处只改传 now)。

### 修复 2:恢复 H0 张数一致性校验,漏检不再当基线(对应 H0=28)

文件:`app/src/main/java/com/example/cardcounter/capture/CaptureProbeStatus.kt`、`capture/CaptureProbeService.kt`

- `isValidBaseline(positionCount: Int?, cards: RankCounts)` 恢复一致性校验(宽容 ±2,不再要求固定 33):
  `positionCount != null && kotlin.math.abs(positionCount - cards.total()) <= 2 && cards.total() > 0 && cards.fitsWithin(Rank.initialInventory)`
- 手牌探测处(HAND_CANDIDATE 分支)在更新 `lastStablePrePlayHand` 时同步保存当时的一致张数:新增字段 `lastStablePositionCount: Int`(默认 0,reset 时归零)。
- `lockPrePlayHand()` 日志改为:`SELF_HAND_LOCKED total=${cards.total()} positionCount=$lastStablePositionCount cards=$cards`,便于真机核对 H0 是否与牌位检测一致。
- 若 H0 一直无法满足一致性(识别持续漏检),保持 PRE_PLAY 不锁定,靠用户"新局重置"重试;不做自动兜底。

### 修复 3:拒记与入账日志补全牌面(对应审计缺口/王定位)

文件:`app/src/main/java/com/example/cardcounter/capture/CaptureProbeService.kt`

- `dispatchCountedPlays` 的 onFailure 日志改为:`PLAY_REJECTED reason=OUT_OF_STOCK zone=$zone seat=$seat cards=${play.cards} cardCount=${play.cardCount}`,包含被拒的具体牌面。
- `dispatchGameEvent` 的 onFailure 增加同样含 cards 的日志。
- 若实现中发现 seat 在 reject 路径不可用,记录 zone 即可,不要为取 seat 引入空指针风险。

## 三、测试

- `app/src/test/java/com/example/cardcounter/capture/HandCandidateDiagnosticsTest.kt`:更新 isValidBaseline 用例为:
  * positionCount=33 且 total=33 → true
  * positionCount=33 且 total=28 → false(±2 之外)
  * positionCount=33 且 total=31 → true(±2 内)
  * positionCount=null 且 total 任意 >0 → false
  * total=0 → false
- 新增(或扩展现有 capture 测试)桌面判定纯函数用例:`isTableVisibleBySamples` 四种组合(绿点占比 60% 上下、无活动时 false、有活动 true)。
- 既有 GameReducerTest / SessionStoreTest 不受影响,不许改动。

## 四、验收

- `.\gradlew.bat testDebugUnitTest --rerun-tasks --console=plain` 全绿
- `.\gradlew.bat assembleDebug --console=plain` SUCCESS
- 不 commit、不 push、不建分支,只改文件;结束后列出改动文件清单与测试结果。

## 五、明确不做(边界)

- 不修"大小王识别"(需采集样本后单独处理),本轮只补日志让它可审计。
- 不动 pc_cardcounter/、docs/、plans/、.tmp-*。
- 不动 OverlayService、CounterOverlayView、RoiPlayEventCache、PublicPlayCountAggregator、domain/**、GameReducer 语义。
- 不做 Canvas/UI 改动。

## 六、真机验证口径(下一轮)

- H0 锁定日志 total 应 = positionCount(约等于实际手牌 33,交贡后±)
- 出完牌后桌面判定不应停摆;外部事件持续到局末
- OUT_OF_STOCK 若再出现,凭日志被拒 cards 判定是防重还是误拒

