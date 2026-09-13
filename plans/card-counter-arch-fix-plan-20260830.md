# 记牌器架构修复实施方案（2026-08-30）

## 1. 背景

多乐够级记牌器（Android/Kotlin，分支 codex/positive-accumulate）。目标：识别开局手牌 H0（任意张数）+ 记录外面五家出牌 → 显示外面剩余。

核心公式：OutsideRemaining = 198 - H0 - 外面已出。selfPlayed 只做审计。

针对 positive5 真机日志（15:10-15:11）暴露的两个现象：
1. 开局 H0 识别错：大王实际 2 个，锁成 1 个；rank 分布全乱。
2. 出一张记十几张：同一张牌停留桌面被反复入账。

## 2. 根因（代码 + 日志双重定位）

### 现象2：出一张记十几张（记账层，优先级最高）

- RoiPlayEventCache.observe：同一区攒3帧即 early finalize，但未把 reported 置 true。
- finalizeRoiEvent 调 completeEarly 只清 earlyInFlight，不清 reported；清空（emptySamples>=6）才允许下一手。
- 牌停留桌面（emptySamples=0）时该区立刻又能攒帧→再次 finalize→再次 dispatch。
- 唯一刹车 StablePlayGate.acceptStable 是 500ms 去重窗；牌停留>500ms 就重记。

日志实锤：15:11:34-49，TOP 记9次 / RIGHT_BOTTOM 记10次 / RIGHT_TOP 记8次。间隔 908ms/1143ms，卡在 500ms 窗外。

根因：事件生成层没有"一手结束=桌面清空"的领域语义，用时间窗冒充状态。

### 现象1：开局 H0 识别错（识别层）

- YOLO 手牌检测漏检：yolo cardCount=19（33张只检出19张），置信0.25。isValidBaseline（±2）拒 YOLO→回退模板。
- 模板逐位扫描无全局约束，凑 total=33 但 rank 分布漂移（大王2→1、Q 3→4）。

日志实锤（15:10）：
- 15:10:04 template 大王=2 total=33 position=33（唯一对）
- 15:10:19 template 大王=1（开始漂移）
- 15:10:21 SELF_HAND_LOCKED 大王=1（锁错）

## 3. 修复方案

### 方案 A：记账层止血（小改快见效）

改动 RoiPlayEventCache.kt + PlayZone.kt（StablePlayGate.acceptStable）：

1. RoiPlayEventCache：early finalize 时把 reported 置 true，只有 emptySamples>=clearSamples（清空）才重置，允许下一手。
2. StablePlayGate.acceptStable：主判定改"同区同牌清空前不得再次接受"，500ms 窗仅辅助。
3. PublicPlayCountAggregator 不改（合并短间隔换牌，与重复入账无关）。

验证：单测模拟同牌停留2秒只记1次、清空后再现记第2次；真机一局 FORMAL_PLAY 数与出牌次数一致。

### 方案 B：识别层治本（单独立项）

1. H0 不再回退模板：YOLO 被 isValidBaseline 拒时继续等待下一帧，不锁错基线；模板仅在 YOLO 不可用时兜底。
2. 模板加全局约束：total==positionCount、各 rank<=4、大王/小王>=2（需真值验证）。
3. YOLO 手牌采样重训（独立任务）：检测率约58%，需真值集。

验证：单测构造漂移候选断言不锁定；真值集回放断言 H0 与实牌一致。

## 4. 分阶段实施（派发子代理边界）

- 阶段1 方案A：仅 RoiPlayEventCache.kt + PlayZone.kt。子代理 Sartre(deepseek-v4-flash)。交付 diff+单测，主代理逐文件审核+重跑测试+assembleDebug。验收：FORMAL_PLAY 数与出牌次数一致，重复入账=0。
- 阶段2 方案B：CaptureProbeService.kt + HandRankMatcher.kt。需用户确认"大王>=2"是否硬约束（需真值）。交付 diff+单测+真值集回放。
- 阶段3 YOLO重训：需用户提供多局真实开局截图样本。

## 5. 验收标准

1. 方案A：真机一局外面出牌全记上，无重复；FORMAL_PLAY 数=出牌次数。
2. 方案B：H0 锁定与实牌一致（大王、rank、张数）；识别层不锁错基线。
3. 回归：79 单测全绿，assembleDebug 通过，APK 可装真机。

## 6. 风险与取舍

| 取舍 | 说明 |
|---|---|
| 方案A只止重复入账 | 识别漂移仍需B；A是最痛点止血 |
| 方案B改H0回退可能"锁不上牌" | 宁可PRE_PLAY不锁，不锁错基线 |
| YOLO重训需样本 | 无真值集前不盲目调参 |
| acceptStable改清空语义可能漏记快节奏 | 500ms窗辅助补偿，主判定以清空为准 |
