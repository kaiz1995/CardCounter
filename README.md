# CardCounter - 多乐够级智能记牌器

基于视觉识别与端侧推理的「多乐够级」全自动实时记牌器，支持 Android 端无侵入悬浮窗实时记牌与 PC 端视频回放/模型训练评估。

---

## 核心特性

- **无侵入实时录屏捕获**：Android 采用 `MediaProjection` 服务捕获游戏画面，结合系统悬浮窗（`SYSTEM_ALERT_WINDOW`）在屏幕左上角直观呈现当前剩余牌张与各点数出牌分布。
- **端侧轻量化模型推理**：集成 YOLO11 28 类牌面检测模型，导出并优化为 TFLite / MNN 格式（如 `poker_yolo11_320.tflite`），保障真机端低延迟、低发热。
- **28 类牌面识别体系**：细分红黑花色与王牌类别（`RA`-`RK`、`BA`-`BK`、`D` 大王、`X` 小王），兼顾贡牌与大牌博弈深度。
- **6 区域时序去抖与抗重机制**：针对左下、左上、顶端、右上、右下及自身 6 个出牌位，设计 `RoiPlayEventCache` 与 `StablePlayGate`，杜绝同一手牌驻留造成的重复记账与无共识漏判。
- **正向累计记牌账本**：独立于牌点库存累计出牌，误分类超额时仍能准确兜底牌张总数，确保对局全局数据自洽。

---

## 目录结构

```text
.
├── app/                  # Android 客户端工程（Kotlin）
│   ├── src/main/assets/  # YOLO11 TFLite/MNN 模型文件
│   ├── src/main/java/    # 捕获探针、时序去抖、悬浮窗渲染与账本逻辑
│   └── src/test/java/    # 状态机与时序门控核心单元测试
├── pc_cardcounter/       # PC 端运行环境与模型训练管线（Python）
│   ├── hud.py            # PC 端桌面悬浮窗与调试展示
│   ├── train.py          # YOLO11 模型微调训练
│   ├── prepare_dataset.py# 标注数据前处理与自动裁切
│   └── replay_eval.py    # 录屏回放与跨局准确率基准评估
├── docs/                 # 技术架构文档、ROI 标定及视觉素材
├── plans/                # 迭代方案与重构技术规划
└── releases/             # 各里程碑版本说明与发布归档
```

---

## 快速开始

### 1. Android 端编译与部署

**环境要求**：
- Android Studio Hedgehog 或更高版本
- JDK 17+
- Android SDK 34 / Gradle 8.7+

```powershell
# 运行核心测试（时序门控、账本与缓存）
./gradlew testDebugUnitTest

# 构建 Debug APK
./gradlew assembleDebug
```

编译产物位于 `app/build/outputs/apk/debug/app-debug.apk`。首次在手机安装后需授予**悬浮窗权限**与**屏幕录制权限**。

### 2. PC 端训练与测试

**环境要求**：
- Python 3.10+
- PyTorch / Ultralytics YOLO11

```powershell
cd pc_cardcounter

# 运行桌面 HUD 调试
python hud.py

# 启动回放评测
python replay_eval.py
```

---

## 分支与版本归档

- **`main`**：稳定归档基线。
- **`codex/positive-accumulate`**：当前活跃分支（正向累计、重试恢复与时序去抖优化）。
- **Release 标签**：`v0.3.0-usable-recognition-improved`、`v0.2.0-usable-initial2`。
