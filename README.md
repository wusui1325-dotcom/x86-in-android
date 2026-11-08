# x86-in-android

獨立原生 Android App：在 ARM 裝置上跑 x86 軟體。

- **不是** Winlator 假桌面殼
- 啟動：進 App → 選裝置上的 `.exe` 或 App 私有工作區 → 全螢幕直開
- Package：`com.wusper.x86`
- 工程：Compose Material3 殼＋P0 啟動管線（host ELF 可跑）；Box64 翻譯層待打包（見 docs/ENGINE.md）

詳見 [docs/PLAN.md](docs/PLAN.md)、[docs/ENGINE.md](docs/ENGINE.md)。

## UI

原生 **Jetpack Compose Material 3**（動態色可選）、繁中、無假桌面。

## 工作區模型

每個 exe 首次／啟動時，在 App 私有 data 下建立專屬工作區：

```
files/workspaces/<id>/
  meta.json     # 來源 exe、顯示名、時間戳
  exe/          # 本體副本（預留）
  data/         # 儲存／額外資源下載落點
```

- UI 兩區：**已有工作區**、**裝置上的 .exe**
- 若已有同名／同路徑工作區，**裝置 exe 列表會隱藏該項**，只顯示工作區入口

## 建置需求

- JDK 17（本機常用 `/workspace/jdk17`）
- Android SDK（本機常用 `/workspace/android-sdk`，platform 35 + build-tools）
- 僅 abi：`arm64-v8a`

## Release 簽名（keystore）

**絕對不要**把 `.jks` / `.keystore` / 密碼 commit 進公開 repo。

本機預設位置（已 gitignore）：

```
/workspace/.secrets/x86-in-android/
  wusper-x86-release.keystore
  keystore.properties
  CREDENTIALS.txt
```

`keystore.properties` 格式見 [`keystore.properties.example`](keystore.properties.example)。

建置讀取順序：

1. 環境變數 `ANDROID_KEYSTORE_PATH` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`
2. `$X86_KEYSTORE_PROPS` 指向的 properties
3. `/workspace/.secrets/x86-in-android/keystore.properties`
4. 專案根 `keystore.properties`

## 版本號（自動 bump）

單一真相來源：[`version.json`](version.json)。

```bash
# 只 bump（versionCode +1，versionName patch +1）
./scripts/bump_version.sh

# 一鍵：bump + assembleRelease
./scripts/release.sh

# 不 bump，只打 release
SKIP_BUMP=1 ./scripts/release.sh
```

`X86_BUMP_NAME=minor|major|none` 可改 versionName 規則（預設 `patch`）。

## 本機 release

```bash
export JAVA_HOME=/workspace/jdk17
export ANDROID_HOME=/workspace/android-sdk
./scripts/release.sh
# APK: app/build/outputs/apk/release/x86-in-android-release.apk
```

Debug：

```bash
./gradlew assembleDebug
```
