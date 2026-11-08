## 0.1.38 — mid prereserve silently dropped (hole-fit)

**Symptom (0.1.37 Boss):** stamp `wine64k-align-0x100000` installed, hex table in asset is
`{0x100000,0x30000000}`, but runtime only prints:
`prereserve 0x10000:0x10000` + `0x7f000000:0x3000000`. **Missing mid.**
`WINE_PE_SMOKE exit=1`, `WINE_EXIT=1`.

**Root cause:** Box64 `wine_prereserve()` calls `isBlockFree(0x100000, 0x30000000)`.
Under Android 39-bit VA / proot, that 768MiB hole is often **not free**. On failure it
`remove_prereserve()` **without logging** when `WINEPRELOADRESERVE` is unset
(`if(addr>=0x10000)` uses the env-parsed addr, which stays NULL).

**Fix:**
- Export `WINEPRELOADRESERVE=100000-1` so "is not free" warnings appear
- Runtime **hole-fit**: probe/patch `my_wine_reserve` mid addr/size in `/usr/local/bin/box64`
  until `WINE prereserve of <addr>:<size> done` appears; persist `.box64-wine64k-holefit`
- Do not export empty `WINEPRELOADRESERVE=` from Kotlin

**Retest:** Cover-install 0.1.38 → expect `HOLEFIT ok addr=… size=…`,
`WINE prereserve of <addr>:<size> done`, then `WINE_START` / frame.

## 0.1.37 — wineboot LdrInitializeThunk SIGSEGV + log-only Session

**Symptom (0.1.36):** Boss「好像成了／沒畫面／全熒幕都是 log」。`DYNAREC=1`、
`prereserve 0x10000:0x10000`／`0x7f000000` OK，但 `wine --version` 對空 prefix 觸發
`wineboot.exe --init`，隨後 **SIGSEGV 迴圈**：`ntdll.dll!LdrInitializeThunk` →
`KiUserExceptionDispatcher`（Emit Signal 11），Session 被 BOX64_LOG=1 刷屏、Surface 無幀。

**Root cause:**
1. Box64 預設低位預留 `0x110000:0x30000000`：**位址非 Wine11 64KiB 對齊**
   （`addr & 0xffff ≠ 0`）。mmap 失敗時被丟棄 → 只剩 DOS+hi 兩段 → PE loader 崩。
2. Wine11 unix `wine` stub **沒有 `--version` 短路**，空 WINEPREFIX 必走 wineboot。
3. 預設 `BOX64_LOG=1`／SHOWSEGV 讓 Session 日誌區淹沒 Surface。

**Fix:**
- Box64 wine64k：`0x110000` → **`0x100000`**（+既有 DOS `0x10000:0x10000`）；stamp
  `wine64k-align-0x100000-2026-11-09`；缺 stamp 強制重抽
- Smoke：版號改讀 `wine.inf`；另做短 `WINE_PE_SMOKE`（偵測 Signal 11 迴圈→exit 139；
  hang→124）
- 預設 `BOX64_LOG=0`；Session 執行中收合日誌＋「顯示日誌」；Surface 置中提示等待 Present

**Retest:** Cover-install **0.1.37** → 運行時自動重拉 box64（新 stamp）→ 日誌應有
`BOX64_WINE64K_STAMP=wine64k-align-0x100000-…`、
`WINE prereserve of 0x100000:0x30000000 done`、`WINE_VERSION_SMOKE ok`、
`WINE_START`；畫面不應再被 log 占滿。若仍 139：貼 prereserve／Emit Signal 11 段。

## 0.1.36 — wine --version hang under DYNAREC=0

**Symptom (0.1.35):** Session shows `執行中`；log stops at `WINE_VERSION_SMOKE begin cmd=…wine --version`
with **no** prereserve／version／exit. Boss confirmed pid still alive → **hang**, not silent crash.
Stamps／SELFTEST／NTDLL／X64LIB／GpuStack all OK.

**Root cause:** 0.1.29–35 forced `BOX64_DYNAREC=0` (interpreter). After wine64k fixed the early
Wine11 `granularity_mask` SIGABRT, `wine --version` proceeds into heavier init／wineserver and
**never returns** under interpreter. Smoke also redirected all stdout/stderr to a file with **no
timeout**, so the UI sat forever on `begin`.

**Fix:**
- Default `BOX64_DYNAREC=1` (dynarecSafe + NOHOSTEXT；bookworm x64lib 已否證「dynarec 必 SIGILL」)
- Smoke: 90s timeout + live tee/heartbeat + disposable `WINEPREFIX` + kill wineserver on timeout (exit 124)
- `ensureWineLaunchScript` 覆寫腳本 → **蓋裝即可**，不必重裝運行時

**Retest:** Cover-install 0.1.36 → expect `DYNAREC=1`, live Box64 lines, `WINE prereserve of 0x10000:0x10000`,
`wine-11.x` version string, `WINE_VERSION_SMOKE ok`. If still hang → `TIMEOUT`／exit 124 with partial out.

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

# 引擎進度

## 目標（Boss · 定案 A）

- 真 x86 主線；殼 `com.wusper.x86`。
- **GpuStack**：glibc Turnip ICD（KGSL）／DXVK／Gladio GLX／**進程內 X → Present→AHB→Session Surface**。
- SoftLegacy（Xvfb XWD→Canvas）僅 `forceSoftLegacy`。

詳見 [GPU_STACK.md](GPU_STACK.md)。

## 圖形／X（0.1.9+）

| 層 | 實作 |
|----|------|
| X 協定 | **進程內** `com.winlator.xserver`（vendored LGPL-2.1 from winlator-app）＋`libwinlator.so` |
| Socket | `files/engine/imagefs/tmp/.X11-unix/X0` bind 進 PRoot `/tmp/.X11-unix` |
| SysV SHM | 同 imagefs `tmp/.sysvshm`＋`ANDROID_SYSVSHM_SERVER` |
| Present | `PresentXFramePump`：GPUImage AHB→EGL／BGRA 單趟→Surface（砍 Java swizzle） |
| Xvfb | **GpuStack 不再使用**；僅 SoftLegacy |

## 手動驗收

1. 選 `.exe` → 日誌含 `啟動 Winlator Java X`／`INPROCESS_X_OK`（客體 gpu-stack.log）。
2. 不應再出現 GpuStack 路徑的 `X_PROTOCOL_STUB=xvfb`。
3. Surface 有幀（客體窗口或 stub 至首幀）。

## proot 可執行（0.1.16）

- 宿主 `proot` 以 `jniLibs/arm64-v8a/libproot.so` 為準（`nativeLibraryDir`，避開 filesDir `execv EACCES`）
- 落地後一律 `Os.chmod`（[ExecBits]）；安裝完成掃描 wine／box64／腳本
- 首次下載：並行＋斷點續傳＋進度條＋可取消

## rootfs /bin/sh（0.1.17）

Ubuntu Noble **usrmerge**：`bin`→`usr/bin`、`usr/bin/sh`→`dash`。解壓必須建立 symlink（舊版曾跳過 → proot `'/bin/sh' not found`）。
安裝時 `ensureUsrmergeShell` 可就地補齊；`VERSION.json` 含 `rootfsOk`／`rootfsShell`。客體 `PATH` 預設含 `/bin`／`/usr/bin`。

## proot 組合（0.1.18）

對齊 Termux／Winlator 可用鏈：
- `proot`（Termux 5.1.107）＋`libexec/proot/loader{,32}`＋`libtalloc.so.2`＋`libandroid-shmem.so`
- 部署於 `codeCacheDir/proot-bundle`；`PROOT_LOADER`／`LD_LIBRARY_PATH`／`PROOT_TMP_DIR`（可 exec）
- **禁止 strip** `libproot*.so`（靜態包內嵌 loader 被 strip → ENOSYS）

## proot 執行路徑（0.1.19）

Android `files`／`code_cache` 常 **noexec**。宿主 `libproot.so`／`libprootloader.so`／`libtalloc.so`／`libandroid-shmem.so` **只**從 `applicationInfo.nativeLibraryDir`（jniLibs）`execve`；`PROOT_TMP_DIR` 可放 `codeCacheDir`。
- **LD_LIBRARY_PATH**：啟動 `libproot.so` 時必須含 `nativeLibraryDir`（勿被客體 Gladio／Turnip 路徑覆寫）；
  `DT_RUNPATH=$ORIGIN` 作保底。客體 `LD_LIBRARY_PATH` 由 `wine-launch.sh` 設定。

## rootfs shell／ld-linux（0.1.21）

真機 `proot error: execve("/usr/bin/sh"): No such file or directory` 常見 **不是** sh 本體缺檔，
而是 **PT_INTERP** `/lib/ld-linux-aarch64.so.1` 在 rootfs 內不可解析（usrmerge `lib`→`usr/lib` 未建、
或 `ld-linux` symlink／實體缺失）——Linux／proot 對缺 interpreter 同樣回 ENOENT。

- `ensureUsrmergeShell`：補 `bin`／`lib`／`sh→dash`，並保證 `lib/ld-linux-aarch64.so.1`（symlink 失敗則 copy）
- `hasValidRootfs`：必須有可解析 shell ELF **且** 有 ld-linux；不接受 dangling symlink
- 啟動 argv 優先 `/usr/bin/dash`（真實 ELF），不再只靠 `/bin/sh` 字面

## box64 glibc（0.1.22）

客體 `/usr/local/bin/box64` 必須是 **glibc**（`libc.so.6`＋`/lib/ld-linux-aarch64.so.1`）。
The412Banner Nightlies `Box64-*-Bionic/Native.wcp` 皆為 bionic（`/system/bin/linker64`），
在 PRoot Ubuntu 內 `exec` → dash `box64: not found`（缺 interpreter 的 ENOENT）。

改用 `runtime-v1/Box64-0.4.4-glibc.wcp`（Winlator 0.4.4 衍生，patchelf INTERP）；
`isGlibcBox64` 校驗；`runtimeId` bump 強制重裝。

## 解壓 symlink／ELOOP（0.1.25）

真機「安裝失敗：Too many symbolic links encountered」：
舊 `extractTarXz` 用 `File.canonicalFile` 做輸出路徑。若 rootfs 已有 usrmerge（`bin`→`usr/bin`），
再解壓 entry `bin`→`usr/bin` 時會把輸出解析成 **`usr/bin` 本身**，刪目錄後建成自指環，後續 I/O 全 ELOOP。

修復：
- 解壓改 **lexical** `normalize`（不跟隨 symlink）
- `bin`／`lib`／`sbin` 只刪連結自身（`deleteSelfNoFollow`）
- 解 rootfs 前 `deleteRootfsSafely`（先拆 usrmerge）
- 拒絕自指 symlink；ELOOP 時清 rootfs 並提示重裝運行時

## Wine ntdll.so／Box64 x64lib（0.1.26）

Kron4ek amd64 Wine 的 `bin/wine` stub 會 `dlopen(.../lib/wine/x86_64-unix/ntdll.so)`。
Box64 以**模擬**方式載入該 x86_64 `.so`，並解析其 `NEEDED`（含 **`libgcc_s.so.1`**）。
`libgcc_s` **不是** Box64 wrapped 庫，必須提供 x86_64 實體於
`/usr/lib/box64-x86_64-linux-gnu/`（組件 `box64-x64lib`），並設定
`BOX64_LD_LIBRARY_PATH`（與宿主 aarch64 `LD_LIBRARY_PATH` 分開）。

缺 x64lib 時錯誤形如：`wine: could not load ntdll.so` + Box64 `Cannot dlopen("…ntdll.so"/…, 2)`
（此處 `2` 是 `RTLD_NOW` flag，不是 errno）。


## Box64 AVX／SIGILL（0.1.27）

ntdll 可載入後若立刻 `Illegal instruction`、客體退出碼 **132**（128+4＝SIGILL）：
常見根因是預設 `BOX64_AVX=1` 讓客體 Wine／glibc 走 AVX 路徑，Box64 dynarec 在部分 SoC
產生**宿主**非法指令。Winlator 7.1+ 亦預設關閉 AVX。

修復：`Box64Preset.performance` 與 `wine-launch.sh` **強制 `BOX64_AVX=0`**，並略收斂
`STRONGMEM`／`BIGBLOCK`；啟動時 `ensureWineLaunchScript` 會覆寫腳本，**通常只需更新 APK 重開**。

## Box64 NOHOSTEXT／SIGILL（0.1.28）

0.1.27 僅關 `BOX64_AVX` 仍 SIGILL。根因更可能是 **dynarec 依 getauxval(HWCAP) 發射宿主擴展指令**
（LSE atomics／FLAGM／AES…）；在 PRoot 或部分 SoC 上若 HWCAP 與實際可執行指令不一致，
會得到宿主 `Illegal instruction`（exit 132），且錯誤出現在 ntdll 可載入之後。

修復：
- `BOX64_DYNAREC_NOHOSTEXT=1`（只用 ASIMD）
- 客體 ISA 廣告全關：`AVX=0 SSE42=0 AES=0 SHAEXT=0 PCLMULQDQ=0`
- `SAFEFLAGS=2 CALLRET=0 BIGBLOCK=0 NATIVEFLAGS=0`
- `wine-launch.sh` 把診斷打到 **stdout**（session 可見）並做 `BOX64_SELFTEST`

## Box64 interpreter 預設（0.1.29）

找茬 R1：即便 `NOHOSTEXT=1`／AVX=0，dynarec 仍可能宿主 SIGILL。Wine／PE 預設改
`BOX64_DYNAREC=0`（interpreter）先過冒煙；通過後再切回 `dynarecSafe`。
「重裝運行時」對 SIGILL **通常無效**；看日誌 `BOX64_SELFTEST`／`BOX64_FLAGS`／`WINE_START`。

## Wine SIGILL under interpreter／x64lib bookworm（0.1.30）

0.1.29 已證：`BOX64_SELFTEST=0`、`DYNAREC=0`、`NOHOSTEXT=1`、ISA 廣告全關，載入
`ntdll.so`＋`libgcc_s.so.1` 後仍立刻 `Illegal instruction`（exit 132）。**dynarec 發射非法
宿主指令**假說已否證；故障在**模擬中的 Wine 早期／x64lib**（或子行程未回 Box64）。

修復：
- 換 **Debian bookworm `libgcc_s.so.1`（gcc-12）** 取代上游 x64lib 內 gcc-15 版；stamp
  `.x64lib-stamp`；缺 stamp 會再拉 `box64-x64lib`
- 客體內寫入 **aarch64 `/usr/local/bin/wine`／`wineserver` 包裝**（`exec box64 $REAL`），
  `WINELOADER` 指包裝；`WINELOADERNOEXEC=1`
- 啟動階梯：`box64 -v` → **`wine --version` smoke** → 再 `explorer /desktop=…`
- 補 `CPUTYPE=0`／`X87DOUBLE=1`／`WAIT=1`／`ALIGNED_ATOMICS=1`／`NORCFILES=1`／`X11GLX=1`

請**更新 APK 後重裝運行時**（換 x64lib）；日誌必看 `BOX64_X64LIB_STAMP`、
`WINE_VERSION_SMOKE`。

## wine --version exit 1／WINELOADER ELF（0.1.31）

0.1.30 把 `WINELOADER` 設成 aarch64 **shell 包裝**（`/usr/local/bin/wine`）。Wine 會依
`WINELOADER` 拼 `%s-preloader` → `/usr/local/bin/wine-preloader`（不存在）→
`could not exec the wine loader` → **exit 1**。SIGILL 已過，但冒煙／explorer 仍失敗。
**與 Android「存取所有檔案」無關。**

修復：
- `WINELOADER=/opt/wine/bin/wine`（x86_64 ELF）
- PATH 上保留包裝給 `execvp("wine"|"wineserver")`
- `/opt/wine/bin/wine-preloader` → `x86_64-unix/wine-preloader` symlink
- `WINE_VERSION_SMOKE` 完整輸出進 session；有 `wine-x.y` 字樣即過；否則硬停 exit 1

更新 APK 後**直接重開**即可（腳本由 ensureWineLaunchScript 覆寫；x64lib 未變通常不必重裝運行時）。

## c0000018 shared user data／unix wine（0.1.32）

`err:virtual:virtual_alloc_first_thread_data wine: failed to map the shared user data: c0000018`
（STATUS_CONFLICTING_ADDRESSES）：Box64 對 Wine 會 `mmap` 預留 `0x7f000000–0x82000000`（含
`0x7ffe0000` KUSER_SHARED_DATA），並把清單寫入主 ELF 的 **`wine_main_preload_info`**。
Kron4ek **`bin/wine` stub 沒有此符號**；只有 **`lib/wine/x86_64-unix/wine`**（與
`wine-preloader`）有。結果預留成功但 Wine 不知情 → 映射衝突。

修復：`WINE_REAL`／`WINELOADER`＝`/opt/wine/lib/wine/x86_64-unix/wine`。
更新 APK 後**直接重開**（不必重裝運行時）。

## Session log UX（0.1.32）

- **複製 log**：一鍵複製完整 session＋gpu-stack 合併日誌到剪貼簿
- **跳轉關鍵**：捲動到首個關鍵行並高亮（FATAL／WINE_VERSION_SMOKE／c0000018／
  BOX64_FLAGS／SELFTEST／Illegal instruction／NTDLL／stamp…）

## 0.1.33 — unix wine 路徑必須硬寫入腳本

0.1.32 聲稱改 `WINELOADER`→`x86_64-unix/wine`，但 assets 腳本仍留 `WINE_REAL=/opt/wine/bin/wine`，
且 log 用未定義的 `$WINE_UNIX` → `ls /wine-preloader`。真機仍 c0000018。

0.1.33：**字面量** `/opt/wine/lib/wine/x86_64-unix/wine` 寫入 WINELOADER／WINE_REAL／smoke／
WINE_START；`ensureWineLaunchScript` 先刪再覆寫。蓋裝即可，不必重裝運行時。
