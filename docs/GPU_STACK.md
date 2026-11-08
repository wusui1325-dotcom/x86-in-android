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
[顯示・下一步] 進程內 X／DRI3 Present → AHardwareBuffer → 同一 Session Surface
[輸入・下一步] WinHandler 級橋（取代 xdotool 檔案佇列）
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
| 顯示 | Present→Surface（待做） | Xvfb `-fbdir` XWD→blit ~15 FPS |
| GL/VK | Turnip＋DXVK | `LIBGL_ALWAYS_SOFTWARE=1` llvmpipe |
| 輸入 | WinHandler 級（待做） | xdotool cmd 佇列 |

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

## 還缺什麼

1. **完整進程內 X**（Winlator Java X／Termux-X11 Lorie 級）— 取代 Xvfb stub
2. **DRI3／AHB 零拷貝**（現為 CPU present；結構已留 GPU_SAMPLED）
3. WinHandler 級輸入；AAudio
4. Turnip ICD thunk 實機驗證

`GpuStack.PRESENTATION_READY` 已為 **true**（Present 通路可測）；X stub≠最終 X。

