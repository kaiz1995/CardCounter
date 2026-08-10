# 多乐够级 4 副牌记牌器 — 技术方案规划（v3.2 设计冻结）

> **定位**：基于视觉感知与状态推理的够级牌局分析助手（仅自用）
> **v3 核心变化**：Rank 库存模型 · 三级识别（模板主+OCR辅）· PlayDetector 事件判断 · GameSession 状态模型
> **v3.1 修正**：性能目标 220ms · P5 异常恢复 · 7 项修改逐项确认
> **v3.2 冻结**：GameRulesConfig 管理牌数 · PlayDetector Hash 去重 · Recognition Conflict 状态 · GameSession Mutable · StateValidator 状态校验
> **设计冻结**：v3.2 后不再大改架构，直接进入 P1 编码

---

## 1. 项目目标

开发 **Android 记牌器 App（仅自用）**，适配多乐够级（4 副牌、6 人、出牌节奏快）。

- 浮窗显示 4 副牌各点数剩余数（**按 Rank 统计，不按花色**）
- MediaProjection 截屏 + **模板匹配为主、OCR 为辅**自动识别出牌
- **软件完全自动运行**——截屏识别出牌后自动扣减，浮窗仅显示剩余数，**不支持手动点按记牌**
- 保留控制按钮：重置（新局）、撤销（纠正自动识别错误）
- 跟踪 6 名玩家已出牌，为概率预测留接口

---

## 2. 多乐够级 4 副牌玩法与规则

> 基于网络检索确认的 6 人多乐够级规则（山东够级标准打法）。

### 基本信息

| 项目 | 值 |
|---|---|
| 牌数 | **4 副扑克牌 = 198 张**（去掉 10 张 3 + 8 张 4，含大小王各 4 张），完整 216 张可选 |
| 人数 | **6 人**，分为两个阵营（联邦），每队 3 人，**间隔而坐** |
| 每人手牌 | 理论上是 198 / 6 = **33 张**；但是存在交g贡m买34等情况   |
| 对门 | 面对面坐的玩家互为「对门」——只有对门可以打够级牌 |
| 不区分花色 | 够级仅按点数比较大小，花色无意义 |

### 玩家位置（6 人圆桌，按逆时针坐）

自己左右两侧是对手，间隔一位是队友：



**联邦（Team A）**：自己(0) + 队友(2) + 队友(4)
**对手（Team B）**：对手(1) + 对手(3) + 对手(5)

### 牌力排序（从大到小）



| 牌 | 数量 | 记牌意义 |
|---|---|---|
| 大王 | 4 张 | 绝对压制，出完 = 对手再无此压制 |
| 小王 | 4 张 | 次大牌 |
| 2 | 16 张 | 级牌核心，单张 2 即为够级牌 |
| A | 16 张 | 2 张 A = 够级牌 |
| K | 16 张 | 2 张 K = 够级牌 |

> 记牌器默认「关键牌视图」聚焦大王/小王/2/A/K —— 这 5 种点数决定牌局走向。

### 够级牌（核心规则）

够级牌一旦打出，**只有对门可以接牌**，其他玩家必须「过」：

| 牌型 | 够级最低数量 |
|---|---|
| 10 | 5 张 |
| J | 4 张 |
| Q | 3 张 |
| K | 2 张 |
| A | 2 张 |
| 2 | **1 张** |
| 大小王 | 任意 1 张 |

### 出牌规则

- **逆时针**出牌
- 必须出同类型、同张数、更大点数的牌压上家
- 无法压牌则「过」，本轮不再出牌
- **烧牌**：非对门玩家出够级牌打断对门够级
- **革命**：手牌无够级牌时可选，身份特殊化

### 名次与胜负

| 名次 | 称呼 | 计分 |
|---|---|---|
| 第 1 | 头科 | +2 |
| 第 2 | 二科 | +1 |
| 第 3 | 三科 | 0 |
| 第 4 | 四科 | 0 |
| 第 5 | 二落(la) | -1 |
| 第 6 | 大落(la) | -2 |

联邦总分 = 三人名次分数之和，高者获胜。

### 进贡规则

- **落贡**：大落 -> 头科 进 2 张；二落 -> 二科 进 1 张
- **闷贡**：最后走 3 前被上牌，进 1 张
- **点贡**：未开点者对开点者进贡
- **烧贡**：被烧者对烧牌者进 1 张
- 进贡进手中最大的牌，收贡者还贡任意牌

> 进贡/还贡导致牌权转移，记牌器无法感知，需 P5 手动修正。

### 记牌器适配要点

| 游戏特性 | 记牌器影响 |
|---|---|
| 6 人 | PlayerPosition 需 6 个值，PlayerState 需 6 个 |
| 每人 36/33 张 | cardsPerPlayer 可配置 |
| 够级牌（仅对门接） | RuleEngine 需校验 |
| 联邦 3v3 | PlayerPosition 带团队属性 |
| 进贡/还贡 | 记牌器无法感知，P5 手动修正 |
| 花色无意义 | 仅识别 Rank（15 类） |

### 记牌器定位

- 不操控游戏，仅悬浮显示剩余牌数
- 离线视觉识别：截屏 -> 识别出牌区 -> 自动扣减
- 不依赖游戏 API，不知晓玩家手牌
- 仅跟踪公共已出牌信息

---

## 3. 技术路线（v3 核心结论）

```
MediaProjection (VirtualDisplay + ImageReader, RGB565)
        ↓
PlayDetector（帧差 → 区域分析 → 牌区判定 → 牌数变化）  ← v3 新增
        ↓
FixedLayoutSlicer（固定 ROI 网格切片）
        ↓
TemplateClassifier（模板匹配，主）  ← v3 改：CNN → 模板匹配
        ↓  (置信度 < 0.85 时)
OcrFallback（ML Kit，辅）           ← v3 改：二级兜底
        ↓
RecognitionResult（rank + confidence）
        ↓
ConfidenceFilter（>95% 自动 / 70-95% 待确认 / <70% 忽略）
        ↓
PlayEvent（player + ranks + confidence + timestamp）
        ↓
RuleEngine（规则校验）
        ↓
GameSession（DeckState + PlayerState×6 + PlayHistory + UndoStack）
        ↓
RankInventory（remaining[15]）      ← v3 改：[54] → [15]
        ↓
FloatingWindow（默认关键牌视图 / 展开 / 调试）
```

### v2 → v3 关键决策变化

| 维度 | v2 | **v3** | 变化原因 |
|---|---|---|---|
| 库存模型 | `remaining[54]`（按花色×点数） | **`remaining[15]`（按 Rank）** | 够级不区分花色，A=16张而非♠A=4张 |
| 识别目标 | 点数 + 花色 → CardId(54类) | **只识别 Rank(15类)** | 减半工作量，符合够级规则 |
| 识别引擎 | MobileNetV3 CNN（需训练） | **模板匹配主 + OCR 辅** | 固定 UI 下模板匹配立即可用、<10ms、无需数据集 |
| 出牌判断 | 帧差直接触发识别 | **PlayDetector 事件判断层** | 过滤聊天/动画/倒计时/特效的无效变化 |
| 状态模型 | GameState（扁平） | **GameSession（含 PlayHistory + 事件级 UndoStack）** | 撤销整次出牌事件，非 +1 计数 |
| 浮窗默认 | 4×14 全显示 | **默认关键牌（王/2/A/K）+ 展开按钮** | 手机屏小，聚焦够级关键牌 |
| 数据采集 | P0 必做（CNN 需训练集） | **去掉 P0**（模板只需每种 Rank 采 1 张参考图） | 模板匹配门槛低，边用边采即可 |
| Phase 顺序 | P0 采集 → P1 浮窗 → ... | **P1 浮窗 → P2 数据模型 → P3 截屏 → P4 识别 → P5 自动扣 → P6 分析** | 数据模型提前，不依赖采集 |

### 保留不变（v2 已正确）

| 维度 | 选择 |
|---|---|
| 语言 | Kotlin + Coroutines |
| minSdk / targetSdk | 29 / 34 |
| 截屏 | MediaProjection + VirtualDisplay + ImageReader (RGB565) |
| 浮窗 | Foreground Service + `TYPE_APPLICATION_OVERLAY` |
| 持久化 | DataStore + JSON |
| 牌张分割 | 固定布局网格切片（非轮廓检测） |
| 无障碍服务 | 不使用 |
| 防误触 | 透明区穿透 |

### 否决方案

- **ML Kit OCR 为主**：小字+抗锯齿+缩放，A↔4、10↔IO 误判率高
- **CNN 为主**：需 500-1000 张标注数据，起步成本高；模板匹配对固定 UI 足够
- **OpenCV 轮廓分割**：阴影/重叠/特效会误切
- **按花色统计**：够级规则中花色无价值，分开统计无意义
- **AccessibilityService**：牌面是位图，无障碍树读不到

---

## 4. 数据模型（v3 核心重构）

### Rank 枚举（15 个值，v3.2 移除硬编码 initialCount）

```kotlin
enum class Rank(val displayName: String) {
    BIG_JOKER("大王"),
    SMALL_JOKER("小王"),
    TWO("2"),
    THREE("3"),
    FOUR("4"),
    FIVE("5"),
    SIX("6"),
    SEVEN("7"),
    EIGHT("8"),
    NINE("9"),
    TEN("10"),
    JACK("J"),
    QUEEN("Q"),
    KING("K"),
    ACE("A");

    companion object { const val SIZE = 15 }
}
```

### GameRulesConfig（v3.2 管理牌数量）

```kotlin
data class GameRulesConfig(
    val deckCount: Int = 4,
    val includeJokers: Boolean = true,
    val trimLowCards: Boolean = true         // 默认198张
) {
    /** 每个 Rank 的初始数量 — 由配置动态计算，不硬编码 */
    fun initialCount(rank: Rank): Int = when (rank) {
        Rank.BIG_JOKER, Rank.SMALL_JOKER -> if (includeJokers) deckCount else 0
        Rank.THREE -> if (trimLowCards) deckCount * 4 - 10 else deckCount * 4
        Rank.FOUR  -> if (trimLowCards) deckCount * 4 - 8  else deckCount * 4
        else -> deckCount * 4
    }

    /** 总牌数 */
    fun totalCards(): Int {
        val jokers = if (includeJokers) deckCount * 2 else 0
        return deckCount * 4 * 13 + jokers - if (trimLowCards) 18 else 0
    }

    /** 每人手牌数（够级 6 人） */
    fun cardsPerPlayer(): Int = totalCards() / 6
}
```

**总牌数**（4 副带王）：4×4×13 + 8 - 18 = 198 张，每人 33 ✓

### RankInventory（库存，v3.2 接受 config 初始化）

```kotlin
class RankInventory {
    val remaining = IntArray(Rank.SIZE)   // [15]，每个 Rank 剩余数
    val played = IntArray(Rank.SIZE)      // [15]，每个 Rank 已出数
    val lastSeen = LongArray(Rank.SIZE)   // [15]，最后出现时间戳

    fun init(config: GameRulesConfig) {
        Rank.values().forEach { remaining[it.ordinal] = config.initialCount(it) }
        played.fill(0)
        lastSeen.fill(0)
    }
    fun reset(config: GameRulesConfig) = init(config)
    fun deduct(rank: Rank, count: Int = 1) {
        remaining[rank.ordinal] -= count
        played[rank.ordinal] += count
        lastSeen[rank.ordinal] = System.currentTimeMillis()
    }
    fun restore(rank: Rank, count: Int = 1) {
        remaining[rank.ordinal] += count
        played[rank.ordinal] -= count
    }
}
```

### PlayEvent（出牌事件）

```kotlin
data class PlayEvent(
    val id: Long,                              // 事件 ID
    val timestamp: Long,
    val player: PlayerPosition,                // 谁出的
    val ranks: List<Rank>,                     // 出了哪些 Rank（不关心花色）
    val confidence: Float,                     // 识别置信度
    val source: EventSource                    // AUTO / MANUAL / CONFIRMED
)

enum class PlayerPosition(val team: Team) {
    SELF(Team.A), RIGHT_OPPONENT(Team.B), RIGHT_TEAMMATE(Team.A),
    OPPOSITE(Team.B), LEFT_TEAMMATE(Team.A), LEFT_OPPONENT(Team.B);
    fun opposite() = values()[(ordinal + 3) % 6]
    companion object { const val SIZE = 6 }
}
enum class Team { A, B }
enum class EventSource { AUTO, MANUAL, CONFIRMED }
```

### PlayerState（玩家状态）

```kotlin
class PlayerState(
    val id: Int,
    val position: PlayerPosition,
    var estimatedRemain: Int,                  // 估计剩余手牌数
    val playedRanks: MutableList<Rank> = mutableListOf()  // 已出 Rank 历史
)
```

### RecognitionResult（v3.2 增加 Conflict 状态）

```kotlin
enum class RecognitionStatus {
    OK,              // 识别成功
    LOW_CONFIDENCE,  // 置信度低
    CONFLICT         // 模板与 OCR 结果冲突（v3.2 新增）
}

data class RecognitionResult(
    val rank: Rank,                            // 最终采用的 Rank
    val confidence: Float,                     // 最终置信度
    val bbox: Rect,
    val status: RecognitionStatus,             // v3.2 新增
    val templateRank: Rank? = null,            // 模板匹配结果
    val ocrRank: Rank? = null,                 // OCR 结果（若触发）
    val timestamp: Long
)
```

**Conflict 判定**：模板匹配与 OCR 结果不一致时，标记 CONFLICT，降级到待确认队列。

### GameSession（v3.2 改 Mutable）

```kotlin
class GameSession(                              // v3.2: data class → class
    val sessionId: Long,
    val startTime: Long,
    var config: GameRulesConfig,
    val inventory: RankInventory = RankInventory(),
    val players: MutableList<PlayerState> = mutableListOf(),   // MutableList
    val playHistory: MutableList<PlayEvent> = mutableListOf(), // MutableList
    val undoStack: MutableList<PlayEvent> = mutableListOf()    // MutableList
) {
    fun applyEvent(event: PlayEvent): ValidationResult {
        // v3.2: 应用前校验
        val before = StateValidator.validateBeforeApply(event, this)
        if (before is ValidationResult.Invalid) return before

        event.ranks.forEach { inventory.deduct(it) }
        players[event.player.ordinal].playedRanks.addAll(event.ranks)
        players[event.player.ordinal].estimatedRemain -= event.ranks.size
        playHistory.add(event)
        undoStack.add(event)

        // v3.2: 应用后校验
        val after = StateValidator.validateAfterApply(this)
        if (after is ValidationResult.Invalid) {
            // 自动回滚
            undoLastEvent()
            return after
        }
        return ValidationResult.Valid
    }

    fun undoLastEvent(): PlayEvent? {
        val event = undoStack.removeLastOrNull() ?: return null
        event.ranks.forEach { inventory.restore(it) }
        players[event.player.ordinal].playedRanks.removeAll(event.ranks)
        players[event.player.ordinal].estimatedRemain += event.ranks.size
        playHistory.removeAt(playHistory.lastIndex)
        return event
    }

    fun newGame() {
        inventory.reset(config)
        players.forEach {
            it.playedRanks.clear()
            it.estimatedRemain = config.cardsPerPlayer()
        }
        playHistory.clear()
        undoStack.clear()
    }
}
```

**关键**：撤销是**撤销整个出牌事件**（含玩家状态恢复），不是简单的 +1 计数。
**v3.2 新增**：applyEvent 前后自动调用 StateValidator，校验失败自动回滚。

### StateValidator（v3.2 新增 — 状态校验器）

```kotlin
object StateValidator {
    /** 应用事件前：库存/手牌是否足够 */
    fun validateBeforeApply(event: PlayEvent, session: GameSession): ValidationResult {
        for (rank in event.ranks) {
            if (session.inventory.remaining[rank.ordinal] <= 0)
                return ValidationResult.Invalid("${rank.displayName} 库存已为 0")
        }
        val player = session.players[event.player.ordinal]
        if (player.estimatedRemain < event.ranks.size)
            return ValidationResult.Invalid("${event.player} 剩余 ${player.estimatedRemain}，不足 ${event.ranks.size}")
        return ValidationResult.Valid
    }

    /** 应用事件后：库存不能为负、已出不超初始 */
    fun validateAfterApply(session: GameSession): ValidationResult {
        Rank.values().forEach { rank ->
            if (session.inventory.remaining[rank.ordinal] < 0)
                return ValidationResult.Invalid("${rank.displayName} 库存为负")
            if (session.inventory.played[rank.ordinal] > session.config.initialCount(rank))
                return ValidationResult.Invalid("${rank.displayName} 已出超初始")
        }
        return ValidationResult.Valid
    }

    /** 断点续记：remaining + played == initialCount */
    fun validateConsistency(session: GameSession): ValidationResult {
        Rank.values().forEach { rank ->
            val sum = session.inventory.remaining[rank.ordinal] + session.inventory.played[rank.ordinal]
            if (sum != session.config.initialCount(rank))
                return ValidationResult.Invalid("${rank.displayName} 状态不一致：${sum} != ${session.config.initialCount(rank)}")
        }
        return ValidationResult.Valid
    }
}

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}
```

---

## 5. 架构（v3 六层）

```
┌────────────────────── 采集层 Capture ──────────────────────┐
│ ScreenCaptureEngine   MediaProjection → VirtualDisplay     │
│                       → ImageReader (RGB565, ROI direct)   │
│ RoiManager            归一化 ROI 坐标 (0..1)                │
└────────────────────────────┬────────────────────────────────┘
                             ↓
┌────────────────────── 事件检测层 Detect (v3 新增) ─────────┐
│ FrameDiffDetector     ROI 帧差（轻量，2-4 fps）             │
│ PlayDetector          变化区域分析 → 牌区判定 → 牌数变化    │
│                       （过滤聊天/动画/倒计时/特效）          │
└────────────────────────────┬────────────────────────────────┘
                             ↓  (仅"确认出牌"才触发)
┌────────────────────── 识别层 Vision ───────────────────────┐
│ FixedLayoutSlicer     固定网格切片（非轮廓）                │
│ TemplateClassifier    模板匹配主（15 个 Rank 模板，<10ms）  │
│ OcrFallback           ML Kit 辅（模板置信度 <0.85 时触发）  │
│ RecognitionResult     rank + confidence + bbox             │
└────────────────────────────┬────────────────────────────────┘
                             ↓  (只产 Rank 结果，不碰状态)
┌────────────────────── 决策层 Decision ─────────────────────┐
│ ConfidenceFilter      >95% 自动 / 70-95% 待确认 / <70% 忽略 │
│ PendingQueue          中置信度排队等用户确认（5 秒超时忽略）│
│ PlayEventBuilder      RecognitionResult → PlayEvent        │
│ RuleEngine            规则校验（库存是否足够？轮次？）       │
└────────────────────────────┬────────────────────────────────┘
                             ↓
┌────────────────────── 状态层 Domain ───────────────────────┐
│ GameSession           牌局会话（含 DeckState + Players）    │
│ RankInventory         remaining[15] + played[15]           │
│ PlayerState ×6        6 玩家已出 Rank + 估计剩余            │
│ PlayHistory           完整事件流（可回放）                   │
│ UndoStack             事件级撤销栈                          │
│ PersistManager        DataStore JSON 持久化                 │
└────────────────────────────┬────────────────────────────────┘
                             ↓
┌────────────────────── UI 层 Presentation ──────────────────┐
│ FloatingWindowService  Foreground Service 宿主             │
│ FloatingWindowView     默认关键牌 / 展开 / 调试 三模式      │
│ MainActivity           设置 / 校准 / 模板采集 / 历史        │
└────────────────────────────────────────────────────────────┘
```

**解耦原则不变**：识别层只产 `RecognitionResult`，状态层只收 `PlayEvent`，决策层桥接。

---

## 6. 三级识别链路（v3 核心）

```
牌区域切片
    ↓
TemplateClassifier（模板匹配）
    ├── 置信度 ≥ 0.85 → 直接采用
    ├── 置信度 0.60-0.85 → 触发 OcrFallback
    └── 置信度 < 0.60 → 标记低置信
    ↓ (仅 0.60-0.85 分支)
OcrFallback（ML Kit Text Recognition）
    ├── OCR 与模板一致 → 提升置信度采用
    ├── OCR 与模板不一致 → 取较高者，降级到待确认
    └── OCR 失败 → 用模板结果，降级到待确认
    ↓
RecognitionResult（rank + finalConfidence）
    ↓
ConfidenceFilter
    ├── ≥ 0.95 → 自动扣减
    ├── 0.70-0.95 → PendingQueue 待确认（5 秒超时忽略）
    └── < 0.70 → 忽略（调试模式可见）
```

### 模板匹配实现

```kotlin
class TemplateClassifier {
    // 15 个 Rank 模板图，从游戏截图采集（每种 Rank 采 1 张）
    private val templates: Map<Rank, Mat>

    fun classify(cardImage: Mat): RecognitionResult {
        var bestRank: Rank? = null
        var bestScore = 0.0
        templates.forEach { (rank, template) ->
            val result = Mat()
            Imgproc.matchTemplate(cardImage, template, result, Imgproc.TM_CCOEFF_NORMED)
            val score = Core.minMaxLoc(result).maxVal
            if (score > bestScore) { bestScore = score; bestRank = rank }
            result.release()
        }
        return RecognitionResult(bestRank!!, bestScore.toFloat(), cardImage.boundingBox())
    }
}
```

- **速度**：15 次模板匹配 × <1ms = <10ms/张
- **模板采集**：App 内"模板采集"模式，用户每种 Rank 截一张即可
- **UI 变化应对**：重新采集模板（一次性，2 分钟）

---

## 7. PlayDetector（v3 新增 — 出牌事件判断，v3.2 增加 Hash 去重）

```kotlin
class PlayDetector {
    private var lastPlayAreaHash: String? = null   // v3.2: 牌区域 Hash

    fun detect(frameDiff: FrameDiff, roiConfig: RoiConfig): PlayDetection? {
        // 1. 变化区域是否在牌区内？
        if (!isInPlayArea(frameDiff.changedRegion, roiConfig.playArea)) return null

        // 2. 变化区域形状是否像牌（横向矩形群）？
        val cardLikeRegions = filterCardLikeRegions(frameDiff.changedBlobs)
        if (cardLikeRegions.isEmpty()) return null

        // 3. 牌数量是否变化（与上次相比）？
        val currentCardCount = cardLikeRegions.size
        val lastCardCount = getLastPlayCardCount()
        if (currentCardCount == 0 || currentCardCount == lastCardCount) return null

        // 4. v3.2: 牌区域 Hash 去重 — 牌面像素未真正变化则忽略
        val playAreaHash = computePlayAreaHash(frameDiff.currentFrame, cardLikeRegions)
        if (playAreaHash == lastPlayAreaHash) return null
        lastPlayAreaHash = playAreaHash

        // 5. 去抖：800ms 内只认一次
        if (isWithinDebounce()) return null

        return PlayDetection(
            cardRegions = cardLikeRegions,
            cardCount = currentCardCount,
            areaHash = playAreaHash,            // v3.2: 供下游去重/日志
            timestamp = System.currentTimeMillis()
        )
    }

    /** v3.2: 对牌区域采样生成 pHash，快速判断牌面是否真正变化 */
    private fun computePlayAreaHash(frame: Mat, regions: List<Rect>): String {
        // 降采样 8x8 灰度 → 中值二值化 → 64bit hash
        // 极轻量（<1ms），避免同一牌面的重复识别
    }
}
```

**v3.2 Hash 的作用**：
- 避免「牌打出后停留期间」的帧差重复触发识别
- 两次出牌相同时（如连续出单张 7），Hash 不同仍能识别
- 断点续记时可用于校验状态一致性

**过滤的场景**：
- 聊天弹幕（不在牌区）
- 动画特效（形状不像牌）
- 倒计时（位置固定、形状不变）
- 牌未变化（Hash 相同）
- 连续帧差（去抖）

---

## 8. 浮窗设计（v3 优化 — 默认关键牌）

### 默认模式：关键牌视图

```
┌──────────────┐
│ 剩 158       │
│ ─────────── │
│ 大王  2      │
│ 小王  3      │
│ 2    12     │
│ A    14     │
│ K    15     │
│ ─────────── │
│ ▼ 展开全部   │
└──────────────┘
```

够级关键牌：大王、小王、2、A、K（级牌/大牌），默认只显示这 5 种。

### 展开模式：全部 Rank

```
┌──────────────┐
│ 剩 158       │
│ ─────────── │
│ 大王  2      │
│ 小王  3      │
│ 2    12     │
│ A    14     │
│ K    15     │
│ Q    16     │
│ J    16     │
│ 10   15     │
│ ...         │
│ 3     6     │
│ ▲ 收起       │
└──────────────┘
```

### 调试模式

```
┌──────────────┐
│ 最近识别:    │
│ 7  0.98  ✓  │
│ K  0.82  ❓ │
│ 10 0.45  ✗  │
│ ─────────── │
│ 上次扣减:    │
│ 左家 77KK ✓ │
│ [撤销]       │
└──────────────┘
```

**切换**：长按浮窗标题栏 → 模式选择。
**防误触**：牌格区消费点击，间隙穿透到下层游戏。

---

## 9. 文件结构（v3 调整）

```
D:/HuaweiMoveData/Users/张大脸小太阳/Documents/workbuddy/记牌器开发/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/cardcounter/
│   │   │   ├── MainActivity.kt
│   │   │   ├── domain/                          # 领域模型（纯逻辑）
│   │   │   │   ├── Rank.kt                      # Rank 枚举 (15)
│   │   │   │   ├── RankInventory.kt             # remaining[15]
│   │   │   │   ├── PlayerState.kt               # 6 玩家
│   │   │   │   ├── PlayEvent.kt                 # 出牌事件
│   │   │   │   ├── GameSession.kt               # 牌局会话 + 事件级撤销
│   │   │   │   ├── GameRulesConfig.kt           # 4 副 + 含王 + trimLowCards
│   │   │   │   ├── RecognitionResult.kt         # rank + confidence
│   │   │   │   └── RuleEngine.kt                # 规则校验
│   │   │   ├── capture/                         # 采集层
│   │   │   │   ├── ScreenCaptureEngine.kt       # MediaProjection + VirtualDisplay
│   │   │   │   └── RoiManager.kt                # 归一化 ROI 坐标
│   │   │   ├── detect/                          # 事件检测层 (v3 新增)
│   │   │   │   ├── FrameDiffDetector.kt         # ROI 帧差
│   │   │   │   └── PlayDetector.kt              # 出牌事件判断
│   │   │   ├── vision/                          # 识别层
│   │   │   │   ├── FixedLayoutSlicer.kt         # 固定网格切片
│   │   │   │   ├── TemplateClassifier.kt        # 模板匹配（主）
│   │   │   │   ├── OcrFallback.kt               # ML Kit（辅）
│   │   │   │   └── TemplateManager.kt           # 模板采集/存储
│   │   │   ├── decision/                        # 决策层
│   │   │   │   ├── ConfidenceFilter.kt
│   │   │   │   ├── PendingQueue.kt
│   │   │   │   └── PlayEventBuilder.kt
│   │   │   ├── state/                           # 状态层
│   │   │   │   ├── GameSessionRepository.kt     # 会话仓储
│   │   │   │   └── PersistManager.kt            # DataStore JSON
│   │   │   ├── service/
│   │   │   │   ├── FloatingWindowService.kt
│   │   │   │   └── ScreenCaptureService.kt
│   │   │   └── ui/
│   │   │       ├── FloatingWindowView.kt        # 三模式浮窗
│   │   │       ├── CalibrationActivity.kt       # ROI 框选
│   │   │       ├── TemplateCollectionActivity.kt # 模板采集 (v3 新增)
│   │   │       └── SettingsActivity.kt
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   ├── values/strings.xml
│   │   │   └── raw/
│   │   │       └── templates/                   # 15 个 Rank 模板图
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

**v3 变化**：
- 新增 `detect/` 包（PlayDetector）
- 新增 `TemplateCollectionActivity.kt` + `TemplateManager.kt`
- `Card.kt` → `Rank.kt`（不再有 Card 概念）
- `GameState.kt` → `GameSession.kt`（含事件级撤销）
- 去掉 `dataset/` 和 `training/` 目录（不再用 CNN）

---

## 10. 关键依赖（v3 调整）

- `androidx.core:core-ktx`
- `androidx.lifecycle:lifecycle-service`
- `androidx.datastore:datastore-preferences`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android`
- `org.opencv:opencv:4.10.0`               # 模板匹配（P4 引入）
- `com.google.mlkit:text-recognition`       # OCR 兜底（二级）
- **移除**：`tensorflow-lite`（v3 不用 CNN）

---

## 11. 实施阶段（v3 重规划 — 6 阶段）

| Phase | 目标 | 关键产物 | 可用性 |
|---|---|---|---|
| **P1 浮窗只读框架** | 关键牌浮窗显示、拖拽、重置、撤销、展开/收起 | 浮窗 UI 骨架（显示初始状态） | ⚠️ 框架就绪，待 P4 自动识别 |
| **P2 数据模型** | Rank/Inventory/GameSession/PlayEvent/PlayerState + 持久化 | 状态模型完备，重启不丢 | ✅ |
| **P3 截屏 + 事件检测** | MediaProjection + VirtualDisplay + PlayDetector + ROI 校准 | 能感知"真实出牌" | ✅ |
| **P4 模板识别 + 自动扣减** | 模板采集 Activity + TemplateClassifier + OcrFallback + 单牌自动扣 | **首个可用版本——全自动记牌** | ✅ 日常可用 |
| **P5 多牌 + 置信度 + 规则引擎 + 异常恢复** | 多牌批量、ConfidenceFilter、PendingQueue、RuleEngine、事件级撤销、**异常恢复（状态校验/自愈/断点续记）** | **全自动多牌版上线** | ✅ |
| **P6 打磨 + 可选 AI 预测** | 自动靠边、防误触、调试模式、概率预测 | 日常稳定 + 高级分析 | ✅ |

**关键调整**：
- 软件完全自动运行，浮窗只读显示，**不支持手动点按记牌**
- P1 为 UI 框架，首个日常可用版本为 **P4**（自动识别上线后）
- 去掉 v2 的 P0 数据采集（模板匹配只需每种 Rank 采 1 张，在 P4 内完成）
- P2 数据模型提前（不依赖截屏，纯逻辑可先做）

---

## 12. 性能指标拆分（v3 细化）

| 模块 | 目标 | 说明 |
|---|---|---|
| 截图 | ≤ 50ms | VirtualDisplay + ImageReader RGB565 |
| ROI 检测 | ≤ 20ms | FrameDiff 降采样灰度 abs-diff |
| 切牌 | ≤ 30ms | FixedLayoutSlicer 网格切片 |
| 模板识别 | ≤ 50ms | 15 模板 × <1ms（主路径，OCR 仅低置信触发） |
| 状态更新 | ≤ 20ms | GameSession.applyEvent |
| UI 刷新 | ≤ 50ms | View invalidate |
| **总计** | **≤ 220ms** | v3.1 采纳第三轮更激进目标 |

**耗电**：< 5%/小时（ROI 2-4 fps + 仅 PlayDetector 确认才识别）

---

## 13. Android 后台限制规避（不变）

| 版本 | 限制 | 规避 |
|---|---|---|
| 10+ | MediaProjection 需前台服务 | `FOREGROUND_SERVICE` + `mediaProjection` 类型 |
| 12+ | 后台服务启动限制 | 用户从浮窗触发 |
| 14+ | 前台服务类型严格 | `FOREGROUND_SERVICE_MEDIA_PROJECTION`；P1 用 `specialUse`；P3 改 `mediaProjection` |

---

## 14. 风险与缓解（v3 更新）

| 风险 | 缓解 |
|---|---|
| 模板匹配误识别 | OCR 兜底 + 置信度过滤 + 待确认队列 + 事件级撤销 |
| 游戏 UI 改版 | 重新采集 15 张模板（2 分钟）+ 重校准 ROI |
| 聊天/动画误触发 | PlayDetector 事件判断层过滤 |
| MediaProjection 每次需确认 | 引导页说明 |
| Android 14 前台服务限制 | `mediaProjection` 类型，P1 用 `specialUse`；P3 改 `mediaProjection` |
| 不同手机分辨率 | ROI 归一化坐标 (0..1)，模板按比例缩放 |
| 多乐够级是否带王 | 默认 198 张（33张×6人），可切换 216 张 |
| 浮窗遮挡游戏 | 透明区穿透 + 默认关键牌小窗 |

---

## 15. v2 → v3 改动总结

| # | v2 → v3 | 原因 |
|---|---|---|
| 1 | `remaining[54]` → **`remaining[15]`** | 够级不区分花色，A=16 而非 ♠A=4 |
| 2 | 识别 CardId(54类) → **只识别 Rank(15类)** | 减半工作量，符合够级规则 |
| 3 | CNN 为主 → **模板匹配主 + OCR 辅** | 固定 UI 下模板立即可用、无需训练集 |
| 4 | 无 PlayDetector → **新增出牌事件判断层** | 过滤聊天/动画/特效的无效识别 |
| 5 | GameState 扁平 → **GameSession + 事件级 UndoStack** | 撤销整次出牌事件，非 +1 计数 |
| 6 | 4×14 全显示 → **默认关键牌 + 展开** | 手机屏小，聚焦够级关键牌 |
| 7 | P0 数据采集 → **去掉，模板在 P4 内采** | 模板只需 15 张参考图 |
| 8 | P2 持久化 → **P2 数据模型（含持久化）** | 数据模型提前，纯逻辑可先做 |
| 9 | 性能 ≤500ms → **拆分到各模块，总 ≤220ms** | 工程化指标可追踪 |

---

## 16. 待确认

- **多乐够级是否带王**？默认 198 张（33张×6人），可切换 216 张。
- **下一步从 P1 开始**？建议：P1 浮窗只读框架 → P2 数据模型，可并行。

---

## 17. 第三轮审查 7 项修改逐项确认（v3.1）

> 针对第三轮审查提出的 7 个必须修改点，逐项标注在 v3 计划文件中的落地位置。

| # | 优先级 | 必须修改 | v3 落地位置 | 状态 |
|---|---|---|---|---|
| 1 | ★★★★★ | `remaining[54]` → Rank 库存 | 第 4 节「Rank 枚举（15 个值）」+「RankInventory」— `remaining: IntArray(Rank.SIZE)` 即 [15]，A=16, 王=4 | ✅ 已落地 |
| 2 | ★★★★★ | 删除花色识别 | 第 3 节「只识别 Rank(15类)」+ 第 5 节识别层无 SuitClassifier + 第 9 节 vision/ 包无 SuitClassifier 文件 | ✅ 已落地 |
| 3 | ★★★★★ | ML Kit 降级为备用 | 第 3 节「模板匹配主 + OCR 辅」+ 第 6 节三级链路（模板≥0.85 直接用 / 0.60-0.85 才触发 OCR）+ 第 12 节依赖标注「OCR 兜底（二级）」 | ✅ 已落地 |
| 4 | ★★★★★ | 增加 PlayEvent | 第 4 节「PlayEvent（出牌事件）」数据类 + 第 3 节技术路线含 PlayEvent 环节 + 第 5 节决策层 PlayEventBuilder | ✅ 已落地 |
| 5 | ★★★★ | 固定 ROI 代替轮廓检测 | 第 4 节「FixedLayoutSlicer 固定网格切片（非轮廓）」+ 第 3 节保留不变表「牌张分割：固定布局网格切片（非轮廓检测）」+ 第 7 节 PlayDetector 配合 ROI | ✅ 已落地 |
| 6 | ★★★★ | 增加 GameSession | 第 4 节「GameSession（牌局会话）」— 含 applyEvent / undoLastEvent（事件级撤销）/ newGame | ✅ 已落地 |
| 7 | ★★★ | 增加 PlayerState | 第 4 节「PlayerState（玩家状态）」— 含 id/position/estimatedRemain/playedRanks，第 5 节状态层 PlayerState×6 | ✅ 已落地 |

### v3.1 采纳的第三轮新建议

| 建议 | v3 → v3.1 | 落地位置 |
|---|---|---|
| 性能目标更激进 | 总 ≤290ms → **≤220ms**（模板识别 100→50ms，合并出牌判定到 ROI 检测） | 第 12 节 |
| P5 增加异常恢复 | P5 补充「异常恢复（状态校验/自愈/断点续记）」 | 第 12 节 |

### 关键确认

- v3 计划文件**不再是"通用扑克 OCR 记牌器"**，而是**"基于牌局状态管理的视觉事件识别系统"**
- 识别层只产 `RecognitionResult(rank, confidence)`，**不碰状态**
- 状态层只收 `PlayEvent`，**不知识别细节**
- 撤销是**事件级**（整次出牌回滚），非 +1 计数
- 模板匹配为主（<10ms），ML Kit 仅在模板置信度 0.60-0.85 时触发兜底
- 花色完全不识别、不统计、不存储

**结论：7 项必须修改 + 2 项新建议全部落地，可进入正式开发阶段。**

---

## 18. v3.2 设计冻结（架构最后修改）

> v3.2 为架构冻结前最后修改，完成后不再大改架构，直接进入 P1 编码。

| # | 修改 | v3.2 落地位置 | 状态 |
|---|---|---|---|
| 1 | GameRulesConfig 管理牌数量 | 第 4 节「GameRulesConfig」— `initialCount(rank)` 动态计算，Rank 枚举移除硬编码 initialCount | ✅ |
| 2 | PlayDetector 增加 Hash | 第 6 节 — `computePlayAreaHash()` pHash 去重，避免牌停留期间重复识别 | ✅ |
| 3 | Recognition 增加 Conflict 状态 | 第 4 节「RecognitionResult」— 新增 `RecognitionStatus` 枚举（OK/LOW_CONFIDENCE/CONFLICT）+ templateRank/ocrRank 字段 | ✅ |
| 4 | GameSession 改 Mutable | 第 4 节「GameSession」— data class → class，players/playHistory/undoStack 改 MutableList | ✅ |
| 5 | 增加 StateValidator | 第 4 节「StateValidator」— validateBeforeApply / validateAfterApply / validateConsistency，applyEvent 自动校验+回滚 | ✅ |

### v3.2 设计冻结声明

**自 v3.2 起，架构设计冻结。后续仅允许：**
- P1–P6 编码实现
- 编码过程中发现的小修（不改架构）
- 模板/ROI 校准等配置调整

**不再允许：**
- 架构层级调整
- 数据模型重构
- 识别方案更换
- Phase 顺序重排

### domain 层最终文件清单（冻结）

```
domain/
├── Rank.kt                  # 枚举 15 值（无硬编码数量）
├── GameRulesConfig.kt       # 副数+含王 → initialCount/totalCards/cardsPerPlayer
├── RankInventory.kt         # remaining[15] + played[15] + lastSeen[15]
├── PlayEvent.kt             # 出牌事件 + PlayerPosition + EventSource
├── PlayerState.kt           # 6 玩家状态
├── RecognitionResult.kt     # rank + confidence + status + templateRank/ocrRank
├── GameSession.kt           # Mutable 牌局会话 + 事件级撤销
├── StateValidator.kt        # 前后校验 + 一致性校验
└── RuleEngine.kt            # 规则校验（P5 实现）
```

**下一步：进入 P1 浮窗只读框架开发。**
