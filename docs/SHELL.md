# 殼說明（0.2.x）

## UX（原生 Material 3）

1. 進 App 單一界面（Compose Material3；Android 12+ 可用動態色）
2. FAB「＋」→ SAF 挑檔 → 建立工作區 → **SessionActivity 真啟動管線**
3. 「已有工作區」→ 點進 → 同管線
4. 「裝置上的 .exe」只列尚未建工作區的已選檔；**已有工作區的 exe 會隱藏**
5. 「跑 host ELF 驗收」→ 內建 arm64 ELF，驗證 fork/exec＋日誌

## 包名 / SDK

- `applicationId` / namespace：`com.wusper.x86`
- minSdk 26、target/compile 35、abi 僅 `arm64-v8a`
- UI：Jetpack Compose + Material3（繁中字串）
