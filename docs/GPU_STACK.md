# GpuStack 架構（Boss 定案 A · 2026-11-09）

**目標**：真 x86（Windows PE／Linux ELF）主線；殼維持 `com.wusper.x86`（M3、選 exe／工作區、Session Surface）；引擎換成 **Winlator／glibc 級 GPU 棧**，**砍掉預設 Xvfb→XWD 軟渲**。

## 最短可落地整合（本版採納）

**不整樹 fork Winlator**（LGPL-2.1 聚合＋追 upstream 地獄），而是：

```
[我們的殼] SessionActivity + SurfaceView + 工作區
        ↓
[啟動／容器] PRoot + Ubuntu glibc rootfs + Box64 + Wine
        ↓
[GPU 棧組件] Mesa Turnip ICD + DXVK（＋之後 VKD3D）
        ↓
[顯示・已接] 進程內 X／DRI3 AHB（GPU_SAMPLED）→ EGL／BGRA 單趟 → Session Surface
[輸入・已接] Session 觸控／鍵鼠 → InProcessXInput → XServer.inject*（GpuStack；非 xdotool）
```

對齊 Winlator 已驗證的層蛋糕，產品 UX 仍是「選 exe → 全螢幕」，不是假桌面。

## 合法重用矩陣

| 組件 | 來源 | 授權 | 接法 |
|------|------|------|------|
| Box64 | ptitSeb/box64 | MIT | 已內嵌／rootfs |
| Wine | Kron4ek Builds / Wine HQ | LGPL | rootfs `/opt/wine` |
| Ubuntu rootfs | proot-distro | 各套件 | PRoot |
| PRoot | Termux／portable | GPL-3 | APK assets；獨立進程 |
| **Mesa Turnip** | nihui/mesa-turnip-android-driver（Mesa） | **MIT** | 下載 ICD → bind `/mnt/gpu/turnip` |
| **DXVK** | doitsujin/dxvk | **Zlib** | 下載 → wineprefix `system32` + native override |
| VKD3D | Wine HQ | LGPL | 後續 |
| Winlator 參考 | brunodev85/winlator | **LGPL-2.1** | **架構／啟動腳本對齊**；不整包併入本 APK |
| Xvfb／xdotool | Ubuntu | 各異 | **僅 SoftLegacy 工程保底** |

義務摘要：動態連結／獨立進程優先；THIRD_PARTY 列清；若日後 **衍生修改並連結** Winlator 原始碼，需遵守 LGPL-2.1（提供目標檔／允許替換庫）。本版尚未 vendor Winlator Java／native 源碼。

## 預設 vs 保底

| | GpuStack（預設） | SoftLegacy（`forceSoftLegacy=true`） |
|--|------------------|-------------------------------------|
| 顯示 | DRI3 AHB→EGL／BGRA 單趟→Surface | Xvfb `-fbdir` XWD→blit ~15 FPS |
| GL/VK | Turnip＋DXVK | `LIBGL_ALWAYS_SOFTWARE=1` llvmpipe |
| 輸入 | **InProcessX.inject***（觸控／鍵／滾輪） | xdotool cmd 佇列 |

## 本版已接（0.1.8）

- **Present 通路**：`present_engine.cpp` — `AHardwareBuffer` → `ANativeWindow`（Session Surface）
- **PresentSurfaceBridge**：shm → XWD feeder → stub 色條（可測）
- **present.shm** 協定（`PresentShm`）供客體／工具寫幀
- **PRESENTATION_READY=true**；PE 在 Turnip＋DXVK 備齊後可進 `WineProot`＋Present
- X 協定：**Xvfb socket stub**（非 SoftLegacy Canvas）；輸入仍暫 xdotool

## 怎麼 Present

1. `nativePresentCreate(w,h)` 配置 AHB（CPU_WRITE/READ + GPU_SAMPLED）
2. `nativePresentAttachSurface(Surface)` → `ANativeWindow_fromSurface`
3. 每幀 RGBA → lock AHB → 寫入 → `ANativeWindow_lock` 拷貝 → `unlockAndPost`
4. 幀來源優先：`files/.../present.shm` → Xvfb `xfb/Xvfb_screen0`（過渡 feeder）→ stub

## 還缺什麼（歷史；見 0.1.10 更新）

進程內 X 與 Session→inject* 已接；剩餘見文末「還缺」。



## 更新 0.1.9 — 進程內 X

- **拿掉 GpuStack Xvfb stub**
- Vendor：`brunodev85/winlator-app` 的 `xserver`／`xconnector`／`sysvshm`／`libwinlator`（LGPL-2.1，見 `third_party/winlator/`）
- 宿主：`InProcessX`＋`PresentXFramePump` → 既有 AHB Present
- Guest：`DISPLAY=:0` 經 PRoot bind 到 imagefs socket
- **不做** Termux-X11／Lorie 整包（GPL-3）

### 還缺（0.1.9 時）

1. DRI3／Vortek 零拷貝（現 CPU 轉 RGBA）
2. Session 觸控 → `XServer.inject*` ← **0.1.10 已接**
3. GLX／gladiorenderer
4. Turnip ICD thunk 實機；AAudio

## 更新 0.1.10 — Session 輸入 → inject*

- 新增 `InProcessXInput`：`Surface` 觸控／鍵鼠／滾輪 → `XServer.injectPointer*`＋`Keyboard.onKeyEvent`
- `SessionActivity`：GpuStack 走 `inProcessInputRef`；**不再**對 GpuStack 建 xdotool FIFO／agent
- SoftLegacy 仍用 `X11InputBridge`＋`x11-input-agent.sh`
- `surfaceChanged` 更新 letterbox `blitRect`（Present 與 SoftLegacy 皆可）
- `GpuStack.statusMessage` 標 `Input✓（inject*）`

## 更新 0.1.11 — DRI3／AHB GPU_SAMPLED Present

- `GPUImage` AHB usage：`GPU_SAMPLED_IMAGE|GPU_COLOR_OUTPUT`（＋可選 CPU）
- `PresentEngine`：`presentAhb`（EGLImage→Surface）／`presentBgra`（單趟 swizzle，砍 Java BGRA→RGBA＋雙拷）
- `PresentXFramePump`：優先 AHB→EGL，其次 BGRA native；Session 不再走 RGBA sink
- Root Drawable 啟動時 promote `GPUImage`（對齊 Present selectInput）

### 還缺

1. 完整 Vortek IPC Vulkan（客體 ICD→socket→獨立 bionic 渲染進程）
2. 多指／右鍵／相對滑鼠／遊戲手把（WinHandler 級）
3. GLX／gladiorenderer（OpenGL 客體）
4. Turnip ICD thunk 實機驗證；Pulse／ALSA→AAudio
5. AHB 已 CPU-lock 時 EGL 穩定性（失敗則 BGRA 單趟保底）
