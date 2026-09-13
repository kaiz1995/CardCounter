# 数量正向累计实验版

- 分支：`codex/count-accuracy`（HEAD `44c65fd`）
- 基线：`v0.3.0-usable-recognition-improved`
- 显示：从 0 正向累计外部出牌张数。
- 数量：独立于牌点累计；误分类超库存时仍累计张数，牌点库存保持非负。
- 时序：合并 300 ms 内“王先出现、主体牌后稳定”的同次出牌；清桌 6 个采样后结算普通单批。
- 兜底：普通单批最多等待 301 ms 自动结算，不再依赖清桌或下一次出牌。
- 离线证据：开发窗口 35/35、冻结留出窗口 20/20 的牌张数一致；尚未完成新一局真机全局验证。

## 交付物

| 文件 | 大小 | SHA256 |
|---|---|---|
| `card-counter-count-accuracy-debug.apk` | 47,024,003 B | `81464A09403E55698CE674D45A51F2B81C8A0A161828F01E3306C8E438480A81` |
| `cardcounter-数量正向累计版-source.zip` | 35,279,529 B | `A237756A8A49E7938309FA4811787497835157F3D67E9949FC7D89367BD67112` |

- 源包内容：`app/src` 全量（`main` + `test` + `androidTest`，106 个文件），
  结构与「可用初版1/2」一致。
- 构建时间：2026-08-14 10:50（APK）。
- 归档副本：`Documents/workbuddy/_git-rescue-20260913/release-数量正向累计版/`
  （含 `SHA256SUMS.txt`）。

## 注意

- 本目录的 `.apk` 与 `-source.zip` 受项目 `.gitignore` 规则
  （`releases/**/*.apk`、`releases/**/*-source.zip`）排除，**不入版本控制**，
  仅保留在本地磁盘；本 README 是入库的唯一记录，SHA256 用于校验完整性。
- 该版本尚未完成真机整局验证，属实验版，不作为稳定基线。
- 稳定基线仍为 `v0.3.0-usable-recognition-improved`（可用识别提升版）。
