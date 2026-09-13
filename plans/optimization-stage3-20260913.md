# 阶段 3 实施方案：性能与 UI 修复

> 前置：阶段 1（会计层）与阶段 2（识别层）已完成并验收。
> 本文件是阶段 3 的施工规格，供子代理执行。

---

## 目标

修复 3 个问题：`SessionRepository` 每帧重建（P0-4）、`OverlayService` 隐藏后失联（P1-5）、两套坐标系并存（P1-8）。

## 问题 1（P0-4）：`SessionRepository` 每帧重建 + 全量 JSON 读写

### 现状

`CaptureProbeService` 中有 5 处 `SessionRepository(this)` 调用：

| 行 | 场景 | 频率 |
|---|---|---|
| L175 | `startCapture` 读 phase | 一次 |
| L411 | `lockPrePlayHand` | 开局一次 |
| L422 | `completeManualNewGame` | 手动新局一次 |
| L458 | `detectFormalPlays`（**死代码，阶段 2 已删**） | — |
| L578 | `finalizeRoiEvent` | **每次出牌确认** |
| L751 | `resetSessionTracking` 读 `selfLockedHandCount` | 每次重置 |

而 `SessionRepository` 的构造函数：

```kotlin
class SessionRepository(context: Context) {
    private val store = SessionStore(context.applicationContext)
    var state: GameState = store.load() ?: GameReducer.newSession()
        private set
    init {
        store.save(state)          // ← 构造即写盘
    }
    fun dispatch(event: GameEvent): GameState {
        state = GameReducer.apply(state, event)
        store.save(state)          // ← 每次 dispatch 全量序列化写盘
        return state
    }
}
```

`store.load()` = `SharedPreferences.getString()` 读全量 JSON 字符串 → `JSONArray` 解析 → 逐个 `decodeEvent` → `GameReducer.replay()` 全量重放。
`store.save()` = 全量 `encodeEvent` → `JSONArray.toString()` → `SharedPreferences.edit().putString().apply()`。

**成本**：单局出牌 50+ 次，每次都全量读写。事件日志随对局增长，O(n²) 行为。

### 修复方案（三选一，推荐 A）

**方案 A（推荐）：单例 + 内存状态 + 写入节流**

```kotlin
class SessionRepository private constructor(context: Context) {
    private val store = SessionStore(context.applicationContext)
    var state: GameState = store.load() ?: GameReducer.newSession()
        private set

    fun dispatch(event: GameEvent): GameState {
        state = GameReducer.apply(state, event)
        scheduleSave()          // 节流：延迟 300ms 合并写入
        return state
    }

    /** 强制立即落盘（进程可能被杀时调用）。 */
    fun flush() { store.save(state) }
    ...
}
```

- `scheduleSave()` 用 `Handler(Looper.getMainLooper())` 或 `HandlerThread` + `removeCallbacks` 合并 300ms 内的多次写。
- **风险**：进程被系统杀死时可能丢失最后 300ms 的事件。
- **缓解**：`onDestroy()` 与关键节点（开局锁牌、手动新局）调 `flush()`。
- **更好的做法**：`SessionStore` 改为**只追加**——保存时记录已写入的事件索引，只序列化新增事件。但 `SharedPreferences` 是 key-value，追加需要读改写整个字符串，收益有限。**保持全量写 + 节流即可。**

**方案 B：仅在服务内持有单个实例（最小改动）**

`CaptureProbeService` 增加 `private lateinit var repository: SessionRepository`，在 `onCreate` 里创建一次，所有方法复用。**不改 `SessionRepository` 本身**。

- 优点：改动面最小，风险最低。
- 缺点：不解决 `save()` 的全量写成本（但消除了构造时的 `load()`+`save()` 重复）。
- **注意**：`OverlayService.kt:51` 与 `MainActivity.kt:30,39` 也各有自己的实例。多进程/多 Service 场景下，**内存状态会不同步**——A 改了 state，B 的持有实例不知道。
  - 当前架构下 `OverlayService` 只在 `onStartCommand` 里读一次 state 用于渲染，`MainActivity` 只在 `onResume` 读一次，**它们读的是磁盘最新值，所以新建实例反而是对的**。
  - **唯一需要复用的是 `CaptureProbeService` 内部**（它高频读写）。

**方案 C：A + B 结合（我最终推荐的）**

1. `CaptureProbeService` 持有单个 `repository` 实例（消除高频构造）。
2. `SessionRepository.save` 加节流（消除高频全量写）。
3. `CaptureProbeService` 在 `onDestroy` 与关键节点调 `flush()`。
4. `OverlayService` / `MainActivity` **保持新建实例**（它们需要读磁盘最新值，且频率极低）。

**跨进程/跨 Service 一致性说明**：`SharedPreferences` 在**同一进程内**是共享的（Android 是内存缓存 + 异步写盘），所以 `CaptureProbeService` 写完后，`OverlayService` 新建实例 `load()` 能读到最新值（同进程）。**前提是两者在同一进程**（当前 `AndroidManifest.xml` 未声明 `android:process`，默认同进程 ✓）。

### 验收
- 新增单测：验证节流逻辑（若抽成可测的纯函数）；或至少验证 `dispatch` 后 `state` 正确且 `flush()` 可强制落盘。
- `SessionStoreTest.kt` 必须继续通过。

---

## 问题 2（P1-5）：`OverlayService` 隐藏后无法恢复

### 现状

```kotlin
private fun setHidden(value: Boolean) {
    hidden = value
    counter?.visibility = if (value) View.GONE else View.VISIBLE
    resetControl?.visibility = if (value) View.GONE else View.VISIBLE
    hideControl?.text = if (value) "显示" else "隐藏"      // ← 改了文字
}
```

`hideControl` 的 `visibility` **从未被设置为 GONE**——等等，让我重新核对。

实际代码（`OverlayService.kt:82-87`）：
```kotlin
private fun setHidden(value: Boolean) {
    hidden = value
    counter?.visibility = if (value) View.GONE else View.VISIBLE
    resetControl?.visibility = if (value) View.GONE else View.VISIBLE
    hideControl?.text = if (value) "显示" else "隐藏"
}
```

**修正我之前的判断**：`hideControl` 确实**没有**被设为 GONE，它保持可见并改文字为「显示」。所以**隐藏后是可以恢复的**。

但仍有真实问题：**`hideControl` 的位置是 `OverlayLayout.hideButton = Rect(10, 499, 68, 68)`——屏幕左下角**。隐藏时 `counter`（全屏 View）变 GONE，`hideControl` 独立存在，所以能看到「显示」按钮。**逻辑是通的。**

那么真正的问题是什么？**`ACTION_HIDE` 这个 action 从未被使用**：

```kotlin
when (intent?.action) {
    ACTION_SHOW -> setHidden(false)
    ACTION_HIDE -> setHidden(true)
}
```

`OverlayService.hide(context)` 存在但**全仓无调用**。而 `hideControl` 的点击是 `setOnClickListener { setHidden(!hidden) }`——直接改状态，不走 Intent。

**这引入一个跨进程/重建问题**：`OverlayService` 是 `START_STICKY`，若被系统杀掉重启，`onStartCommand` 会用 `intent == null` 走 `attachIfNeeded()`，此时 `hidden` 字段重置为 `false`，**但用户之前设的隐藏状态丢失**（浮窗会重新显示）。这是次要问题。

**我重新评估此项**：原判「点隐藏后永久失联」是**误判**，需要更正。真实问题降级为：
- `hidden` 状态未持久化，服务重启后丢失
- `hideControl` 尺寸由 `OverlayLayout.hideButton` 给的是 **68×68**，但 `control()` 创建的 TextView 是圆形背景 + 18f 文字，实际显示文字「显示」/「隐藏」（2 字）在 68px 圆内可能拥挤

### 修复方案

1. **保留 `hideControl` 可见性逻辑不变**（它是对的）。
2. **持久化 hidden 状态**：用 `SharedPreferences` 存一个布尔值，`onCreate` 时读取并应用。
3. **给 `hideControl` 加内边距/调整字号**，确保「隐藏」「显示」两字在圆内居中不溢出（可选，低优先级）。

### 验收
- 新增或用现有测试验证 `OverlayLayout.hideButton` 尺寸足以容纳 2 个 18f 汉字（可按经验规则：2 字 × 18sp ≈ 36sp 宽，68px 在 1280 设计宽度下对应约 68/1280 屏宽比例，实测需真机确认 → 标记为待真机项）。

---

## 问题 3（P1-8）：两套坐标系并存

### 精确现状（已测算）

| 座位 | PlayZone 识别带（1280×578 px） | 识别带中心 | OverlayLayout 面板 | 面板中心 | 中心偏差 |
|---|---|---|---|---|---|
| TOP | left=689, right=770 | 729.5 | x=439, w=350 | 614.0 | **+115.5** |
| LEFT_TOP | left=189, right=340 | 264.5 | x=49, w=344 | 221.0 | **+43.5** |
| LEFT_BOTTOM | left=180, right=359 | 269.5 | x=44, w=349 | 218.5 | **+51.0** |
| RIGHT_TOP | left=960, right=1099 | 1029.5 | x=868, w=344 | 1040.0 | -10.5 |
| RIGHT_BOTTOM | left=960, right=1099 | 1029.5 | x=866, w=346 | 1039.0 | -9.5 |

**宽度差异是设计使然**：识别带只扫每张牌左上角的 Rank 字符（很窄，81~139px）；面板要容纳 15 个 Rank 格子 + 出牌数（350px 左右）。**两者宽度本就不该相同。**

**现有测试的约束**（`OverlayLayoutTest.kt:18`）：
```kotlin
assertTrue(panel.y >= zone.rankY(DESIGN_HEIGHT) + zone.visibilityHeight)
```
→ 只断言面板在识别带**下方**（不遮挡）。这是设计意图。

### 诚实边界

**在没有真机浮窗截图的情况下，我无法验证面板 x 中心应该在哪里。**
- MediaProjection **不捕获 overlay 层**（已通过 `g2-analysis/*.png` 确认：所有截图都只有游戏画面，没有我们的浮窗）。
- 面板位置的"正确性"最终由用户在真机上的视觉判断决定。

因此本问题**不做猜测式坐标重排**。改为做**结构性的、确定有益的**改进：

### 修复方案

1. **建立单一坐标真相源**：`OverlayLayout` 的座位面板 x/宽/中心改为**从 `PlayZone` 派生**（或在 `OverlayLayout` 内用 `PlayZone` 的数据计算），消除"两处独立硬编码"的结构性风险。
   - 具体：`OverlayLayout.seatPanels` 改为计算属性，x 中心取 `PlayZone` 识别带中心（`(zone.left(W)+zone.right(W))/2 - width/2`），宽度保持现有值。
   - **但这会改变面板位置**（TOP 会右移 115px）→ 可能让真机显示变差。
   - **更保守的做法**：保留现有像素值不变，但在 `OverlayLayout` 中加**显式注释 + 一致性测试**，说明"面板 x 与识别带 x 的关系是待真机标定的"，并把偏差量记录在案。

2. **加一致性测试**（确定性、可在 CI 跑）：
   - 断言每个座位的面板在识别带下方（保留现有断言）。
   - 断言面板**水平覆盖**对应识别带（`panel.x <= zone.left(W)` 且 `panel.x + panel.w >= zone.right(W)`）——面板应包含识别带，因为面板更宽。
   - **实测**：TOP 面板 439~789 vs 识别带 689~770 → **覆盖 ✓**；LEFT_TOP 面板 49~393 vs 识别带 189~340 → **覆盖 ✓**；LEFT_BOTTOM 44~393 vs 180~359 → **覆盖 ✓**；RIGHT_TOP 868~1212 vs 960~1099 → **覆盖 ✓**；RIGHT_BOTTOM 866~1212 vs 960~1099 → **覆盖 ✓**。
   - **好消息**：所有面板都已水平覆盖对应识别带！所以**现有布局在"覆盖关系"上是自洽的**，只是中心不重合。

3. **结论**：**不做坐标改动**。加入上述覆盖性测试 + 把中心偏差记录为待真机标定项。原因：现有布局已满足"面板在下、水平覆盖识别带"的自洽性；中心对齐属于视觉标定，无真机依据前不动。

### 验收
- 新增 `OverlayLayoutTest` 断言：面板水平覆盖识别带、面板在识别带下方。
- 在 `OverlayLayout.kt` 顶部加注释说明两套坐标的关系与待标定项。

---

## 修正记录（重要）

本文件在编写过程中修正了总控文件 `optimization-master-20260913.md` 中的**一处误判**：

- **P1-5 原描述**：「`hideControl` 自己也 GONE，用户点隐藏后永久失联」→ **错误**。
  实际代码中 `hideControl` 的 visibility 从未被设为 GONE，它保持可见并改文字为「显示」，**可以恢复**。
  真实问题降级为「hidden 状态未持久化，服务重启后丢失」。
- 该误判已同步修正到总控文件。

**教训**：审查时对「隐藏/显示」这类需要交叉验证多个赋值语句的逻辑，必须把整个方法读完再下结论，不能只看前两行就推断。
