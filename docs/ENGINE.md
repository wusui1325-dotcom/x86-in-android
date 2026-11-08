# 引擎進度

## 目標（Boss）

- 真的能跑 x86 應用（例：Codex 級 GUI／CLI）。
- 選 `.exe` → 工作區 → 直開；畫面進 App Surface。
- 效能：同機同負載 Box64 ≥ 原生 arm64 **70%**（CPU 微基準）。

## 圖形方案（本版）

**最短路徑：`Xvfb -fbdir`（XWD）→ `SurfaceFrameBridge` blit 到 `SessionActivity` SurfaceView。**

```
wine-launch.sh
  └─ Xvfb :0 -screen 1280x720x24 -fbdir /mnt/data/xfb
  └─ box64 wine explorer /desktop=x86,1280x720 app.exe
Android
  └─ 輪詢 /data/.../xfb/Xvfb_screen0（XWD）
  └─ 解碼 → Bitmap → SurfaceHolder.lockCanvas blit（約 15 FPS）
```

- X11 組件：`x11-overlay-arm64.tar.xz`（GitHub Release `runtime-v1`），首次／升級時由 `ComponentInstaller` 下載解進 rootfs。
- 啟動腳本：`assets/engine/wine-launch.sh` → rootfs `/usr/local/bin/wine-launch.sh`。

### 為何不是 Xwayland／VirGL／Termux-X11

| 方案 | 取捨 |
|------|------|
| **Xvfb＋XWD→Surface（本版）** | 實作最短、不需 GPU IPC；延遲／幀率有限，夠衝 GUI 冒煙與 Codex 級窗口出現 |
| Termux-X11 | 業界常用但綁外部 APK／複雜整合 |
| VirGL／Turnip／Vortek | 效能更好，工程量大（P3） |
| Wine android.drv | 需專用 Wine 建置，非 Kron4ek Linux amd64 |

## 已接上

| 層 | 狀態 |
|----|------|
| PE→PRoot→Box64→Wine | ✅ |
| Xvfb＋虛擬桌面 | ✅ |
| XWD→Surface | ✅ |
| ANDROID=1 Box64 ELF | ✅ |
| 70% microbench 芯片 | ✅ |

## 限制

1. **軟渲＋檔案輪詢**：預期 10–20 FPS 級，非遊戲幀率；色彩／對齊依 Xvfb XWD 布局可能需微調。
2. **無觸控→X11 輸入映射**（下步）；暫靠外接鍵鼠／鍵盤（若系統送達）。
3. **無音訊**。
4. Codex 等複雜 Electron／GPU 應用可能仍缺庫或需 DXVK／Vulkan（未接）。
5. 首次運行時下載約 **220MB+**（含 X11 overlay）。
6. minSdk 28。

## 手動驗收

1. 有網路裝置安裝 APK →「跑 Wine whoami.exe」或選任意 GUI `.exe`。  
2. Session Surface 應不再只是佔位字，出現 Xvfb 桌面／視窗像素（whoami 可能一閃即逝；可用 notepad／winecfg 測）。  
3. 日誌含「圖形橋：Xvfb fbdir → Surface」。  
