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
