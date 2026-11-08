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

## 本版已接

- `GraphicsBackend`／`GpuStack`：就緒檢查、缺口列表、Turnip／DXVK 下載解壓、DXVK stage 進 prefix
- `LaunchPipeline`：PE 預設 GpuStack；Present 未就緒 → `WineGpuPending`（不啟動軟渲）
- `wine-launch.sh`：`GRAPHICS_BACKEND=gpu|soft`；gpu 路徑拒絕無 X 時的 Xvfb 偷跑
- Session：GpuStack 先備料組件；僅 SoftLegacy 掛 XWD blit

## 下一步（可跑 .exe 全螢幕的缺口）

1. **進程內 X／合成器**（對齊 Winlator `XServer`＋`GLRenderer`／`XServerView`）或 DRI3 Present→`AHardwareBuffer`→Session Surface  
2. 把 Turnip ICD 以 **thunk／adrenotools／VK_ICD** 真正接到客體 Vulkan（glibc↔bionic 邊界）  
3. DXVK／VKD3D 預設 override 驗證（D3D 測試 exe）  
4. **輸入橋**：觸控／鍵鼠→客體（WinHandler UDP 或 X 注入，取代 xdotool 檔）  
5. 音訊：Pulse／ALSA→AAudio  
6. （可選）Winlator 容器啟動腳本／環境變數表對齊，仍不繼承假桌面 UX  

`GpuStack.PRESENTATION_READY` 目前為 **false**；變 true 的條件＝缺口 1 落地並通過至少一個 PE 全螢幕煙測。
