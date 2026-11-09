## 0.1.62 — real PE launch (no start.exe) + forced +x11drv

**Smoke (0.1.61):** RpcSs ready=1 alive+ole-probe but `start.exe /unix /mnt/app/app.exe`
→ ShellExecuteEx failed. wine-run froze after actxprxy; WINEDEBUG stayed err+all,+seh,+loaddll.

**Fix:**
- Seed `/mnt/app/app.exe` → `C:\App\app.exe`; launch `explorer /desktop=… C:\App\app.exe` (unix fallback). No start.exe.
- Force WINEDEBUG=…,+ole,+rpcss,+x11drv at WINE_RUN and WINE_START
- xwininfo -root -tree best-effort after start
- RpcSs: proc_alive authoritative; re-check after OLE probe

**Expect:** WINE_START app_map mode=seed-copy → Loaded …App\app.exe — no ShellExecuteEx — Present or clear +x11drv/+ole signature.

## 0.1.61 — reject false RpcSs ready (alive + OLE/sc4)

**Smoke (0.1.60):** `ready=1 reason=sc-query-running` while `rpcss_pid alive=0`.
Relaunch storm; sc 1056; AUTO-start 1053; wine-run froze after actxprxy; black Present.

**Fix:**
- Ready requires **proc_alive=1** AND (`sc STATE 4 RUNNING` OR OLE `regsvr32` probe clean)
- Reject START_PENDING / dead-pid false ready (`false-ready-dead`)
- Max **2** rpcss launches; 1056 = already-running OK; no per-second relaunch
- RpcSs/MountMgr **DEMAND_START** (no AUTO 1053 race); MountMgr only after true ready
- WINE_START: explorer desktop first, then `start.exe /unix` app; `WINEDEBUG=err+ole,err+rpcss` (heavier if not ready)

**Expect:** `RPCSS_PRESTART done ready=1 … proc_alive=1` (not alive=0); no relaunch storm; Present or next OLE/x11drv signature.

## 0.1.60 — RpcSs SCM-ready + soft-continue UX + log scroll

**Smoke (0.1.59 full):** soft-continue OK, WINE_START alive, `app_seen=1`, but **no Present**.
explorer hit `RPC_S_SERVER_UNAVAILABLE (0x6ba)` after actxprxy; wineboot
`start_rpcss Failed to open RpcSs service`. Direct `rpcss.exe` `alive=1` was **not**
enough — OLE `OpenService(RpcSs)` fails and gives up without talking to that pid.

**Fix:**
- `services.exe` (SCM) up first; `net`/`sc start RpcSs` (TERM-only, **no KILL**);
  direct rpcss belt+suspenders; **probe** (`sc query` RUNNING / `regsvr32 actxprxy`)
  up to ~30s before WINE_START
- MountMgr fire-and-forget after probe (no wait/KILL)
- RpcSs service Start=AUTO in offline inject
- soft-continue `>=50KB` OR `>=30KB`+boot markers, stable ~15s
- UI:「正在初始化 Wine… / RpcSs…」not Present wait during boot
- Session log: LazyColumn scroll fix (weight outside SelectionContainer)

## 0.1.59 — valid Wine Version 2 regs (no UTF-8 stub pre-create)

**Bug (0.1.58 smoke):** After heal+wipe, wineboot still failed with
`system.reg is not a valid registry file` (bytes=61) and
`32-bit installation cannot support 64-bit applications`.

**Root cause:** Offline heal **created** stub regs with wrong header
`WINE REGISTRY File` (must be **`WINE REGISTRY Version 2`**). Wine 11
(`server/registry.c`) rejects them, then defaults `prefix_type` to
**PREFIX_32BIT**. (Wine 11 writes UTF-8 text via fprintf — not Windows
UTF-16LE `.reg`.)

**Fix:**
- **Never** pre-create system.reg/user.reg/userdef.reg before wineboot.
- Detect invalid (wrong header / UTF-16 BOM Wine can't fgets) → purge →
  `WINEARCH=win64` + mkdir syswow64 → wineboot writes real Version 2 regs.
- Then offline UTF-8 patch `#arch=win64` + RpcSs/MountMgr via `wine-reg-helper.py`.
- Auto-repair on `not a valid registry file` or `32-bit installation`.

**Expect KEY_LOG:** `WINE_LAUNCH_STAMP=0.1.59` →
`WINE_REG encoding=utf8 valid=yes` → `WINE_ARCH … #arch=win64` →
`RPCSS_REG ensure done … file_rpcss=yes` → `RPCSS_PRESTART alive=1` →
`WINE_START` — **no** `not a valid registry file` / `32-bit installation`.

## 0.1.58 — win64 prefix heal (32-bit installation refusal)

**Bug (0.1.57 smoke):** Offline RpcSs/MountMgr SUCCESS (`file_rpcss=yes`), GUI_SEED OK, but
every wine launch printed `wine: '/home/xuser/.wine' is a 32-bit installation, it cannot
support 64-bit applications.` → `RPCSS_PRESTART alive=0` → `WINE_EXIT=1`.

**Root cause:** Wine server (`registry.c`): if `system.reg` loads **without** `#arch=win64`,
`prefix_type` defaults to **PREFIX_32BIT**. Reused ~1.5MB prefix lacked the directive
(or had win32). x86_64-unix wine then refuses all 64-bit PE (rpcss/explorer/app).

**Fix:**
- `export WINEARCH=win64` on all wine paths.
- Offline heal: force `#arch=win64` in `system.reg` / `user.reg` / `userdef.reg`;
  `mkdir -p syswow64`. Never wine reg.
- Re-heal after RpcSs offline patch (wineserver -k).
- If wine still prints 32-bit message → `WINE_ARCH FATAL 32bit-prefix` + auto wipe+reinit
  once (app heals; Boss need not manually wipe unless `.arch-repair-done` already set).

**Expect KEY_LOG:** `WINE_LAUNCH_STAMP=0.1.58` → `WINEARCH=win64` →
`WINE_ARCH ensure done … #arch=win64` → `RPCSS_REG ensure done offline=yes` →
`RPCSS_PRESTART … alive=1` (no 32-bit installation line) → `WINE_START` → Present or next err.

## 0.1.57 — offline system.reg RpcSs/MountMgr (no wine reg)

**Bug (0.1.56 smoke):** Prefix complete (`system.reg≈1.5MB`) skipped wineboot; seed bins
OK; `RPCSS_REG precheck file_rpcss=no file_mountmgr=no` then **hung forever** on
in-Wine `reg.exe add` (chicken-egg: reg/COM needs RpcSs). Never reached
`RPCSS_REG ensure done` / `RPCSS_DIAG` / `RPCSS_PRESTART` / `WINE_START`. Secondary:
grep used 1 backslash so even present keys could read as missing; after X death
`start.exe` hit c0000135 missing win32u/user32.

**Fix:**
- **Offline** Python text-patch of `system.reg` (Wine registry file format) injecting
  `CurrentControlSet` + `ControlSet001` `Services\RpcSs` and `Services\MountMgr`
  with ImagePath/Type/Start/ErrorControl — **never** `wine reg` / `reg.exe`.
- `wineserver -k` before patch so next wine reloads file.
- Detection via `grep -F 'Services\\Name]'` (two backslashes).
- PRESTART: **direct `rpcss.exe` first**, then timed `winedevice MountMgr` / `net` / `sc`
  (hard timeouts; never hang).
- Always run ensure even when prefix complete; `GUI_SEED` re-copy win32u/user32/gdi32.

**Expect KEY_LOG:** `WINE_LAUNCH_STAMP=0.1.57` → `RPCSS_REG precheck` →
`RPCSS_REG.py: offline … now_rpc=True` → `RPCSS_REG ensure done offline=yes file_rpcss=yes` →
`RPCSS_DIAG … reg_rpcss=yes` → `RPCSS_PRESTART … alive=1` → `WINE_START` → Present or
next clear err (not hang after precheck).

## 0.1.56 — wineboot soft-continue + RpcSs/MountMgr service reg

**Bug (0.1.55 smoke):** Soft-kill fixed — `system.reg` grew ~51KB→~850KB over ~75s with
`winex11.drv` loaded and seed `rpcss=yes mountmgr=yes`. But **wineboot --init never
natural-exits** (stuck waiting after RpcSs `Failed to open RpcSs service` during early
init + `MountMgr failed to start: 2`). UI stayed「等待客體畫面」with pid alive — hang
was wineboot, not Present. Timeout was 180s; Boss exported ~75s in.

**Root cause:** (1) During fresh `wineboot --init`, ole32 `start_rpcss` runs **before**
SCM has RpcSs keys in the live DB (`system.reg` still 0B) → OpenService fails; wineboot
later writes a full reg but never exits. (2) MountMgr err 2: kernel driver start needs
**winedevice.exe** on disk (host for `drivers\mountmgr.sys`); 0.1.54 seeded mountmgr.sys
but not winedevice.

**Fix:**
- Soft-continue when `system.reg >=100KB` **and** size stable 20s **and** `.update-timestamp`
  exists → TERM wineboot, **keep wineserver**, proceed (NOT the 0.1.54 5s early kill).
- Seed `winedevice.exe` + `ntoskrnl.exe`.
- `ensure_rpcss_mountmgr_service_reg` (`reg add` RpcSs + MountMgr ImagePath/Type/Start)
  before `RPCSS_DIAG` / `RPCSS_PRESTART`.
- PRESTART: best-effort `winedevice MountMgr`, `net start`, `sc start`, then direct `rpcss.exe`.

**Expect KEY_LOG:** `WINE_LAUNCH_STAMP=0.1.56` → `rpcss=yes mountmgr=yes` →
`WINEBOOT_INIT soft-continue … system.reg=` (large) `stable=` → `RPCSS_REG ensure done` →
`RPCSS_DIAG … reg_rpcss=yes` → `RPCSS_PRESTART … alive=1` → `WINE_START` → Present or
next clear err (not endless「等待客體畫面」on wineboot hang).

## 0.1.55 — wineboot soft-kill / wipe regression (exit=143)

**Bug (0.1.54 smoke):** Seed rpcss/mountmgr OK, but `prefix_is_complete` required RpcSs
reg keys → marked usable 1.6MB 0.1.53-era prefix as `stub/half-init` → **WINEBOOT_WIPE**.
Then `run_wineboot_init` treated `.update-timestamp`+kernel32 as soft-pass markers and
**SIGTERM'd wineboot after 5s** (`exit=143`) before `system.reg` was written → FATAL.
GIT_SHA in About lagged tip (`c2c79ea` while tip claimed `9747390`).

**Fix:** Never soft-kill `wineboot --init` on markers — wait for natural exit or real
timeout; exit 143 without usable `system.reg` is FAIL. Wipe only true stubs (`system.reg`
<20KB); **reuse** large prefixes (`>=100KB`) with seed bins + `wineboot -u` +
`RPCSS_PRESTART` (no mandatory wipe for missing RpcSs keys). Soft-ok proceed when
`system.reg` usable even if complete=no. Commit tip **before** assembleRelease so
About/GIT_SHA matches tip.

**Expect KEY_LOG:** `WINE_LAUNCH_STAMP=0.1.55` → no `force wipe … 1680940B` →
`reuse large` **or** `WINEBOOT_INIT … no soft-kill` with `system.reg` surviving →
`RPCSS_DIAG` / `RPCSS_PRESTART` → `WINE_START` → Present or next err (not exit=143 FATAL).

## 0.1.54 — RpcSs / MountMgr (Failed to open RpcSs + MountMgr err 2)

**Bug (0.1.53 smoke):** winex11 loads (`DRIVER_DIAG` all yes, no nodrv); explorer hangs on
`err:ole:start_rpcss` / **Failed to open RpcSs service** for 120s+; services.exe
`Auto-start service L"MountMgr" failed to start: 2` (`GetBinaryTypeW` on
`drivers\mountmgr.sys`). Root cause: 0.1.52 seed-copy + skip-wineboot left a bind-era
`system.reg` (~1.6MB) over a real system32 with only ~36 seeded DLLs — **no**
`rpcss.exe` / `drivers/mountmgr.sys`.

**Fix:** Seed service PE (`rpcss.exe`, `svchost`, `plugplay`, …) + copy `*.sys` into
`system32/drivers` (esp. `mountmgr.sys`); `prefix_is_complete` requires those bins +
RpcSs in `system.reg`; `RPCSS_DIAG` + `RPCSS_PRESTART` (`net start RpcSs` then
`rpcss.exe`); default `WINEDEBUG` drops `+module` (keep `err+all,+seh,+loaddll`).

**Expect KEY_LOG:** `WINE_LAUNCH_STAMP=0.1.54` → `rpcss=yes mountmgr=yes` →
`RPCSS_DIAG … reg_rpcss=yes` → `RPCSS_PRESTART … alive=1` → no endless
`Failed to open RpcSs` → `app.exe` loaddll / Present / next clear err.

## 0.1.51 — WINE_START hang + full log export + speed

**Bug (0.1.50 smoke):** Soft-pass OK; `WINEBOOT_INIT skip (system.reg present)` then stuck at
`WINE_START` with status「執行中」and black「等待客體畫面（Present）…」. Root causes:
(1) `WINEDEBUG=err+all,fix+all` + `BOX64_LOG=1` flood made first explorer glacial;
(2) wineboot skip only checked `system.reg` (stub from noexec era);
(3) no heartbeat after START so Session looked frozen.

**Fix:** Light `WINE_RUN` debug (`err+all,+seh,+loaddll`, `BOX64_LOG=0`); `prefix_is_complete`
(system.reg size, user.reg, kernel32, dll count, `.update-timestamp`); force wineboot if
incomplete; smoke wineserver cleanup; `WINE_HB` / `DISPLAY_DIAG` heartbeats; Session
「匯出完整 log」FileProvider share (session + gpu-stack + wine-run + smoke outs).
Speed: parallel `extractTarXz` writes (nproc 2–8), 1MiB buffers; `parallel_dir_copy` for
PREFIX_EXEC; holefit `cached+binary` skip probe. Keep NTDLL_ANON_PE + soft-pass.

**Expect KEY_LOG:** `WINE_LAUNCH_STAMP=0.1.51` → `NTDLL_ANON_PE ok` → `HOLEFIT ok (cached+binary)`
or `HOLEFIT ok` → soft-pass → `WINEBOOT_INIT` skip-or-force → `DISPLAY_DIAG` → `WINE_START pid=`
→ `WINE_HB t=10s/30s/60s` → Present or next unmuted err. UI:「匯出完整 log」next to 複製 log.

## 0.1.50 — WINE_PE_SMOKE soft-continue (live wineboot ≠ FATAL hang)

**Bug (0.1.49 smoke):** PE finally runs (`NTDLL_ANON_PE ok`, no `map_image … noexec`);
`wineboot --init` reaches `services.exe` / `rundll32 setupapi` / `explorer.exe /desktop`,
but `WINE_PE_SMOKE` 45s timeout still `FATAL: WINE_PE_SMOKE HANG (exit 124)` and aborts
before `WINE_RUN`. Root cause: hang gate treated any timeout as hard fail, ignoring boot
markers.

**Fix:** Default smoke timeout 90s; early soft-pass when `system.reg` / services|explorer
markers appear; on timeout soft-continue if `PE_BOOT_OK` or mid+inf known. Kill smoke
prefix cleanly and continue to `WINE_RUN` / `WINE_START`. Keep NTDLL_ANON_PE + pe_ok FATAL.
Note missing `libxkbregistry.so.0` (non-fatal).

**Expect KEY_LOG:** `WINE_LAUNCH_STAMP=0.1.50` → `NTDLL_ANON_PE ok` → `PREFIX_EXEC ok … pe_ok=yes`
→ `HOLEFIT ok` → `WINE_PE_SMOKE progress soft-pass` **or** `hang soft-continue` → `WINE_RUN` /
`WINE_START` → **no** `FATAL: WINE_PE_SMOKE HANG` when boot progressed → no `map_image … noexec`.

## 0.1.49 — NTDLL_ANON_PE (Wine W^X / false pe_ok green)

**Bug (0.1.48 smoke):** `pe_ok=yes` / `PREFIX_EXEC ok` / HOLEFIT ok, but Wine still
`map_image_into_view … ntdll.dll … noexec filesystem?` then stack overflow on mid.
Root cause: `probe_pe_exec` does file `mmap(PROT_READ|PROT_EXEC)` (allowed). Stock Wine
maps PE **RW then mprotect→RX** on file-backed pages; Android W^X/execmod denies that.
Bind/scratch cannot fix mount-flag inheritance under PRoot for this path.

**Fix:** Binary-patch Kron4ek `x86_64-unix/ntdll.so` `virtual_map_image` @ `0x7a7a8` to
force `removable=1` and jump to the existing **pread→anonymous** path (GameNative
`force_anon` equivalent). Then `mprotect(PROT_EXEC)` on anon pages works (same as Box64
dynarec). Log `NTDLL_ANON_PE ok`; add `wine_wx` diagnostic (expected `no` on filesDir).
Keep plain-dir scratch + `pe_ok` FATAL.

**Expect KEY_LOG:** `WINE_LAUNCH_STAMP=0.1.49` → `NTDLL_ANON_PE ok` → `PREFIX_EXEC ok … pe_ok=yes`
→ `HOLEFIT ok addr≥0x1200000` → **no** `map_image … noexec` → `WINE_EXIT=0` (or next unmuted err).

## 0.1.48 — PREFIX_EXEC plain rootfs scratch (no guest tmpfs)

**Bug (0.1.47 smoke):** `tmpfs mount claimed` on `.wine/.pe-exec` / `.wine-pe-exec` / `/tmp/.wine-pe-exec`
then immediate `ls: cannot access … No such file` — PRoot guest `mount -t tmpfs` returns 0 but
the mountpoint **vanishes** (ENOENT). More path candidates cannot fix a broken mount primitive.

**Fix:** Scratch = **plain directory** under rootfs (`/opt/wine-pe-exec`, then `/var/tmp/…`,
`/home/xuser/…`) — same FS as pe_ok `/opt/wine`. No guest `mount -t tmpfs` except last-resort
with immediate verify. Copy PE tree → `mount --bind` over system32 / x86_64-windows. Keep
0.1.47 pe_ok FATAL gate + mid≥`0x1200000`.

**Expect KEY_LOG:** `WINE_LAUNCH_STAMP=0.1.48` → `PREFIX_EXEC scratch ok mode=plain-dir path=/opt/wine-pe-exec`
→ `PREFIX_EXEC bind system32 ok` → `post-bind probe pe_ok=yes` → `PREFIX_EXEC ok mode=scratch+bind… pe_ok=yes`.

## 0.1.47 — PREFIX_EXEC pe_ok required (no false green) + scratch re-verify

**Bug (0.1.46 dig):** `PREFIX_EXEC ok mode=tmpfs+bind … pe_ok=no` still returned success —
Wine could later `map_image_into_view … ntdll.dll … noexec` while log looked green.
Also nested tmpfs under `$WINEPREFIX/.pe-exec` needed post-bind sticky re-verify;
About/GIT_SHA lagged tip when APK was built before commit.

**Fix:**
- After tmpfs+bind: require `probe_pe_exec` (mmap PROT_EXEC) on `$pe_ntdll` **and**
  sys32 ntdll when present; `pe_ok=no` → **FATAL** (never print PREFIX_EXEC ok).
- Stale "already mounted" pe/sys32 binds: re-probe; umount+rebind if noexec.
- Post-bind: `scratch_verify` again + `mountgrep[post-bind]`.
- Release order: **commit tip first, then assembleRelease** so About GIT_SHA matches tip.
- Keep `$WINEPREFIX/.pe-exec` durable scratch + hole-fit mid≥`0x1200000`.
- stamp `0.1.47`; `runtimeId` → `…v0.1.47…peok`.

**Retest:** Cover-install **0.1.47** → `WINE_LAUNCH_STAMP=0.1.47` →
`PREFIX_EXEC ok … pe_ok=yes` (or `FATAL: … pe_ok=no … refusing false green`) →
`PREFIX_EXEC verify ok` after bind → `HOLEFIT addr≥0x1200000` → no noexec / `WINE_EXIT=0`.

## 0.1.46 — PREFIX_EXEC durable scratch (PRoot /tmp flake)

**Bug (0.1.45):** `PREFIX_EXEC tmpfs scratch ok … path=/tmp/.wine-pe-exec` then
`FATAL: cannot mkdir /tmp/.wine-pe-exec/x86_64-windows` and `ls: cannot access … No such file`.
Mount claimed OK on ephemeral `/tmp` but path vanished under PRoot.

**Fix:** Prefer scratch `$WINEPREFIX/.pe-exec` (then `/home/xuser/.wine-pe-exec`, `/tmp` last).
After every mount: verify dir exists + writable + in `/proc/mounts` + mkdir probe; log `ls -ld` / `mountgrep`.
ok_marker moved to `$WINEPREFIX/.prefix-exec-ok`. Keep sys32 tmpfs+bind + mid>=0x1200000.

**Retest:** Cover-install **0.1.46** → `WINE_LAUNCH_STAMP=0.1.46` →
`PREFIX_EXEC tmpfs scratch ok … path=…/.pe-exec` (not only `/tmp`) →
`PREFIX_EXEC ok mode=tmpfs+bind…` / `tmpfs+bind-preseed` → no vanish FATAL.

## 0.1.45 — PREFIX_EXEC scratch mkdir EEXIST fix

**Symptom (0.1.44):** `WINE_LAUNCH_STAMP=0.1.44`, then
`PREFIX_EXEC tmpfs scratch ok … path=/tmp/.wine-pe-exec` →
`PREFIX_EXEC copying … → tmpfs` → `mkdir: cannot create directory '/tmp/.wine-pe-exec': File exists`
and script aborted (`set -e`) before hole-fit. Cause: toybox/busybox `mkdir -p` on a
tmpfs **mountpoint** parent returns EEXIST.

**Fix:**
- `ensure_dir` / `prepare_scratch`: never fatal on existing dir/mountpoint; reuse mounted
  scratch; leaf `mkdir` after `cd` into parent; `rm -rf` + recreate only if path is not a dir.
- Keep 0.1.44: always tmpfs+bind `$WINEPREFIX/.../system32`; STACK_HEAD mid `≥0x1200000`.
- stamp `0.1.45`; `runtimeId` → `…v0.1.45…sys32`.

**Retest:** Cover-install **0.1.45** → `WINE_LAUNCH_STAMP=0.1.45` →
`PREFIX_EXEC ok mode=tmpfs+bind-sys32`（或 preseed）**without** `File exists` →
`HOLEFIT ok addr≥0x1200000` → `WINE_EXIT=0` 或下一 unmuted err.

## 0.1.44 — system32 PREFIX_EXEC + STACK_HEAD mid + 重裝運行時可見

**Symptom (0.1.43):** `PREFIX_EXEC ok mode=probe` on `/opt/wine/.../ntdll.dll`, then
`map_image_into_view … C:\windows\system32\ntdll.dll … noexec`; HOLEFIT `0x1000000` →
stack overflow at `0x1000ec0` (stack followed mid base).

**Fix:**
- PREFIX_EXEC: never early-ok on `/opt/wine` alone; per-prefix marker; **always**
  tmpfs+bind active `$WINEPREFIX/drive_c/windows/system32` before wineboot.
- hole-fit: hole base `B` → mid `B+0x200000` (STACK_HEAD 2MiB); reject mid `<0x1200000`;
  prefer high bases; clear sticky `0x1000000` markers.
- UI: 首頁芯片＋⋮、Session 頂欄 ⋮、錯誤卡 皆有「重裝運行時」（未刪，只是 Session 先前只在錯誤卡）。

- `runtimeId` → `…v0.1.44…sys32`; stamp `0.1.44`.

**Retest:** Cover-install **0.1.44** → `WINE_LAUNCH_STAMP=0.1.44` →
`PREFIX_EXEC ok mode=tmpfs+bind-sys32`（或 preseed）→ `HOLEFIT ok addr≥0x1200000` →
無 `noexec`／`0x1000ec0` → `WINE_EXIT=0` 或下一 unmuted err。
首頁應直接看到「重裝運行時」芯片；Session 右上 ⋮ 也有。

## 0.1.43 — PREFIX_EXEC real fix (noexec ntdll / false remount green)

**Symptom (0.1.42 dig):** Hole-fit OK (`addr≠0x800000`), but still
`map_image_into_view … ntdll.dll … noexec` → `WINE_EXIT=1`. Log showed
`PREFIX_EXEC remount … ok` then noexec — remount return code is a **false green**
under PRoot+filesDir. system32-only tmpfs also misses the real PE path
`/opt/wine/lib/wine/x86_64-windows/ntdll.dll`.

**Fix:**
- Remount logged as `claimed (not trusted alone)`; success only after
  `python3` `mmap(PROT_READ|PROT_EXEC)` probe on ntdll.dll.
- On probe fail: tmpfs (768M/512M) + copy `x86_64-windows` + bind over
  `/opt/wine/lib/wine/x86_64-windows` (and system32). Hard **FATAL** if still fail.
- Session `KEY_LOG_PATTERNS`: `WINE_LAUNCH_STAMP` / `HOLEFIT` / `PREFIX_EXEC` /
  `noexec` / `map_image` / `stack overflow` / `WINEBOOT_INIT`.
- `runtimeId` → `…v0.1.43…peexec`; stamp `0.1.43`.

**Retest:** Cover-install **0.1.43** → `WINE_LAUNCH_STAMP=0.1.43` →
`PREFIX_EXEC ok mode=tmpfs+bind` (or `mode=probe`) → `HOLEFIT ok addr≠0x800000` →
no `noexec` / no `0x800ec0` stack overflow → `WINE_EXIT=0` (or next unmuted err).
Note: 0.1.42 still OK to smoke for hole-fit only; **0.1.43 is the noexec real fix**.

## 0.1.42 — mid overlaps Wine stack + noexec ntdll

**Symptom (0.1.41 Boss):** `WINE_LAUNCH_STAMP=0.1.41`, `HOLEFIT ok (cached) 0x800000:0x10000000`,
DOS+mid+hi `done`, `boot_ok=1` / `system.reg` exists, but:
```
err:virtual:map_image_into_view … ntdll.dll … noexec filesystem?
err:virtual:virtual_setup_exception stack overflow … stack 0x800ec0 (0x800000-0x801000-0xa00000)
```
→ `WINE_EXIT=1`.

**Root cause:**
1. Mid prereserve `0x800000:0x10000000` **covers Wine default TEB/stack** `[0x800000,0xa00000)`.
2. Android/proot `filesDir` wineprefix is often **MS_NOEXEC** → `mprotect(PROT_EXEC)` on PE `.text` fails.

**Fix:**
- Hole-fit candidates start at `≥0x1000000`; drop `0x800000`; reject any mid overlapping stack;
  clear sticky marker that overlaps / stamp≠0.1.42.
- `ensure_prefix_exec`: remount,exec on prefix/drive_c/opt/wine; else tmpfs+bind over system32.
- Stamp `WINE_LAUNCH_STAMP=0.1.42`.

**Retest:** Cover-install **0.1.42** → stamp=0.1.42 → `HOLEFIT ok addr≠0x800000` →
no stack overflow at `0x800ec0` → ntdll map ok / `WINE_EXIT=0` (or next unmuted err).

## 0.1.41 — wineboot/--version exit 1 after hole-fit (muted err)

**Symptom (0.1.40 Boss):** `HOLEFIT ok 0x800000:0x10000000`, DOS+mid+hi `done`, then
`WINE_PE_SMOKE exit=1 (no version line)` and `WINE_START` → `WINE_EXIT=1`. No 134.

**Root cause (diagnostic):**
1. Wine11 unix stub has **no `--version` short-circuit** → empty prefix always runs
   `wineboot.exe --init`; exit 1 after ntdll load was opaque because
   `WINEDEBUG=-all` (Kotlin) suppressed all `err:` / `wine:` channels.
2. PE smoke only `head -60` of BOX64 chatter; real `explorer` stdout/stderr not saved.
3. Real prefix may never have gotten a successful `wineboot --init` (`system.reg`).

**Fix:**
- PE smoke + real run: `WINEDEBUG=err+all,fix+all` + `BOX64_LOG=1`
- Dump **key lines + tail** of `wine-version.out`; detect `system.reg` / `drive_c` as boot_ok
- Explicit `WINEBOOT_INIT` before explorer if no `system.reg`; capture `wine-run.out`

**Retest:** Cover-install **0.1.41** → `WINE_LAUNCH_STAMP=0.1.41` → `HOLEFIT ok` →
`WINEBOOT_INIT` key lines (or skip) → `WINE_EXIT=0` / Present. If still exit 1, paste
`WINEBOOT_INIT` / `wine-run` **key lines** (now unmuted).

## 0.1.39 — hole-fit endless scan / 100000-1 occupies low page

**Symptom (0.1.38 Boss):** 「感覺不太對」. HOLEFIT tried shrinking sizes at `0x100000`
(`is not free`), then mid at `0x800000:0x10000000` **did** print `done`, but the loop
kept scanning (`0x800000` smaller sizes → `0x1000000` → `0x10000000`…) and hung.
Also always `WINE prereserve of 0x100000:0x1 done` because `WINEPRELOADRESERVE=100000-1`.

**Root cause:**
1. `WINEPRELOADRESERVE=100000-1` occupies Wine's critical low page during probe and
   confuses success detection.
2. Probe did not reliably stop after a useful mid `done` (race / tiny `0x1` reserve).

**Fix:**
- `unset WINEPRELOADRESERVE` for probe and real run (no more `100000-1`)
- Parse probe output after kill; success = mid addr∉{DOS,hi} with size>0x1000
- **Stop on first success**, persist `.box64-wine64k-holefit` immediately
- Prefer largest size first (`0x10000000` …); Boss's first free hole was `0x800000:0x10000000`

**Retest:** Cover-install 0.1.39 → expect `HOLEFIT ok addr=… size=…` (often `0x800000:0x10000000`),
three prereserve lines (DOS + mid + hi), **no** endless `HOLEFIT try`, then `WINE_START` / frame.

## 0.1.38 — mid prereserve silently dropped (hole-fit)

**Symptom (0.1.37 Boss):** stamp `wine64k-align-0x100000` installed, hex table in asset is
`{0x100000,0x30000000}`, but runtime only prints:
`prereserve 0x10000:0x10000` + `0x7f000000:0x3000000`. **Missing mid.**
`WINE_PE_SMOKE exit=1`, `WINE_EXIT=1`.

**Root cause:** Box64 `wine_prereserve()` calls `isBlockFree(0x100000, 0x30000000)`.
Under Android 39-bit VA / proot, that 768MiB hole is often **not free**. On failure it
`remove_prereserve()` **without logging** when `WINEPRELOADRESERVE` is unset
(`if(addr>=0x10000)` uses the env-parsed addr, which stays NULL).

**Fix:**
- Export `WINEPRELOADRESERVE=100000-1` so "is not free" warnings appear
- Runtime **hole-fit**: probe/patch `my_wine_reserve` mid addr/size in `/usr/local/bin/box64`
  until `WINE prereserve of <addr>:<size> done` appears; persist `.box64-wine64k-holefit`
- Do not export empty `WINEPRELOADRESERVE=` from Kotlin

**Retest:** Cover-install 0.1.38 → expect `HOLEFIT ok addr=… size=…`,
`WINE prereserve of <addr>:<size> done`, then `WINE_START` / frame.

## 0.1.37 — wineboot LdrInitializeThunk SIGSEGV + log-only Session

**Symptom (0.1.36):** Boss「好像成了／沒畫面／全熒幕都是 log」。`DYNAREC=1`、
`prereserve 0x10000:0x10000`／`0x7f000000` OK，但 `wine --version` 對空 prefix 觸發
`wineboot.exe --init`，隨後 **SIGSEGV 迴圈**：`ntdll.dll!LdrInitializeThunk` →
`KiUserExceptionDispatcher`（Emit Signal 11），Session 被 BOX64_LOG=1 刷屏、Surface 無幀。

**Root cause:**
1. Box64 預設低位預留 `0x110000:0x30000000`：**位址非 Wine11 64KiB 對齊**
   （`addr & 0xffff ≠ 0`）。mmap 失敗時被丟棄 → 只剩 DOS+hi 兩段 → PE loader 崩。
2. Wine11 unix `wine` stub **沒有 `--version` 短路**，空 WINEPREFIX 必走 wineboot。
3. 預設 `BOX64_LOG=1`／SHOWSEGV 讓 Session 日誌區淹沒 Surface。

**Fix:**
- Box64 wine64k：`0x110000` → **`0x100000`**（+既有 DOS `0x10000:0x10000`）；stamp
  `wine64k-align-0x100000-2026-11-09`；缺 stamp 強制重抽
- Smoke：版號改讀 `wine.inf`；另做短 `WINE_PE_SMOKE`（偵測 Signal 11 迴圈→exit 139；
  hang→124）
- 預設 `BOX64_LOG=0`；Session 執行中收合日誌＋「顯示日誌」；Surface 置中提示等待 Present

**Retest:** Cover-install **0.1.37** → 運行時自動重拉 box64（新 stamp）→ 日誌應有
`BOX64_WINE64K_STAMP=wine64k-align-0x100000-…`、
`WINE prereserve of 0x100000:0x30000000 done`、`WINE_VERSION_SMOKE ok`、
`WINE_START`；畫面不應再被 log 占滿。若仍 139：貼 prereserve／Emit Signal 11 段。

## 0.1.36 — wine --version hang under DYNAREC=0

**Symptom (0.1.35):** Session shows `執行中`；log stops at `WINE_VERSION_SMOKE begin cmd=…wine --version`
with **no** prereserve／version／exit. Boss confirmed pid still alive → **hang**, not silent crash.
Stamps／SELFTEST／NTDLL／X64LIB／GpuStack all OK.

**Root cause:** 0.1.29–35 forced `BOX64_DYNAREC=0` (interpreter). After wine64k fixed the early
Wine11 `granularity_mask` SIGABRT, `wine --version` proceeds into heavier init／wineserver and
**never returns** under interpreter. Smoke also redirected all stdout/stderr to a file with **no
timeout**, so the UI sat forever on `begin`.

**Fix:**
- Default `BOX64_DYNAREC=1` (dynarecSafe + NOHOSTEXT；bookworm x64lib 已否證「dynarec 必 SIGILL」)
- Smoke: 90s timeout + live tee/heartbeat + disposable `WINEPREFIX` + kill wineserver on timeout (exit 124)
- `ensureWineLaunchScript` 覆寫腳本 → **蓋裝即可**，不必重裝運行時

**Retest:** Cover-install 0.1.36 → expect `DYNAREC=1`, live Box64 lines, `WINE prereserve of 0x10000:0x10000`,
`wine-11.x` version string, `WINE_VERSION_SMOKE ok`. If still hang → `TIMEOUT`／exit 124 with partial out.

## 0.1.35 — XZFormatException on wine64k.wcp extract

**Symptom (0.1.34 cover-install / reinstall):** `安裝失敗：Input is not in the XZ format` (`XZFormatException`)
while pulling `Box64-0.4.4-glibc-wine64k.wcp` after stamp-forced box64 reinstall.

**Root cause:** `extractBox64` → `extractTarXz` requires XZ magic `FD 7zXZ`. The 0.1.34
runtime asset was uploaded as an **uncompressed ustar** (~30.7 MiB, header name `box64`) that
already contained the correctly patched binary + stamp. Installer opened it with `XZInputStream` → fail.
Working `Box64-0.4.4-glibc.wcp` is xz (~3.5 MiB).

**Fix:** Re-upload `runtime-v1/Box64-0.4.4-glibc-wine64k.wcp` as xz-compressed ustar
(sha256 `2e77cd8c…`, ~3.4 MiB, magic `FD 7zXZ`). APK 0.1.35 updates embedded sha256 +
`requireXzMagic` guard before extract. Path A / WineProot / GpuStack unchanged.

**Retest:** Cover-install 0.1.35 (or 重裝運行時). Expect box64-glibc re-download ~3.4MB,
`BOX64_WINE64K_STAMP=…`, `WINE prereserve of 0x10000:0x10000 done`, smoke ok.
Do **not** reuse 0.1.34 alone after the asset replace — its pinned sha256 is the old raw tar.

## 0.1.34 — Wine11 `set_mem_area_state` / `granularity_mask` (exit 134)

**Symptom (0.1.33):** `WINELOADER` correct (`x86_64-unix/wine`), then:
`Assertion !(size & granularity_mask) failed` → `WINE_VERSION_SMOKE exit=134` (SIGABRT).
mmap of mem_areas OK; assert fires while applying Box64 `wine_main_preload_info`.

**Root cause:** Box64 default DOS prereserve is `{0x10000, 0x8000}` (32 KiB). Wine 11
`granularity_mask = 0xffff` requires size **and** addr 64 KiB-aligned. `0x8000 & 0xffff ≠ 0` → abort.
(Winlator/Proton 9 often never hit this path; Wine 11’s new mem_areas table is strict.)

**Fix:** Ship `Box64-0.4.4-glibc-wine64k.wcp` with DOS size patched `0x8000 → 0x10000`, plus
`.box64-wine64k-stamp`. ComponentInstaller force-reinstalls box64 when stamp missing.
Also `BOX64_MMAP32=0` (Winlator-style). No Wine version swap needed for this assert.

**Retest:** Cover-install APK → runtime auto-pulls new box64 (stamp) → expect
`WINE prereserve of 0x10000:0x10000 done` and `wine-11.21` version string (smoke exit 0).

# 相容性與已知失敗模式

本 App 在 **ARM64（arm64-v8a）**、**Android 9+（minSdk 28）** 上執行 x86／Windows PE。

## GPU 期望

- 預設圖形路徑：**Mesa Turnip（KGSL）＋ DXVK**，需要裝置節點 `/dev/kgsl-3d0`。
- **Qualcomm Adreno**（有 KGSL）為主要驗證目標。
- **Mali／PowerVR／其他**：目前無 Vortek／adrenotools 通路；可能黑屏或無法出幀。App 會在啟動前警告「此裝置可能無畫面」。

## 虛擬桌面

- 固定 **1280×720**（Wine desktop）；螢幕以 letterbox 適配。解析度選擇器尚未提供。

## 已知失敗模式（請先「重裝運行時」）

| 體感 | 常見根因 | 建議 |
|------|----------|------|
| 已結束 (-1127)／命令不存在 | 缺 glibc box64、shell、或動態連結器 | App 內「重裝運行時」 |
| 黑畫面但狀態「執行中」 | 非 Adreno／無 KGSL；或 GPU 組件未裝完 | 見啟動前警告；確認 Adreno |
| 安裝後仍無法啟動 | 半殘 rootfs／舊 bionic box64 | 「重裝運行時」（不清工作區） |
| 權限被拒／無法執行 | 舊版曾把 proot 放在 noexec 目錄 | 升級 App；勿依賴「儲存權限」 |
| libtalloc／CANNOT LINK | 客體環境蓋掉宿主 `LD_LIBRARY_PATH` | 升級 App；必要時重裝運行時 |
| could not load ntdll.so／Cannot dlopen | 缺 Wine unix `ntdll.so`，或缺 Box64 x64lib（`libgcc_s.so.1`） | 「重裝運行時」（0.1.26+ 會拉 x64lib） |
| Illegal instruction／exit 132／SIGILL | interpreter 下仍炸＝ntdll／libgcc／wine 子行程 | 升到 **0.1.30+** 並**重裝運行時**（bookworm x64lib）；看 `WINE_VERSION_SMOKE`／`BOX64_X64LIB_STAMP` |
| wine --version／exit 1／-1001 | `WINELOADER` 指到 shell 包裝或缺 preloader | 升到 **0.1.31+** 直接重開；看 `WINE_VERSION_SMOKE` 全文；**不是**「存取所有檔案」 |
| c0000018／shared user data | `bin/wine` 無 `wine_main_preload_info`，Box64 預留位址衝突 | 升到 **0.1.32+** 直接重開（改用 `x86_64-unix/wine`） |
| `/usr/bin/sh` not found | 常為缺 `ld-linux`（PT_INTERP），非真缺 sh | 「重裝運行時」 |

## 回報

請到 [Issues](https://github.com/wusui1325-dotcom/x86-in-android/issues) 附：版本名＋versionCode、裝置 SoC／GPU、session 錯誤卡原文。勿貼完整內部路徑以外的隱私資料。

多 runtime 釘選／回退尚未提供；「重裝運行時」會依目前 APK 的 `runtimeId` 拉取最新組件集。
