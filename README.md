# CardCounter - 多乐够级智能记牌器（Android 端）

基于视觉识别与端侧实时推理的「多乐够级」全自动 Android 记牌器，采用无侵入悬浮窗形式在实战中实时呈现剩余牌张与各点数出牌分布。

---

## 核心特性

- **无侵入实时录屏捕获**：通过 Android `MediaProjection` 服务实时采集游戏画面，配合系统悬浮窗（`SYSTEM_ALERT_WINDOW`）在屏幕左上角动态渲染剩余牌力与出牌详情。
- **端侧轻量化模型推理**：集成针对够级牌面优化训练的 YOLO11 28 类模型，导出为端侧 TFLite / MNN 双格式（`poker_yolo11_320.tflite` / `poker_yolo11_320.mnn`），保障手机端实战低延迟与低功耗运行。
- **28 类牌面识别体系**：完整覆盖红黑花色与王牌分类（`RA`-`RK`、`BA`-`BK`、`D` 大王、`X` 小王），精准区分进贡/憋三关键牌型。
- **6 区域时序去抖与抗重机制**：针对左下、左上、顶端、右上、右下及自身 6 个出牌位，设计 `RoiPlayEventCache` 与 `StablePlayGate`，避免同一手牌滞留导致重复扣牌，并在无共识时支持快速重新采样。
- **正向累计记牌账本**：独立于牌点库存累计出牌张数，在误分类超额时仍能准确兜底牌张总数，确保对局全局数据自洽。

---

## 工程结构

```text
.
├── app/
│   ├── src/main/assets/       # 端侧 YOLO11 模型（TFLite / MNN）
│   ├── src/main/java/         # 核心源码（Kotlin）
│   │   ├── capture/           # 探针捕获、区域裁切、时序去抖（RoiPlayEventCache）
│   │   ├── service/           # 悬浮窗服务（OverlayService）与录屏后台服务
│   │   └── vision/            # 模型推理接口与后处理
│   └── src/test/java/         # 时序门控、正向累计与账本单元测试
├── docs/                      # 架构设计与视觉标定资源
├── plans/                     # 历史重构与演进方案
└── releases/                  # 历史发布版本说明与基准归档
```

---

## 快速开始

### 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17+
- Android SDK 34
- Gradle 8.7+

### 编译与运行

```powershell
# 执行核心单元测试（时序门控、去抖与账本测试）
./gradlew testDebugUnitTest

# 构建 Debug APK
./gradlew assembleDebug
```

APK 产物生成于 `app/build/outputs/apk/debug/app-debug.apk`。

### 真机使用流程
1. 安装生成的 APK 至 Android 手机（已验证华为鸿蒙 / EMUI / 标准 Android 机型）。
2. 首次启动授予**悬浮窗（Display over other apps）**权限与**屏幕录制（MediaProjection）**授权。
3. 启动记牌服务，打开「多乐够级」，左上角悬浮条将自动开始检测出牌与统计剩余牌张。

---

## 分支与版本说明

- **`main`**：稳定归档基线。
- **`codex/positive-accumulate`**：当前活跃主开发分支（包含正向累计、清桌防重与重试机制）。
- **版本标签**：`v0.3.0-usable-recognition-improved`、`v0.2.0-usable-initial2`。
