# 相容性與已知失敗模式

本 App 在 **ARM64（arm64-v8a）**、**Android 9+（minSdk 28）** 上執行 x86／Windows PE。

## GPU 期望

- 預設圖形路徑：**Mesa Turnip（KGSL）＋ DXVK**，需要裝置節點 `/dev/kgsl-3d0`。
- **Qualcomm Adreno**（有 KGSL）為主要驗證目標。
- **Mali／PowerVR／其他**：目前無 Vortek／adrenotools 通路；可能黑屏或無法出幀。App 會在啟動前警告「此裝置可能無畫面」。

## 虛擬桌面

- 固定 **1280×720**（Wine desktop）；螢幕以 letterbox 適配。解析度選擇器尚未提供。

## 已知失敗模式（請先「重裝運行時」）

| 體感 | 常見根因 | 建議 |
|------|----------|------|
| 已結束 (-1127)／命令不存在 | 缺 glibc box64、shell、或動態連結器 | App 內「重裝運行時」 |
| 黑畫面但狀態「執行中」 | 非 Adreno／無 KGSL；或 GPU 組件未裝完 | 見啟動前警告；確認 Adreno |
| 安裝後仍無法啟動 | 半殘 rootfs／舊 bionic box64 | 「重裝運行時」（不清工作區） |
| 權限被拒／無法執行 | 舊版曾把 proot 放在 noexec 目錄 | 升級 App；勿依賴「儲存權限」 |
| libtalloc／CANNOT LINK | 客體環境蓋掉宿主 `LD_LIBRARY_PATH` | 升級 App；必要時重裝運行時 |
| could not load ntdll.so／Cannot dlopen | 缺 Wine unix `ntdll.so`，或缺 Box64 x64lib（`libgcc_s.so.1`） | 「重裝運行時」（0.1.26+ 會拉 x64lib） |
| Illegal instruction／exit 132／SIGILL | Box64 開 AVX 後 Wine 走 AVX 路徑，dynarec 宿主非法指令 | 升到 0.1.27+（強制 `BOX64_AVX=0`）後重開；一般**不必**重裝運行時 |
| `/usr/bin/sh` not found | 常為缺 `ld-linux`（PT_INTERP），非真缺 sh | 「重裝運行時」 |

## 回報

請到 [Issues](https://github.com/wusui1325-dotcom/x86-in-android/issues) 附：版本名＋versionCode、裝置 SoC／GPU、session 錯誤卡原文。勿貼完整內部路徑以外的隱私資料。

多 runtime 釘選／回退尚未提供；「重裝運行時」會依目前 APK 的 `runtimeId` 拉取最新組件集。
