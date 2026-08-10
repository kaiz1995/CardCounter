# 多乐够级记牌器——实施就绪规格

> 版本：v5.9 对齐稿
>
> 边界：本文件是编码前规格，不包含业务代码。

## 1. 目标与固定范围

- 首版仅适配：华为 Mate 60，HarmonyOS 4.2.0，游戏横屏。
- 游戏规则固定：6 人、4 副、198 张；大王4、小王4、3有6张、4有8张，其余 Rank 各16张。
- 只显示一个左上单行横条，显示15个 Rank 的外面剩余数；不显示总剩。
- 无逐玩家统计、无手工 Rank 加减、无识别确认弹窗、无联网、无整局录像。

唯一公式：

    OutsideRemaining
    = InitialInventory - SelfLockedHand - OutsidePlayed

自己后续出牌不改变 OutsideRemaining。

## 2. 最小领域状态

持久化状态只包含：

| 状态 | 含义 |
|---|---|
| InitialInventory[15] | 固定库存 |
| SelfLockedHand[15] | 正式出牌前锁定的自己实际持牌 |
| OutsidePlayed[15] | 五名对手累计公开正式出牌 |
| SessionPhase | PRE_PLAY / ACTIVE / PAUSED / CLOSED |
| EventLog | 事件唯一事实源 |
| RuleVersion | 固定规则版本 |
| ZoneCalibrationVersion | 六区标定版本 |
| TemplateVersion | 视觉模板版本 |

禁止持久化：玩家 ID、队伍、座位、逐人手牌、逐人出牌、过牌历史、完整屏幕帧。

事件只需要：

| 事件 | 作用 |
|---|---|
| NewSession | 创建新局 |
| SelfHandLocked | 写入 SelfLockedHand |
| OutsideCardsPlayed | 增加 OutsidePlayed |
| SelfCardsPlayed | 诊断和去重，不改库存 |
| Reverted | 撤销最后一次已接受 OutsideCardsPlayed |
| SessionPaused | 停止本局自动记牌 |
| SessionClosed | 结束旧会话 |

正式出牌类型只有两种：普通出牌、开点。

## 3. 首次启动与会话流程

    启动应用
    → 检查浮窗授权
    → 用户点击开始截屏
    → 系统截屏授权
    → 显示左上横条
    → PRE_PLAY
    → 锁定 SelfLockedHand
    → ACTIVE

系统授权属于 Android 必要交互，不属于识别确认。

ACTIVE 中：

- 正式牌组稳定落入 SELF 展示区：记录 SelfCardsPlayed，不扣。
- 正式牌组稳定落入任一 OUTSIDE 展示区：记录 OutsideCardsPlayed，刷新横条。
- 不确定、跨区、区外、Rank 不稳定、未知语义：RETRY；事件窗口结束仍无结论则 PAUSE。

PAUSED 是本局终态：

- 不再自动记牌。
- 撤销不解除暂停。
- 只有新局重置可进入新的 PRE_PLAY。

## 4. 视觉处理边界

帧处理顺序：

    MediaProjection 帧
    → 裁出游戏画布
    → 排除左上横条与系统控件
    → 识别六个展示区
    → 每区独立状态机
    → Rank 锚点与牌边缘分割
    → 正式出牌语义门禁
    → SELF / OUTSIDE
    → 事件日志归约

六区名称只用于瞬时视觉处理：

    TOP
    LEFT_UP / RIGHT_UP
    LEFT_DOWN / RIGHT_DOWN
    SELF

标定规则：

- 使用画布归一化坐标，禁止写死像素。
- 以头像向牌桌内侧的方向生成候选区。
- 再收缩为六个不重叠接受安全区。
- 一组牌的全部 Rank 锚点与可见牌边缘必须完整落在同一区。
- 跨区、区外、遮挡区均为 UNKNOWN，禁止最近邻猜测。

每区独立经历：

    EMPTY → APPEARING → STABLE → CONSUMED

旧牌可留在原区；另一展示区的新牌组仍可产生新事件。

## 5. 浮窗规格

- 固定左上、透明灰底、单行。
- 初始状态显示15个 Rank；可收起为图标。
- 只保留：收起/展开、新局重置、撤销、调试状态。
- 不支持正常拖动。
- 横条最终尺寸和偏移从属于六区安全边界；冲突时只能缩小单行单元。
- 红色 PAUSE 状态显示“本局停记”；调试状态可显示原因码。

## 6. 本地数据与恢复

- 不申请网络权限，不上传任何数据。
- 事件日志与会话元数据保存在应用私有存储。
- RETRY 所需 ROI 环形缓冲只存内存；不保存完整画面或整局视频。
- 应用或截屏中断后：存在旧会话则重放为 PAUSED；不存在旧会话而已在正式阶段，则要求从新局开始观察。

## 7. 模块边界

| 模块 | 责任 |
|---|---|
| launcher | 浮窗授权、截屏授权、启动状态 |
| capture | MediaProjection 生命周期、帧获取、方向变化 |
| vision | 画布裁切、控件过滤、六区、Rank 识别、稳定判定 |
| domain | 固定库存、事件归约、不变量、PAUSE |
| persistence | 事件日志、版本、重放、会话恢复 |
| overlay | 单横条、状态颜色、控制按钮 |

旧 v3.2 的 PlayerState、PlayerPosition、Team、手工扣减、旧库存模型和竖向浮窗不得迁入新领域层。

## 8. 仍需真机或完整素材验证

这些事项不能在当前电脑上伪造完成：

| 门禁 | 最小验证 |
|---|---|
| G1 截屏 | Mate60 上确认截屏授权、实际画布、帧率、浮窗可见性、耗电 |
| G2 自己手牌 | 交贡/还贡/买牌后的不同布局，全部 Rank 与边缘双计数正确 |
| G3 六区事件 | 六区覆盖所有正式牌组；跨区/区外从不被接受 |
| G4 来源 | SELF 与 OUTSIDE 二分类达到门槛 |
| G5 生命周期 | 结算、发牌、等待动画不会误清零；恢复不重建基线 |
| G6 整局 | 15个 Rank 最终库存与人工标注一致 |

完整素材最低应覆盖：发牌、交贡、还贡、买3、买4、普通出牌、开点、过牌、结算、新局发牌。

## 9. 旧工程处理

- 当前工程缺少 gradlew.bat，无法做离线构建验证。
- 当前 MainActivity 存在未转义引号文本，预期无法直接编译。
- 正式编码开始时，先建立可构建入口和 v5.9 最小领域层，再迁移经验证可复用的 Android 基础设施。

## 10. 编码开始条件

用户明确下令进入编码后，按以下顺序执行：

1. 补齐构建入口并完成空工程编译。
2. 实现固定规则、事件归约和不变量测试。
3. 在 Mate60 做 G1 截屏/浮窗探针。
4. 再接入自己手牌和六区视觉识别。
5. 用完整素材逐关验证 G2 到 G6。

没有完整素材或真机证据时，不把 G1 到 G6 标记为完成。
