# 空殼說明（0.1.x）

## UX

1. 進 App 單一界面
2. 「選取 .exe」→ SAF 挑檔 → 建立工作區 → Toast「即將啟動」
3. 「已有工作區」→ 點進 → Toast 假啟動
4. 「裝置上的 .exe」只列尚未建工作區的已選檔；**已有工作區的 exe 會隱藏**

## 包名 / SDK

- `applicationId` / namespace：`com.wusper.x86`
- minSdk 26、target/compile 35、abi 僅 `arm64-v8a`
