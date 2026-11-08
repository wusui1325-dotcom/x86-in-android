# 引擎進度（P0）

## 已接上

- **啟動管線** `LaunchPipeline`：工作區 → 解析目標 → ELF/PE probe → 選模式 → `fork/exec` → `logs/session.log`
- **JNI** `libx86engine.so`：`nativeProbe`／`nativeExec`／`nativeStop`／`nativeWaitNonBlocking`
- **SessionActivity**：全螢幕 Surface 佔位＋即時日誌；返回結束客體進程
- **EngineForegroundService**：前景通知保活
- **內建測試 ELF**（`assets/testdata/`）
  - `hello_host_arm64`：可在裝置上直接 exec（**P0 驗收路徑**）
  - `hello_x86_64`：Linux x86_64 靜態 ELF，等 Box64

## 尚未完成（Box64）

本輪曾以 NDK 編 ptitSeb/box64 `v0.4.4`（`-DANDROID=1 -DARM64=1`）：

- Bionic 缺 `pthread_mutexattr_*protocol`（改 `-DTERMUX=1` 可過 threads）
- 缺／難用 `glob`（已在 vendor 樹對 `steam.c` 打 stub，**未回上游**）
- 續撞 `posix_spawn` 等，完整編過需一組 Android 相容補丁

因此 **APK 尚未內嵌 box64 二進位**。管線已預留：

1. 把可執行 `box64` 放到 `files/engine/box64`，或
2. 以 `jniLibs/arm64-v8a/libbox64.so`（可 exec）打包

之後 `ElfX86_64` 會自動走 `box64 <elf>`。

完整「Linux glibc x86_64 世界」仍需 **最小 rootfs + PRoot**（見 PLAN）；ANDROID=1 的 Box64 偏 Android x86_64 ELF。

## 手動驗收

1. 安裝 APK → 點「跑 host ELF 驗收」
2. Session 應出現 `x86-in-android host-smoke OK` 日誌，進程結束後狀態「已結束」
3. 返回鍵不可留殭屍（`nativeStop`）
