# 引擎進度

## 目標（Boss）

- 真的能跑 x86 應用（例：Codex 級 **可點可操作** GUI／CLI）。
- 選 `.exe` → 工作區 → 畫面進 Surface → 觸控／鍵鼠進 X11。

## 圖形

**Xvfb `-fbdir`（XWD）→ `SurfaceFrameBridge` → Session SurfaceView**（約 15 FPS 軟渲）。

## 輸入（本版）

**最短路徑：Surface 觸控／鍵鼠 → 命令檔佇列 → 客體 `xdotool`（XTEST）。**

```
Android SurfaceView
  onTouch / onKey / onGenericMotion
    → 座標：letterbox 反查到 1280×720 絕對座標
    → append /mnt/data/x11input.cmd
Guest x11-input-agent.sh
  → 輪詢 cmd 檔 → xdotool mousemove/mousedown/mouseup/key…
  → DISPLAY=:0（同一個 Xvfb）
```

- 至少：**button1 click／drag／move**；外接滑鼠 hover／滾輪；常見鍵盤鍵。
- 組件：`input-overlay-arm64.tar.xz`（xdotool 等，Release `runtime-v1`）。

## Electron／常見 Win GUI 庫（runtime overlay）

啟動時若 `runtimeId` 落後或缺 stamp，`ComponentInstaller` 會自動從 GitHub Release **`runtime-v1`** 補下載（不進 APK）：

| overlay | 內容（摘要） | 約大小 |
|---------|--------------|--------|
| `electron-overlay-arm64.tar.xz` | NSS／GTK3／libxcb*／ALSA＋Pulse 客戶端、null `asound.conf` 等 aarch64 宿主庫 | ~62 MB |
| `wine-addons-overlay.tar.xz` | wine-mono **10.1.0** MSI、wine-gecko **2.47.4**（x86_64＋x86）→ `/opt/wine/share/wine/` | ~161 MB |

`runtimeId`：`wine-proot-v1.3-electron`。

## 圖形：軟渲 vs DXVK

- **預設軟渲**：`LIBGL_ALWAYS_SOFTWARE=1`、`GALLIUM_DRIVER=llvmpipe`（可改 `softpipe`）。畫面仍走 Xvfb XWD → Surface（~15 FPS）。
- **未捆 DXVK**：Android／PRoot 內無穩定 Vulkan ICD＋DXVK 二進位鏈；需要時可自行把 `dxvk` 的 x86_64 `dll` 丟進 wineprefix `drive_c/windows/system32` 並設 `WINEDLLOVERRIDES=d3d11,dxgi=n`，且宿主需可用 Vulkan（目前未保證）。
- Electron／Chromium 在 Wine 下多半走 OpenGL／ANGLE；缺 GPU 時依軟渲，效能遠低於原生。

## 音訊

無 Pulse 伺服器：預設 `winepulse.drv=d`＋`/etc/asound.conf` null PCM，避免卡在音訊初始化。真實喇叭輸出尚未接 Android AudioTrack。

## 限制

1. 輸入經檔案佇列＋xdotool，有數十 ms 級延遲；快速拖曳可能掉點。
2. 多點觸控只取主指標；無完整手勢。
3. IME／中文輸入法未接（ASCII／硬體鍵為主）。
4. 圖形仍軟渲 XWD；DXVK 未預裝（見上）。
5. Electron／Codex：常見缺庫已用 overlay 補；仍可能缺個別 `.so`、或因軟渲／Box64 過慢而「看似卡住」。
6. wine-mono 首次 wineboot 會從 MSI 展開，可能多花一次時間。

## 手動驗收

1. 安裝 0.1.6+，舊運行時會再下 electron／wine-addons overlay。  
2. 跑 GUI／Electron `.exe` → Surface 有畫面；`libnss3`／GTK 相關 dlopen 不應再缺。  
3. 手指點／拖與硬體鍵仍可用。  
4. 日誌：`xvfb.log`／`x11input.log`；mono 路徑：`rootfs/opt/wine/share/wine/mono/`。  
