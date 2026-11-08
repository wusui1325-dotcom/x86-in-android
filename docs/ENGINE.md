# 引擎進度（P0）

## 已接上

- **啟動管線** `LaunchPipeline`：工作區 → 解析目標 → ELF/PE probe → 選模式 → `fork/exec` → `logs/session.log`
- **JNI** `libx86engine.so`：`nativeProbe`／`nativeExec`（含 env）／`nativeStop`／`nativeWaitNonBlocking`
- **SessionActivity**：全螢幕 Surface 佔位＋即時日誌；返回結束客體進程
- **EngineForegroundService**：前景通知保活
- **Box64（已打進 APK）**
  - 來源：ptitSeb/box64 `v0.4.4`，NDK r27、`-DANDROID=1 -DARM64=1`、`ANDROID_PLATFORM=android-28`、**ARM_DYNAREC=ON**、Release
  - 資產：`assets/engine/box64`（strip 後約 22MB）→ 首次執行解到 `files/engine/box64`
  - 啟動預設 **performance preset**（`Box64Preset.performance`）：`BOX64_DYNAREC=1`、FASTNAN／FASTROUND／CALLRET、STRONGMEM=0 等
  - 建置腳本：`scripts/build_box64_android.sh`
- **內建測試 ELF**（`assets/testdata/`）
  - `hello_host_arm64`／`hello_android_x86_64`／`hello_x86_64`
  - `microbench_host_arm64`／`microbench_android_x86_64`：同原始碼、同 iters 的整數微基準

## Boss 效能硬指標

**目標：同機、同負載下，Box64（x86_64 客體）≥ 原生 arm64 的 70%。**

選路徑原則：

1. 翻譯層優先 **Box64 + Dynarec**（已開 `ARM_DYNAREC`），不做解釋器當預設。
2. 預設 env 走 **performance preset**（見上）；穩定性問題再切 `Box64Preset.stability`。
3. 客體優先 **Android x86_64 ELF**（與 `ANDROID=1` Box64 對齊）；Linux glibc／PRoot 世界另線，不拿來充 70% 數字。
4. 圖形／音訊／Wine 不計入此 CPU 微基準；那些會另立幀率／延遲指標。

### 同負載對照怎麼跑

App 內兩顆芯片（同一 `microbench.c`、預設 `iters=50000000`）：

1. **對照：原生 arm64** → 讀日誌 `time_ms=…`
2. **對照：Box64 x86_64** → 讀日誌 `time_ms=…`
3. 比值：`native_ms / box64_ms`（越大越好；≥ **0.70** 即達標）

或手動：

```text
ratio = time_ms(native) / time_ms(box64)
達標 ⇔ ratio ≥ 0.70
```

本建置機無法代替真機（無同 SoC 對跑）；**請在目標裝置上跑上述兩芯片並把兩行 `time_ms` 貼回**。建置機上僅驗證了 x86_64 二進位可原生執行（開發機本身是 x86_64），不代表 Android ARM 上的 Box64 比值。

參考（僅開發機 x86_64 原生跑 guest 二進位，**不是** 70% 驗收）：`iters=2e7` 約 `time_ms≈36`。

## 限制

1. **minSdk 28**（Bionic `posix_spawn`／`glob`／`pthread_mutexattr_*protocol`）。
2. **ANDROID=1 Box64** 面向 Android x86_64 ELF；完整 Linux glibc rootfs＋PRoot 未接。
3. Linux 靜態 `hello_x86_64` 不保證成功。
4. 無圖形合成；Surface 仍佔位。
5. 70% 是 **CPU 整數微基準目標**，不是任意遊戲／任意 x86 軟體保證。
6. Wine／PE＝P2。

## 手動驗收

1. API 28+ 裝置安裝 APK  
2. 「跑 Box64（Android x86_64）」→ 日誌含 `guest-elf OK (android-x86_64 via box64)`  
3. 兩顆 microbench 芯片 → 算 ratio，對照 ≥0.70  
4. 返回鍵結束、無殭屍  
