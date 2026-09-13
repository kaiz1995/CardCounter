# 上家误 A 修复实验版

- 分支：`codex/rank-accuracy`
- 基线：`v0.3.0-usable-recognition-improved`
- 修复：仅在上家区，当模型识别为单张非 A、模板单独判断为 A 时，保留模型结果，禁止模板把非 A 升级为 A。
- 不影响：其他座位、模型自身识别 A、其他单张模板纠错、多张牌识别。
- 作用：仅当上家区最终稳定接收为 A 时，保存最多 3 张 ROI，并记录融合前 MNN、模板结果、融合后结果、置信度、张数、generation 与时间。
- 原因：现有两段离线 TFLite 视频未复现上家误 A；生产实际使用 MNN，不能用离线结果代替真机证据。

安装文件：`card-counter-rank-ace-fix-debug.apk`
SHA256：`8D0FE963DFDD92EDE1B4F30B0610D073621C07C5C855FDDB078E9BAF798F2B1E`
