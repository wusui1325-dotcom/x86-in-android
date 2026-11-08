## 0.1.35 — XZFormatException on wine64k.wcp extract

**Symptom (0.1.34 cover-install / reinstall):** `安裝失敗：Input is not in the XZ format` (`XZFormatException`)
while pulling `Box64-0.4.4-glibc-wine64k.wcp` after stamp-forced box64 reinstall.

**Root cause:** `extractBox64` → `extractTarXz` requires XZ magic `FD 7zXZ`. The 0.1.34
runtime asset was uploaded as an **uncompressed ustar** (~30.7 MiB, header name `box64`) that
already contained the correctly patched binary + stamp. Installer opened it with `XZInputStream` → fail.
Working `Box64-0.4.4-glibc.wcp` is xz (~3.5 MiB).

**Fix:** Re-upload `runtime-v1/Box64-0.4.4-glibc-wine64k.wcp` as xz-compressed ustar
(sha256 `2e77cd8c…`, ~3.4 MiB, magic `FD 7zXZ`). APK 0.1.35 updates embedded sha256 +
`requireXzMagic` guard before extract. Path A / WineProot / GpuStack unchanged.

**Retest:** Cover-install 0.1.35 (or 重裝運行時). Expect box64-glibc re-download ~3.4MB,
`BOX64_WINE64K_STAMP=…`, `WINE prereserve of 0x10000:0x10000 done`, smoke ok.
Do **not** reuse 0.1.34 alone after the asset replace — its pinned sha256 is the old raw tar.

## 0.1.34 — Wine11 `set_mem_area_state` / `granularity_mask` (exit 134)

**Symptom (0.1.33):** `WINELOADER` correct (`x86_64-unix/wine`), then:
`Assertion !(size & granularity_mask) failed` → `WINE_VERSION_SMOKE exit=134` (SIGABRT).
mmap of mem_areas OK; assert fires while applying Box64 `wine_main_preload_info`.

**Root cause:** Box64 default DOS prereserve is `{0x10000, 0x8000}` (32 KiB). Wine 11
`granularity_mask = 0xffff` requires size **and** addr 64 KiB-aligned. `0x8000 & 0xffff ≠ 0` → abort.
(Winlator/Proton 9 often never hit this path; Wine 11’s new mem_areas table is strict.)

**Fix:** Ship `Box64-0.4.4-glibc-wine64k.wcp` with DOS size patched `0x8000 → 0x10000`, plus
`.box64-wine64k-stamp`. ComponentInstaller force-reinstalls box64 when stamp missing.
Also `BOX64_MMAP32=0` (Winlator-style). No Wine version swap needed for this assert.

**Retest:** Cover-install APK → runtime auto-pulls new box64 (stamp) → expect
`WINE prereserve of 0x10000:0x10000 done` and `wine-11.21` version string (smoke exit 0).

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
| Illegal instruction／exit 132／SIGILL | interpreter 下仍炸＝ntdll／libgcc／wine 子行程 | 升到 **0.1.30+** 並**重裝運行時**（bookworm x64lib）；看 `WINE_VERSION_SMOKE`／`BOX64_X64LIB_STAMP` |
| wine --version／exit 1／-1001 | `WINELOADER` 指到 shell 包裝或缺 preloader | 升到 **0.1.31+** 直接重開；看 `WINE_VERSION_SMOKE` 全文；**不是**「存取所有檔案」 |
| c0000018／shared user data | `bin/wine` 無 `wine_main_preload_info`，Box64 預留位址衝突 | 升到 **0.1.32+** 直接重開（改用 `x86_64-unix/wine`） |
| `/usr/bin/sh` not found | 常為缺 `ld-linux`（PT_INTERP），非真缺 sh | 「重裝運行時」 |

## 回報

請到 [Issues](https://github.com/wusui1325-dotcom/x86-in-android/issues) 附：版本名＋versionCode、裝置 SoC／GPU、session 錯誤卡原文。勿貼完整內部路徑以外的隱私資料。

多 runtime 釘選／回退尚未提供；「重裝運行時」會依目前 APK 的 `runtimeId` 拉取最新組件集。
