# 引擎進度

## 目標（Boss · 定案 A）

- 真 x86 主線（Windows PE／Linux ELF）；Codex 只是例子。
- 殼：`com.wusper.x86`（M3、選 exe／工作區、Session Surface）。
- 引擎：**GpuStack**＝Turnip／DXVK／真 Surface（Winlator／glibc 級）；**禁止預設** Xvfb→XWD 軟渲。

詳見 [GPU_STACK.md](GPU_STACK.md)。

## 圖形

| 後端 | 狀態 | 說明 |
|------|------|------|
| **GpuStack（預設）** | 架構已切；Present 橋未就緒 | 可下載 Turnip＋DXVK；全螢幕顯示待進程內 X／Present |
| SoftLegacy | 工程保底 | `LaunchPipeline.forceSoftLegacy=true` 才走 Xvfb XWD→blit |

## 啟動模式

- `HostExec`／`Box64`：ELF 煙測／CLI  
- `WineProot`：PE＋PRoot＋Wine（僅 SoftLegacy 或 GpuStack Present 就緒時）  
- `WineGpuPending`：PE 預設路徑；組件可備料，但缺口未清前**不**啟動客體、**不**退回軟渲  

## 輸入

- SoftLegacy：Surface → cmd 佇列 → xdotool（保留）  
- GpuStack：待 WinHandler 級橋（見 GPU_STACK.md）

## 音訊

無 Pulse：`winepulse.drv=d`＋null ALSA。真喇叭→AAudio 未接。

## 手動驗收（0.1.7+）

1. 選 `.exe` → Session 顯示 **GpuStack** 狀態與缺口列表（Present✗）。  
2. 應自動嘗試下載 Turnip／DXVK；日誌有 `Turnip✓／DXVK✓` 或錯誤。  
3. **不應**再預設出現「Xvfb fbdir → Surface」blit。  
4. 工程：`forceSoftLegacy=true` 才允許舊軟渲路徑。  
