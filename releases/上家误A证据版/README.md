# 上家误 A 证据实验版

- 分支：`codex/rank-accuracy`
- 基线：`v0.3.0-usable-recognition-improved`
- 识别决策：未改变。
- 作用：仅当上家区最终稳定接收为 A 时，保存最多 3 张 ROI，并记录融合前 MNN、模板结果、融合后结果、置信度、张数、generation 与时间。
- 原因：现有两段离线 TFLite 视频未复现上家误 A；生产实际使用 MNN，不能用离线结果代替真机证据。

安装文件：`card-counter-rank-ace-audit-debug.apk`
SHA256：`B3F3B7972EFE696F9FB0364B7D313572691153D6970BC8EB24834C74625C6FC8`
