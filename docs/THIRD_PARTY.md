# 第三方組件

## Box64

- 專案：https://github.com/ptitSeb/box64
- 版本：v0.4.4
- 授權：MIT
- 用途：x86_64 → ARM64 動態翻譯（Android 建置，`-DANDROID=1`）
- 二進位：`app/src/main/assets/engine/box64`（本專案 NDK 交叉編譯產物）

## PRoot（Android portable）

- https://github.com/skirsten/proot-portable-android-binaries
- 授權：GPL-3（隨 Termux proot）
- 用途：無 root 進入 glibc rootfs

## Ubuntu rootfs（proot-distro）

- https://github.com/termux/proot-distro （noble aarch64 映像）
- 用途：glibc 用戶態

## Wine（Kron4ek Builds）

- https://github.com/Kron4ek/Wine-Builds （11.21 staging amd64）
- 授權：LGPL／Wine 授權
- 用途：跑 Windows PE（經 Box64 翻譯）

## Box64（glibc 組件包）

- The412Banner/Nightlies `Box64-0.4.3.wcp`（源自 ptitSeb/box64，MIT）
- 用途：rootfs 內翻譯 x86_64 Wine／客體

## X11／Xvfb overlay

- 來源：Ubuntu noble arm64 套件（xvfb、xserver-xorg-core 及依賴）
- 打包：`x11-overlay-arm64.tar.xz`（Release runtime-v1）
- 用途：客體 DISPLAY=:0；`-fbdir` XWD 供 Android Surface 讀取

## xdotool（input-overlay）

- Ubuntu noble arm64：`xdotool`、`libxdo3`、`libxtst6` 等
- 授權：隨套件（xdotool 多為 BSD／MIT 系；以 deb 為準）
- 用途：客體注入 X11 指標／鍵盤事件

## Electron／GUI 庫 overlay

- 來源：Ubuntu noble arm64 debs（NSS、GTK3、libxcb*、ALSA、Pulse 客戶端等；已排除 systemd／大型 llvm+mesa-dri）
- 打包：`electron-overlay-arm64.tar.xz`（Release runtime-v1）
- 用途：Box64 包裝宿主 aarch64 `.so`，供 Electron／常見 Win GUI 在 rootfs 內 dlopen

## wine-mono／wine-gecko（wine-addons-overlay）

- wine-mono 10.1.0：https://dl.winehq.org/wine/wine-mono/（Wine HQ）
- wine-gecko 2.47.4 x86_64＋x86：https://dl.winehq.org/wine/wine-gecko/
- 打包：`wine-addons-overlay.tar.xz` → `/opt/wine/share/wine/{mono,gecko}/`
- 用途：.NET／內嵌 HTML 等；首次 wineboot 可能展開 MSI
