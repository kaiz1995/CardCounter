# 记牌器开发交接（基础编码完成）

更新时间：2026-07-27

## 先看

1. `plans/card-counter-technical-plan-v5.md`：业务方案。
2. `implementation-readiness.md`：实施规格。
3. `implementation-review.md`：本轮编码范围与审查。
4. `progress.md`：当前进度。

## 当前代码入口

- 领域：`app/src/main/java/com/example/cardcounter/domain/`
- 持久化：`app/src/main/java/com/example/cardcounter/persistence/`
- 横条：`app/src/main/java/com/example/cardcounter/ui/CounterBarView.kt`
- 浮窗：`app/src/main/java/com/example/cardcounter/service/OverlayService.kt`
- 截屏探针：`app/src/main/java/com/example/cardcounter/capture/CaptureProbeService.kt`
- 启动页：`app/src/main/java/com/example/cardcounter/MainActivity.kt`
- 单元测试：`app/src/test/java/com/example/cardcounter/domain/GameReducerTest.kt`

## 已确认命令

```powershell
gradlew.bat test
```

首次使用需要下载 Gradle 8.7；本机已有 Android SDK `D:\SDKstudio`，直接 Gradle 验证已通过。

## 不要做

- 不恢复旧 `GameSession` 或逐玩家统计。
- 不给横条增加总剩或手工 Rank 加减。
- 不在没有完整录屏前固化六区原始像素坐标、模板和阈值。
- 不把未知牌组猜成 OUTSIDE；无法闭环必须 PAUSE。

## 下一步

安装 Debug APK 到华为 Mate 60 / HarmonyOS 4.2，验证浮窗、MediaProjection、帧尺寸和后台运行；之后再根据完整录屏实现视觉事件适配层。
