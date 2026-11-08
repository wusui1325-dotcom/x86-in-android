# 引擎進度

## 目標（Boss）

- **真的能跑 x86 應用**（例：Codex 等 Windows／x86 程式），不是 hello／microbench 而已。
- 啟動 UX：選裝置上 `.exe` → 建工作區（`files/workspaces/<id>/`，本體落 `exe/app.exe`，資料／Wine prefix 落 `data/`）→ 直開。
- 效能目標：同機同負載 Box64 ≥ 原生 arm64 **70%**（CPU 微基準；見下）。

## 已接上

| 層 | 狀態 |
|----|------|
| Compose Material3 殼 | ✅ |
| 工作區／同名藏 exe | ✅ |
| JNI fork/exec＋env | ✅ |
| ANDROID=1 Box64（APK 內） | ✅ 跑 Android x86_64 ELF |
| PRoot（APK 內 assets） | ✅ |
| Wine＋Ubuntu rootfs＋glibc Box64 | ✅ **首次 PE 啟動下載安裝**（`ComponentInstaller`） |
| PE 啟動鏈 | ✅ `proot → box64 → wine app.exe`（每工作區獨立 `data/wineprefix`） |

### PE／Wine 啟動鏈

```
選 .exe → 工作區 exe/app.exe
→（若尚未安裝）下載：
   Ubuntu noble aarch64 rootfs（proot-distro）
   Kron4ek Wine 11.21 staging amd64
   Nightlies Box64-0.4.3.wcp（glibc／aarch64）
→ proot -r rootfs -b exe:/mnt/app -b wineprefix:~/.wine \
     /usr/local/bin/box64 /opt/wine/bin/wine /mnt/app/app.exe
```

煙測芯片：**跑 Wine whoami.exe**（內建 `testdata/wine_whoami.exe`，來自 Wine 套件）。

### 70% 微基準

芯片「對照：原生 arm64／Box64 x86_64」；`ratio = native_ms / box64_ms ≥ 0.70`。預設 `Box64Preset.performance`（Dynarec）。

## 限制（務必讀）

1. **minSdk 28**；首次 PE 需網路下載 **約 160MB+**（之後離線可開）。
2. **尚無 X11／圖形合成進 Surface**——GUI 程式（含多數 Codex UI）可能黑屏／失敗；**先衝 console／輕量 PE**。
3. ANDROID=1 Box64 ≠ glibc rootfs 內的 Box64；兩條並存。
4. Linux glibc 動態 ELF 未全面驗證；Wine 依賴可能缺庫（需再補 rootfs 套件）。
5. 整叉 Winlator 仍避免；組件來自上游（Wine LGPL、Box64 MIT、Ubuntu、PRoot GPL）。見 `docs/THIRD_PARTY.md`。
6. 70% 是 CPU 微基準，不是任意遊戲／任意 exe 保證。

## 手動驗收

1. API 28+、有網路：安裝 APK → **跑 Wine whoami.exe**（首次較久）→ 日誌應見 Wine／whoami 輸出或至少 wineserver 啟動痕跡。  
2. 選任意 `.exe` → 進工作區 → Session 走同一條 Wine 鏈。  
3. 返回結束進程。  
