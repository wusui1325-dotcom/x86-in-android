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

## 限制

1. 輸入經檔案佇列＋xdotool，有數十 ms 級延遲；快速拖曳可能掉點。
2. 多點觸控只取主指標；無完整手勢。
3. IME／中文輸入法未接（ASCII／硬體鍵為主）。
4. 圖形仍軟渲 XWD；無 DXVK／觸控精密校准 UI。
5. Codex 級 Electron 仍可能缺庫／GPU；但窗口應可點。

## 手動驗收

1. 安裝 0.1.5+，首次補下載 input-overlay（若舊運行時缺 xdotool）。  
2. 跑 GUI `.exe`／whoami 桌面 → Surface 有畫面。  
3. 手指點／拖：游標／按鈕應有反應；硬體鍵盤可打字。  
4. `/data/.../x11input.log` 可見 cmd 記錄。  
