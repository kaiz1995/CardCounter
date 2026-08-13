# 数量正向累计实验版

- 分支：`codex/count-accuracy`
- 基线：`v0.3.0-usable-recognition-improved`
- 显示：从 0 正向累计外部出牌张数。
- 数量：独立于牌点累计；误分类超库存时仍累计张数，牌点库存保持非负。
- 时序：合并 300 ms 内“王先出现、主体牌后稳定”的同次出牌；清桌 6 个采样后结算普通单批。
- 离线证据：开发窗口 35/35、冻结留出窗口 20/20 的牌张数一致；尚未完成新一局真机全局验证。

安装文件：`card-counter-count-accuracy-debug.apk`
SHA256：`126CC6F685394119ED7EEE19108D9B3F9E0E19B9156384D9E32118157CAD26BD`
