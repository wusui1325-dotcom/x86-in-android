# 用戶找茬 P0 驗收（0.1.23）

基底：`a3fef09`（0.1.22 glibc box64）。本批 **不**改 wine-launch box64 路徑邏輯。

## 已關閉

| # | 項目 | 驗收 |
|---|---|---|
| 2 | 退出碼解碼 | Session 結束後錯誤卡標題／說明為繁中；括號或次行可見原始碼（如 -1127→客體命令不存在） |
| 3 | 黑屏錯誤卡 | 失敗／非零結束時 Surface 上覆 Card，含「重試」「重裝運行時」 |
| 4+20 | 一鍵重裝運行時 | 錯誤卡＋首頁 ⋮ 選單；清 `files/engine/runtime`＋`cache`＋`gpu-stack`；**不**刪 workspaces；文案統一「重裝運行時」 |
| 5 | 下載確認 | 首次需裝 runtime 前 AlertDialog，顯示約 xxxMB |
| 6 | sha256 | `rootfs` 已填；`box64-glibc`／`wine` 沿用 0.1.22；CRITICAL 缺 sha 拒裝 |
| 15 | 禁 URI→hostArm64 | `resolveTargetFile` 失敗拋錯；Session 顯示錯誤卡 |
| 22 | GPU 失敗重試 | 錯誤卡「重試」在 gpuFailed 時只重跑 `GpuStack.installComponents` |
| 14 | 煙測閘 | 預設隱藏；連點版本號 7 次或開開發者後顯示 |
| 17 | 發行命名 | README「下載哪個 APK？」 |
| 12 | 刪工作區 | 長按工作區 → 確認刪除 `files/workspaces/<id>` |
| 13 | 同名 exe | `findByExe`／`filterVisibleExes` 只比路徑 |

## 刻意未動（Grok／P1）

| # | 原因 |
|---|---|
| 1 | 已於 0.1.22 關閉 |
| 7 | Box64 來源已換 runtime-v1 glibc；路徑脆弱性後續 |
| 8 | 部分內部字串已改；其餘 P1 |
| 9–11, 16, 18–19, 21, 23–28 | P1 |

## Smoke（真機／模擬）

1. 裝 0.1.23（或本分支 assembleRelease）於已有 0.1.22 之上。
2. 清資料後選 .exe → 應先出「需要下載運行時（約 450MB）」→ 取消則錯誤卡；確認才下載。
3. 人為撤銷 URI 權限後從工作區再開 → 錯誤卡「無法讀取…」，**不得**跑 hello_host。
4. 強制結束客體／缺命令 → 錯誤卡解碼，非只「已結束 (-1127)」。
5. 錯誤卡「重裝運行時」→ 工作區仍在；再進 Session 會重新下載。
6. 首頁預設無煙測芯片；連點版本 7 次後出現。
7. 兩路徑同名 `game.exe` 皆可見；長按工作區可刪。

