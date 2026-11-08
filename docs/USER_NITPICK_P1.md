# 用戶找茬 P1 驗收（0.1.24）

基底：P0 `fix/user-nitpick-p0`（0.1.23）＋ main glibc box64（0.1.22）。**不**改 wine-launch box64 多路徑／FATAL。

## 已關閉

| # | 項目 | 驗收 |
|---|---|---|
| 7 | Box64 解壓後校驗 | extract 後 `isGlibcBox64`；安裝收尾再 assert；失敗繁中＋「重裝運行時」 |
| 8 | 人話錯誤 | LaunchPipeline／ComponentInstaller 用戶可見字串去工程路徑；導向重裝 |
| 9 | gaps 只列阻斷 | 僅 Turnip／DXVK／Present；不再永遠夾 Vortek／dinput |
| 10 | 無 KGSL 警告 | Session 啟動前 AlertDialog「此裝置可能無畫面」可繼續／取消 |
| 11 | 1280×720 標註 | 首頁 subtitle、Session 頂欄／狀態列、About |
| 16 | 儲存預檢 | 確認下載後 `assertEnoughSpace`；不足錯誤卡 |
| 18 | 版本＋SHA | 頂欄 versionName／Code／GIT_SHA；選單「關於」 |
| 19 | runtime 軟說明 | 重裝對話框註明依目前 App 拉最新；不支援釘選（docs） |
| 21 | POST_NOTIFICATIONS | MainActivity 首次 LaunchedEffect 請求；Session 啟動 FGS 前再 ensure |
| 23 | COMPAT.md | `docs/COMPAT.md` Adreno／失敗模式／Issues |
| 24–26 | 回歸文案 | ExitStatusMessages／presentFailure 指「重裝運行時」非 adb |
| 27 | 備份排除 engine | `backup_rules`＋`data_extraction_rules` 排除 `files/engine` |
| 28 | abi／minSdk | README、首頁、About |

## Smoke

1. 裝 0.1.24；首頁見「僅 arm64-v8a／Android 9+」「1280×720」；版本列含短 SHA。
2. 選單「關於」顯示 versionName、versionCode、tip。
3. API 33+ 首次開 App 跳出通知權限（可拒）。
4. 清資料後選 .exe → 下載確認 →（可人為塞滿儲存）應「儲存空間不足」錯誤卡。
5. 無 `/dev/kgsl-3d0` 裝置／模擬：GpuStack 啟動前出「可能無畫面」→ 取消則錯誤卡；繼續可往下走。
6. 人為弄壞 box64 後啟動 → 繁中校驗失敗＋重裝運行時（勿見 `/usr/local/bin` 當唯一說明）。
7. gaps／GpuPending 文案不含 Vortek／dinput 必裝語氣。
8. 結束碼含權限／linker／sh → 錯誤卡建議重裝運行時。
9. 備份規則：換機還原不應帶回半殘 `files/engine`（prefs／工作區策略見 xml）。

## 刻意未做／剩餘

| 項 | 說明 |
|---|---|
| 完整解析度選擇器 | 僅標註固定 1280×720 |
| 多 runtime 釘選／回退 | 僅文案＋COMPAT；stamp／runtimeId 行為不變 |
| #1 box64 路徑 | 0.1.22 已關；勿覆寫 |
| 真機 farm 驗證 | 本批以編譯＋靜態驗收為主 |
