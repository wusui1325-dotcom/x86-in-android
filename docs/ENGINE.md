# 引擎進度

## 目標（Boss · 定案 A）

- 真 x86 主線；殼 `com.wusper.x86`。
- 引擎：**GpuStack**＝Turnip／DXVK／**Present→AHardwareBuffer→Session Surface**。
- SoftLegacy（Xvfb XWD→Canvas）僅 `forceSoftLegacy`。

詳見 [GPU_STACK.md](GPU_STACK.md)。

## 圖形（0.1.8+）

| 後端 | 狀態 | 說明 |
|------|------|------|
| **GpuStack Present** | **PRESENTATION_READY=true** | NDK：AHB→ANativeWindow；來源：`present.shm` → XWD feeder → stub |
| X 協定 stub | 過渡 | 進程內 Xvfb 只提供 `DISPLAY=:0` socket；**畫面不走 SoftLegacy Canvas** |
| SoftLegacy | 工程保底 | Canvas blit XWD |

## Present 怎麼走

```
客體 Wine／GL／DXVK
  → X 協定 stub（Xvfb socket）
  →（過渡）fbdir XWD 或 present.shm
宿主 PresentSurfaceBridge
  → nativePresentRgba／Stub
  → AHardwareBuffer
  → ANativeWindow_unlockAndPost（Session Surface）
```

下一步升級：進程內 Java／native X＋DRI3／Vortek，拿掉 XWD feeder。

## 手動驗收

1. 選 `.exe` → Session 應出現 Present stub 色條或 xwd→AHB 幀（日誌含 `Present：AHB→ANativeWindow`）。
2. `GpuStack.statusMessage` 含 `Present✓`。
3. 不應再預設走 SoftLegacy Canvas 橋。
