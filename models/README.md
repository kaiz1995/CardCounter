# models/

`poker_rank15_yolo11n_320.onnx` 是 v2 的最终导出，但 **10,450,050 字节（10.0 MiB）超过 8 MiB 入库阈值**，
因此 `.gitignore` 忽略了本目录的 `*.onnx`，二进制不入 git，只在本机保留。

| 项 | 值 |
|---|---|
| SHA256 | `95a7b7cd76e907b4f5407501b7c152853d9b7c267b31a67c57dc32aac6a65390` |
| 字节 | 10,450,050 |
| opset | 17 |
| 输入 | `images` `[1,3,320,320]` float32 |
| 输出 | `output0` `[1,19,2100]` float32，无 NMS |

复现：

```powershell
python tools/training/export.py --weights "$env:CARDCOUNTER_RUNS\train\stage_b\weights\best.pt" `
  --out models/poker_rank15_yolo11n_320.onnx
python tools/training/eval_crops.py --onnx models/poker_rank15_yolo11n_320.onnx
```

契约与评测见 `docs/rebuild/MODEL-V2.md`。
