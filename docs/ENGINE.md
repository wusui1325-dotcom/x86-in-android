# 引擎進度

## 目標（Boss · 定案 A）

- 真 x86 主線；殼 `com.wusper.x86`。
- **GpuStack**：glibc Turnip ICD（KGSL）／DXVK／Gladio GLX／**進程內 X → Present→AHB→Session Surface**。
- SoftLegacy（Xvfb XWD→Canvas）僅 `forceSoftLegacy`。

詳見 [GPU_STACK.md](GPU_STACK.md)。

## 圖形／X（0.1.9+）

| 層 | 實作 |
|----|------|
| X 協定 | **進程內** `com.winlator.xserver`（vendored LGPL-2.1 from winlator-app）＋`libwinlator.so` |
| Socket | `files/engine/imagefs/tmp/.X11-unix/X0` bind 進 PRoot `/tmp/.X11-unix` |
| SysV SHM | 同 imagefs `tmp/.sysvshm`＋`ANDROID_SYSVSHM_SERVER` |
| Present | `PresentXFramePump`：GPUImage AHB→EGL／BGRA 單趟→Surface（砍 Java swizzle） |
| Xvfb | **GpuStack 不再使用**；僅 SoftLegacy |

## 手動驗收

1. 選 `.exe` → 日誌含 `啟動 Winlator Java X`／`INPROCESS_X_OK`（客體 gpu-stack.log）。
2. 不應再出現 GpuStack 路徑的 `X_PROTOCOL_STUB=xvfb`。
3. Surface 有幀（客體窗口或 stub 至首幀）。

## proot 可執行（0.1.16）

- 宿主 `proot` 以 `jniLibs/arm64-v8a/libproot.so` 為準（`nativeLibraryDir`，避開 filesDir `execv EACCES`）
- 落地後一律 `Os.chmod`（[ExecBits]）；安裝完成掃描 wine／box64／腳本
- 首次下載：並行＋斷點續傳＋進度條＋可取消
