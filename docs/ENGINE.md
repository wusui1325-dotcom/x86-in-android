## 0.1.111 — NTDLL_GRAN_ALIGN (Dig110 set_mem_area_state survives MMAP32=0)

**Dig 110:** `WINE_FLAVOR=amd64_wow64` ✅ `mmap32_wow64_guest=0` ✅ child
`BOX64_MMAP32=0` ✅ — but machine-14c child still
`ntdll set_mem_area_state: Assertion !(size & granularity_mask)` → **SIGABRT**
with `WINEPRELOADRESERVE="400000-692000"` (PE SizeOfImage not 64k-aligned) +
box64 **39bits**. Dig109 incomplete: MMAP32 bit alone does not cure assert.
`wow64cpu_init=[]` · `started_pid_after_app=0` · 0× Loaded. Canary
`machine_cmd_014c=1` early=exited but `started_pid_cmd=0`. `+wow64` missing
from WINEDEBUG; stale `mmap32_child=1` labels.

**Knives:** (A) runtime patch `ntdll.so` `set_mem_area_state` entry → 64k-align
addr↓ / size↑ before assert (`NTDLL_GRAN_ALIGN`; PE 0x692000 → 0x6a0000);
keep guest MMAP32=0 / parent NOEXEC / HOLEFIT. (B) after child lives require
`wow64cpu_init` + `started_pid_after_app` + Loaded App. (C) put `+wow64` in
WINE_START WINEDEBUG; stop stale mmap32_child=1; rename fail class to
`virtual_granularity_assert` when guest MMAP32=0. KEEP 107
`/machine x86 /wait /exec`; NO trampoline; no i386 MAIN.

**Expect:** `WINE_LAUNCH_STAMP=0.1.111`; `WINE_FLAVOR=amd64_wow64`;
`mmap32_wow64_guest=0`; `NTDLL_GRAN_ALIGN ok`; **0×** `set_mem_area_state`;
canary `machine_cmd_014c=1`+`started_pid_cmd=1`; `wow64cpu_init` non-empty;
`started_pid_after_app=1`; `Loaded L"C:\App\app.exe"`.

## 0.1.110 — wow64 guest MMAP32=0 (Dig109 set_mem_area_state)

**Dig 109:** `WINE_FLAVOR=amd64_wow64` ✅ trampoline DROPPED ✅ App NtCreate
**machine 14c** ✅ — but child wine for `app.exe` applied box64rc `[app.exe]
BOX64_MMAP32=1` → `ntdll set_mem_area_state: Assertion !(size & granularity_mask)`
→ **SIGABRT** before wow64cpu maps (`wow64cpu_init=[]`, `started_pid_after_app=0`,
ShellExecuteEx fatal / FormatMessage failed). Canary timeout-blind 8s
(`machine_cmd_014c=0`).

**Knives:** (A) under `amd64_wow64`, rewrite box64rc so `[app.exe]` / wow64cpu /
syswow64 guests / creators use `BOX64_MMAP32=0` (parent env stays 0); stamp
`mmap32_wow64_guest=0`; `+wow64` WINEDEBUG; seed path sizes. (B) dig
`create_fail=mmap32_granularity_assert` + `gle_raw` + create_class
(fail vs success-no-start). (C) canary same argv as App; dig NtCreate/assert
before kill; PASS iff `machine_cmd_014c=1` + `started_pid_cmd=1`. KEEP 107
`/machine x86 /wait /exec` order; NO trampoline; NOEXEC kept; no i386 MAIN.

**Expect:** `WINE_LAUNCH_STAMP=0.1.110`; `WINE_FLAVOR=amd64_wow64`;
`mmap32_wow64_guest=0`; canary `machine_cmd_014c=1`+`started_pid_cmd=1`;
`wow64cpu_init` non-empty; `started_pid_after_app=1`; `Loaded L"C:\App\app.exe"`.

## 0.1.109 — Wine amd64-wow64 + DROP trampoline (A2 systemic 14c)

**Dig 108:** trampoline put MACHINE_TYPE=i386 on syswow64\cmd — same A2 as App
(`started_pid_cmd=0`, `wow64cpu_init=[]`, ShellExecuteEx fatal). Root: Kron4ek
`staging-amd64` ships `i386-unix` → classic wine32 re-exec; under box64 +
`WINELOADERNOEXEC=1` any 14c CreateProcess aborts before wow64cpu init.

**Knives:** (1) `components.json` Wine → `wine-11.21-staging-amd64-wow64`
(0× i386-unix; in-process wow64cpu; `ntdll.so` identical → NTDLL_ANON_PE kept);
stamp `WINE_FLAVOR=amd64_wow64`. (2) DROP trampoline — PRIMARY
`start /machine x86 /wait /exec C:\App\app.exe` (KEEP 107 machine-before-exec).
(3) canary + `ntstatus_hex` dig (`c000xxxx` / STATUS_ / not-supported-machine).

**Expect:** `WINE_LAUNCH_STAMP=0.1.109`; `WINE_FLAVOR=amd64_wow64`;
`canary_dig machine_cmd_014c=1 started_pid_cmd=1`; App `machine_014c=1` +
`started_pid_after_app=1` + `Loaded L"C:\App\app.exe"`; `wow64cpu_init` non-empty.

## 0.1.108 — App-only post-14c trampoline + NTSTATUS surface

**Dig 107:** PRIMARY `start /machine x86 /wait /exec` restored App NtCreate **machine 14c**,
but CreateProcess still returned without `started process`; FormatMessage failed;
`wow64cpu_init=[]`. Direct 64-bit cmd still machine 0 + creator_detach.

**Knives:** (1) PRIMARY trampoline — `/exec C:\windows\syswow64\cmd.exe /c C:\App\x86_launch.cmd`
(KEEP machine-before-exec order; WoW64-create small i386 cmd, then 32→32 App);
(2) wow64 canary; (3) dig stamps FormatMessage/GLE + err:ntdll + trampoline_dig.

**Expect:** `canary_dig machine_cmd_014c=1 started_pid=1`; PRIMARY `started_pid_cmd=1` +
`started_pid_after_app=1` + Loaded `C:\App\app.exe`; non-empty NTSTATUS/GLE when still failing.

## 0.1.106 — Loaded A2 + phase-bound UX (no tick-rotate / hideable log)

**Smoke (0.1.105):** `/machine x86` real spawn + CPIW single-BS + NtCreate machine 14c PASS;
still **0× Loaded** / **0× App started_pid**; ShellExecuteEx fatal after machine 14c;
ladder **0×** advance; dig stamps missing; Boss: status tick-rotate + `!running` force-open log covers Present.

**P0 A2:** (1) `app_load_slice_file` kill `dd bs=1` (105 early_dig dump hung → starved dig+ladder);
(2) `machine_014c` match Wine TRACE bare `machine 14c`; restore dig stamps;
(3) PRIMARY `start /exec /machine x86 /wait`; (4) fast ladder on fail_sig/inner fatal (≥2s);
(5) `create_fail=shellex_after_machine14c` + NTSTATUS hint. KEEP 102–105 gates / quiet / cap40 /
MAP_WAIT / defer / NOEXEC / mmap32_creators / stub_quoted.

**P0 UX:** status = **true phase-bound** (forbid tick rotate); log overlay **hideable**
(user hide wins; never `!running` force; auto-collapse on WINE_START). KEEP BSOD `#0000AA` +
footer + `：（` + ≥50 multi-line + status library ≥100.

**Expect:** dig stamps `machine_014c=1` / `create_fail=` / `started_pid_after_app`; ladder advance
after machine fail; ideally `Loaded L"C:\App\app.exe"` + started pid; status not tick-rotating;
log hideable with Present visible.

## 0.1.104 — Wow64/machine App create + CPIW dblslash kill

**Smoke (0.1.103):** RPCSS ready=1 / sc_rc=0 / hard-gate pass / creators OK;
A2 residual same as 100 — App NtCreate `machine 0`, `started_pid_after_app=0`,
0× Loaded; CPIW `app=C:\App\\app.exe` dblslash; `/exec`+Windows path → FNF.

**P0:** (1) normalize `APP_WIN=C:\App\app.exe` (single BS) for cmd/start/explorer/
stub; (2) ladder `direct → machine_x86 → explorer → /exec /unix → start` so
`start /machine x86 /wait` stamps TRACE `machine 0x014c`; (3) `/exec /unix $SEED`
(not `/exec /wait C:/App`); (4) dig `creator_detach_after_machine0` +
`ntstatus_hint` + `path_hygiene`. KEEP 102/103 gates / quiet RPCSS / soft-cap40 /
MAP_WAIT / defer / mmap32_creators / parent MMAP32=0 / WINELOADERNOEXEC.

**Expect:** `machine_014c=1` on `/machine x86` App NtCreate; `dblslash_hits=0`;
ideally `started_pid_after_app≥1` + `Loaded L"C:\App\app.exe"`.

## 0.1.100 — A2 expand MMAP32 to NtCreate creators (cmd/start)

**Smoke (0.1.99 mainline):** A2 **config PASS** — `mmap32_child=1` /
`mmap32_parent=0` / `NORCFILES=0` / `BOX64_RCFILE` / `noexec=kept` / **0× SIGILL**.
A2 **outcome FAIL**: every App `NtCreateUserProcess` TRACE `machine 0`, never
`started process pid`, **0× Loaded App**. `[app.exe]` RCFILE never applies
because App never becomes a running Box64 process; creator cmd still env
`MMAP32=0`.

**P0:** Expand box64rc `BOX64_MMAP32=1` from only `[app.exe]` to **creators**
`[cmd.exe]` + `[start.exe]` (ladder `direct_cmd64` / `start_exec64` /
`machine_i386`). Keep `[app.exe]` + `/wow64cpu.dll` sections. **Parent
wine64 / explorer stay env `BOX64_MMAP32=0`** — no `[explorer.exe]` section;
never parent `export BOX64_MMAP32=1` (096 SIGILL). KEEP WINELOADERNOEXEC.
Stamp `mmap32_creators=cmd,start` / `mmap32_child=1` / `mmap32_parent=0` /
`noexec=kept`. Stronger dig: always stamp `machine_014c` / `machine_zero` /
`loaded_app` / `GetLastError_tokens` / `NTSTATUS_tokens` / creator rcfile
apply; early dig dump at HB=8/12 when `path_seen=1` && no Loaded. Never i386
MAIN; no hot bake; DXVK x32 deferred until Loaded.

**Expect:** creator rcfile apply (`Looking for [cmd.exe]`); App NtCreate →
`machine 0x014c` or explicit NTSTATUS; `started process pid` +
`Loaded L"C:\App\app.exe"`; then non-blue. If still `machine 0` — dig
NTSTATUS/GetLastError stamps decide next knife.

## 0.1.99 — A2 child-only MMAP32 (box64rc [app.exe])

**Smoke (0.1.98 mainline):** A1 PASS — `noexec=kept` / `mmap32_parent=0` /
`decide=direct_cmd64` / **0× SIGILL**; cmd/start/explorer all reach
`NtCreateUserProcess(C:\App\app.exe)`. Residual A2: every App NtCreate TRACE
`machine 0`, never `started process pid`, **0× Loaded App**, ladder
`direct→unix_seed→explorer→start` all `no_loaded_t`; Present still blue.

**P0 A2:** Child-only `BOX64_MMAP32=1` via `BOX64_RCFILE` section `[app.exe]`
(+ `/wow64cpu.dll` path sections). `BOX64_NORCFILES=0` so RCFILE loads (skips
`/etc` + `~/.box64rc`). Stamp `mmap32_child=1` / `mmap32_parent=0` /
`noexec=kept`. **Never** parent `export BOX64_MMAP32=1` (096 SIGILL). Keep
WINELOADERNOEXEC / direct_cmd64 / BinaryType_host + stronger App NtCreate dig
(NTSTATUS/GetLastError/GetBinaryTypeW return/wow64cpu/box64 rcfile apply).
Optional ladder probe: `start //machine i386` (non-primary). Never i386 MAIN.
DXVK x32 deferred until Loaded.

**Expect:** `mmap32_child=1` stamp; App NtCreate → `started process pid` +
`Loaded L"C:\App\app.exe"`; ideally machine 0x014c / wow64cpu init; then
non-blue. If still machine 0 / no started — dig NTSTATUS + rcfile apply lines.

## 0.1.98 — NOEXEC kept (097 SIGILL root; not MMAP32)

**Smoke (0.1.97 mainline):** `mmap32_parent=0` / `BOX64_MMAP32=0` landed, but
`wine start //exec //wait` and ladder `wine cmd //c` still **SIGILL rc=132**
right after Box64 emulated `ntdll.so`, before PE map of start.exe. Differential:
095 `cmd //c` + **WINELOADERNOEXEC kept** → lived to NtCreate App; 097 explorer
(NOEXEC kept) lives; only APP_LOAD start/cmd **`unset WINELOADERNOEXEC`** dies.
Ladder-3 explorer reaches `NtCreateUserProcess(App) machine 0` with no started
pid / 0× Loaded (residual A2).

**P0 A1:** Delete `unset WINELOADERNOEXEC` in `wow64_direct` / `wow64_unix_seed`;
inherit global `WINELOADERNOEXEC=1`; stamp `noexec=kept`. Primary =
`wine cmd //c C:/App/app.exe` (`decide=direct_cmd64`); ladder-2 =
`start //exec //wait` with NOEXEC kept. Keep `mmap32_parent=0` — never parent
`export BOX64_MMAP32=1`; never i386 MAIN. Keep BinaryType_host + ntcreate_diag +
C:/App forward-slash + RPCSS ready=1.

**A2 (follow-up, non-blocking):** child-only MMAP32 (box64rc `[app.exe]` /
wow64cpu) + Wow64 `machine=0x014c` for started pid / Loaded. DXVK x32 deferred.

**Expect:** no start/cmd rc=132; `NtCreateUserProcess` App again (like 095);
then A2 for started pid + Loaded; non-blue.

## 0.1.97 — MMAP32 parent=0 (096 SIGILL rollback)

**Smoke (0.1.96-hot.1 / Path A 0.1.96):** BinaryType_host=SCS_32BIT_BINARY +
decide=direct_start64 + C:/App OK, but `wine start //exec //wait` and ladder
`wine cmd //c` under **BOX64_MMAP32=1** → parent **SIGILL rc=132**;
ntcreate_app=none; 0× Loaded. 0.1.95 with MMAP32=0 reached NtCreateUserProcess(App).

**P0:** APP_LOAD `wow64_direct` / `wow64_unix_seed` **no longer export**
`BOX64_MMAP32=1` — 64-bit start/cmd parent inherits global `BOX64_MMAP32=0`.
Stamp `mmap32_parent=0`. Keep C:/App, `start //exec //wait`, unset
`WINELOADERNOEXEC` (64-bit MAIN), BinaryType_host + ntcreate_diag, RPCSS ready=1.
Child-only MMAP32 (box64rc `[app.exe]` / wow64cpu) deferred — `BOX64_ENV_*` only
adds guest env, not safe per-child box64 knobs without device smoke.

**Expect:** no parent rc=132; NtCreateUserProcess App (+ started pid + Loaded);
non-blue screen. DXVK x32 still deferred.

## 0.1.96 — NtCreateUserProcess i386 / MMAP32 / path

**P0:** `wow64_direct` = `wine start //exec //wait C:/App/app.exe` with
`BOX64_MMAP32=1` + `WINELOADERNOEXEC` unset (64-bit MAIN; never i386 MAIN).
Forward-slash path kills `C:\App\\app.exe` double-backslash. Ladder-2 =
`cmd //c` with same MMAP32/NOEXEC knives. Stamp `BinaryType_host` +
`ntcreate_diag` (started pid / machine / mscoree). DXVK x32 still deferred.

**Expect:** `decide=direct_start64` → `NtCreateUserProcess` App has
`started process pid` → `Loaded L"C:\App\app.exe"`; RPCSS ready=1 kept.

## 0.1.95 — 64-bit cmd CreateProcess (no i386 MAIN / no SIGILL)

**Smoke (0.1.94):** RPCSS ready=1 + decide=direct_unix_seed OK, but
`wine $SEED` (i386 PE as MAIN, WINELOADERNOEXEC unset) → **rc=132 SIGILL**;
0× Loaded App\\app.exe; BOX64 never Detected app.exe.

**Root:** On box64+win64, putting an i386 PE as wine MAIN argv makes the loader /
arch-switch path try to execute 32-bit code as the process image → SIGILL. Keep
`WINELOADERNOEXEC=1` and launch via a **64-bit** Windows PE that CreateProcesses
the app (Wow64 NtCreateUserProcess). Also: unix argv `/exec`/`/c` is mangled into
a DOS path — use `//exec` / `//c`.

**Knife:**
- `wow64_direct` = `wine cmd //c C:\\App\\app.exe` (64-bit cmd MAIN; NOEXEC kept)
- ladder-2 = `wine start //exec //wait C:\\App\\app.exe`
- APP_LOAD WTERMSIG / BOX64_SHOWSEGV / SIGILL_PC slice stamp
- RPCSS ready=1 + exe assoc from 0.1.94 kept; DXVK x32 still deferred

**Expect:** `decide=direct_cmd64` → `Loaded L"C:\\App\\app.exe"`; no rc=132;
slice_first_pe ≠ start.exe (or start then App); RPCSS ready=1.

## 0.1.94 — unix-seed MAIN + RPCSS STATE→ready

**Smoke (0.1.93):** decide=direct_win / `wine C:/App` still Loaded `start.exe` then
ShellExecute NOASSOC (WINELOADERNOEXEC → Wine rewrites i386 MAIN to start.exe;
NtCreateUserProcess of App\app.exe fails). RPCSS `STATE : 4 RUNNING` in
wine-rpcss.out but probe ready=0 (WINEDEBUG pollution / ole_ok stuck).

**Fix:**
- `wow64_direct` = `wine $SEED` (drive_c unix-seed MAIN) with `WINELOADERNOEXEC` unset
- ladder-2 = Windows-path bare wine + WINELOADERNOEXEC unset (no start /unix)
- `inject-exe-assoc` offline (.exe→exefile→open\command + Wow6432Node)
- RPCSS: STATE RUNNING in wine-rpcss.out / sc_rc=0+alive → sc_running=1 → ready=1;
  sc query with `WINEDEBUG=-all`
- fail_hr: drop map_image section-flag false positives

**Expect:** `decide=direct_unix_seed` → `Loaded L"C:\App\app.exe"`;
`RPCSS … ready=1` / `alive+sc-running`; slice_first_pe ≠ start.exe.

## 0.1.92 — APP_LOAD /exec + orphan-reap + RPCSS crush + UX

**Boss 0.1.91 藍屏：** K0–K3 過；`wine C:/App` → Loaded `start.exe` + ShellExecute「no application associated」；orphan-reap 誤殺 services.exe；RPCSS wall ~30s；舊工作區半殘。

- **P0:** orphan-reap whitelist（永不殺 services/rpcss）；fail_sig per-mode wine-run slice + 真 fail_hr；`wow64_direct` = `start /exec`（CreateProcess）
- **P1:** RPCSS services sleep 2／sc cap 10／skip ole when RUNNING／wall soft-continue@45s
- **UX:** 「新建工作區」乾淨 prefix；裝置 .exe 全掃（MANAGE_EXTERNAL_STORAGE）；＋號精確匯入到新工作區；半殘提示重建（不默刪）
- DXVK x32 仍 defer（尚未 Loaded）

## 0.1.91 — K0–K3 prove APP_LOAD (stamp + RPCSS done + seed gate)

Boss 0.1.90 smoke: live log never entered WINE_START/APP_LOAD (stuck RPCSS t=30,
0× done). Export `session.log=(missing)` (wrong path). wine-run start.exe /
「no associated」= **stale**. Cannot judge A1/A3 from that export.

**K0:** export `logs/session.log` + durable `/mnt/data/app-load.stamp` (log tee);
`# STAMP` into wine-run. **K1:** clear wine-run at script start; orphan start.exe
reap before WINE_START. **K2:** seed gate refuse blind `wow64_start`. **K3:** RPCSS
heartbeat + soft-continue @t≥30 → always `done`. Keep 090 A1/A3; DXVK x32 defer.

**Expect:** `WINE_LAUNCH_STAMP=0.1.91`; `RPCSS_PRESTART done`; `WINE_START begin`;
`APP_DOSDEVICES c=…`; `decide=direct_win`; `APP_SEED gate=OK|FAIL`; app-load.stamp
in export; then Loaded **or** ladder/fail_hr — never silent ShellExecute-only.

## 0.1.90 — A1+A3+A2 APP_LOAD (hang-alive + seed/c: + SysWOW64)

Boss 087 hang-alive + seed footguns still open after 089 direct-first.

**A3:** kill stuck `APP_PID` + fail_sig/no_Loaded timed ladder `direct→explorer→start`;
APP_RELAUNCH reaps; DO_HB 8/12/15 (pre-090 "t≥15" actually waited until HB=30).

**A1:** never demote `APP_WOW64=0` on `visible=no`; force `dosdevices/c:`→`../drive_c`;
seed re-cp / `APP_SEED repair=`; winepath `timeout 2` (non-blocking).

**A2:** expand SysWOW64 pe32 (CRT/d3dcompiler/vulkan + pe64 extras); call ensure_* before
i386 CreateProcess. Overlay clear gated on `desktopOnly=0`. DXVK x32 still deferred.

**Expect:** `WINE_LAUNCH_STAMP=0.1.90`; `APP_SEED visible=… c=…`; `decide=direct_win`;
`Loaded L"…app.exe"` **or** `APP_LOAD ladder advance reason=…` then Loaded / FATAL+`fail_hr=`.


## 0.1.89 — WoW64 direct-first (no start.exe ShellExecute)

**Boss smoke (0.1.87 full log):** `APP_WOW64_SEED` / `decide=start_win` /
`cmdline: wine start C:/App/app.exe` / `APP_SEED` 169MB all OK, but still
`ShellExecuteEx failed` + `FormatMessage failed` → `APP_LOAD_FATAL missing-Loaded`
t=60s; Present desktopOnly/guestBlack (app never started). `start.exe` ShellExecute
can fail without quick-exit ≤2s → 087 ladder never reached `wow64_direct`.

**Fix:**
- i386 default `APP_SPAWN_MODE=wow64_direct` (`wine C:/App/app.exe` CreateProcess)
- Ladder: direct → `explorer /desktop=x86,WxH,x11 C:/App` → `wine start` LAST (diag)
- `decide=direct_win`; `APP_SEED visible=` + winepath/dir probe; ShellExecute HRESULT/hex dump
- DXVK x32→SysWOW64 **defer** (not Loaded yet)
- Keep 088 InProcessX cpuAccess=false / 087 WoW64 seed / 086–070

**Expect:** `WINE_LAUNCH_STAMP=0.1.89`; `APP_LOAD wow64=1 … decide=direct_win`;
`cmdline: wine C:/App/app.exe`; strict `Loaded L"…app.exe"` (not missing-Loaded).

## 0.1.88 — InProcessX root GPUImage cpuAccess=false

**Dig (deferred from 087):** `GPUImage.createOrObtain` keeps the first GPUImage forever.
0.1.83 fixed `PresentExtension.selectInput` → `cpuAccess=false` (match DRI3), but
`InProcessX.start` still pre-promoted root with `cpuAccess=true` before DRI3/Present →
empty CPU AHB locked on root → desktopOnly / guestBlack early frames. Comment claimed
alignment with PresentExtension but still passed `true`. PresentXFramePump 0.1.70: NEVER
promote with cpuAccess=true.

**Fix:**
- `InProcessX`: `createOrObtain(xServer, root.getContent(), false, true)`; Log.i once
  `InProcessX GPUImage cpuAccess=false`
- Keep pre-promote (do not remove entirely this tip)
- Keep 087 WoW64 i386 / 086 vulkan seed / 085 awk POSIX + forceGpu / 084–070

**Expect:** `WINE_LAUNCH_STAMP=0.1.88`; `InProcessX GPUImage cpuAccess=false`; Present DIAG
`ahbCpuAccess=0` on early root frames (not poisoned `=1`).

## 0.1.87 — WoW64 i386 APP_LOAD (machine=0x014c)

**Boss smoke (0.1.85 full log):** workspace `Antigravity-x64` but `APP_PE machine=0x014c`
(i386). unix absolute `wine /mnt/app/app.exe` → ShellExecuteEx / FormatMessage failed →
`APP_LOAD_FATAL missing-Loaded`; Present desktopOnly/guestBlack (app never started).
Also `APP_PE WARN unexpected machine` because od prints `014c` but case only matched `14c`.

**Why unix absolute fails for 0x014c:** under win64, unix CreateProcess does not reliably
engage WoW64; need Windows-path `wine start C:/App/app.exe` so wow64cpu thunks to SysWOW64
i386 ntdll. 0.1.67 C:/ ban stays for **amd64**; i386 is the exception.

**Fix:**
- Fingerprint `014c`/`14c` → `APP_LOAD wow64=1` → `wine start C:/App/app.exe` (fallback
  bare `wine C:/App/app.exe`); 0x8664 keeps unix ladder
- `ensure_wow64_seed` — wow64{,cpu,win}.dll → system32; core i386 PE → SysWOW64
- Fix PE machine case to accept `014c`
- InProcessX root cpuAccess **defer 088+** (app never Loaded — not this tip)
- Keep 086 vulkan seed / 085 awk POSIX + forceGpu / 084 d3dcompiler / 083–070

**Expect:** `WINE_LAUNCH_STAMP=0.1.87`; `APP_PE machine=0x014c` (no unexpected WARN);
`APP_WOW64_SEED … wow64cpu=yes …`; `APP_LOAD wow64=1 machine=0x014c … decide=start_win`;
strict `Loaded L"…app.exe"` (not missing-Loaded).

## 0.1.86 — ensure_vulkan_seed (vulkan-1 + winevulkan)

**Smoke risk (0.1.85 residual):** awk abort fixed so APP_LOAD / DXVK / vkCreate now run;
DXVK `LoadLibrary(vulkan-1.dll)` → winevulkan under seed-copy prefix. pe_dir ships
`vulkan-1.dll` + `winevulkan.dll` (x86_64 + i386) but PREFIX_EXEC seed_list + DXVK
reseed omit them — same class as 080 CRT / 084 d3dcompiler. Miss → vkCreate fail /
guestBlack after HWND ready.

**Fix:**
- `ensure_vulkan_seed` → system32 from x86_64-windows + SysWOW64 from i386-windows
- Log `APP_VULKAN_SEED … vulkan1=yes winevulkan=yes`
- PREFIX_EXEC seed_list +vulkan-1/winevulkan; call after d3dcompiler / on missing_dll relaunch
- DXVK_DIAG also fingerprints system32 `vulkan-1` (+ winevulkan) size/MISSING
- Keep 085 awk POSIX + forceGpu / 084 d3dcompiler / 083 Present cpuAccess / 082 sync /
  081 msgbox / 080 CRT / 079 CUI / 078 sticky / 076 TU_GMEM / 070 ahb_gpu

**Expect:** `WINE_LAUNCH_STAMP=0.1.86`; `APP_VULKAN_SEED … vulkan1=yes winevulkan=yes`;
DXVK_DIAG `vulkan-1:NNNN` (not MISSING); DXVK can LoadLibrary vulkan-1.

## 0.1.85 — awk→POSIX + Present forceGpu ahbCpuAccess0

**Boss smoke (0.1.84):** `/usr/local/bin/wine-launch.sh: 428: awk: not found` — imagefs has
no awk. TURNIP probe `ls -la | awk` aborted mid-launch. Also Present residual: GPU-only AHB
(`ahbCpuAccess=0`) with stale non-null software `data` waited for guestBlack sticky →
ahb_skip/BGRA black.

**Fix:**
- Kill **all** `awk` in wine-launch.sh → POSIX (`wc`/`while read` `/proc/mounts`); TURNIP
  probe, `path_is_mounted`, wipe bind check. No busybox awk / no install awk into imagefs.
- `forceGpu |= (lastAhbCpuAccess == 0)` when `ahbPtr != 0`; log once
  `PRESENT_DIAG forceGpu=ahb_gpu reason=ahbCpuAccess0`
- Keep BGRA for software Drawables (`ahbPtr==0`); **not** Present CPU-first flip
- Keep 084 d3dcompiler / 083 Present cpuAccess / 082 syncInterval / 081 msgbox / 080 CRT /
  079 CUI / 078 sticky / 076 TU_GMEM / 070 ahb_gpu

**Expect:** `WINE_LAUNCH_STAMP=0.1.85`; no `awk: not found`; `TURNIP_PROBE so=[…]`;
DIAG `ahbCpuAccess=0` → `forceGpu=ahb_gpu reason=ahbCpuAccess0`.

## 0.1.84 — d3dcompiler seed (47/46/43)

**Smoke risk (0.1.83):** HWND + Present cpuAccess=false still black possible when titles
`LoadLibrary(d3dcompiler_47)` (or 43/46) and prefix system32 lacks the PE — pe_dir has
`d3dcompiler_33…47` but PREFIX_EXEC seed_list + DXVK reseed only copy d3d8/9/10core/11/dxgi
(+ CRT). Miss → shader compile fail / MessageBox / c0000135 / guestBlack after HWND ready.

**Fix:**
- `ensure_d3dcompiler_seed` → system32 from x86_64-windows + SysWOW64 from i386-windows
- Seed **47 + 46 + 43** minimum (+ 33–42 when present); log `APP_D3DCOMPILER_SEED … dc47=`
- PREFIX_EXEC seed_list +47/46/43; call after CRT seed / on missing_dll relaunch path
- Classify `APP_CRT_SIG` also matches `d3dcompiler_*`
- Keep 083 Present cpuAccess / 082 syncInterval / 081 msgbox / 080 CRT / 079 CUI / 078 sticky / 076 TU_GMEM

**Expect:** `WINE_LAUNCH_STAMP=0.1.84`; `APP_D3DCOMPILER_SEED … dc47=yes dc46=yes dc43=yes`;
D3D titles LoadLibrary d3dcompiler_47; Present path unchanged.

## 0.1.83 — PresentExtension GPUImage cpuAccess=false

**Smoke risk (0.1.82):** HWND + DXGI syncInterval=0 still black possible when Present
`selectInput` attaches `GPUImage.createOrObtain(..., cpuAccess=true)` **before** DRI3
`bufferFromPixmap` (`cpuAccess=false`). `createOrObtain` keeps the first GPUImage forever
→ empty CPU-access AHB / locked virtualData → guestBlack (0.1.70 pump: NEVER promote with
cpuAccess=true). 070 forceGpu sticky mitigates late; first attach was wrong.

**Fix:**
- `PresentExtension.selectInput` → `createOrObtain(..., false, true)` (match DRI3)
- Log once `PresentExtension GPUImage cpuAccess=false … DIAG` on create
- Present DIAG `ahbCpuAccess=` (virtualData!=null ⇒ 1)
- Keep 082 syncInterval / 081 msgbox / 080 CRT / 079 CUI / 078 sticky / 076 TU_GMEM / 070 ahb_gpu

**Expect:** `WINE_LAUNCH_STAMP=0.1.83`; `PresentExtension GPUImage cpuAccess=false`;
`PRESENT_DIAG … ahbCpuAccess=0` on AHB path; guestBlack↓ when HWND+Present.

## 0.1.82 — DXGI syncInterval (Present fence / VSync stall)

**Smoke risk (0.1.81):** HWND path saturated (077–081). Guest can stay black with
HWND mapped + Present-ok when DXVK waits on VSync (FIFO syncInterval=1) for a refresh
that never arrives on virtual/mobile WSI. Managed dxvk.conf already had
deferSurfaceCreation / tearFree=False / maxFrameLatency / numBackBuffers / dialogMode
but **never** `dxgi.syncInterval` (DXVK 2.7.1 dxvk.conf documents it; default -1 = no override).

**Fix:**
- Seed `dxgi.syncInterval = 0` + `d3d9.presentInterval = 0` (disable VSync wait)
- Overridable: `X86_DXGI_SYNC=n` (default 0); `X86_DXVK_CONF=keep` leaves file
- Log `DXVK_CONFIG … syncInterval=0`
- Keep 081 msgbox / 080 CRT / 079 CUI / 078 sticky / 076 TU_GMEM

**Expect:** `WINE_LAUNCH_STAMP=0.1.82`; `DXVK_CONFIG … syncInterval=0`; HWND+Present
path → frames without VSync stall (`guestBlack=0`).

## 0.1.81 — MessageBox / title sticky + +msgbox

**Smoke risk (0.1.80):** after VS CRT seed, CREATEWINDOW_MISS may still be a Wine
MessageBox (error dialog). Default WINEDEBUG lacked `+msgbox` so `reason=messagebox`
almost never fired; host `isDialogBox()` was too strict for `#32770`; sticky skipped
dialogs without app/HWND; Present did not prefer dialog over desk chrome.

**Fix:**
- Add `+msgbox` to WINE_RUN / WINE_START WINEDEBUG defaults (overridable)
- `Window.isDialogLike()` = isDialogBox || `#32770` || Type.DIALOG → sticky/raise/NOHWND/focus
- Present prefer dialog HWND / dialog before non-desk / desktop
- Guest AppActivate title nudge (Error/Warning/錯誤/警告) + `APP_MSGBOX_DETECT source=wine|x11`
- Keep 080 CRT / 079 CUI / 078 sticky

**Expect:** `WINE_LAUNCH_STAMP=0.1.81`; `WINEDEBUG=…,+msgbox`; on MessageBox →
`APP_MSGBOX_DETECT` → Present `wmClass=#32770` / `desktopOnly=0`.

## 0.1.80 — VS CRT seed / CREATEWINDOW_MISS missing_dll

**Smoke risk (0.1.79):** `APP_CREATEWINDOW_MISS reason=missing_dll` (or early_exit with
msvcp/vcruntime in wine-run.out) — PREFIX_EXEC seeded only `ucrtbase/msvcrt`, not
VS CRT redistributables present under `x86_64-windows` / `i386-windows`. GUI never
CreateWindow → scan=none looks like Present black.

**Fix:**
- Seed `msvcp140/vcruntime140/vcruntime140_1/concrt140/msvcp120/msvcr120/…` into
  system32 + SysWOW64 (`APP_CRT_SEED`)
- After CREATEWINDOW_MISS `missing_dll`: one CRT relaunch (`APP_CRT_RELAUNCH`) + `APP_CRT_SIG=`
- Keep 079 CUI/classify / 078 sticky/nohwnd / 077 fingerprint

**Expect:** `WINE_LAUNCH_STAMP=0.1.80`; `APP_CRT_SEED … vcruntime140=yes`;
on miss → `APP_CRT_RELAUNCH` → `APP_HWND_WAIT ready`; Present `desktopOnly=0`.

## 0.1.79 — CUI / CreateWindow (wineconsole wrap)

**Smoke risk (0.1.78):** sticky/nohwnd map can still leave Loaded-but-no-HWND when
`nohwnd_scan=none` — the PE never CreateWindow (CUI subsystem=3 with bare `wine`,
MessageBox-only, missing CRT, or early exit). Present/Turnip cannot paint a missing HWND.

**Fix:**
- Seed `wineconsole.exe` into system32
- PE `subsystem=3` (or `X86_CUI_WRAP=1`) → `wine wineconsole --backend=user` (user32 HWND)
- After NOHWND_MAP still miss: `APP_CREATEWINDOW_MISS reason=` + one CUI relaunch if not wrapped
- `X86_CUI_WRAP=0` disables wrap; keep 078 sticky/nohwnd / 077 fingerprint

**Expect:** `WINE_LAUNCH_STAMP=0.1.79`; CUI → `APP_CUI_WRAP=wineconsole`;
`APP_HWND_WAIT ready`; Present `desktopOnly=0`. GUI miss → `APP_CREATEWINDOW_MISS reason=…`.

## 0.1.78 — Loaded-but-no-HWND / winex11 map deeper

**Smoke risk (0.1.77):** strict Loaded + APP_HWND_WAIT can still WARN Loaded-but-no-HWND when
explorer remaps overwrite `.x86-x11-ready` (wipe app hwnd), when Wine creates `_NET_WM_HWND`
but never MapWindow/ShowWindow, or when desktop raise steals ABOVE the app after map+raise.

**Fix:**
- Sticky ready marker — desk remaps cannot wipe `app=1 hwnd=0x…`; property updates refresh sticky
- Desk-steal-block — sticky app re-raised/focused instead of explorer
- Guest `.x86-nohwnd-req` (basename=PE) → host scan force `mapWindow`+`raiseWindow` + `nohwnd_scan=`
- APP_HWND_WAIT backoff after req; best-effort `AppActivate` nudge
- Keep 077 fingerprint / 076 Smart TU_GMEM / 075 map+raise / VIEWABLE

**Expect:** `WINE_LAUNCH_STAMP=0.1.78`; strict Loaded; `APP_HWND_WAIT ready` or
`NOHWND_MAP … nohwnd_scan=…`; Present `desktopOnly=0`; `guestBlack=0`.

## 0.1.77 — APP_LOAD fingerprint harden + bringToFront→raise

**Smoke risk (0.1.76):** Smart TU_GMEM can still leave a black window when APP_LOAD
false-positives "Loaded" from bare `/mnt/app/app.exe` path echoes (blocking RELAUNCH /
missing-Loaded FATAL), when PE is CUI (subsystem=3, no HWND), when Loaded never maps
an app HWND, or when focus/dialog paths call no-op `bringToFront` after MapWindow raise.

**Fix:**
- Strict Loaded fingerprint = loaddll `Loaded L"…app.exe"`; path echo → soft `path_seen=` only
- PE `subsystem=` (2=GUI / 3=CUI) + WARN on CUI
- `APP_HWND_WAIT` after APP_LOAD; `Loaded-but-no-HWND` WARN for Boss KEY_LOG
- `WinHandler.bringToFront` → host `raiseWindow` + setFocus (075 leftover)
- Keep 076 Smart TU_GMEM / 075 map+raise / VIEWABLE

**Expect:** `WINE_LAUNCH_STAMP=0.1.77`; `APP_PE … subsystem=2`; `APP_LOAD fingerprint seen early (strict Loaded…)`;
`APP_HWND_WAIT ready … app=1`; Present `desktopOnly=0`; `guestBlack=0`.

## 0.1.76 — Smart TU_GMEM auto (a710/715/720/725)

**Smoke risk (0.1.75):** map+raise / MAP_WAIT / VIEWABLE / maxFrameLatency can still leave a
black window when Turnip stays on `sysmem` on Adreno 710/715/720/725 (Autotuner/Pipetto
gmem class), or when `X86_TU_GMEM=1` never applied because GpuStack hardcoded `TU_DEBUG=…sysmem`.

**Fix:**
- wine-launch owns `TU_DEBUG`: probe `/sys` (+ host `X86_ADRENO_MODEL`) → `gmem` on
  710/715/720/725; else `sysmem`. Keep `noconform,noubwc`.
- Semantics: unset/`auto`=probe; `X86_TU_GMEM=1` force gmem; `=0` force sysmem
- Log `TURNIP_GMEM=auto|on|off model=… decision=gmem|sysmem`
- GpuStack stops hardcoding `TU_DEBUG` (074 MED was dead); exports model + X86_TU_GMEM
- Keep 075 map+raise / VIEWABLE / maxFrameLatency

**Expect:** `WINE_LAUNCH_STAMP=0.1.76`; `TURNIP_GMEM=auto model=Adreno710 decision=gmem`
(or sysmem on non-match); `guestBlack=0`.

## 0.1.75 — map+raise / CreateSwapChain timing

**Smoke risk (0.1.74):** HWND prefer + UseXRandR/ScreenDepth can still leave a black window when
DXGI CreateSwapChain binds before MapWindow/raise, Present samples desktop while HWND is
UNVIEWABLE/zero-geom, or APP_LOAD races explorer desktop map.

**Fix:**
- Host WM: `raiseWindow` (ABOVE + ConfigureNotify + Expose) on MapWindow for app/HWND/desktop
- Ready marker `/mnt/data/.x86-x11-ready` → wine-launch `MAP_WAIT` before APP_LOAD
- Present: require `MapState.VIEWABLE`; pending HWND wait (no desktop steal); DIAG `mapState=`/`mapWait=`
- dxvk.conf: `dxgi.maxFrameLatency=1` + `dxgi.numBackBuffers=3` (keep deferSurface/dialogMode)
- `X86_TU_GMEM` stays opt-in; keep 074 HWND/FS knobs

**Expect:** `WINE_LAUNCH_STAMP=0.1.75`; `MAP_WAIT desktop ready`; `DXVK_CONFIG … maxFrameLatency=1`;
Present `mapState=VIEWABLE hwnd=0x… desktopOnly=0`; `guestBlack=0`.

## 0.1.74 — winex11 HWND / exclusive FS / depth

**Smoke risk (0.1.73):** `noubwc,sysmem` + Mesa cache can still leave a black window when
winex11 attempts XRandR/XVidMode mode-switch or DXGrab on in-process X, or Present
samples a non-HWND child while DXGI paints into `_NET_WM_HWND`.

**Fix:**
- `patch-present-knobs`: `UseXRandR=N`, `UseXVidMode=N`, `DXGrab=N`, `ScreenDepth=32`,
  `Decorated=N` (keep GrabFullscreen=N Managed=Y UseTakeFocus=N)
- Present prefer `_NET_WM_HWND` windows; DIAG `hwnd=0x…`
- dxvk.conf: `d3d9.enableDialogMode=True`, `dxvk.allowFse=False`
- MED: `X86_TU_GMEM=1` → `TU_DEBUG=noconform,noubwc,gmem` (default sysmem unchanged)
- Keep 073 noubwc/sysmem / 072 dxvk.conf / 071 ICD / 070 ahb_gpu

**Expect:** `WINE_LAUNCH_STAMP=0.1.74`; `DRIVER_REG presentknobs … UseXRandR=N ScreenDepth=32`;
Present `hwnd=0x… desktopOnly=0`; `guestBlack=0` with real pixels.

## 0.1.73 — Turnip queue noubwc/sysmem + Mesa shader cache

**Smoke risk (0.1.72):** `deferSurfaceCreation` + VideoMemory/GrabFullscreen/ESYNC can still
leave a black window when Turnip submits UBWC tiles that AHB Present samples as linear, or
when Mesa has no writable shader cache under PRoot (`/tmp`/`$HOME/.cache` noexec/missing).

**Fix:**
- Default `TU_DEBUG=noconform,noubwc,sysmem` (overridable) — UBWC-off for AHB Present
  readback/sample; sysmem skips GMEM resolve hang/black (Pipetto 25.1 R2, not Autotuner)
- `MESA_SHADER_CACHE_DIR=/mnt/data/mesa_shader_cache` + `DISABLE=false` + `MAX_SIZE=512MB`
  + `XDG_CACHE_HOME=/mnt/data/xdg-cache`
- Soft bind `/dev/dma_heap/system{,-uncached}` if present; best-effort chmod on kgsl/dma;
  `TURNIP_QUEUE` log. No hard-block KGSL.
- Keep 072 dxvk.conf / 071 ICD absolute / 070 ahb_gpu

**Expect:** `WINE_LAUNCH_STAMP=0.1.73`; `TURNIP_QUEUE TU_DEBUG=noconform,noubwc,sysmem`;
`mesa_cache=/mnt/data/mesa_shader_cache`; `guestBlack=0` with real pixels after submit.

## 0.1.72 — DXGI Present deferSurface + Wine Direct3D/X11 knobs

**Smoke risk (0.1.71):** ICD absolute + DllOverrides can make `vkCreate`/`DXVK:` Adapter
OK while the window stays black — VkSurface bound at swapchain create before the
APP_LOAD HWND is mapped/sized; Turnip uncapped heaps; exclusive fullscreen grab on
in-process X.

**Fix:**
- Seed `/mnt/data/dxvk.conf` + `DXVK_CONFIG_FILE`: `dxgi.deferSurfaceCreation=True`,
  `d3d9.deferSurfaceCreation=True`, `dxgi.maxDeviceMemory=2048`, `dxvk.tearFree=False`
- `patch-present-knobs`: `VideoMemorySize=2048`, `csmt=1`/`EnableCSMT=Y`,
  `GrabFullscreen=N`, `Managed=Y`, `UseTakeFocus=N`
- `WINEESYNC=1` (Winlator-align); `BISECT_HINT` for wined3d if still sticky black
- Keep 071 ICD absolute / 070 ahb_gpu / Present CPU-first default

**Expect:** `WINE_LAUNCH_STAMP=0.1.72`; `DXVK_CONFIG deferSurface=1`;
`DRIVER_REG presentknobs VideoMemorySize=2048 GrabFullscreen=N`; `WINEESYNC=1`;
`guestBlack=0` with real pixels after first Present.

## 0.1.71 — Turnip ICD absolute library_path + DllOverrides (vkCreate)

**Smoke risk (0.1.70):** Present `ahb_gpu` can be healthy while DXVK never gets a Vulkan
device — ICD used bare `libvulkan_freedreno.so` (system search). Winlator puts the .so in
`/usr/lib`; we bind `/mnt/gpu/turnip`, so bare name silently fails when `LD_LIBRARY_PATH`
reshapes. Env-only `WINEDLLOVERRIDES` can also be lost by wineboot children.

**Fix:**
- ICD `library_path=/mnt/gpu/turnip/libvulkan_freedreno.so` (guest-absolute); rewrite each launch
- `TURNIP_DLOPEN` probe (fail-loud, no hard-block)
- `DllOverrides` reg `d3d*=native,builtin` (or builtin for wined3d bisect)
- Keep Turnip on `LD_LIBRARY_PATH` after aarch64 prepend

**Expect:** `WINE_LAUNCH_STAMP=0.1.71`; `TURNIP_ICD library_path=/mnt/gpu/turnip/…`;
`TURNIP_DLOPEN=ok`; `DRIVER_REG dlloverrides=native`; `DXVK:` Adapter / no flood `err:vulkan`;
`guestBlack=0` with real pixels.

## 0.1.70 — Present AHB GPU pixel path (guestBlack real fix)

**Smoke risk (0.1.69):** HUD-band/`wined3d` bisect 可觀測，但 CPU-first `ahb_skip` 讓 DRI3
無 virtualData 的 AHB 永遠上不了屏；Present 泵強升 GPUImage 還會抹掉 GDI 像素。

**Fix:**
- Present 泵：**停**對軟體 Drawable 強升 GPUImage（保 PutImage／GDI）
- `presentAhb(forceGpu)`：無 CPU 映射或 `guestBlack` sticky → 強制 `ahb_gpu`（EGLImage）
- 預設仍 CPU-first／`X86_PRESENT_GPU=1` 可全程 GPU；成功 ahb_gpu 可清 sticky
- KEY_LOG: `ahb_gpu`／`forceGpu`／`guestBlack=0 recovered`

**Expect:** `WINE_LAUNCH_STAMP=0.1.70`; soft windows `path=bgra nonBlack↑`;
DRI3／DXVK `path=ahb_gpu` with real pixels (not HUD-only); `guestBlack=0` after recovery.

## 0.1.69 — guestBlack HUD harden + wined3d bisect plumbing

**Smoke risk (0.1.68):** `DXVK_HUD` paints into swapchain → `isNearlyBlack` false-green
(`guestBlack` never sticks); sticky never clears on recover; `X86_DXWRAPPER=wined3d`
only in wine-launch (Session/guestEnv never exports it).

**Fix:**
- Present: exclude HUD band (y<64 + BL 180×48) when sampling; sticky clears with
  `guestBlack=0 recovered`; AHB-no-data → `guestSample=none`; DIAG `nonBlackGuest=` / `guestSample=`
- GpuStack/LaunchPipeline/Session: resolve + export `X86_DXWRAPPER` from Intent extra /
  `File(wsData,dxwrapper)` / prefs / env — `echo wined3d > …/dxwrapper`
- Keep HUD for eye (`devinfo,fps`); `X86_DXVK_HUD=0` to disable; `hud_sample_policy=exclude_top64`
- Knife #3: `VK_LOADER_DEBUG=error`, `TURNIP_PROBE`, KEY_LOG Adapter/vkCreate/recovered

**Expect:** `WINE_LAUNCH_STAMP=0.1.69`; HUD visible but `guestBlack=1` when app empty;
after paint `guestBlack=0 recovered`; file/env → `DXVK_DIAG wrapper=wined3d`.

## 0.1.68 — DXVK/Turnip diagnostic knife (guestBlack)

**Smoke risk (0.1.67):** Loaded + mappedKids>0 + Present ok can still be **guest-black**
(HUD-only / Turnip silent fail). No `DXVK_LOG_*` / `DXVK_HUD` / ICD-dll fingerprints.

**Fix:**
- guestEnv + wine-launch: `DXVK_LOG_LEVEL=info`, `DXVK_HUD=devinfo,fps`, `DXVK_STATE_CACHE_PATH=/mnt/data/dxvk-cache`
- `DXVK_DIAG icd=… dxvk_dlls=… kgsl=…` (+ soft WARN if kgsl missing / dll missing)
- Present DIAG sticky `guestBlack=1` when ok rises but pixels stay black/HUD-only (~45 frames)
- Optional bisect: `X86_DXWRAPPER=wined3d` → d3d*=builtin (default stays DXVK)
- KEY_LOG: DXVK_DIAG / DXVK_HUD / guestBlack / d3d11.dll / freedreno / ICD / DXVK: / err:vulkan

**Expect:** `WINE_LAUNCH_STAMP=0.1.68`; `DXVK_DIAG icd=yes … kgsl=yes`; loaddll native d3d11;
`PRESENT_DIAG … guestBlack=0` with real nonBlack (not only hud) when picture is real.

## 0.1.67 — APP_LOAD start /unix + Present prefer-app

**Smoke risk (0.1.66):** unix CreateProcess may still quick-exit (deps/cwd); relative
retry alone may not be enough. `pickLargestMapped` by area always prefers explorer
desktop (full WxH) over real app children → nonBlack/ok can look healthy while guest
app never maps.

**Fix:**
- APP_LOAD ladder: absolute unix → cwd-relative → **`wine start /unix $APP`** (never C:/App)
- APP_RELAUNCH @15s uses `start /unix`; PE machine WARN; noexec mount hint; FATAL fingerprints
- Present: prefer focused/application/non-desktop mapped window over explorer desktop
- DIAG: `desktopOnly=` `mappedKids=` `wmClass=`; KEY_LOG: `start /unix` / `desktopOnly`

**Expect:** `WINE_LAUNCH_STAMP=0.1.67`; `APP_LOAD … mode=absolute|relative|start_unix`;
`PRESENT_DIAG … desktopOnly=0` when app mapped; `Loaded L"` or clear `APP_LOAD_FATAL`.

## 0.1.66 — Present ahb CPU-first default + APP_LOAD harden

**Smoke risk (0.1.65):** `presentAhb` still called `ensureEgl()` first (pump prefers AHB).
GPU half-success / per-frame EGL rebuild could steal `ANativeWindow_lock` again.
APP_LOAD unix may exit quickly with no Loaded fingerprint; no clear fail line.
GPUImage permanently locks AHB → `presentAhbCpu` double-lock always fails.
`paintHudBarBgra(data.duplicate())` corrupted guest SHM.

**Fix:**
- `presentAhb`: default skip AHB-CPU (`path=ahb_skip` / `-EAGAIN`); `X86_PRESENT_GPU=1` for GPU
- True CPU path = BGRA(`virtualData`); pump prefers BGRA when data present
- Log: `PRESENT path=ahb_cpu skipped` / `ahb_gpu`; DIAG `path=bgra|hud|stub|ahb_skip`
- HUD only via independent `hudScratch` — never write into guest SHM
- APP_LOAD: unix-only; relative retry; `APP_LOAD_FATAL` / Loaded fingerprint (no C:/ fallback)
- KEY_LOG: `ANativeWindow_lock` / `lockFail` / `APP_PE` / `APP_LOAD_FATAL` / `Loaded L"` / `PRESENT path`

**Expect:** `WINE_LAUNCH_STAMP=0.1.66`; `PRESENT_DIAG … path=bgra ok>0 lockFail=0`;
`APP_LOAD cmdline: wine /mnt/app/app.exe`; `Loaded L"…app.exe"` or clear `APP_LOAD_FATAL`.

## 0.1.65 — Present CPU-first (fix EGL/ANativeWindow) + APP_LOAD unix

**Smoke (0.1.64):** PRESENT_DIAG `nonBlack` rising but `ok=0 fail` rising, `egl=true`,
`hud=0`. EGL window surface from attachSurface owned the buffer queue →
`ANativeWindow_lock` failed for BGRA/CPU. APP_LOAD `C:/App/app.exe` → start.exe +
ShellExecuteEx failed.

**Fix:**
- attachSurface: no ensureEgl; CPU path default
- Before ANativeWindow_lock: release EGL window surface
- presentAhb: GPU on demand; tear down EGL before CPU fallback
- pathStats: lastRc / lockFail
- APP_LOAD / APP_RELAUNCH: unix `/mnt/app/app.exe` only (seed C:/App kept)

**Expect:** `PRESENT_DIAG … bgra>0 ok>0 lastRc=0 egl=false` (or ahb); overlay clears;
`APP_LOAD cmdline: wine /mnt/app/app.exe`; no ShellExecuteEx.

## 0.1.64 — real Present (window-ready) + APP_LOAD + sticky diag

**Smoke (0.1.63):** overlay 「等待客體」cleared but Surface pure black. Export had
**zero** PRESENT_DIAG (logText wiped every 400ms). explorer.exe Loaded; **never**
`C:/App/app.exe`. Fake presentOk: `ready()` was ahb OR window; presentAhb/stub
returned 0 without window_.

**Fix:**
- `ready()` requires `window_`; presentAhb/stub/rgba without window → -EINVAL;
  presentBgra stages but returns 1 (no presentOk)
- Sticky `uiStickyLog` keeps Present/PRESENT_DIAG across session.log overwrite
- Explorer desktop alone → `APP_LOAD C:/App/app.exe`; `APP_RELAUNCH` once at 15s
- Nearly-black frames get bright 8px HUD bar (`nonBlack`/`hud` in pathStats)
- GpuStack path no longer assigns dead PresentSurfaceBridge (shutdown used to detach)

**Expect:** PRESENT_DIAG with waitAttach then ready=true after attach; cyan/magenta
HUD or desktop pixels; APP_LOAD Loaded fingerprint; overlay clears only after real post.

## 0.1.63 — Present→Surface last mile (overlay + diag + APP_WIN)

**Smoke (0.1.62):** NTDLL_ANON_PE / seed-copy / RpcSs ready / WINE_START explorer+app OK;
winex11 SET_DRAWABLE hdc→drawable 0x1400007 dc_rect 1280×720 repeatedly — Wine **is**
painting. UI still 「等待客體畫面（Present）…」; Surface black. Boss: 95%.

**Fix:**
- Overlay `guestPresented` clears 「等待客體」after first Present ok (AHB/BGRA/stub-fallback)
- `PresentXFramePump`: waitAttach gate; largest mapped child (recursive); GPUImage promote;
  pathStats (ahb/bgra/stub/ok/fail); stub-fallback when empty after attach
- Session `PRESENT_DIAG` every 2s (cap 15)
- `APP_WIN=C:/App/app.exe` + `log()` via printf — no BEL in session log

**Expect:** `PRESENT_DIAG … ready=true` after attach; overlay clears; no `\a` in APP_WIN line;
desktop pixels or stub band proving Surface path.

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

# 引擎進度

## 目標（Boss · 定案 A）

- 真 x86 主線；殼 `com.wusper.x86`。
- **GpuStack**：glibc Turnip ICD（KGSL）／DXVK／Gladio GLX／**進程內 X → Present→AHB→Session Surface**。
- SoftLegacy（Xvfb XWD→Canvas）僅 `forceSoftLegacy`。

詳見 [GPU_STACK.md](GPU_STACK.md)。

## 圖形／X（0.1.9+）

| 層 | 實作 |
|----|------|
| X 協定 | **進程內** `com.winlator.xserver`（vendored LGPL-2.1 from winlator-app）＋`libwinlator.so` |
| Socket | `files/engine/imagefs/tmp/.X11-unix/X0` bind 進 PRoot `/tmp/.X11-unix` |
| SysV SHM | 同 imagefs `tmp/.sysvshm`＋`ANDROID_SYSVSHM_SERVER` |
| Present | `PresentXFramePump`：GPUImage AHB→EGL／BGRA 單趟→Surface（砍 Java swizzle） |
| Xvfb | **GpuStack 不再使用**；僅 SoftLegacy |

## 手動驗收

1. 選 `.exe` → 日誌含 `啟動 Winlator Java X`／`INPROCESS_X_OK`（客體 gpu-stack.log）。
2. 不應再出現 GpuStack 路徑的 `X_PROTOCOL_STUB=xvfb`。
3. Surface 有幀（客體窗口或 stub 至首幀）。

## proot 可執行（0.1.16）

- 宿主 `proot` 以 `jniLibs/arm64-v8a/libproot.so` 為準（`nativeLibraryDir`，避開 filesDir `execv EACCES`）
- 落地後一律 `Os.chmod`（[ExecBits]）；安裝完成掃描 wine／box64／腳本
- 首次下載：並行＋斷點續傳＋進度條＋可取消

## rootfs /bin/sh（0.1.17）

Ubuntu Noble **usrmerge**：`bin`→`usr/bin`、`usr/bin/sh`→`dash`。解壓必須建立 symlink（舊版曾跳過 → proot `'/bin/sh' not found`）。
安裝時 `ensureUsrmergeShell` 可就地補齊；`VERSION.json` 含 `rootfsOk`／`rootfsShell`。客體 `PATH` 預設含 `/bin`／`/usr/bin`。

## proot 組合（0.1.18）

對齊 Termux／Winlator 可用鏈：
- `proot`（Termux 5.1.107）＋`libexec/proot/loader{,32}`＋`libtalloc.so.2`＋`libandroid-shmem.so`
- 部署於 `codeCacheDir/proot-bundle`；`PROOT_LOADER`／`LD_LIBRARY_PATH`／`PROOT_TMP_DIR`（可 exec）
- **禁止 strip** `libproot*.so`（靜態包內嵌 loader 被 strip → ENOSYS）

## proot 執行路徑（0.1.19）

Android `files`／`code_cache` 常 **noexec**。宿主 `libproot.so`／`libprootloader.so`／`libtalloc.so`／`libandroid-shmem.so` **只**從 `applicationInfo.nativeLibraryDir`（jniLibs）`execve`；`PROOT_TMP_DIR` 可放 `codeCacheDir`。
- **LD_LIBRARY_PATH**：啟動 `libproot.so` 時必須含 `nativeLibraryDir`（勿被客體 Gladio／Turnip 路徑覆寫）；
  `DT_RUNPATH=$ORIGIN` 作保底。客體 `LD_LIBRARY_PATH` 由 `wine-launch.sh` 設定。

## rootfs shell／ld-linux（0.1.21）

真機 `proot error: execve("/usr/bin/sh"): No such file or directory` 常見 **不是** sh 本體缺檔，
而是 **PT_INTERP** `/lib/ld-linux-aarch64.so.1` 在 rootfs 內不可解析（usrmerge `lib`→`usr/lib` 未建、
或 `ld-linux` symlink／實體缺失）——Linux／proot 對缺 interpreter 同樣回 ENOENT。

- `ensureUsrmergeShell`：補 `bin`／`lib`／`sh→dash`，並保證 `lib/ld-linux-aarch64.so.1`（symlink 失敗則 copy）
- `hasValidRootfs`：必須有可解析 shell ELF **且** 有 ld-linux；不接受 dangling symlink
- 啟動 argv 優先 `/usr/bin/dash`（真實 ELF），不再只靠 `/bin/sh` 字面

## box64 glibc（0.1.22）

客體 `/usr/local/bin/box64` 必須是 **glibc**（`libc.so.6`＋`/lib/ld-linux-aarch64.so.1`）。
The412Banner Nightlies `Box64-*-Bionic/Native.wcp` 皆為 bionic（`/system/bin/linker64`），
在 PRoot Ubuntu 內 `exec` → dash `box64: not found`（缺 interpreter 的 ENOENT）。

改用 `runtime-v1/Box64-0.4.4-glibc.wcp`（Winlator 0.4.4 衍生，patchelf INTERP）；
`isGlibcBox64` 校驗；`runtimeId` bump 強制重裝。

## 解壓 symlink／ELOOP（0.1.25）

真機「安裝失敗：Too many symbolic links encountered」：
舊 `extractTarXz` 用 `File.canonicalFile` 做輸出路徑。若 rootfs 已有 usrmerge（`bin`→`usr/bin`），
再解壓 entry `bin`→`usr/bin` 時會把輸出解析成 **`usr/bin` 本身**，刪目錄後建成自指環，後續 I/O 全 ELOOP。

修復：
- 解壓改 **lexical** `normalize`（不跟隨 symlink）
- `bin`／`lib`／`sbin` 只刪連結自身（`deleteSelfNoFollow`）
- 解 rootfs 前 `deleteRootfsSafely`（先拆 usrmerge）
- 拒絕自指 symlink；ELOOP 時清 rootfs 並提示重裝運行時

## Wine ntdll.so／Box64 x64lib（0.1.26）

Kron4ek amd64 Wine 的 `bin/wine` stub 會 `dlopen(.../lib/wine/x86_64-unix/ntdll.so)`。
Box64 以**模擬**方式載入該 x86_64 `.so`，並解析其 `NEEDED`（含 **`libgcc_s.so.1`**）。
`libgcc_s` **不是** Box64 wrapped 庫，必須提供 x86_64 實體於
`/usr/lib/box64-x86_64-linux-gnu/`（組件 `box64-x64lib`），並設定
`BOX64_LD_LIBRARY_PATH`（與宿主 aarch64 `LD_LIBRARY_PATH` 分開）。

缺 x64lib 時錯誤形如：`wine: could not load ntdll.so` + Box64 `Cannot dlopen("…ntdll.so"/…, 2)`
（此處 `2` 是 `RTLD_NOW` flag，不是 errno）。


## Box64 AVX／SIGILL（0.1.27）

ntdll 可載入後若立刻 `Illegal instruction`、客體退出碼 **132**（128+4＝SIGILL）：
常見根因是預設 `BOX64_AVX=1` 讓客體 Wine／glibc 走 AVX 路徑，Box64 dynarec 在部分 SoC
產生**宿主**非法指令。Winlator 7.1+ 亦預設關閉 AVX。

修復：`Box64Preset.performance` 與 `wine-launch.sh` **強制 `BOX64_AVX=0`**，並略收斂
`STRONGMEM`／`BIGBLOCK`；啟動時 `ensureWineLaunchScript` 會覆寫腳本，**通常只需更新 APK 重開**。

## Box64 NOHOSTEXT／SIGILL（0.1.28）

0.1.27 僅關 `BOX64_AVX` 仍 SIGILL。根因更可能是 **dynarec 依 getauxval(HWCAP) 發射宿主擴展指令**
（LSE atomics／FLAGM／AES…）；在 PRoot 或部分 SoC 上若 HWCAP 與實際可執行指令不一致，
會得到宿主 `Illegal instruction`（exit 132），且錯誤出現在 ntdll 可載入之後。

修復：
- `BOX64_DYNAREC_NOHOSTEXT=1`（只用 ASIMD）
- 客體 ISA 廣告全關：`AVX=0 SSE42=0 AES=0 SHAEXT=0 PCLMULQDQ=0`
- `SAFEFLAGS=2 CALLRET=0 BIGBLOCK=0 NATIVEFLAGS=0`
- `wine-launch.sh` 把診斷打到 **stdout**（session 可見）並做 `BOX64_SELFTEST`

## Box64 interpreter 預設（0.1.29）

找茬 R1：即便 `NOHOSTEXT=1`／AVX=0，dynarec 仍可能宿主 SIGILL。Wine／PE 預設改
`BOX64_DYNAREC=0`（interpreter）先過冒煙；通過後再切回 `dynarecSafe`。
「重裝運行時」對 SIGILL **通常無效**；看日誌 `BOX64_SELFTEST`／`BOX64_FLAGS`／`WINE_START`。

## Wine SIGILL under interpreter／x64lib bookworm（0.1.30）

0.1.29 已證：`BOX64_SELFTEST=0`、`DYNAREC=0`、`NOHOSTEXT=1`、ISA 廣告全關，載入
`ntdll.so`＋`libgcc_s.so.1` 後仍立刻 `Illegal instruction`（exit 132）。**dynarec 發射非法
宿主指令**假說已否證；故障在**模擬中的 Wine 早期／x64lib**（或子行程未回 Box64）。

修復：
- 換 **Debian bookworm `libgcc_s.so.1`（gcc-12）** 取代上游 x64lib 內 gcc-15 版；stamp
  `.x64lib-stamp`；缺 stamp 會再拉 `box64-x64lib`
- 客體內寫入 **aarch64 `/usr/local/bin/wine`／`wineserver` 包裝**（`exec box64 $REAL`），
  `WINELOADER` 指包裝；`WINELOADERNOEXEC=1`
- 啟動階梯：`box64 -v` → **`wine --version` smoke** → 再 `explorer /desktop=…`
- 補 `CPUTYPE=0`／`X87DOUBLE=1`／`WAIT=1`／`ALIGNED_ATOMICS=1`／`NORCFILES=1`／`X11GLX=1`

請**更新 APK 後重裝運行時**（換 x64lib）；日誌必看 `BOX64_X64LIB_STAMP`、
`WINE_VERSION_SMOKE`。

## wine --version exit 1／WINELOADER ELF（0.1.31）

0.1.30 把 `WINELOADER` 設成 aarch64 **shell 包裝**（`/usr/local/bin/wine`）。Wine 會依
`WINELOADER` 拼 `%s-preloader` → `/usr/local/bin/wine-preloader`（不存在）→
`could not exec the wine loader` → **exit 1**。SIGILL 已過，但冒煙／explorer 仍失敗。
**與 Android「存取所有檔案」無關。**

修復：
- `WINELOADER=/opt/wine/bin/wine`（x86_64 ELF）
- PATH 上保留包裝給 `execvp("wine"|"wineserver")`
- `/opt/wine/bin/wine-preloader` → `x86_64-unix/wine-preloader` symlink
- `WINE_VERSION_SMOKE` 完整輸出進 session；有 `wine-x.y` 字樣即過；否則硬停 exit 1

更新 APK 後**直接重開**即可（腳本由 ensureWineLaunchScript 覆寫；x64lib 未變通常不必重裝運行時）。

## c0000018 shared user data／unix wine（0.1.32）

`err:virtual:virtual_alloc_first_thread_data wine: failed to map the shared user data: c0000018`
（STATUS_CONFLICTING_ADDRESSES）：Box64 對 Wine 會 `mmap` 預留 `0x7f000000–0x82000000`（含
`0x7ffe0000` KUSER_SHARED_DATA），並把清單寫入主 ELF 的 **`wine_main_preload_info`**。
Kron4ek **`bin/wine` stub 沒有此符號**；只有 **`lib/wine/x86_64-unix/wine`**（與
`wine-preloader`）有。結果預留成功但 Wine 不知情 → 映射衝突。

修復：`WINE_REAL`／`WINELOADER`＝`/opt/wine/lib/wine/x86_64-unix/wine`。
更新 APK 後**直接重開**（不必重裝運行時）。

## Session log UX（0.1.32）

- **複製 log**：一鍵複製完整 session＋gpu-stack 合併日誌到剪貼簿
- **跳轉關鍵**：捲動到首個關鍵行並高亮（FATAL／WINE_VERSION_SMOKE／c0000018／
  BOX64_FLAGS／SELFTEST／Illegal instruction／NTDLL／stamp…）

## 0.1.33 — unix wine 路徑必須硬寫入腳本

0.1.32 聲稱改 `WINELOADER`→`x86_64-unix/wine`，但 assets 腳本仍留 `WINE_REAL=/opt/wine/bin/wine`，
且 log 用未定義的 `$WINE_UNIX` → `ls /wine-preloader`。真機仍 c0000018。

0.1.33：**字面量** `/opt/wine/lib/wine/x86_64-unix/wine` 寫入 WINELOADER／WINE_REAL／smoke／
WINE_START；`ensureWineLaunchScript` 先刪再覆寫。蓋裝即可，不必重裝運行時。
