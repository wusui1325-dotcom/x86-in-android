<!-- 0.1.91: APP_LOAD stamp/export + RPCSS done + seed gate; DXVK x32 still defer -->
# 第三方組件

## Box64

- 專案：https://github.com/ptitSeb/box64
- 版本：v0.4.4（APK）／glibc 組件 0.4.4（runtime-v1 Box64-0.4.4-glibc.wcp；非 Nightlies bionic）
- 授權：MIT
- 用途：x86_64 → ARM64 動態翻譯

## PRoot（Termux 組合 · 0.1.18）

- https://github.com/termux/proot （套件 `proot` 5.1.107.102 aarch64）
- 依賴：`libtalloc`、`libandroid-shmem`（Termux apt）
- 授權：GPL-3
- 用途：無 root 進入 glibc rootfs（`PROOT_LOADER`＋loader）
- 備援靜態包：https://github.com/skirsten/proot-portable-android-binaries（`proot-static`；勿 strip）

## Ubuntu rootfs（proot-distro）

- https://github.com/termux/proot-distro （noble aarch64）
- 用途：glibc 用戶態

## Wine（Kron4ek Builds）

- https://github.com/Kron4ek/Wine-Builds （11.21 staging amd64）
- 授權：LGPL／Wine
- 用途：Windows PE（經 Box64）

## Mesa Turnip glibc ICD（GpuStack · Winlator 等價）

- https://github.com/K11MCH1/WinlatorTurnipDrivers （`winlator_v25.1_r2` → `libvulkan_freedreno.so`）
- 上游：Mesa freedreno／turnip（Pipetto-crypto／KGSL 移植）
- 授權：**MIT**（Mesa）
- 用途：PRoot **glibc** 客體 Vulkan ICD（直載；需 `/dev/kgsl-3d0`）
- 路徑：`files/engine/gpu-stack/turnip-25.1.0-R2/`＋`freedreno_icd.json`（客體絕對 `library_path=/mnt/gpu/turnip/libvulkan_freedreno.so`；0.1.71）；0.1.72 seed `dxvk.conf`；0.1.73 `TU_DEBUG=noconform,noubwc,sysmem`＋Mesa shader cache；0.1.74 winex11 HWND/FS knobs＋Present HWND prefer；0.1.75 map+raise／MAP_WAIT／VIEWABLE＋dxvk maxFrameLatency；0.1.76 Smart TU_GMEM auto a710/715/720/725；0.1.77 APP_LOAD fingerprint＋bringToFront raise；0.1.78 Loaded-but-no-HWND sticky＋nohwnd map；0.1.79 CUI wineconsole＋APP_CREATEWINDOW_MISS；0.1.80 VS CRT seed＋APP_CRT_RELAUNCH；0.1.81 MessageBox/title＋msgbox＋dialog sticky＋APP_MSGBOX_DETECT；0.1.82 dxgi.syncInterval=0＋d3d9.presentInterval=0（DXVK 2.7.1）；0.1.83 PresentExtension GPUImage cpuAccess=false（match DRI3）；0.1.84 d3dcompiler_47/46/43 seed（APP_D3DCOMPILER_SEED）；0.1.85 awk→POSIX（no imagefs awk）＋Present forceGpu ahbCpuAccess0（reason=ahbCpuAccess0）；0.1.86 ensure_vulkan_seed（vulkan-1＋winevulkan；APP_VULKAN_SEED）；0.1.87 WoW64 i386 APP_LOAD（0x014c→wine start C:/App；ensure_wow64_seed；014c match）；0.1.88 InProcessX root GPUImage cpuAccess=false（align PresentExtension 0.1.83／DRI3）；0.1.89 WoW64 direct-first（wine C:/App CreateProcess；ladder direct→explorer→start；APP_SEED visible；DXVK x32 defer）；0.1.90 hang-alive ladder advance（kill+fail_sig/no_Loaded timed；dosdevices c:；DO_HB 8/12/15）
- 依賴：rootfs `x11-overlay`（`libvulkan.so.1`／`libdrm`／`libxcb-*`）

### 未採用（本輪）

- nihui/mesa-turnip-android-driver：**bionic** ICD，不能給 glibc Wine／Box64 `dlopen`
- adrenotools／Vortek IPC：宿主 bionic 渲染進程通路，另檔

## DXVK（GpuStack）

- https://github.com/doitsujin/dxvk （v2.7.1）
- 授權：**Zlib**
- 用途：D3D8/9/10/11 → Vulkan；stage 進 wineprefix `system32`


## Winlator Java X／libwinlator（進程內 X · 已 vendor）

- 來源：https://github.com/brunodev85/winlator-app
- 授權：**LGPL-2.1**（`third_party/winlator/LICENSE`）
- 路徑：`app/src/main/java/com/winlator/{xserver,xconnector,sysvshm,…}`、`app/src/main/cpp/winlator/`
- 用途：進程內 X11＋epoll connector＋drawable／MIT-SHM；畫面接我們的 Present
- 已 vendor：`gladiorenderer`＋客體 `libGL.so`（Gladio 1.1）；未併入 Winlator 假桌面 UX

## Winlator（其餘架構參考）

- https://github.com/brunodev85/winlator
- 授權：**LGPL-2.1**
- 用途：容器／GPU／輸入參考；Java X 已部分 vendor（見上）
- 若未來連結其修改後函式庫：遵守 LGPL（來源告知、目標檔／可替換連結）

## SoftLegacy 專用（非預設）

### X11／Xvfb overlay

- Ubuntu noble arm64；Release `runtime-v1` `x11-overlay-arm64.tar.xz`
- 用途：僅 `GRAPHICS_BACKEND=soft`

### xdotool（input-overlay）

- Ubuntu noble arm64；`input-overlay-arm64.tar.xz`
- 用途：SoftLegacy 觸控注入

## Electron／GUI 庫 overlay

- Ubuntu noble arm64 debs；`electron-overlay-arm64.tar.xz`
- 用途：常見缺庫補齊

## wine-mono／wine-gecko

- Wine HQ；`wine-addons-overlay.tar.xz`
- 用途：.NET／內嵌 HTML

## Gladio／GLX（GpuStack OpenGL）

- 來源：https://github.com/brunodev85/winlator-app（`gladiorenderer`＋`graphics_driver/gladio-1.1.tzst`）
- 授權：**LGPL-2.1**（隨 Winlator；見 `third_party/winlator/LICENSE`）
- 宿主：`libgladiorenderer.so`＋`GLXExtension`（取代 BadImplementation stub）
- 客體：`/opt/gladio/lib/libGL.so*`（Wine OpenGL → 自訂 GLX opcode → 環緩衝 → GLES）
- 備註：VirGL／Zink overlay 未本輪預設啟用（Turnip Vulkan＋DXVK 仍為 D3D 主線）

## ALSA android_aserver → AAudio（0.1.14）

- 協定／Java server 對齊：https://github.com/brunodev85/winlator-app（`alsaserver`／`android_aserver` plugin）
- 授權：**LGPL-2.1**（隨 Winlator；見 `third_party/winlator/LICENSE`）
- 宿主播放：**AAudio**（Android NDK；非 AudioTrack）；native：`app/src/main/cpp/aaudio/`
- 客體 plugin asset：`engine/audio/alsa-lib/libasound_module_pcm_android_aserver.so`（自 Winlator rootfs_patches 抽取）
