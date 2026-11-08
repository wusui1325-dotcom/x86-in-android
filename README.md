# x86-in-android

獨立原生 Android App：在 ARM 裝置上跑 x86 軟體。

- **不是** Winlator 假桌面殼
- 啟動：進 App → 選裝置上的 `.exe` 或 App 私有工作區檔 → 全螢幕直開
- 工程：Box64（翻譯）＋之後 Wine（Windows PE）；產品主路徑朝 Windows exe

詳見 [docs/PLAN.md](docs/PLAN.md)。

Package 目標：`com.wusper.x86`（獨立於 `com.wusper`）。
