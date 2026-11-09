# smokeHot — Google Drive auth (0.1.96-hot.2+)

Boss must **not** paste an OAuth access token. Use in-app **「連 Google」** or HTTPS relay.

## Preferred: in-app Google Sign-In

1. Open [Google Cloud Console](https://console.cloud.google.com/) → APIs & Services.
2. Create/select a project; enable **Google Drive API**.
3. Credentials → **Create credentials → OAuth client ID**:
   - Application type: **Android**
   - Package name: `com.wusper.x86.smokehot`
   - SHA-1: `41:AE:CC:47:82:33:A8:B0:AF:98:3E:22:5C:B3:6B:3A:C6:DA:24:F3`
     (release keystore `wusper-x86` / `wusper-x86-release.keystore`)
4. (Optional) Also create a **Web** client; put its client id into prefs
   `x86_hot_smoke.driveOAuthClientId` or assets `hot/smoke-hot-defaults.json` →
   `driveOAuthClientId` (only needed for server-auth code; basic Drive scope works
   with the Android client alone).
5. Configure OAuth consent screen (External/Internal); add test users if app is in Testing.
6. Install smokeHot APK → ⋮ → **連 Google** → pick account → grant Drive file access.
7. ⋮ → **跑一輪熱更煙測**. Upload lands in Drive folder **x86-a-log**
   (`1n2ZPm-GOQV66ikxfnPh6XRLbio5PE6fz`); older `smoke-*.log` trashed after success.

Scope used: `https://www.googleapis.com/auth/drive.file` (app-created files only;
prune of this app’s prior smoke logs works).

If Sign-In fails with **code 10 / DEVELOPER_ERROR**, the Android OAuth client SHA-1
or package name is wrong — recheck step 3.

## Fallback: HTTPS relay

```bash
export GOOGLE_ACCESS_TOKEN=ya29....   # or GOOGLE_SERVICE_ACCOUNT_JSON=/path/sa.json
export DRIVE_FOLDER_ID=1n2ZPm-GOQV66ikxfnPh6XRLbio5PE6fz
python3 scripts/hot-smoke-drive-relay.py --port 8787
```

On device: ⋮ → **改用 HTTPS 上傳** → set
`http://<reachable-host>:8787/upload/%s`.

For service accounts: share folder `x86-a-log` with the SA email (Editor), then
`pip install google-auth`.

## UX

- Smoke with no Google login → dialog **連 Google** / **改用 HTTPS 上傳** (not a dead-end toast).
- Menu shows connected email; tap again to sign out.
