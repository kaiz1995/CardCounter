# 多乐够级记牌器——工作进度

更新时间：2026-09-13

> ⚠️ **本文件在 2026-08-01 ~ 2026-09-13 期间未同步。**
> 下方「已完成」保留 08-01 口径的历史记录，最新真实状态见本节。
> 接手时请同时查看 `git log`、`plans/` 与代码，勿只依赖本文档。

## 当前真实状态（2026-09-13）

### 分支拓扑（worktree 并行开发）

```
8d98b21 (08-13, v0.3.0 基线)
   ├─ codex/rank-accuracy    (08-13→08-14, 13 提交) 牌面识别：A 识别、模板融合
   └─ codex/count-positive   (08-14→08-15, 3 提交)  会计重构：v6 阶段 1/2/3
        ↑ 已于 2026-09-13 合并 = d826447
        ↑ 已于 2026-09-13 合入 main = b48d18f
```

| 分支 | HEAD | 说明 |
|---|---|---|
| **`main`** | **`b48d18f`** | **主线（最新）**：已接收全部成果 |
| `codex/count-positive` | `94dae59` | 来源分支：会计重构 + rank-accuracy + 文档回写 |
| `codex/positive-accumulate` | `bec72b6` | 主 worktree，内容较旧（历史使命已结束） |
| `codex/rank-accuracy` | `1a2a768` | 已并入 count-positive |
| `codex/count-accuracy` | `44c65fd` | |
| `backup/count-positive-20260913` | `d79dd26` | 合并前快照 |
| `backup/rank-accuracy-20260913` | `1a2a768` | 合并前快照 |

- worktree 位置：`Documents/workbuddy/.zcf/记牌器开发/{count-positive,rank-accuracy,count-accuracy}/`
- 合并专用 worktree：`Documents/workbuddy/_merge-main/`（已执行完，可删）
- 远端：`https://github.com/kaiz1995/CardCounter.git`（7 个分支已同步）

### 已完成（截至 2026-09-13）

- **G1 真机探针**：完成（2688×1216、约 59–60fps、双前台服务存活）。
- **G2 自己手牌识别**：完成（9/9 离线回归、真机锁牌 33 张、59.2fps 验收）。
- **G3 六区与来源分类**：完成（33 张边界、SELF/OUTSIDE、稳定去重、内存单飞）。
- **08-29 修复方案**：已落地（多点采样桌面判定、H0 只信 YOLO 不回退模板、
  `PLAY_REJECTED` 含 cards、RoiPlayEventCache early finalize 置 `reported`）。
- **08-30 架构修复方案 A**：已落地（同牌停留不再重复入账）。
- **v6 会计重构（阶段 1/2/3）**：已在 `codex/count-positive` 实现：
  - 阶段 1 手动新局：长按重置 800ms 触发 `newGame()` + `resetSession()`
  - 阶段 2 手牌差分：新增 `SelfHandCountTracker`（**只诊断，不生成事件**）
  - 阶段 3 正数会计：删 `PublicPlayCountAggregator`；
    `GameReducer` 新增 `withinConservation()`，越界改为**暂停而非 clamp**
- **合并 rank-accuracy → count-positive**：完成（`d826447`），62/62 单测通过。
- **文档回写**：`progress.md` / `task_plan.md` / `findings.md` 已按 09-13 真实状态更新
  （commit `94dae59`）。
- **合并 count-positive → main**：**完成**（合并提交 **`b48d18f`**）：
  - 冲突仅 1 处（`.gitignore`，两边添加了相同的 `.tmp*`/`*.tmp` 两行 → 取 count-positive 版）
  - `main` 侧零源码改动 → 源码零冲突，29 个源码文件全部自动合并
  - `README.md`（main 独有）自动保留
  - **验收**：`testDebugUnitTest` **62/62 通过**（0 fail / 0 error / 0 skip）；
    `assembleDebug` BUILD SUCCESSFUL；`git diff count-positive main -- '*.kt'` **为空**
  - 已 push 至远端（`bf8e172..b48d18f`）
  - 预演报告：`plans/merge-main-preview-20260913.md`

### 已完成（2026-09-13 补充）

- **交付物补齐**：`releases/数量正向累计版/` 已补全三件套（APK + source.zip + README）：
  - `card-counter-count-accuracy-debug.apk`（47,024,003 B）
    SHA256 `81464A09403E55698CE674D45A51F2B81C8A0A161828F01E3306C8E438480A81`
  - `cardcounter-数量正向累计版-source.zip`（35,279,529 B，`app/src` 全量 106 文件）
    SHA256 `A237756A8A49E7938309FA4811787497835157F3D67E9949FC7D89367BD67112`
  - README 补入交付物清单与校验；**README 已并入 main**（`11ae0c6`），
    分支侧同步（`3dc9d9f` on `codex/count-accuracy`）
- **`releases/上家误A证据版/`** APK 从 `rank-accuracy` worktree 复制到主仓库磁盘
  （SHA256 与 README 记录一致：`8D0FE963...`）；该版本无 source.zip（原设计）
- **备份归档**：`_git-rescue-20260913/release-数量正向累计版/` 与
  `release-上家误A证据版/`（含 `SHA256SUMS.txt`）
- **releases 现状（5 版）**：
  | 版本 | README 入库 | 二进制在磁盘 |
  |---|---|---|
  | 可用初版1 | ✅ | ✅ APK + source.zip |
  | 可用初版2 | ✅ | ✅ APK + source.zip |
  | 可用识别提升版 | ✅ | ✅ APK + source.zip（源包 328MB 异常大，含 build 产物） |
  | 上家误A证据版 | ✅ | ✅ APK（无源包） |
  | 数量正向累计版 | ✅ | ✅ APK + source.zip |

> ⚠️ **注意**：所有 `.apk` / `-source.zip` 均被 `.gitignore`
> （`releases/**/*.apk`、`releases/**/*-source.zip`）排除，**不入版本控制**，
> 仅存本地磁盘。README 是入库的唯一记录，SHA256 用于完整性校验。

### 未完成

1. **真机整局验证**：装 `11ae0c6` 的 APK 跑一局，验证 `FORMAL_PLAY` 数 = 实际出牌次数。
2. **G4 六区完整牌局验证**：未完成。
3. **G5 生命周期整局验证**：未完成。
4. **G6 真机完整牌局（15 Rank 最终正确率）**：未开始。
5. **验收门槛未达成**：`handoff.md` 要求 30 局独立验证集 / 3000 标注事件，
   实际开发集仅 9 局。
6. **老问题未修**：大王识别（`v0.3.0` 即记「大王完全没有被识别」）。
7. **架构不一致**：`OverlayLayout.seatPanels`（v5 五座位全宽框）与 PlayZone 归一化六区
   两套坐标系并存。
8. **主 worktree 索引异常**：索引指向空树（`git status` 显示大量 `A`），
   但 173 条目与工作区文件完好，无数据风险。
9. **`可用识别提升版` 源包异常**：328MB，含 build 产物与 `.git` 类内容，
   与另两版的 `src/` 结构不一致，建议重打包。

### 下一步顺序

1. 装 `11ae0c6` 的 APK 跑一局真机，验证 `FORMAL_PLAY` 数 = 实际出牌次数。
2. 推进 G4 六区完整牌局验证。
3. 统一 `OverlayLayout.seatPanels` 与 PlayZone 两套坐标系。
4. 修复主 worktree 索引异常；重打包 `可用识别提升版` 源包。

## 历史记录（2026-08-01 及以前，保留原口径）

> 以下内容记录时的阶段定位为「G2、G3 已完成；G4 进行中」，
> 后续工作重心已转向 v6 会计重构，此段作为历史留档。

## 已完成

- 固定规则：6 人、4 副、198 张；大王4、小王4、3共6、4共8，其余各16。
- 统计模型：`外面剩余 = 固定初始库存 - 自己锁定手牌 - 外面累计出牌`。
- 旧 `GameSession`、逐玩家模型、手工扣牌、竖向可拖浮窗、总剩显示已删除。
- 新领域模型：`Rank`、`RankCounts`、`GameEvent`、`GameState`、`GameReducer`。
- 新会话状态：`PRE_PLAY → ACTIVE → PAUSED → CLOSED`；PAUSED 不接受自动出牌事件。
- 本地事件持久化与重放：不联网、不保存完整录像。
- 左上固定只读横条：15 个 Rank，无总剩；点击只切换收起/展开，不改变牌数。
- `OverlayService`：浮窗服务和通知。
- `CaptureProbeService`：MediaProjection 帧流；首出前识别稳定手牌，首出时写入一次 `SelfHandLocked`；不保存完整帧。
- `MainActivity`：修复旧字符串问题，提供浮窗、截屏探针、新局重置入口。
- `gradlew.bat`、Gradle Wrapper、`local.properties` 已创建；非 ASCII 路径门禁已配置。
- 单元测试覆盖 198 张库存、自己锁牌、外面扣减、自己出牌不扣、PAUSE 门禁、事件重放和候选区变化率。

## 验证结果

- `compileDebugKotlin`、`compileDebugUnitTestKotlin`：本轮通过。
- `HandCandidateDiagnosticsTest`：本轮通过。
- 构建使用本机 Android SDK：`D:\SDKstudio`。
- Wrapper 脚本可启动；首次使用 8.7 发行包时需要网络下载。
- Mate 60 / HarmonyOS 4.2：设备已连接，应用可启动，浮窗权限已授予，`OverlayService` 已进入前台运行。
- MediaProjection：已持续采集 `2688×1216`，游戏前台约 `59–60 fps`；浮窗与探针服务同时前台存活。
- 录屏样本：`SVID_20260728_135222_1.mp4`（49.14 秒，1280×578，约60 fps）已覆盖初始手牌、布局变化、提示/出牌/过牌、公开牌组和开点。
- G2 标注：见 `g2-analysis/notes.md`；已定位交换结束后的 34 张候选锁牌帧，以及紧随其后的 SELF 四张 5 出牌，不能据此扣外面剩余。
- G2 样本：已收集并复核 9 局补充录屏，均为约 60 fps 的有效横屏样本，进入 Rank 模板标定。
- G2 真机首帧对照（20:47）：Mate 60 实时画面完整手牌候选识别为 33 张；日志为“小王1、2×1、A×1、K×6、Q×4、J×4、10×3、9×2、8×1、7×2、6×1、5×5、4×1、3×1”，与截图逐项一致。证据帧：`g2-analysis/cardcounter-g2-manual.png`。本轮只记录诊断，不锁定手牌、不改变横条。
- G2 回归基线：用当前 Android 匹配逻辑等价回放 9 局稳定帧，对照 `rank-ground-truth.md` 为 **0/9 全 Rank 向量完全一致**；单局仅差 1–2 张，但涉及 2/Q/10/6/小王/大王/A/4 等漏识别或混淆。结论：现有模板可作为诊断，不满足自动锁牌条件；后续模板校准必须以这 9 帧回归，不能只优化当前成功帧。
- 本轮完整构建：`clean test assembleDebug` 成功；12 个单元测试通过、0 失败、0 错误；Debug APK 已安装至设备。
- 2026-07-29 G2 校准：黑色 10 的第二模板使 9 局离线回归从 0/9、284/292 槽位提升至 2/9、287/292 槽位；已以 `rank_ten_black.png` 接入候选识别，不改变锁牌和扣牌行为。`test assembleDebug` 已生成更新后的 Debug APK。
- 该 APK 已通过 ADB 安装至 Mate 60；安装后手机进入锁屏，截屏服务需在解锁后重新授予本次 MediaProjection 会话，尚未完成新版真机日志验证。
- 重试后已强制停止旧进程、重新授予截屏权限。新 `CaptureProbe` 进程在游戏完整手牌画面持续约 60fps，连续 4 次输出同一 33 张候选；MediaProjection 为 `com.example.cardcounter`。证明新 APK 的采集与候选链路已加载，仍不触发 `SelfHandLocked`。
- 已新增“连续相同 Rank 候选”诊断计数，编译并生成新 APK（20:22）。真机验证被 Mate 60 自动锁屏阻断：授权页约 3 秒后被系统取消，`Media Projection: null`，仅浮窗服务存活；待用户保持解锁并手动允许后验证 `stableRanks` 连续递增。
- G2 交叉复核：离线牌位边缘链与新版 Rank 总数在 9 局中 7 局一致，而原人工总数仅 4 局一致；原“0/9 全 Rank 向量”不能再作为算法失败结论。下一步将牌位链整理为可测试组件，重新审计不一致样本；在此之前仍不锁牌。
- 2026-07-29 G2 标注审计：`145436@12s` 原表漏记一张 9，已更正为 33 张。`145333@14s` 人工复核仍为 32 张，但现有端点/固定间距几何诊断为 33 张；证明该公式受自动铺牌间距影响，会误报。测试已改为明确回归“诊断输出”，不把它误称为独立真值；该链路继续只打日志，不能触发锁牌。
- G2 图像测试已改为运行时读取桌面 JDK 的 PNG 解码器，避免 Android 单测编译期依赖 `ImageIO`；`test assembleDebug` 本轮通过。
- G2 真机安全门：锁屏时 MediaProjection 仍会产生黑帧，旧日志会将其累计为稳定 0 张。现已改为 `HAND_CANDIDATE_EMPTY` 并重置稳定计数；单元测试覆盖该分支，`test assembleDebug` 通过。新版 APK 已于 20:54 安装；安装会结束旧投屏会话，需解锁后重新授权验证。
- G2 新版真机复测（20:57）：Mate 60 游戏前台、MediaProjection 与两个前台服务均存活，帧流约 60fps。`HAND_CANDIDATE` 连续从 `stableRanks=6` 增至 `14`，Rank 候选稳定为 29 张；牌位诊断为 33，`totalsAgree=false`，因此未写入 `SelfHandLocked`。持久化事件仅有 `NewSession`，证明未误进入 ACTIVE。证据帧：`g2-analysis/g2-verified-live.png`。
- Mate 60 Q 漏识别根因：顶排 Rank 的 canonical Y 会随同一局画面布局在约 380 与 395 间漂移；固定 Y 无法覆盖。单元测试与 `test assembleDebug` 通过。
- 该固定 Y 校准在同一局的另一帧失效，已撤销，改为顶排在 `380/385/390/395` 四个候选行中选择全行模板总分最高者。真机回归（21:09）连续得到 `stableRanks=3/4/5`、总数 33、`positionCount=33`、`totalsAgree=true`；Q=4，其他 Rank 与当前画面一致。事件日志仍只有 `NewSession`，没有误锁牌。说明 G2 的单帧双计数与稳定门在 Mate 60 当前手牌上成立；尚缺从交贡/还贡/买牌到首个正式动作的多局门禁验证。
- 2026-07-29 用户确认 G2 素材口径：不再要求 10 局完整录屏，也不要求每局分别覆盖交贡/还贡/买 3/买 4；这些都属于首个正式出牌前阶段。逐秒复核现有 9 段，均包含首出前稳定手牌和第一组正式出牌，可直接作为首出锁牌门回归集。
- G2 首出锁牌门已接入：连续 Rank 候选至少 2 次且与牌位总数一致后保存 `lastStablePrePlayHand`；正式操作按钮出现后才布防，中央第一组正式牌连续 2 个样本后写入 `SelfHandLocked`。9 段离线边界均命中首出（19/18/15/16/14/14/13/13/12 秒），未命中此前交换画面；`test assembleDebug` 成功，新 APK 已安装，待真机授权后闭环验证事件持久化与横条刷新。
- G2 真机闭环：`SelfHandLocked` 已持久化 33 张，Rank 与手牌截图逐项一致；横条逐项等于固定库存减锁牌，证据为 `g2-analysis/g2-lock-verified.png`。锁牌后最终版采集恢复 58–60 fps。
- G2 九帧二次审计修正 5 行旧人工转写；补充灰色小王/大王和抬起牌模板，并合并底排不同高度候选。当前 Android 匹配逻辑等价回放为 **9/9 全 Rank 向量一致**。
- 2026-07-30 G2 最终版真机验收：APK 已覆盖安装；MediaProjection 为 `TYPE_SCREEN_CAPTURE`，`CaptureProbe` 持续 `2688×1216 / 59.2 fps`；持久化事件保留 `NewSession + SelfHandLocked`（33 张）；左上横条浮窗窗口已创建。G2 闭环完成。
- 2026-07-31 G3 首轮：按实测录屏定义上、左上、左下、右上、右下、中央自己六个互不重叠出牌区；中央只归 SELF，其余五区只归 OUTSIDE，不使用最近邻或轮次推断。每区同一 Rank 组连续两次才生成事件，连续两次空区后才允许相同牌组再次生成，避免静态画面重复扣减。代码已接入 `CaptureProbeService`，但 `test assembleDebug` 在 Gradle 启动阶段受 Windows Sockets 10106 阻断，尚未完成编译、离线回放或真机验证；G3 仍未验收。
- 2026-07-31 重试：Windows 套接字已恢复；`test assembleDebug` 通过。新版 Debug APK 已覆盖安装至 Mate 60，应用进程可启动（PID 27335）。覆盖安装会结束旧的 MediaProjection 会话，G3 仍待解锁后重新授权截屏并在完整牌局中验证。
- 2026-07-31 G3 归一化复核：六区改为相对游戏画布坐标，不依赖 Mate 60 的原始像素；补充缩放与稳定去重单元测试。`test assembleDebug` 通过（15 个测试），最新版 Debug APK 已重新覆盖安装并启动。待真机授权后的正式出牌日志验证。
- 2026-07-31 G3 真机首测：已确认 `TYPE_SCREEN_CAPTURE` 和六区实际画面；右上 6×2、右下 5×2 存在时横条未变化，不能验收。根因是首轮模板快照误采整屏，帧率降至约 1–30fps，稳定门难以触发。现改为仅采样底部手牌与六区 Rank 小块；`test assembleDebug` 再次通过，APK 已覆盖安装，待重新授权复测。
- 2026-07-31 G3 复测：小块采样后帧率提升至约 16–38fps，但发现中央空区误识别为 K，右侧公开牌未形成事件，不能验收。现增加“白色牌体存在 + Rank 匹配”双门：无牌区模板命中不入事件；有牌而 Rank 连续两次为空则 PAUSE。`test assembleDebug` 通过（16 个测试），APK 已覆盖安装，待重新授权验证。
- 2026-07-31 G3 右区校准：双门已在右上有牌但未识别时正确触发 `RANK_UNSTABLE` PAUSE，没有错误扣减。逐模板对照确认公开牌与底部手牌的角标像素形态不同；从真机证据帧加入 6、5 的公开区专用小角标模板，并将公开牌去重间距从手牌的 28px 独立为 10px。`test assembleDebug` 通过（17 个测试），APK 已覆盖安装，待重新授权复测。
- 2026-07-31 G3 基线性能修复：新局首次正式出牌后，真机手牌稳定样本已达 281，但全横向、全模板扫描未及时产出候选，导致错过安全基线。现以积分图先排除不可能达到模板 IoU 阈值的空白位置，保留原匹配阈值与稳定门；`test assembleDebug` 通过（18 个测试）。Mate 60 在安装前 ADB 断开，尚未覆盖新 APK 或重置复测。
- 2026-08-01 G3 采集回归修复：积分图构建位于采集回调时，真机帧率降至 1.4fps；已移到识别线程，采集回调仅提取必要小块。`test assembleDebug` 通过；最新版 APK 已覆盖安装，待解锁并重新授权 MediaProjection 后验证帧率与新局闭环。
- 2026-08-01 G3 首轮事件闭环：真机锁定 33 张基线；中央自己出 K 生成 `SelfCardsPlayed`，右下与右上各出一张 6 分别生成两条 `OutsideCardsPlayed`。横条中 K 保持 14，证明自己出牌不扣外面；6 从初始外面 13 减为 11，证明外面事件逐条扣减。左下出现一对 7 时旧模板未识别，系统按门禁进入 `RANK_UNSTABLE`，没有猜牌；已加入两种公开 7 模板及重叠双牌回归测试。
- 2026-08-01 G3 基线安全门：交换阶段真机识别到 34 张时连续输出 `baselineValid=false`，未写入 `SelfHandLocked`；仅允许牌位数和 Rank 总数同时为 33。随后发现有效 33 张会被首出过渡帧清空，已调整边界顺序：出现正式出牌控件且已有有效基线时冻结该基线；回归测试和 `test assembleDebug` 通过。
- 2026-08-01 G3 新局状态修复：真机发现“新局重置”仅清事件、未清截屏服务上一局的稳定门。现由按钮同步发送会话重置信号，清空首出边界、六区稳定去重、未知牌计数和 Rank 候选，同时保持 MediaProjection 不断；两套稳定门均补充重新布防测试，`test assembleDebug` 通过。最终候选 APK 已安装，当前只待用户确认系统录屏授权后做最后一局复验。
- 2026-08-01 G3 最终候选真机闭环：新局 33 张候选连续稳定识别并跨首出边界保留，随后持久化 `SelfHandLocked`；中央自己出 6 写入 `SelfCardsPlayed`，外面依次写入 7+6、6、K。横条实测为大王3、小王3、2=14、A=15、K=15、Q=13、J=12、10=13、9=11、8=12、7=15、6=11、5=12、4=7、3=5，与固定库存减自己锁牌减外面事件逐项一致；自己出的 6 未二次扣减。
- 2026-08-01 G3 内存稳定性修复：识别耗时高于采样周期时旧实现会无限排队截图快照，约 400MB 后在 `HandCardPositionDetector.snapshot` OOM。现用单飞门保证同时最多一个 Rank 任务；单元测试覆盖占用/释放。Mate 60 连续 190 秒复验 PID 始终为 21248，PSS 在约 46.5–51.6MB 间回落波动，MediaProjection 保持 `TYPE_SCREEN_CAPTURE`，无 OOM 或进程重启。
- 2026-08-01 G3 公开 5 回归：左下红 5 在旧 APK 中触发 `RANK_UNSTABLE`，安全暂停且未误扣。真机帧回放确认 14×14 专用模板 IoU 达阈值，但原资源目录会按 Mate 60 的 520dpi 缩放；5/6 专用模板已只保留 `drawable-nodpi` 版本。新增真实红 5 小样回归，校验模板原尺寸和 IoU；APK 资源表确认两模板仅有 `nodpi` 条目，`test assembleDebug` 通过。
- 2026-08-01 G3 最终交付：`clean test assembleDebug` 成功，26 个单元测试全部通过（0 失败、0 错误、0 跳过）；最终 Debug APK 已覆盖安装至 Mate 60，已验证事件日志在覆盖安装后完整保留。安装按 Android 规则结束本次 MediaProjection，会在下一阶段开始时重新授权。
- 2026-08-01 G4 启动：Mate 60 已建立新会话并保留当前 MediaProjection；新局完整 33 张手牌连续稳定识别，牌位数与 Rank 总数均为 33，`baselineValid=true`，采集约 44–60fps。当前等待玩家完成宣点及后续正常出牌，以采集六区、更多 Rank 和清空后同牌重新布防证据。
- 2026-08-01 G4 实帧修复：首出后已正确锁定 33 张自己手牌，并扣除外面 `7×3`；左下外区 `4×2` 因缺少公开区 4 模板安全暂停。已从该真机帧补入 `rank_four_play` 模板与像素回归，`test assembleDebug` 通过并覆盖安装 APK；安装结束旧 MediaProjection，当前等待系统重新授权后验证 4×2 不再暂停。
- 2026-08-01 G4 顶部区遮挡修复：新局首手为自己 `5×3`，已锁定的 33 张基线中 `5=3`，横条 `5=13` 符合库存公式；右下外区 `6×3` 已持久化扣减。随后顶部外区 `10×3` 被旧横条遮挡，触发安全暂停而未猜测。横条已收窄至 TOP 区左侧，`test assembleDebug` 通过并安装；当前局已暂停，需下一局验证 TOP `10×3` 与其余六区覆盖。
- 2026-08-01 G4 规则修正：自己开局手牌不固定为33张；买3/4、同伙代买和互相给牌会形成31、32、34、35等张数。锁牌改为稳定 Rank 向量与独立牌位计数一致，不再硬编码33；归约器同步允许任意非空、未超过固定库存的自己手牌。实帧还发现抬起的顶排 J 会与普通牌不同行，顶排改为跨候选行合并去重，当前 `J×5` 已真机识别。公开外区 `10/Q/8` 的专用模板及像素回归已补入，`test assembleDebug` 通过并安装；待下一局实机覆盖验证。

## 尚未完成

1. G4 六区完整牌局验证：覆盖更多 Rank、同牌清空后重新出现和五个外面区的连续动作。
2. 开局/结算/新局门禁的完整录屏验证。
3. G4/G6 的独立完整牌局与动作事件验证。

## 下一步顺序

1. 进入 G4：用完整牌局扩大六区、Rank 与清空后重新布防覆盖；不改变已验收的 G3 归约规则。

## 重要边界

- 不恢复逐玩家统计。
- 自己后续出牌不扣外面库存。
- 识别不确定时 RETRY，无法闭环时 PAUSE，不静默猜测。
- 只有新局重置才允许重新进入 PRE_PLAY。
- G2、G3 已完成当前确认口径；G4/G6 尚未完成。

历史方案审计备份：`progress.before-code.md`、`task_plan.before-code.md`。

---

## 自主优化轮（2026-09-13 夜间，22:00–23:10）

用户授权全权推进（至 09-14 08:00）：排查架构/代码缺陷 → 出方案 → 派子代理实施 → 主代理验收。

### 交付

| 项 | 值 |
|---|---|
| 分支 | `optimization-20260913`（基线 `main`=`dda6b45`） |
| Commit | `08e3fbde8ff06a7f3aee9bbd86f6de2722aa69b3` |
| 远端 | GitHub `kaiz1995/CardCounter`，`refs/heads/codex/optimization-20260913` |
| 变更 | 35 files changed, +1369 / −169 |
| 单测 | **80 tests / 0 failures / 0 errors**（基线 75） |
| APK | 47,040,387 bytes，SHA256 `30977fe4937b23a3b036b42eaa9c85e97a131c911256bbd2dcf9a8b4be4ea9af` |

> ⚠️ APK SHA256 **每次构建都会变**（ZIP 条目的时间戳导致不可复现），但字节大小恒定
> 为 47,040,387。首次构建 `30977fe4937b...`、复核构建 `a3aa8e00a011...`，
> 两者**大小完全一致**，属已知的非确定性现象，**不是功能差异**（与本项目此前
> 「47MB vs 51MB 差异纯来自 ZIP 压缩率」同一性质，文件清单与 dex md5 一致）。
> 校验 APK 时请以**字节大小 + 文件清单**为准，不要只比对 SHA256。

修复 12 个问题（P0×4 / P1×5 / P2×3），清单见
`plans/optimization-report-20260913.md` 与 `findings.md` 新章节。

### 执行方式

4 个阶段：① 会计与领域层（子代理 A）② 识别路径（子代理 B）
③ 性能与 UI（子代理 C）④ 主代理独立回归 + 交付。
**子代理只改工作区、不 commit**，由主代理统一验证并提交。

### 主代理额外修复的 3 个缺陷（子代理引入、测试全绿却未被发现）

1. 版本迁移逻辑被整个删除 → 恢复 `loadOrMigrate()`，迁移标记与日志同次原子 `commit()`。
2. 预热路径清空帧数 6→9 → 按路径分别取值；补的回归测试经**反证法**确认可捕获。
3. 节流过保守（性能收益低于预期）→ 保留保守策略并记为待优化。

### 计划文档

- `plans/optimization-master-20260913.md`（总控）
- `plans/optimization-stage3-20260913.md`（阶段 3 规格）
- `plans/optimization-report-20260913.md`（验收报告）
- `OPTIMIZATION-STATUS.md`（进度快照）

### 待真机验证（本次无条件覆盖）

1. **对局中途强杀进程 → 重启验证事件未丢**（验节流 + `flush()` 落盘可靠性）
2. `hidden` 持久化：隐藏浮窗 → 重启 App → 仍为隐藏态
3. 服务重启后识别恢复速度（验预热路径修复效果）
4. 浮窗面板观感：面板中心与识别带中心偏差（TOP 达 115.5px）是否影响使用

### ⚠️ 尚未合并回 `main`

本分支**未合入 `main`**，等待用户 08:00 检查后再决定。
