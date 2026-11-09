# smokeHot — 全自動熱更（0.1.96-hot.4+）

Boss **零操作**日常流程：推 core＋manifest；裝置已連 Google 就會自動 poll → 熱更 → 煙測 → 上傳 Drive `x86-a-log` → 刪舊。

## 何時才要重裝 APK？

| 情況 | 要不要重裝 |
|--|--|
| 修 `wine-launch.sh` / 腳本 / 小 core 檔 | **不用** — 發 hot-core release 即可 |
| 提高 `minApkVersionCode` 超過裝置 vc | 要（或把 minApk 保持 ≤ 裝置） |
| Google Sign-In / OAuth client / 包名大改 | 要 |
| 熱更／prefs 遷移邏輯大改（如 configGen） | 建議裝一次新 smokeHot |
| 其餘日常 Path A 腳本煙測迭代 | **不用** |

## 官方 manifest

```
https://github.com/wusui1325-dotcom/x86-in-android/releases/download/hot-core-0.1.98.1/manifest.json
```

- schema=`1`
- 目前 coreVersion=`0.1.98+core.hot4`
- `minApkVersionCode`=`109701` → **hot.1 / hot.2 / hot.3** 都能套用（不必 109704）
- `wine-launch.sh` = 0.1.98（NOEXEC kept + `cmd //c` + `mmap32_parent=0`）

## 裝置行為（hot.4）

1. 預設 `autoLoop=true`、`pollIntervalSec=300`（5 分鐘；可 30–86400）
2. `configGen=4` 遷移：舊 hot.2/hot.3 已寫入的空 `manifestUrl` / `autoLoop=false` **會被強制改成**官方 URL + autoLoop=true（除非 prefs `autoLoopUserDisabled=true`）
3. `manifestUrl` 空白時，程式硬編碼回退官方 URL
4. 啟動即 `SmokeHotLoop.startAutoPoll`；已連 Google 則每輪：fetch → applyIfNewer → collect session.log → Drive 上傳 → prune
5. ⋮ → **啟用自動熱更**：寫入官方 URL + autoLoop + 立刻跑一輪
6. ⋮ → **跑一輪熱更煙測** / `adb … --ez hot_smoke_now true`：手動一輪

## 發新 core（不發 APK）

```bash
# 可選：附 stamp 檔證明有拉到新 core
printf 'core=…\n' > /tmp/hot-core-stamp.txt
CORE_EXTRA_FILES=/tmp/hot-core-stamp.txt \
  ./scripts/publish-hot-core.sh '0.1.98+core.1' hot-core-0.1.98.1
```

腳本會：算 sha256/size、寫 manifest、建／更新 GitHub release assets、印出 `manifestUrl`。

裝置已開 autoLoop 且 URL 指到**同一個 tag**時：若你用**新 tag**，要嘛改裝置 URL（或發新 APK bake），要嘛覆寫同一穩定 tag 的 assets（`gh release upload --clobber`）。建議日常用新 tag + 把 `OFFICIAL_MANIFEST_URL`／defaults 留在「目前穩定 tag」；已開 auto 的裝置若 URL 已固定，可直接 **clobber 同一 tag** 並把 `coreVersion` 字串調高，下一輪 poll 就會套用。

## hot.2 / hot.3 現況

| 已裝 | wine-launch 098 | 自動熱更 |
|--|--|--|
| hot.3（109703） | APK 已 bake，**不用為 098 再裝** | 需裝一次 hot.4（遷移）或 ⋮啟用；之後零操作 |
| hot.2（109702） | 仍可能是 096/097（缺 NOEXEC kept） | **裝一次 hot.4**（遷移＋之後可熱拉）；或 adb 寫 `manifestUrl`＋`autoLoop` 後拉 `0.1.98+core.hot4`（minApk=109701）**不必**為 core 再裝 |

## Prefs

`SharedPreferences` 名：`x86_hot_smoke`

重要鍵：`manifestUrl`, `autoLoop`, `autoLoopUserDisabled`, `configGen`, `pollIntervalSec`, `driveFolderId`, `driveAccessToken`, `driveAccountEmail`
