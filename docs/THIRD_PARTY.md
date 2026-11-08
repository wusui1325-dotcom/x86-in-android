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

## Mesa Turnip（GpuStack）

- https://github.com/nihui/mesa-turnip-android-driver （26.2.6）
- 上游：Mesa freedreno／turnip
- 授權：**MIT**
- 用途：Adreno Vulkan ICD；下載至 `files/engine/gpu-stack/turnip-*`

## DXVK（GpuStack）

- https://github.com/doitsujin/dxvk （v2.7.1）
- 授權：**Zlib**
- 用途：D3D8/9/10/11 → Vulkan；stage 進 wineprefix `system32`

## Winlator（架構參考・未整樹 vendor）

- https://github.com/brunodev85/winlator
- 授權：**LGPL-2.1**
- 用途：對齊容器／GPU／輸入層蛋糕與 Present→Surface 思路；本專案自研殼，不繼承假桌面 UX
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
