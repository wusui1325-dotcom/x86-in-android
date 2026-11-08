# 第三方組件

## Box64

- 專案：https://github.com/ptitSeb/box64
- 版本：v0.4.4（APK）／glibc 組件 0.4.3
- 授權：MIT
- 用途：x86_64 → ARM64 動態翻譯

## PRoot（Android portable）

- https://github.com/skirsten/proot-portable-android-binaries
- 授權：GPL-3（隨 Termux proot）
- 用途：無 root 進入 glibc rootfs

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
- 路徑：`files/engine/gpu-stack/turnip-25.1.0-R2/`＋`freedreno_icd.json`（客體相對 `library_path`）
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
- 注意：未 vendor gladiorenderer（GLX stub）；未併入 Winlator 假桌面 UX

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
