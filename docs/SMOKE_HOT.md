# smokeHot channel — core hot-update + smoke log loop (MVP)

> **全自動／零操作**：見 [`SMOKE_HOT_AUTO.md`](./SMOKE_HOT_AUTO.md)（0.1.96-hot.4+：`autoLoop`、官方 manifest、configGen 遷移）。

Parallel to Path A (`mainline`). **Does not bump** `version.json` 0.1.96 / vc97.

## Versions
| | value |
|--|--|
| versionName | `0.1.96-hot.4` |
| versionCode | `109704` (= `100000 + 97*100 + 4`) |
| applicationId | `com.wusper.x86.smokehot` |
| flavor | `smokeHot` |

Build: `./scripts/release-smoke-hot.sh` → `assembleSmokeHotRelease`.

## Manifest
See `hot/manifest.example.json`. Schema `1`. Device polls `manifestUrl` (prefs `x86_hot_smoke`).

## Upload → Drive `x86-a-log`
- Folder name: **`x86-a-log`**
- Folder id (default): `1n2ZPm-GOQV66ikxfnPh6XRLbio5PE6fz`
- Filename: `smoke-{version}-{yyyyMMdd-HHmmss}-{device}.log`
- Mode `drive`: set `driveAccessToken` (Bearer) in prefs; after **successful** upload, trash older `smoke-*.log` / `*.log` in that folder (keep new).
- Mode `https`: PUT/POST to `uploadUrl`; relay should land in `x86-a-log` and prune. See `scripts/hot-smoke-drive-relay.py`.

### Configure on device
```text
SharedPreferences name: x86_hot_smoke
keys: manifestUrl, uploadMode, driveFolderId, driveAccessToken,
      uploadUrl, uploadMethod, uploadHeadersJson, autoLoop, pollIntervalSec,
      smokeWorkspaceId, smokeWaitSeconds
```
ADB example:
```bash
adb shell run-as com.wusper.x86.smokehot \
  cat /data/data/com.wusper.x86.smokehot/shared_prefs/x86_hot_smoke.xml
# Or write via a tiny helper / debug UI later. MVP: edit defaults JSON before bake,
# or use `adb shell` + file push into shared_prefs after first launch.
```

## Trigger first round
1. Install smokeHot APK (side-by-side with mainline).
2. Launch a workspace once so `session.log` exists (or hot-only round will say no log).
3. ⋮ → **跑一輪熱更煙測**
4. Or: `adb shell am start -n com.wusper.x86.smokehot/.MainActivity --ez hot_smoke_now true`
5. Auto loop (hot.4+): default `autoLoop=true` + official `manifestUrl` (poll every `pollIntervalSec`, default 300).
   ⋮ → **啟用自動熱更** forces official URL + one round. See `SMOKE_HOT_AUTO.md`.

## Rollback
Hot apply sha/HTTP failure → restore `engine/hot/previous` → `current`. APK assets remain ultimate fallback.

## Path A
`mainline` flavor unchanged. Tip branch only; do not FF this as mainline bump.
