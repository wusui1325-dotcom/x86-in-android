#!/bin/sh
set -e
export DISPLAY=:0
BACKEND="${GRAPHICS_BACKEND:-gpu}"

W="${WINE_DESKTOP_W:-1280}"
H="${WINE_DESKTOP_H:-720}"
mkdir -p /mnt/data /tmp/.X11-unix /tmp/.sysvshm /tmp/.sound

# 同時寫 session.log（stdout／stderr → nativeExec）與 gpu-stack.log
log() {
  # printf: never interpret \a/\n in APP_WIN etc. (echo -e / busybox echo would)
  printf '%s\n' "$*"
  printf '%s\n' "$*" >> /mnt/data/gpu-stack.log
  # 0.1.91 K0c: durable APP_LOAD/WINE_START stamps — survive GRAPHICS_BACKEND wipe
  case "$*" in
    WINE_LAUNCH_STAMP*|WINE_START*|WINE_RUN_OUT*|APP_*|*decide=*|*ladder*|MAP_WAIT*|"RPCSS_PRESTART done"*|"RPCSS_PRESTART heartbeat"*|"RPCSS_PRESTART soft-continue"*)
      printf '%s\n' "$*" >> /mnt/data/app-load.stamp 2>/dev/null || true
      ;;
  esac
}

# 0.1.91 K0d: mirror critical stamps into wine-run.out as '# STAMP …'
stamp_wine_run() {
  printf '# STAMP %s\n' "$*" >> /mnt/data/wine-run.out 2>/dev/null || true
}


# 0.1.85: POSIX mountpoint check — imagefs has no awk (Boss: awk: not found @ TURNIP probe).
# /proc/mounts field2 = mountpoint (spaces as \040; our Wine paths have none).
path_is_mounted() {
  _pim_s="$1"
  [ -n "$_pim_s" ] || return 1
  while read -r _pim_dev _pim_mp _pim_rest; do
    [ "$_pim_mp" = "$_pim_s" ] && { unset _pim_s _pim_dev _pim_mp _pim_rest; return 0; }
  done < /proc/mounts 2>/dev/null
  unset _pim_s _pim_dev _pim_mp _pim_rest
  return 1
}

# 0.1.98: P0 KEEP WINELOADERNOEXEC on APP_LOAD (097 SIGILL root cause).
# Dig 097: mmap32_parent=0 worked; start/cmd still SIGILL rc=132 after emulated
# ntdll.so, before PE map of start.exe. Differential: 095 cmd + NOEXEC kept →
# lived to NtCreate App; 097 explorer (kept) lives; only APP_LOAD start/cmd
# **unset WINELOADERNOEXEC** dies. Knives: (1) DELETE unset WINELOADERNOEXEC in
# wow64_direct / wow64_unix_seed — inherit global NOEXEC=1; stamp noexec=kept;
# (2) primary back to 095 `wine cmd //c C:/App/app.exe` (decide=direct_cmd64);
# ladder-2 = start //exec //wait with NOEXEC kept; (3) keep mmap32_parent=0 —
# NEVER parent export BOX64_MMAP32=1; NEVER i386 MAIN; (4) keep BinaryType_host +
# ntcreate_diag + C:/App forward-slash + RPCSS ready. Child-only MMAP32 /
# Wow64 machine 0x014c (A2) deferred if non-trivial. DXVK x32 defer.
# 0.1.97: P0 MMAP32 parent rollback — 096 APP_LOAD exported BOX64_MMAP32=1 onto
# 64-bit wine/start/cmd → parent SIGILL rc=132 before NtCreateUserProcess(App).
# Knives: (1) parent keeps global BOX64_MMAP32=0 (remove APP_LOAD export);
# (2) stamp mmap32_parent=0. 097 still unset NOEXEC — fixed in 098.
# Keep 096 path/diag; DXVK x32 defer.
# 0.1.96: P0 NtCreateUserProcess i386 fail. Knives later rolled: MMAP32 parent
# export REMOVED (097); unset WINELOADERNOEXEC REMOVED (098). Keep C:/App,
# BinaryType_host + ntcreate_diag; never i386 MAIN; DXVK x32 defer.
# 0.1.94: P0 unix-seed MAIN + unset WINELOADERNOEXEC (stop Wine→start.exe rewrite);
# inject .exe assoc (H4); RPCSS treat STATE RUNNING in wine-rpcss.out as ready=1;
# fail_hr drop section-flag false positives (c0000040/c0000080). Keep 093 knives.
# 0.1.93: P0 bare wine CreateProcess (drop start /exec primary); RPCSS wait sc
# RUNNING (no early TERM on /proc appear); fail_hr no tid scrap; overlay after Loaded
# 0.1.92: P0 orphan-reap whitelist (never kill services/rpcss); fail_sig per-mode
# wine-run slice + real fail_hr; wow64_direct = start /exec (CreateProcess, not
# ShellExecute); P1 RPCSS wall compress. Keep 091 K0–K3 / 090 A1/A3; DXVK x32 defer.
# 0.1.91: K0–K3 prove APP_LOAD — durable app-load.stamp; early wine-run clear;
# orphan start.exe reap; seed gate refuse blind wow64_start; RPCSS heartbeat+done
# soft-continue @t>=30 so WINE_START always reached. Keep 090 A1/A3; DXVK x32 defer.
# 0.1.90: A1+A3+A2 APP_LOAD — (A3) hang-alive ladder: kill stuck APP_PID + fail_sig/
# no_Loaded timed direct→explorer→start; APP_RELAUNCH reaps; DO_HB 8/12/15.
# (A1) never demote APP_WOW64=0 on visible=no; force dosdevices/c: + seed re-cp;
# winepath timeout 2s. (A2) expand SysWOW64 pe32 (CRT/d3dcompiler/vulkan + pe64 extras).
# DXVK x32 still defer (not Loaded). Keep 089 direct-first / 088–070.
# 0.1.89: WoW64 direct-first — i386 default wow64_direct; ladder direct→explorer→start;
# APP_SEED visible=; ShellExecute HRESULT dump. DXVK x32 defer.
# 0.1.88: InProcessX root GPUImage cpuAccess=false (Java; stamp bump). Keep 087 WoW64.
# 0.1.87: WoW64 i386 APP_LOAD — Boss 0.1.85: Antigravity PE machine=0x014c (i386) under
# win64; unix absolute `wine /mnt/app/app.exe` → ShellExecuteEx/missing-Loaded (app never
# starts; Present desktopOnly/guestBlack). Fix: fingerprint 014c/14c → wine start
# C:/App/app.exe (WoW64); 8664 keeps unix ladder. ensure_wow64_seed (wow64*.dll→sys32 +
# core i386 PE→SysWOW64). Fix od `014c` vs case `14c` false WARN. InProcessX defer 088+.
# Keep 086 vulkan seed / 085 awk+forceGpu / 084–070.
# 0.1.86: vulkan-1 + winevulkan seed — pe_dir ships both arches; PREFIX_EXEC seed_list
# + DXVK reseed omit them. DXVK LoadLibrary(vulkan-1.dll) → winevulkan; seed-copy
# prefix needs file-visible PE in system32 (same class as 080 CRT / 084 d3dcompiler).
# Log APP_VULKAN_SEED; DXVK_DIAG also checks vulkan-1 (+ winevulkan). Keep 085 awk
# POSIX + forceGpu / 084 d3dcompiler / 083 Present / 082 sync / 081–070.
# 0.1.85: (1) P0 awk→POSIX — imagefs has no awk; set -e + TURNIP probe `ls|awk` → exit 127
# → UI「客體命令不存在」; APP_LOAD/d3dcompiler/Present never ran. Kill all awk → wc /
# while-read /proc/mounts (path_is_mounted, wipe bind). No busybox awk / no install.
# (2) Present forceGpu when ahbCpuAccess=0 (083 residual stale software data). Log
# reason=ahbCpuAccess0. Not Present CPU-first flip. Keep 084–070 knives.
# 0.1.84: d3dcompiler seed — d3dcompiler_47/46/43 (+33–42) into system32 + SysWOW64
# (pe_dir has them; PREFIX_EXEC/DXVK reseed omit). Log APP_D3DCOMPILER_SEED. Miss →
# shader compile fail / MessageBox / black after HWND. Keep 083 Present cpuAccess /
# 082 syncInterval / 081 msgbox / 080 CRT / 079 CUI / 078 sticky / 076 TU_GMEM.
# 0.1.83: PresentExtension GPUImage cpuAccess=false (match DRI3 bufferFromPixmap).
# First-attach race: Present selectInput often before DRI3; createOrObtain keeps first
# GPUImage — cpuAccess=true empty AHB → guestBlack (0.1.70 pump warning). Host Java fix;
# stamp for KEY_LOG. Keep 082 syncInterval / 081 msgbox / 080 CRT / 079 CUI / 078 sticky / 076 TU_GMEM.
# 0.1.82: DXGI syncInterval — seed dxgi.syncInterval=0 + d3d9.presentInterval=0
# (DXVK 2.7.1 dxvk.conf; unused before). Default VSync=1 can stall forever on virtual/
# mobile WSI → HWND mapped + Present ok but guest black. X86_DXGI_SYNC=n (default 0);
# X86_DXVK_CONF=keep leaves file. Keep 081 msgbox / 080 CRT / 079 CUI / 078 sticky / 076 TU_GMEM.
# 0.1.81: MessageBox / title — WINEDEBUG +msgbox; classify msgbox:; host dialog-like
# sticky (#32770 / Type.DIALOG); Present prefer dialog; AppActivate Error/Warning/錯誤/警告
# nudge; APP_MSGBOX_DETECT source=wine|x11. Keep 080 CRT / 079 CUI / 078 sticky.
# 0.1.80: VS CRT seed — msvcp140/vcruntime140/concrt140/msvcp120/… into system32 +
# SysWOW64; after CREATEWINDOW_MISS missing_dll → APP_CRT_SEED + one relaunch; log
# APP_CRT_SIG. Keep 079 CUI/classify / 078 sticky/nohwnd / 077 fingerprint / 076 TU_GMEM.
# 0.1.79: CUI / CreateWindow — seed wineconsole.exe; PE subsystem=3 →
# wineconsole --backend=user (HWND); after nohwnd scan=none classify + one CUI relaunch;
# APP_CREATEWINDOW_MISS reason=. Keep 078 sticky/nohwnd / 077 fingerprint / 076 TU_GMEM.
# 0.1.78: Loaded-but-no-HWND — sticky ready marker (desk cannot wipe app hwnd);
# guest .x86-nohwnd-req → host force map+raise + nohwnd_scan=; APP_HWND_WAIT backoff;
# desk-steal-block. Keep 077 fingerprint / 076 Smart TU_GMEM / 075 map+raise.
# 0.1.77: APP_LOAD fingerprint — strict Loaded (no false path_seen); PE subsystem=
# (2=GUI/3=CUI); APP_HWND_WAIT after load; Loaded-but-no-HWND WARN. Host bringToFront→raise.
# Keep 076 Smart TU_GMEM / 075 map+raise.
# 0.1.76: Smart TU_GMEM — auto gmem on Adreno 710/715/720/725 (/sys + X86_ADRENO_MODEL);
# X86_TU_GMEM=0 force-off / =1 force-on / unset|auto=probe; log TURNIP_GMEM=auto|on|off.
# Fix: GpuStack no longer hardcodes TU_DEBUG (074 X86_TU_GMEM was dead). Keep 075 map+raise.
# 0.1.75: map+raise before CreateSwapChain — host raiseWindow + MAP_WAIT .x86-x11-ready
# before APP_LOAD; Present VIEWABLE gate + mapState/mapWait DIAG; dxvk maxFrameLatency=1
# + numBackBuffers=3. Keep 074 HWND/FS knobs.
# 0.1.74: winex11 HWND/FS — UseXRandR/UseXVidMode/DXGrab=N ScreenDepth=32 Decorated=N;
# Present prefer _NET_WM_HWND; dxvk dialogMode; optional X86_TU_GMEM (superseded by 076 auto).
# 0.1.73: Turnip queue — TU_DEBUG noconform,noubwc,sysmem + Mesa shader cache under /mnt/data;
# soft KGSL/dma_heap bind (LaunchPipeline). Keep 0.1.72 dxvk.conf/ESYNC.
# 0.1.72: DXGI Present — dxvk.conf deferSurfaceCreation + Direct3D/X11 reg + WINEESYNC.
# 0.1.71: Turnip ICD library_path guest-absolute + DllOverrides reg + TURNIP_DLOPEN probe.
# 0.1.70: Present forceGpu ahb_gpu (no-CPU AHB / guestBlack sticky); keep 0.1.69 HUD sample + DXVK_DIAG.
# 0.1.68: DXVK_LOG/HUD/cache + DXVK_DIAG (ICD/dll/kgsl) + guestBlack Present DIAG;
# optional X86_DXWRAPPER=wined3d bisect (default stays DXVK). Soft WARN if no KGSL.
# 0.1.67: APP_LOAD start /unix retry + PE/noexec WARN; Present prefer app over explorer desktop.
# 0.1.66: Present ahb CPU-first default (X86_PRESENT_GPU=1 for EGL); APP_LOAD unix harden (no C:/ fallback).
# 0.1.65: APP_LOAD unix path (no C:/ → start.exe/ShellExecute); Present CPU-first (no EGL steal).
# 0.1.64: explorer desktop alone then APP_LOAD; APP_RELAUNCH; no fake Present.
# 0.1.63: Present→Surface diag + overlay clear; APP_WIN=C:/App/app.exe (no BEL in logs).
# 0.1.62: launch PE via explorer/C:/App (no start.exe); force +ole/+rpcss/+x11drv.
# 0.1.61: RpcSs ready requires alive process + OLE/sc4 (no false sc READY).
# 0.1.60: RpcSs SCM-ready before WINE_START; soft-continue >=50KB (or >=30KB+boot markers) stable~15s; keep 0.1.59
# reg path (never stub pre-create; Wine Version 2 UTF-8).
# 0.1.59: NEVER pre-create Wine regs; Wine 11 needs "WINE REGISTRY Version 2"
# (UTF-8 text). 0.1.58 UTF-8 stubs used wrong header "WINE REGISTRY File" →
# "not a valid registry file" → PREFIX_32BIT. Detect/purge invalid → wineboot
# writes real regs → then offline UTF-8 patch RpcSs/MountMgr/#arch.
# 0.1.58: WINEARCH heal attempt. 0.1.57: offline RpcSs. 0.1.52: seed-copy.
WINE_LAUNCH_STAMP=0.1.98
log "WINE_LAUNCH_STAMP=$WINE_LAUNCH_STAMP"
# 0.1.91 K1a: clear stale wine-run / durable stamp at script entry (before WINE_START)
: > /mnt/data/wine-run.out
: > /mnt/data/app-load.stamp
log "WINE_RUN_OUT cleared stamp=$WINE_LAUNCH_STAMP (drop stale start.exe/ShellExecute)"
log "APP_LOAD_STAMP cleared path=/mnt/data/app-load.stamp"
RPCSS_DONE_LOGGED=0
WINE_START_REACHED=0
trap 'if [ "${RPCSS_DONE_LOGGED:-0}" != "1" ]; then printf "%s\n" "RPCSS_PRESTART done ready=0 reason=trap/abort" >> /mnt/data/gpu-stack.log 2>/dev/null; printf "%s\n" "RPCSS_PRESTART done ready=0 reason=trap/abort" >> /mnt/data/app-load.stamp 2>/dev/null; fi; if [ "${WINE_START_REACHED:-0}" != "1" ]; then printf "%s\n" "WINE_START abort reason=signal/exit-before-start" >> /mnt/data/app-load.stamp 2>/dev/null; fi' EXIT

# Wine registry helper (UTF-8 "WINE REGISTRY Version 2") — never invent stubs
WINE_REG_HELPER=""
for _cand in \
  "${WINE_REG_HELPER_PATH:-}" \
  /usr/local/bin/wine-reg-helper.py \
  "$(dirname "$0")/wine-reg-helper.py" \
  /mnt/data/engine/wine-reg-helper.py \
  /home/xuser/wine-reg-helper.py
 do
  [ -n "$_cand" ] && [ -f "$_cand" ] && WINE_REG_HELPER="$_cand" && break
done
wine_reg_helper() {
  # usage: wine_reg_helper <cmd> [extra…] — always targets $WINEPREFIX as argv2
  # Prefer /usr/local/bin/wine-reg-helper.py (installed with wine-launch.sh)
  if [ -z "$WINE_REG_HELPER" ] || [ ! -f "$WINE_REG_HELPER" ]; then
    _self=$(readlink -f "$0" 2>/dev/null || echo "")
    if [ -n "$_self" ] && [ -f "$(dirname "$_self")/wine-reg-helper.py" ]; then
      WINE_REG_HELPER="$(dirname "$_self")/wine-reg-helper.py"
    fi
    unset _self
  fi
  if [ -z "$WINE_REG_HELPER" ] || [ ! -f "$WINE_REG_HELPER" ]; then
    log "FATAL: wine-reg-helper.py missing"
    return 127
  fi
  _cmd="$1"; shift
  python3 "$WINE_REG_HELPER" "$_cmd" "$WINEPREFIX" "$@"
  _rc=$?
  unset _cmd
  return $_rc
}


export WINEDLLOVERRIDES="${WINEDLLOVERRIDES:-winepulse.drv=d}"
if [ ! -f /opt/wine/share/wine/mono/wine-mono-10.1.0-x86.msi ] && \
   [ ! -d /opt/wine/share/wine/mono/wine-mono-10.1.0 ]; then
  export WINEDLLOVERRIDES="mscoree,mshtml=;$WINEDLLOVERRIDES"
fi

# 0.1.68: DXVK observability + optional wined3d bisect (X86_DXWRAPPER=wined3d)
# Default wrapper=dxvk. wined3d clears d3d* native so Wine builtin paints (align Winlator factory).
case "${X86_DXWRAPPER:-dxvk}" in
  wined3d|WINE_D3D|builtin)
    log "DXVK_DIAG wrapper=wined3d — d3d* → builtin (bisect; DXVK HUD/log cleared)"
    # Force builtin D3D; keep audio overrides if present
    _audio_ov=""
    case ";$WINEDLLOVERRIDES;" in
      *winealsa.drv=b*) _audio_ov="winepulse.drv=d;winealsa.drv=b" ;;
      *) _audio_ov="winepulse.drv=d" ;;
    esac
    case ";$WINEDLLOVERRIDES;" in
      *mscoree,mshtml=*) _audio_ov="mscoree,mshtml=;$_audio_ov" ;;
    esac
    export WINEDLLOVERRIDES="d3d8,d3d9,d3d10core,d3d11,dxgi=b;$_audio_ov"
    unset DXVK_HUD DXVK_LOG_LEVEL DXVK_STATE_CACHE_PATH DXVK_CONFIG_FILE 2>/dev/null || true
    unset _audio_ov
    ;;
  *)
    # Ensure native DXVK overrides present even if guestEnv omitted them
    case ";$WINEDLLOVERRIDES;" in
      *d3d11=n*|*d3d11,*=n*|*d3d8,d3d9,d3d10core,d3d11,dxgi=n*) ;;
      *)
        export WINEDLLOVERRIDES="d3d8,d3d9,d3d10core,d3d11,dxgi=n,b;$WINEDLLOVERRIDES"
        ;;
    esac
    mkdir -p "${DXVK_STATE_CACHE_PATH:-/mnt/data/dxvk-cache}"
    # X86_DXVK_HUD passthrough: 0/off → unset HUD; else keep/default devinfo,fps (eye);
    # Present isNearlyBlack excludes top64+BL (hud_sample_policy=exclude_top64).
    case "${X86_DXVK_HUD:-}" in
      0|off|OFF|false|FALSE) unset DXVK_HUD 2>/dev/null || true ;;
      "")
        export DXVK_HUD="${DXVK_HUD:-devinfo,fps}"
        ;;
      *)
        export DXVK_HUD="$X86_DXVK_HUD"
        ;;
    esac
    case "${X86_DXVK_LOG:-}" in
      debug|DEBUG) export DXVK_LOG_LEVEL=debug ;;
      *) export DXVK_LOG_LEVEL="${DXVK_LOG_LEVEL:-info}" ;;
    esac
    export DXVK_STATE_CACHE_PATH="${DXVK_STATE_CACHE_PATH:-/mnt/data/dxvk-cache}"
    # 0.1.72: seed dxvk.conf — defer VkSurface until first Present (post-create black)
    _dxvk_conf="${DXVK_CONFIG_FILE:-/mnt/data/dxvk.conf}"
    mkdir -p "$(dirname "$_dxvk_conf")"
    if [ "${X86_DXVK_CONF:-}" = "keep" ] && [ -f "$_dxvk_conf" ]; then
      log "DXVK_CONFIG keep existing $_dxvk_conf"
    else
      # 0.1.82: X86_DXGI_SYNC=n (default 0) — disable VSync wait on mobile/virtual WSI
      _sync="${X86_DXGI_SYNC:-0}"
      case "$_sync" in
        ''|*[!0-9]*) _sync=0 ;;
      esac
      cat > "$_dxvk_conf" <<DXVKEOF
# x86-in-android 0.1.82 — managed (set X86_DXVK_CONF=keep to preserve edits)
# Defer VkSurface until IDXGISwapChain::Present (window may stay black otherwise)
dxgi.deferSurfaceCreation = True
d3d9.deferSurfaceCreation = True
# Cap reported VRAM — Turnip uncapped/odd heaps confuse some titles
dxgi.maxDeviceMemory = 2048
# Prefer fifo; tearFree=True → mailbox often unsupported on mobile WSI
dxvk.tearFree = False
# 0.1.74: avoid exclusive-ish D3D8/9 paths; FSE is Windows-only (explicit off)
d3d9.enableDialogMode = True
dxvk.allowFse = False
# 0.1.75: timing — fewer queued frames into settling HWND; stable WSI image count
dxgi.maxFrameLatency = 1
d3d9.maxFrameLatency = 1
dxgi.numBackBuffers = 3
d3d9.numBackBuffers = 3
# 0.1.82: DXVK 2.7.1 — force syncInterval=0 (disable VSync). Default -1/1 can stall
# forever waiting for a refresh that never arrives on virtual/mobile WSI (HWND black).
# Override: X86_DXGI_SYNC=n
dxgi.syncInterval = ${_sync}
d3d9.presentInterval = ${_sync}
DXVKEOF
      log "DXVK_CONFIG wrote $_dxvk_conf deferSurface=1 maxDeviceMemory=2048 tearFree=0 dialogMode=1 allowFse=0 maxFrameLatency=1 numBackBuffers=3 syncInterval=${_sync}"
      unset _sync
    fi
    export DXVK_CONFIG_FILE="$_dxvk_conf"
    log "DXVK_DIAG hud_sample_policy=exclude_top64 hud=${DXVK_HUD:-off} config=${DXVK_CONFIG_FILE:-off} (Present skips HUD band)"
    log "BISECT_HINT if guestBlack sticky after create: echo wined3d > /mnt/data/dxwrapper"
    unset _dxvk_conf
    ;;
esac

# 0.1.69 Knife #3: Vulkan loader fingerprint (Turnip vkCreate / ICD)
export VK_LOADER_DEBUG="${VK_LOADER_DEBUG:-error}"

# 0.1.72: Winlator-align esync (eventfd fences; no fsync/futex2)
export WINEESYNC="${WINEESYNC:-1}"
log "WINEESYNC=${WINEESYNC}"

# 定案 A：客體 ALSA android_aserver → 宿主 AAudio（由 GpuStack 寫入 asound.conf）
if [ -n "${ANDROID_ALSA_SERVER:-}" ]; then
  mkdir -p /tmp/.sound
  if [ ! -f /etc/asound.conf ] || ! grep -q android_aserver /etc/asound.conf 2>/dev/null; then
    printf 'pcm.!default { type android_aserver }\nctl.!default { type android_aserver }\n' > /etc/asound.conf
  fi
elif [ ! -f /etc/asound.conf ]; then
  printf 'pcm.!default { type null }\nctl.!default { type null }\n' > /etc/asound.conf
fi

if [ -d /mnt/gpu/dxvk/x64 ]; then
  mkdir -p /home/xuser/.wine/drive_c/windows/system32
  for dll in d3d8 d3d9 d3d10core d3d11 dxgi; do
    if [ -f "/mnt/gpu/dxvk/x64/${dll}.dll" ]; then
      cp -f "/mnt/gpu/dxvk/x64/${dll}.dll" /home/xuser/.wine/drive_c/windows/system32/ 2>/dev/null || true
    fi
  done
fi

# Turnip glibc ICD（KGSL）— 對齊 Winlator；非 bionic／adrenotools
# 0.1.71: rewrite ICD library_path to guest-absolute every launch.
# Bare filename needs system search path (Winlator: /usr/lib). Our bind is
# /mnt/gpu/turnip — bare name silently fails when LD_LIBRARY_PATH reshapes.
if [ -f /mnt/gpu/turnip/libvulkan_freedreno.so ]; then
  _turnip_so=/mnt/gpu/turnip/libvulkan_freedreno.so
  _turnip_icd=/mnt/gpu/turnip/freedreno_icd.json
  chmod +x "$_turnip_so" 2>/dev/null || true
  cat > "$_turnip_icd" <<'ICDEOF'
{
  "file_format_version": "1.0.0",
  "ICD": {
    "library_path": "/mnt/gpu/turnip/libvulkan_freedreno.so",
    "api_version": "1.3.278"
  }
}
ICDEOF
  export VK_ICD_FILENAMES="$_turnip_icd"
  export VK_DRIVER_FILES="$_turnip_icd"
  export LD_LIBRARY_PATH="/mnt/gpu/turnip${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  # 0.1.76: Smart TU_GMEM — wine-launch owns TU_DEBUG (GpuStack no longer hardcodes).
  # Keep noubwc+noconform. gmem on Adreno 710/715/720/725; else sysmem (073 default).
  # Explicit non-default TU_DEBUG from user/session still wins.
  _tu_default_sys="noconform,noubwc,sysmem"
  _tu_default_gmem="noconform,noubwc,gmem"
  _probe_model="${X86_ADRENO_MODEL:-}"
  if [ -z "$_probe_model" ]; then
    for _gm in \
      /sys/class/kgsl/kgsl-3d0/gpu_model \
      /sys/class/kgsl/kgsl-3d0/gpuinfo \
      /sys/devices/platform/*/kgsl/kgsl-3d0/gpu_model \
      /sys/devices/platform/*/kgsl/kgsl-3d0/gpuinfo
    do
      [ -r "$_gm" ] || continue
      _probe_model=$(tr -d '\0\n\r' < "$_gm" 2>/dev/null | head -c 120)
      [ -n "$_probe_model" ] && break
    done
    unset _gm
  fi
  _gmem_match=0
  case "$(printf '%s' "$_probe_model" | tr 'A-Z' 'a-z')" in
    *adreno*710*|*adreno*715*|*adreno*720*|*adreno*725*|*a710*|*a715*|*a720*|*a725*)
      _gmem_match=1 ;;
  esac
  # Also match bare numeric tokens in gpuinfo lines
  if [ "$_gmem_match" = "0" ]; then
    printf '%s' "$_probe_model" | grep -qiE '(^|[^0-9])(710|715|720|725)([^0-9]|$)' && _gmem_match=1 || true
  fi
  _gmem_mode="${X86_TU_GMEM:-auto}"
  _gmem_decision=sysmem
  _gmem_log=auto
  case "$_gmem_mode" in
    1|y|Y|yes|YES|true|TRUE|on|ON)
      _gmem_decision=gmem
      _gmem_log=on
      ;;
    0|n|N|no|NO|false|FALSE|off|OFF)
      _gmem_decision=sysmem
      _gmem_log=off
      ;;
    auto|AUTO|"")
      _gmem_log=auto
      if [ "$_gmem_match" = "1" ]; then
        _gmem_decision=gmem
      else
        _gmem_decision=sysmem
      fi
      ;;
    *)
      # unknown value → treat as auto
      _gmem_log=auto
      if [ "$_gmem_match" = "1" ]; then
        _gmem_decision=gmem
      else
        _gmem_decision=sysmem
      fi
      ;;
  esac
  _tu_want="$_tu_default_sys"
  [ "$_gmem_decision" = "gmem" ] && _tu_want="$_tu_default_gmem"
  # Rewrite when unset OR still the 073/GpuStack default sysmem (so auto/force can apply).
  # Any other explicit TU_DEBUG is preserved.
  if [ -z "${TU_DEBUG:-}" ] || [ "${TU_DEBUG}" = "$_tu_default_sys" ] || [ "${TU_DEBUG}" = "$_tu_default_gmem" ]; then
    export TU_DEBUG="$_tu_want"
  else
    export TU_DEBUG
    _gmem_log="keep"
    _gmem_decision="custom"
  fi
  log "TURNIP_GMEM=${_gmem_log} model=${_probe_model:-unknown} match=${_gmem_match} decision=${_gmem_decision} X86_TU_GMEM=${_gmem_mode} TU_DEBUG=${TU_DEBUG}"
  unset _tu_default_sys _tu_default_gmem _probe_model _gmem_match _gmem_mode _gmem_decision _gmem_log _tu_want
  # Writable Mesa shader cache under /mnt/data (Winlator-align; avoid PRoot /tmp noexec)
  export MESA_SHADER_CACHE_DIR="${MESA_SHADER_CACHE_DIR:-/mnt/data/mesa_shader_cache}"
  export MESA_SHADER_CACHE_DISABLE="${MESA_SHADER_CACHE_DISABLE:-false}"
  export MESA_SHADER_CACHE_MAX_SIZE="${MESA_SHADER_CACHE_MAX_SIZE:-512MB}"
  export XDG_CACHE_HOME="${XDG_CACHE_HOME:-/mnt/data/xdg-cache}"
  mkdir -p "$MESA_SHADER_CACHE_DIR" "$XDG_CACHE_HOME"
  log "TURNIP_ICD library_path=/mnt/gpu/turnip/libvulkan_freedreno.so (guest-absolute; 0.1.71)"
  _kgsl_open=no
  if [ -e /dev/kgsl-3d0 ] && [ -r /dev/kgsl-3d0 ]; then _kgsl_open=yes; fi
  _dma=no
  [ -e /dev/dma_heap/system ] && _dma=system
  [ -e /dev/dma_heap/system-uncached ] && _dma="${_dma}+uncached"
  [ "$_dma" = "no" ] || true
  log "TURNIP_QUEUE TU_DEBUG=${TU_DEBUG} mesa_cache=${MESA_SHADER_CACHE_DIR} disable=${MESA_SHADER_CACHE_DISABLE} max=${MESA_SHADER_CACHE_MAX_SIZE} kgsl_open=${_kgsl_open} dma_heap=${_dma}"
  unset _kgsl_open _dma
  # Early dlopen probe — fail-loud for missing NEEDED deps; do NOT hard-block Session
  if command -v python3 >/dev/null 2>&1; then
    if python3 -c "import ctypes; ctypes.CDLL('/mnt/gpu/turnip/libvulkan_freedreno.so')" >/mnt/data/turnip-dlopen.out 2>&1; then
      log "TURNIP_DLOPEN=ok so=/mnt/gpu/turnip/libvulkan_freedreno.so"
    else
      log "TURNIP_DLOPEN=FAIL so=/mnt/gpu/turnip/libvulkan_freedreno.so (see /mnt/data/turnip-dlopen.out) — vkCreate will likely fail"
      if [ -f /mnt/data/turnip-dlopen.out ]; then
        while read -r _dl; do log "TURNIP_DLOPEN_ERR $_dl"; done < /mnt/data/turnip-dlopen.out || true
      fi
    fi
  else
    log "TURNIP_DLOPEN=skip (no python3)"
  fi
  unset _turnip_so _turnip_icd _dl
elif [ -f /mnt/gpu/turnip/freedreno_icd.json ]; then
  log "WARN: TURNIP_ICD json present but libvulkan_freedreno.so MISSING"
fi

export ANDROID_SYSVSHM_SERVER="${ANDROID_SYSVSHM_SERVER:-/tmp/.sysvshm/SM0}"

XPID=
IPID=

if [ "$BACKEND" = "soft" ]; then
  # SoftLegacy only
  export LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-1}"
  export GALLIUM_DRIVER="${GALLIUM_DRIVER:-llvmpipe}"
  mkdir -p /mnt/data/xfb
  if [ -x /usr/bin/Xvfb ] || command -v Xvfb >/dev/null 2>&1; then
    Xvfb :0 -screen 0 ${W}x${H}x24 -ac -nolisten tcp -fbdir /mnt/data/xfb >/mnt/data/xvfb.log 2>&1 &
    XPID=$!
    i=0
    while [ $i -lt 80 ]; do
      if [ -S /tmp/.X11-unix/X0 ] || [ -e /mnt/data/xfb/Xvfb_screen0 ]; then break; fi
      i=$((i+1)); sleep 0.1
    done
  else
    echo "FATAL: SoftLegacy needs Xvfb" >> /mnt/data/gpu-stack.log
    exit 92
  fi
else
  unset LIBGL_ALWAYS_SOFTWARE || true
  unset GALLIUM_DRIVER || true
  echo "GRAPHICS_BACKEND=gpu inprocess_x=1 present=AHB" > /mnt/data/gpu-stack.log
  if [ -n "${VK_ICD_FILENAMES:-}" ]; then
    echo "TURNIP_ICD=${VK_ICD_FILENAMES}" >> /mnt/data/gpu-stack.log
    echo "TURNIP_SO=$(ls -la /mnt/gpu/turnip/libvulkan_freedreno.so 2>&1)" >> /mnt/data/gpu-stack.log
  else
    echo "WARN: Turnip ICD not bound" >> /mnt/data/gpu-stack.log
  fi
  if [ -e /dev/kgsl-3d0 ]; then
    echo "KGSL=ok /dev/kgsl-3d0" >> /mnt/data/gpu-stack.log
  else
    echo "WARN: /dev/kgsl-3d0 missing (non-Adreno or no access)" >> /mnt/data/gpu-stack.log
  fi
  if [ -e /usr/lib/aarch64-linux-gnu/libvulkan.so.1 ]; then
    echo "VULKAN_LOADER=ok" >> /mnt/data/gpu-stack.log
  else
    echo "WARN: libvulkan.so.1 missing in rootfs (need x11-overlay)" >> /mnt/data/gpu-stack.log
  fi
  # 0.1.68 DXVK_DIAG fingerprint (ICD size / system32 dlls / kgsl / HUD)
  _icd_ok=no
  _icd_sz=0
  if [ -f /mnt/gpu/turnip/freedreno_icd.json ] && [ -f /mnt/gpu/turnip/libvulkan_freedreno.so ]; then
    _icd_ok=yes
    _icd_sz=$(wc -c < /mnt/gpu/turnip/libvulkan_freedreno.so 2>/dev/null || echo 0)
  fi
  _kgsl=no
  [ -e /dev/kgsl-3d0 ] && _kgsl=yes
  _dlls=""
  _dll_miss=0
  # 0.1.86: also fingerprint vulkan-1 (+ winevulkan) — DXVK LoadLibrary path
  for _d in d3d11 dxgi d3d9 d3d10core d3d8 vulkan-1 winevulkan; do
    _f="/home/xuser/.wine/drive_c/windows/system32/${_d}.dll"
    if [ -f "$_f" ]; then
      _sz=$(wc -c < "$_f" 2>/dev/null || echo 0)
      _dlls="${_dlls}${_d}:${_sz} "
    else
      _dlls="${_dlls}${_d}:MISSING "
      _dll_miss=$((_dll_miss + 1))
      log "WARN: DXVK dll missing system32/${_d}.dll (after seed)"
    fi
  done
  log "DXVK_DIAG icd=${_icd_ok} icd_so=${_icd_sz}B dxvk_dlls=[${_dlls}] kgsl=${_kgsl} hud=${DXVK_HUD:-off} log=${DXVK_LOG_LEVEL:-off} cache=${DXVK_STATE_CACHE_PATH:-off} wrapper=${X86_DXWRAPPER:-dxvk} overrides=${WINEDLLOVERRIDES:-}"
  log "DXVK_DIAG note: expect wine-run.out loaddll native d3d11/dxgi (not only builtin); DXVK: info lines when LOG_LEVEL=info"
  if [ "$_dll_miss" -gt 0 ] && [ "${X86_DXWRAPPER:-dxvk}" != "wined3d" ]; then
    log "WARN: DXVK_DIAG ${_dll_miss} dll(s) missing — DXVK may silently fall back"
  fi
  if [ "$_kgsl" = "no" ]; then
    log "WARN: DXVK_DIAG kgsl=no — Turnip almost certainly black on non-Adreno (soft WARN, session still allowed)"
  fi
  # 0.1.69 TURNIP_PROBE — ICD JSON + so + kgsl node (vkCreate fingerprint aid)
  _icd_json=/mnt/gpu/turnip/freedreno_icd.json
  _probe_so=MISSING
  _probe_json=MISSING
  if [ -f /mnt/gpu/turnip/libvulkan_freedreno.so ]; then
    _probe_sz=$(wc -c < /mnt/gpu/turnip/libvulkan_freedreno.so 2>/dev/null || echo 0)
    _probe_so="${_probe_sz} /mnt/gpu/turnip/libvulkan_freedreno.so"
  fi
  [ -f "$_icd_json" ] && _probe_json=$(head -c 200 "$_icd_json" 2>/dev/null | tr '\n' ' ')
  _kgsl_ls=$(ls -la /dev/kgsl-3d0 2>/dev/null || echo missing)
  log "TURNIP_PROBE so=[${_probe_so}] kgsl_node=[${_kgsl_ls}] icd_head=[${_probe_json}] VK_LOADER_DEBUG=${VK_LOADER_DEBUG:-} wrapper=${X86_DXWRAPPER:-dxvk}"
  log "TURNIP_PROBE note: grep wine-run.out for DXVK: / err:vulkan / vkCreate / Adapter"
  unset _icd_ok _icd_sz _kgsl _dlls _dll_miss _d _f _sz _icd_json _probe_so _probe_sz _probe_json _kgsl_ls
  # Gladio 客體 libGL（Wine OpenGL → GLX → 宿主 gladiorenderer）
  if [ -f /opt/gladio/lib/libGL.so.1.7.0 ] || [ -f /opt/gladio/lib/libGL.so.1 ]; then
    export LD_LIBRARY_PATH="/opt/gladio/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    echo "GLADIO_LIBGL=ok" >> /mnt/data/gpu-stack.log
  else
    echo "WARN: Gladio libGL missing (/opt/gladio/lib)" >> /mnt/data/gpu-stack.log
  fi
  # 等待宿主進程內 X（Winlator Java X）socket
  i=0
  while [ $i -lt 100 ]; do
    if [ -S /tmp/.X11-unix/X0 ] || [ -e /tmp/.X11-unix/X0 ]; then break; fi
    i=$((i+1)); sleep 0.1
  done
  if [ ! -S /tmp/.X11-unix/X0 ] && [ ! -e /tmp/.X11-unix/X0 ]; then
    echo "FATAL: in-process X socket missing (/tmp/.X11-unix/X0)" >> /mnt/data/gpu-stack.log
    exit 93
  fi
  echo "INPROCESS_X_OK wait=${i}" >> /mnt/data/gpu-stack.log
  # 等待宿主 ALSA→AAudio socket
  if [ -n "${ANDROID_ALSA_SERVER:-}" ]; then
    j=0
    while [ $j -lt 50 ]; do
      if [ -S "${ANDROID_ALSA_SERVER}" ] || [ -e "${ANDROID_ALSA_SERVER}" ]; then break; fi
      j=$((j+1)); sleep 0.1
    done
    if [ -S "${ANDROID_ALSA_SERVER}" ] || [ -e "${ANDROID_ALSA_SERVER}" ]; then
      echo "ALSA_AAUDIO_OK sock=${ANDROID_ALSA_SERVER} wait=${j}" >> /mnt/data/gpu-stack.log
    else
      echo "WARN: ALSA AAudio socket missing (${ANDROID_ALSA_SERVER})" >> /mnt/data/gpu-stack.log
    fi
    echo "X86_AUDIO=${X86_AUDIO:-unset} WINEDLLOVERRIDES=${WINEDLLOVERRIDES:-}" >> /mnt/data/gpu-stack.log
  fi
fi

# 輸入：xdotool 僅 SoftLegacy；GpuStack 由宿主 InProcessX.inject*（不啟動 agent）
if [ "$BACKEND" = "soft" ]; then
  if [ -x /usr/bin/xdotool ] || command -v xdotool >/dev/null 2>&1; then
    if [ -x /usr/local/bin/x11-input-agent.sh ]; then
      /usr/local/bin/x11-input-agent.sh >/mnt/data/x11input-agent.out 2>&1 &
      IPID=$!
    elif [ -f /mnt/data/x11-input-agent.sh ]; then
      sh /mnt/data/x11-input-agent.sh >/mnt/data/x11input-agent.out 2>&1 &
      IPID=$!
    fi
  fi
fi

# Box64：必須是 glibc 客體二進位（INTERP /lib/ld-linux-aarch64.so.1）
BOX64=
for c in \
  /usr/local/bin/box64 \
  /opt/box64/bin/box64 \
  /usr/bin/box64
do
  if [ -x "$c" ] || [ -f "$c" ]; then BOX64=$c; break; fi
done
if [ -z "$BOX64" ]; then
  log "FATAL: box64 missing (expected /usr/local/bin/box64 glibc). Reinstall runtime."
  ls -la /usr/local/bin 2>&1 | tee -a /mnt/data/gpu-stack.log || true
  exit 127
fi
log "BOX64=$BOX64 $(ls -la "$BOX64" 2>&1)"
chmod +x "$BOX64" 2>/dev/null || true
export BOX64

# 0.1.33：硬指定 x86_64-unix/wine（含 wine_main_preload_info，避 c0000018）
# 勿用 bin/wine stub；勿依賴環境變數覆寫
WINE_UNIX=/opt/wine/lib/wine/x86_64-unix
WINE_REAL=/opt/wine/lib/wine/x86_64-unix/wine
WINESERVER_REAL=/opt/wine/bin/wineserver
WINE_PRELOADER=/opt/wine/lib/wine/x86_64-unix/wine-preloader
mkdir -p /usr/local/bin /opt/wine/bin
if [ ! -f "$WINE_REAL" ]; then
  log "FATAL: unix wine missing ($WINE_REAL). Reinstall runtime."
  ls -la /opt/wine/lib/wine/x86_64-unix 2>&1 | tee -a /mnt/data/gpu-stack.log || true
  exit 127
fi
# bin/wine-preloader symlink（Wine %s-preloader 有時相對 bin/）
if [ -f "$WINE_PRELOADER" ] && [ ! -e /opt/wine/bin/wine-preloader ]; then
  ln -sf /opt/wine/lib/wine/x86_64-unix/wine-preloader /opt/wine/bin/wine-preloader 2>/dev/null || true
fi
# aarch64 包裝：一律 exec box64 → unix wine
cat > /usr/local/bin/wine << 'WRAP'
#!/bin/sh
BOX64="${BOX64:-/usr/local/bin/box64}"
REAL=/opt/wine/lib/wine/x86_64-unix/wine
exec "$BOX64" "$REAL" "$@"
WRAP
chmod +x /usr/local/bin/wine
cat > /usr/local/bin/wineserver << 'WRAP'
#!/bin/sh
BOX64="${BOX64:-/usr/local/bin/box64}"
REAL=/opt/wine/bin/wineserver
exec "$BOX64" "$REAL" "$@"
WRAP
chmod +x /usr/local/bin/wineserver
export WINE_REAL WINESERVER_REAL WINE_UNIX WINE_PRELOADER
export PATH="/usr/local/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/wine/bin"

# 自檢：box64 -v（勿無參，會印 Missing operand）
log "BOX64_SELFTEST begin"
set +e
"$BOX64" -v >/mnt/data/box64-selftest.out 2>&1
ST=$?
if [ "$ST" != "0" ]; then
  "$BOX64" --version >/mnt/data/box64-selftest.out 2>&1
  ST=$?
fi
set -e
log "BOX64_SELFTEST exit=$ST"
head -30 /mnt/data/box64-selftest.out 2>/dev/null | tee -a /mnt/data/gpu-stack.log || true
if [ "$ST" = "132" ]; then
  log "FATAL: box64 itself SIGILL (exit 132). Host CPU cannot run this Box64 build."
  exit 132
fi

if [ ! -x "$WINE_REAL" ] && [ ! -f "$WINE_REAL" ]; then
  log "FATAL: wine missing at $WINE_REAL"
  exit 127
fi

# Wine amd64 unix libs（ntdll.so）+ Box64 模擬用 x86_64 依賴（libgcc_s 等）
NTDLL=/opt/wine/lib/wine/x86_64-unix/ntdll.so
X64LIB_GCC=/usr/lib/box64-x86_64-linux-gnu/libgcc_s.so.1
X64LIB_STAMP=/usr/lib/box64-x86_64-linux-gnu/.x64lib-stamp
if [ ! -f "$NTDLL" ]; then
  log "FATAL: wine ntdll.so missing ($NTDLL). Reinstall runtime."
  ls -la /opt/wine/lib/wine/x86_64-unix 2>&1 | tee -a /mnt/data/gpu-stack.log || true
  exit 127
fi
if [ ! -f "$X64LIB_GCC" ]; then
  log "FATAL: box64 x64lib missing ($X64LIB_GCC). Reinstall runtime."
  ls -la /usr/lib/box64-x86_64-linux-gnu 2>&1 | tee -a /mnt/data/gpu-stack.log || true
  exit 127
fi
if [ -f "$X64LIB_STAMP" ]; then
  log "BOX64_X64LIB_STAMP=$(cat "$X64LIB_STAMP" 2>/dev/null)"
else
  log "WARN: x64lib stamp missing (want bookworm libgcc). Reinstall runtime recommended."
fi

# 0.1.40: Box64 wine64k stamp (align-0x100000) + hole-fit (size-outer, FATAL on fail, no 100000-1)
BOX64_WINE64K_STAMP=/usr/local/bin/.box64-wine64k-stamp
if [ ! -f "$BOX64_WINE64K_STAMP" ] || ! grep -qE 'wine64k-align-0x100000|wine64k-holefit-0x100000' "$BOX64_WINE64K_STAMP" 2>/dev/null; then
  log "FATAL: box64 wine64k-align/holefit stamp missing ($BOX64_WINE64K_STAMP). Reinstall runtime (Box64-0.4.4-glibc-wine64k)."
  exit 127
fi
log "BOX64_WINE64K_STAMP=$(cat "$BOX64_WINE64K_STAMP" 2>/dev/null)"

export BOX64_LD_LIBRARY_PATH="/opt/wine/lib/wine/x86_64-unix:/usr/lib/box64-x86_64-linux-gnu:/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu${BOX64_LD_LIBRARY_PATH:+:$BOX64_LD_LIBRARY_PATH}"
# 0.1.53: winex11.so needs aarch64 libX11/libXext via Box64 native wrap
export LD_LIBRARY_PATH="/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
# 0.1.71: keep Turnip dir on LD_LIBRARY_PATH even after aarch64 prepend (ICD is absolute,
# but NEEDED deps of libvulkan_freedreno.so still search LD_LIBRARY_PATH; also helps
# any residual bare-name ICDs). Prepend so it wins over accidental same-named host so.
if [ -f /mnt/gpu/turnip/libvulkan_freedreno.so ]; then
  case ":$LD_LIBRARY_PATH:" in
    *:/mnt/gpu/turnip:*) ;;
    *) export LD_LIBRARY_PATH="/mnt/gpu/turnip${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" ;;
  esac
fi
export BOX64_PREFER_WRAPPED="${BOX64_PREFER_WRAPPED:-1}"
export WINEDLLPATH="${WINEDLLPATH:-/opt/wine/lib/wine}"
# 關鍵：強制 WINELOADER＝unix wine ELF（覆寫 Kotlin／舊 env）
export WINELOADER=/opt/wine/lib/wine/x86_64-unix/wine
export WINE_REAL=/opt/wine/lib/wine/x86_64-unix/wine
export WINELOADERNOEXEC=1
# 0.1.40: do NOT set WINEPRELOADRESERVE=100000-1 — that 1-byte reserve occupies
# Wine's critical low page and made hole-fit think mid failed / scan forever.
# Hole-fit probes with WINEPRELOADRESERVE unset; detect mid via "prereserve done".
unset WINEPRELOADRESERVE
# Winlator 同款：關閉 MMAP32，讓 39-bit VA 下 prereserve／mmap 行為較穩
export BOX64_MMAP32=0
export WINEPREFIX="${WINEPREFIX:-/home/xuser/.wine}"
export WINEARCH="${WINEARCH:-win64}"
log "WINEARCH=$WINEARCH (force win64 for x86_64-unix wine)"
mkdir -p "$WINEPREFIX"
log "NTDLL=ok $(ls -la "$NTDLL" 2>&1)"
log "BOX64_X64LIB=ok $(ls -la "$X64LIB_GCC" 2>&1)"
log "BOX64_LD_LIBRARY_PATH=$BOX64_LD_LIBRARY_PATH"
# libxkbregistry is optional (xkbcommon); missing on rootfs is non-fatal for Wine PE
if [ ! -e /usr/lib/aarch64-linux-gnu/libxkbregistry.so.0 ] &&    [ ! -e /usr/lib/libxkbregistry.so.0 ] &&    [ ! -e /lib/aarch64-linux-gnu/libxkbregistry.so.0 ]; then
  log "NOTE: libxkbregistry.so.0 missing (non-fatal; xkbcommon optional)"
fi
log "WINELOADER=$WINELOADER WINELOADERNOEXEC=$WINELOADERNOEXEC WINE_REAL=$WINE_REAL"
log "WINEPRELOADRESERVE='${WINEPRELOADRESERVE-<unset>}' BOX64_MMAP32=$BOX64_MMAP32"
log "PRELOADER=$(ls -la /opt/wine/lib/wine/x86_64-unix/wine-preloader /opt/wine/bin/wine-preloader 2>&1)"
log "WINEPREFIX=$WINEPREFIX"

# ---- 0.1.44: REAL PE exec — ALWAYS make active WINEPREFIX system32 exec ----
# 0.1.43 probed /opt/wine/.../ntdll.dll, logged mode=probe, then wineboot mapped
# C:\windows\system32\ntdll.dll from the *prefix* copy on noexec filesDir/tmp → still
# "noexec filesystem?". /opt/wine probe OK is NOT enough.
# Fix: per-prefix marker; require PROT_EXEC on $WINEPREFIX/.../system32/ntdll.dll when
# present; always tmpfs+bind system32 (and x86_64-windows if needed) before wineboot.
probe_pe_exec() {
  local f="$1"
  [ -f "$f" ] || return 1
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$f" <<'PY'
import mmap, os, sys
path = sys.argv[1]
fd = os.open(path, os.O_RDONLY)
try:
    sz = os.fstat(fd).st_size
    if sz <= 0:
        sys.exit(1)
    length = min(65536, sz)
    m = mmap.mmap(fd, length, prot=mmap.PROT_READ | mmap.PROT_EXEC)
    m.close()
except Exception as e:
    sys.stderr.write("probe_pe_exec: %s\n" % e)
    sys.exit(1)
finally:
    os.close(fd)
sys.exit(0)
PY
    return $?
  fi
  return 1
}

# Wine-like diagnostic: file MAP_PRIVATE RW then mprotect RX (Android W^X often denies).
probe_pe_wine_wx() {
  local f="$1"
  [ -f "$f" ] || return 1
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$f" <<'PY'
import os, sys, ctypes
path = sys.argv[1]
fd = os.open(path, os.O_RDONLY)
libc = ctypes.CDLL(None, use_errno=True)
try:
    sz = os.fstat(fd).st_size
    if sz <= 0:
        sys.exit(1)
    length = min(65536, sz) & ~0xfff
    if length < 4096:
        length = min(sz, 4096)
    PROT_READ, PROT_WRITE, PROT_EXEC = 1, 2, 4
    MAP_PRIVATE = 2
    libc.mmap.restype = ctypes.c_void_p
    addr = libc.mmap(None, length, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0)
    bad = addr is None or addr == ctypes.c_void_p(-1).value or addr == 0xffffffffffffffff
    if bad:
        sys.stderr.write("probe_pe_wine_wx: mmap RW failed errno=%s\n" % ctypes.get_errno())
        sys.exit(1)
    rc = libc.mprotect(ctypes.c_void_p(addr), length, PROT_READ | PROT_EXEC)
    libc.munmap(ctypes.c_void_p(addr), length)
    if rc != 0:
        sys.stderr.write("probe_pe_wine_wx: mprotect RX failed errno=%s\n" % ctypes.get_errno())
        sys.exit(1)
except Exception as e:
    sys.stderr.write("probe_pe_wine_wx: %s\n" % e)
    sys.exit(1)
finally:
    os.close(fd)
sys.exit(0)
PY
    return $?
  fi
  return 1
}

# Kron4ek wine-11.21 ntdll.so virtual_map_image @ 0x7a7a8: force removable=1 → pread anon.
# ORIG mov removable; mov ptr; test; jne pread  →  NEW movl $1; mov ptr; jmp pread; nop
patch_ntdll_anon_pe() {
  local ntdll=/opt/wine/lib/wine/x86_64-unix/ntdll.so
  local marker=/opt/wine/lib/wine/x86_64-unix/.ntdll-anon-pe-0.1.49
  local off=501672
  local orig_hex=448b6c24704d8b67204585ed0f8596000000
  local new_hex=c7442470010000004d8b6720e99700000090
  if [ ! -f "$ntdll" ]; then
    log "FATAL: NTDLL_ANON_PE missing $ntdll"
    exit 1
  fi
  if ! command -v python3 >/dev/null 2>&1; then
    log "FATAL: NTDLL_ANON_PE needs python3"
    exit 1
  fi
  local result
  result=$(python3 - "$ntdll" "$off" "$orig_hex" "$new_hex" "$marker" <<'PY'
import sys, os
from pathlib import Path
path = Path(sys.argv[1])
off = int(sys.argv[2])
orig = bytes.fromhex(sys.argv[3])
new = bytes.fromhex(sys.argv[4])
marker = Path(sys.argv[5])
data = bytearray(path.read_bytes())
chunk = bytes(data[off:off + len(orig)])
if chunk == new:
    marker.write_text("anon-pe=1 stamp=0.1.49 already\n")
    print("already")
    sys.exit(0)
if chunk != orig:
    sys.stderr.write("unexpected bytes at 0x%x got=%s\n" % (off, chunk.hex()))
    sys.exit(2)
bak = path.with_suffix(".so.pre-anon-pe")
if not bak.exists():
    bak.write_bytes(path.read_bytes())
data[off:off + len(new)] = new
tmp = path.with_suffix(".so.anon-pe-tmp")
tmp.write_bytes(data)
os.replace(str(tmp), str(path))
marker.write_text("anon-pe=1 stamp=0.1.49 off=0x%x\n" % off)
print("patched")
sys.exit(0)
PY
) || {
    log "FATAL: NTDLL_ANON_PE patch failed path=$ntdll"
    exit 1
  }
  log "NTDLL_ANON_PE ok mode=$result path=$ntdll off=0x7a7a8 (force removable/anon PE)"
  return 0
}


# Parallel directory copy (nproc workers). Falls back to cp -a.
parallel_dir_copy() {
  local src="$1" dst="$2"
  [ -d "$src" ] || return 1
  mkdir -p "$dst" || return 1
  local n
  n=$(nproc 2>/dev/null || echo 1)
  if [ "$n" -lt 1 ]; then n=1; fi
  if [ "$n" -gt 8 ]; then n=8; fi
  if [ "$n" -gt 1 ] && command -v find >/dev/null 2>&1; then
    # File-level parallel copy; create dirs first
    (cd "$src" && find . -type d -print0) | while IFS= read -r -d '' d; do
      mkdir -p "$dst/$d" 2>/dev/null || true
    done
    if (cd "$src" && find . -type f -print0) | xargs -0 -P "$n" -I{} cp -p "$src/{}" "$dst/{}" 2>/dev/null; then
      # also copy symlinks
      (cd "$src" && find . -type l -print0) | while IFS= read -r -d '' l; do
        cp -a "$src/$l" "$dst/$l" 2>/dev/null || true
      done
      return 0
    fi
  fi
  cp -a "$src"/. "$dst"/
}

# 0.1.80: seed Visual C++ redistributable PE DLLs into system32 + SysWOW64.
# pe_dir (x86_64-windows) already ships them; 079 seed_list omitted them → GUI
# CREATEWINDOW_MISS reason=missing_dll (msvcp/vcruntime) with no HWND.
ensure_vs_crt_seed() {
  local pe64=/opt/wine/lib/wine/x86_64-windows
  local pe32=/opt/wine/lib/wine/i386-windows
  local sys32="$WINEPREFIX/drive_c/windows/system32"
  local wow64="$WINEPREFIX/drive_c/windows/syswow64"
  local crt_list="msvcp140.dll msvcp140_1.dll msvcp140_2.dll vcruntime140.dll vcruntime140_1.dll
    concrt140.dll msvcp120.dll msvcr120.dll msvcp110.dll msvcr110.dll
    msvcp100.dll msvcr100.dll ucrtbase.dll msvcrt.dll"
  mkdir -p "$sys32" 2>/dev/null || true
  mkdir -p "$wow64" 2>/dev/null || true
  local n64=0 n32=0 miss="" d
  for d in $crt_list; do
    if [ -f "$pe64/$d" ]; then
      if cp -f "$pe64/$d" "$sys32/$d" 2>/dev/null; then
        n64=$((n64+1))
      else
        miss="$miss $d"
      fi
    fi
    if [ -f "$pe32/$d" ]; then
      if cp -f "$pe32/$d" "$wow64/$d" 2>/dev/null; then
        n32=$((n32+1))
      fi
    fi
  done
  # Also mirror 64-bit CRT into sys32 if pe64 missing a name but pe32 has it (rare)
  local v140=no v140_1=no m140=no c140=no
  [ -f "$sys32/vcruntime140.dll" ] && v140=yes
  [ -f "$sys32/vcruntime140_1.dll" ] && v140_1=yes
  [ -f "$sys32/msvcp140.dll" ] && m140=yes
  [ -f "$sys32/concrt140.dll" ] && c140=yes
  log "APP_CRT_SEED pe64→sys32=$n64 pe32→syswow64=$n32 vcruntime140=$v140 vcruntime140_1=$v140_1 msvcp140=$m140 concrt140=$c140 miss=${miss:-none}"
  return 0
}

# 0.1.84: seed d3dcompiler_* PE DLLs into system32 + SysWOW64.
# pe_dir ships d3dcompiler_33…47; PREFIX_EXEC seed_list + DXVK reseed omit them →
# D3D9/11 titles LoadLibrary d3dcompiler_47 (or 43/46) fail → shader compile /
# MessageBox / c0000135 / guestBlack with HWND ready.
ensure_d3dcompiler_seed() {
  local pe64=/opt/wine/lib/wine/x86_64-windows
  local pe32=/opt/wine/lib/wine/i386-windows
  local sys32="$WINEPREFIX/drive_c/windows/system32"
  local wow64="$WINEPREFIX/drive_c/windows/syswow64"
  # Prefer 47+46+43 (most titles); also seed 33–42 when present (cheap, already in pe_dir).
  # No 44/45 in Wine pe_dir — skip inventing.
  local dc_list="d3dcompiler_47.dll d3dcompiler_46.dll d3dcompiler_43.dll
    d3dcompiler_42.dll d3dcompiler_41.dll d3dcompiler_40.dll d3dcompiler_39.dll
    d3dcompiler_38.dll d3dcompiler_37.dll d3dcompiler_36.dll d3dcompiler_35.dll
    d3dcompiler_34.dll d3dcompiler_33.dll"
  mkdir -p "$sys32" 2>/dev/null || true
  mkdir -p "$wow64" 2>/dev/null || true
  local n64=0 n32=0 miss="" d
  for d in $dc_list; do
    if [ -f "$pe64/$d" ]; then
      if cp -f "$pe64/$d" "$sys32/$d" 2>/dev/null; then
        n64=$((n64+1))
      else
        miss="$miss $d"
      fi
    fi
    if [ -f "$pe32/$d" ]; then
      if cp -f "$pe32/$d" "$wow64/$d" 2>/dev/null; then
        n32=$((n32+1))
      fi
    fi
  done
  local dc47=no dc46=no dc43=no
  [ -f "$sys32/d3dcompiler_47.dll" ] && dc47=yes
  [ -f "$sys32/d3dcompiler_46.dll" ] && dc46=yes
  [ -f "$sys32/d3dcompiler_43.dll" ] && dc43=yes
  log "APP_D3DCOMPILER_SEED pe64→sys32=$n64 pe32→syswow64=$n32 dc47=$dc47 dc46=$dc46 dc43=$dc43 miss=${miss:-none}"
  return 0
}

# 0.1.86: seed vulkan-1.dll + winevulkan.dll into system32 + SysWOW64.
# pe_dir ships both arches; PREFIX_EXEC seed_list + DXVK reseed omit them →
# DXVK LoadLibrary(vulkan-1.dll) → winevulkan fails under seed-copy prefix
# (no bind-over) → vkCreate / guestBlack after HWND ready. Same class as 080/084.
ensure_vulkan_seed() {
  local pe64=/opt/wine/lib/wine/x86_64-windows
  local pe32=/opt/wine/lib/wine/i386-windows
  local sys32="$WINEPREFIX/drive_c/windows/system32"
  local wow64="$WINEPREFIX/drive_c/windows/syswow64"
  local vk_list="vulkan-1.dll winevulkan.dll"
  mkdir -p "$sys32" 2>/dev/null || true
  mkdir -p "$wow64" 2>/dev/null || true
  local n64=0 n32=0 miss="" d
  for d in $vk_list; do
    if [ -f "$pe64/$d" ]; then
      if cp -f "$pe64/$d" "$sys32/$d" 2>/dev/null; then
        n64=$((n64+1))
      else
        miss="$miss $d"
      fi
    else
      miss="$miss pe64:$d"
    fi
    if [ -f "$pe32/$d" ]; then
      if cp -f "$pe32/$d" "$wow64/$d" 2>/dev/null; then
        n32=$((n32+1))
      fi
    else
      miss="$miss pe32:$d"
    fi
  done
  local v1=no wv=no
  [ -f "$sys32/vulkan-1.dll" ] && v1=yes
  [ -f "$sys32/winevulkan.dll" ] && wv=yes
  log "APP_VULKAN_SEED pe64→sys32=$n64 pe32→syswow64=$n32 vulkan1=$v1 winevulkan=$wv miss=${miss:-none}"
  return 0
}

# 0.1.87: WoW64 thunk PE (system32) + core i386 PE (SysWOW64) for machine=0x014c apps.
# seed-copy prefix may lack wow64cpu / SysWOW64 ntdll after wineboot; without them
# `wine start C:/App/app.exe` cannot hand off to 32-bit loader.
ensure_wow64_seed() {
  local pe64=/opt/wine/lib/wine/x86_64-windows
  local pe32=/opt/wine/lib/wine/i386-windows
  local sys32="$WINEPREFIX/drive_c/windows/system32"
  local wow64="$WINEPREFIX/drive_c/windows/syswow64"
  local n64=0 n32=0
  local miss=""
  mkdir -p "$sys32" "$wow64" 2>/dev/null || true
  # 64-bit wow64 layer lives in system32
  for d in wow64.dll wow64cpu.dll wow64win.dll; do
    if [ -f "$pe64/$d" ]; then
      if cp -f "$pe64/$d" "$sys32/$d" 2>/dev/null; then
        n64=$((n64 + 1))
      else
        miss="$miss sys32:$d"
      fi
    else
      miss="$miss pe64:$d"
    fi
  done
  # Core 32-bit PE into SysWOW64 (CreateProcess + x11 + common deps)
  # 0.1.90 A2: align with pe64 seed_list extras (setupapi/shcore/wininet/…) + CRT/d3d/vulkan
  # names (ensure_*_seed also copies; idempotent here so i386 path is self-sufficient)
  for d in ntdll.dll kernel32.dll kernelbase.dll advapi32.dll bcrypt.dll sechost.dll \
           ucrtbase.dll msvcrt.dll rpcrt4.dll ws2_32.dll dnsapi.dll \
           user32.dll gdi32.dll win32u.dll imm32.dll setupapi.dll cfgmgr32.dll \
           shell32.dll shlwapi.dll shcore.dll ole32.dll oleaut32.dll combase.dll coml2.dll \
           actxprxy.dll wininet.dll mpr.dll cryptbase.dll userenv.dll \
           comctl32.dll uxtheme.dll winex11.drv winmm.dll version.dll \
           wsock32.dll iphlpapi.dll netapi32.dll secur32.dll authz.dll \
           msvcp140.dll msvcp140_1.dll msvcp140_2.dll vcruntime140.dll vcruntime140_1.dll \
           concrt140.dll msvcp120.dll msvcr120.dll msvcp110.dll msvcr110.dll \
           msvcp100.dll msvcr100.dll \
           d3dcompiler_47.dll d3dcompiler_46.dll d3dcompiler_43.dll \
           vulkan-1.dll winevulkan.dll \
           start.exe wineboot.exe wineconsole.exe cmd.exe explorer.exe; do
    if [ -f "$pe32/$d" ]; then
      if cp -f "$pe32/$d" "$wow64/$d" 2>/dev/null; then
        n32=$((n32 + 1))
      else
        miss="$miss wow64:$d"
      fi
    fi
  done
  local wcpu=no wntdll=no wk32=no
  [ -f "$sys32/wow64cpu.dll" ] && wcpu=yes
  [ -f "$wow64/ntdll.dll" ] && wntdll=yes
  [ -f "$wow64/kernel32.dll" ] && wk32=yes
  log "APP_WOW64_SEED pe64→sys32=$n64 pe32→syswow64=$n32 wow64cpu=$wcpu syswow64_ntdll=$wntdll syswow64_k32=$wk32 miss=${miss:-none}"
  return 0
}

ensure_prefix_exec() {
  # 0.1.52: NTDLL_ANON_PE already bypasses Android W^X (anon/pread). Binding a
  # scratch tree OVER $WINEPREFIX/.../system32 HIDES wineboot-created DLLs and
  # left child start.exe with c0000135 (kernel32 DLL_NOT_FOUND) while parent
  # still loaded builtins. Do NOT bind-over system32 — seed PE DLLs by copy.
  local p="${WINEPREFIX:-/home/xuser/.wine}"
  local pe_dir=/opt/wine/lib/wine/x86_64-windows
  local pe_ntdll="$pe_dir/ntdll.dll"
  local sys32="$p/drive_c/windows/system32"
  local ok_marker="$p/.prefix-exec-ok"

  ensure_dir() {
    local d="$1"
    [ -d "$d" ] && return 0
    mkdir -p "$d" 2>/dev/null || true
    [ -d "$d" ] && return 0
    local parent="${d%/*}"
    local leaf="${d##*/}"
    if [ -n "$parent" ] && [ -d "$parent" ] && [ -n "$leaf" ] && [ "$leaf" != "$d" ]; then
      ( cd "$parent" && mkdir "$leaf" ) 2>/dev/null || true
    else
      mkdir "$d" 2>/dev/null || true
    fi
    [ -d "$d" ]
  }

  # path_is_mounted: use global POSIX helper (0.1.85 — no awk)

  # Drop stale bind from 0.1.48–0.1.51 that covered real system32
  if path_is_mounted "$sys32"; then
    log "PREFIX_EXEC umount stale system32 bind path=$sys32 (0.1.52: seed-copy, no bind-over)"
    umount "$sys32" 2>/dev/null || umount -l "$sys32" 2>/dev/null || true
    if path_is_mounted "$sys32"; then
      log "WARN: PREFIX_EXEC could not umount system32 bind — wineboot may still see scratch tree"
    else
      log "PREFIX_EXEC umount system32 ok"
    fi
  fi

  ensure_dir "$p" || true
  ensure_dir "$sys32" || true

  # Remount: claimed only — never success alone
  if mount -o remount,exec,rw "$p" 2>/dev/null; then
    log "PREFIX_EXEC remount WINEPREFIX claimed (not trusted alone)"
  fi
  if mount -o remount,exec,rw "$p/drive_c" 2>/dev/null; then
    log "PREFIX_EXEC remount drive_c claimed (not trusted alone)"
  fi
  if mount -o remount,exec,rw /opt/wine 2>/dev/null; then
    log "PREFIX_EXEC remount /opt/wine claimed (not trusted alone)"
  fi
  if [ -d "$pe_dir" ] && mount -o remount,exec,rw "$pe_dir" 2>/dev/null; then
    log "PREFIX_EXEC remount x86_64-windows claimed (not trusted alone)"
  fi

  if [ ! -f "$pe_ntdll" ]; then
    log "FATAL: PREFIX_EXEC missing $pe_ntdll"
    exit 1
  fi

  # Seed critical PE DLLs into system32 (copy; never delete wineboot extras)
  # Wineboot will add more; child start.exe needs kernel32.dll visible as a file
  # under C:\windows\system32 (builtin-only was flaky after half-init + bind).
  # 0.1.53: + winex11.drv (explorer LoadLibrary) + actxprxy/oleaut32/comctl32/uxtheme
  # 0.1.54: + service PE (rpcss/svchost/plugplay) + drivers/mountmgr.sys (MountMgr err 2)
  # 0.1.80: + VS CRT redistributables (msvcp140/vcruntime140/…) — pe_dir has them;
  # 079 seed only ucrtbase/msvcrt → GUI CREATEWINDOW_MISS missing_dll.
  # 0.1.84: + d3dcompiler_47/46/43 (ensure_d3dcompiler_seed also seeds 33–42).
  # 0.1.86: + vulkan-1.dll winevulkan.dll (ensure_vulkan_seed also seeds SysWOW64).
  # 0.1.87: + wow64.dll wow64cpu.dll wow64win.dll (ensure_wow64_seed also seeds SysWOW64 i386).
  local seed_list="ntdll.dll kernel32.dll kernelbase.dll advapi32.dll bcrypt.dll
    sechost.dll ucrtbase.dll msvcrt.dll rpcrt4.dll ws2_32.dll dnsapi.dll
    user32.dll gdi32.dll win32u.dll imm32.dll setupapi.dll cfgmgr32.dll
    shell32.dll shlwapi.dll shcore.dll ole32.dll oleaut32.dll combase.dll coml2.dll
    actxprxy.dll wininet.dll mpr.dll cryptbase.dll userenv.dll
    comctl32.dll uxtheme.dll winex11.drv winewayland.drv
    winmm.dll version.dll wsock32.dll iphlpapi.dll netapi32.dll secur32.dll
    authz.dll
    msvcp140.dll msvcp140_1.dll msvcp140_2.dll vcruntime140.dll vcruntime140_1.dll
    concrt140.dll msvcp120.dll msvcr120.dll msvcp110.dll msvcr110.dll
    msvcp100.dll msvcr100.dll
    d3dcompiler_47.dll d3dcompiler_46.dll d3dcompiler_43.dll
    vulkan-1.dll winevulkan.dll
    wow64.dll wow64cpu.dll wow64win.dll"
  local seeded=0
  local skipped=0
  local d
  for d in $seed_list; do
    if [ -f "$pe_dir/$d" ]; then
      if cp -f "$pe_dir/$d" "$sys32/$d" 2>/dev/null; then
        seeded=$((seeded+1))
      else
        log "PREFIX_EXEC seed fail $d"
        skipped=$((skipped+1))
      fi
    fi
  done
  # Boot + SCM service PE exes (OpenService RpcSs needs rpcss.exe on disk for StartService;
  # MountMgr needs drivers\\mountmgr.sys — GetBinaryTypeW err 2 without it)
  # winedevice.exe hosts kernel drivers (MountMgr); without it StartService → err 2
  for d in wineboot.exe start.exe services.exe explorer.exe rundll32.exe \
           rpcss.exe svchost.exe plugplay.exe conhost.exe wineconsole.exe cmd.exe sc.exe \
           msiexec.exe reg.exe regsvr32.exe rundll32.exe lsass.exe \
           winedevice.exe ntoskrnl.exe \
           winemenubuilder.exe winebrowser.exe; do
    if [ -f "$pe_dir/$d" ]; then
      cp -f "$pe_dir/$d" "$sys32/$d" 2>/dev/null && seeded=$((seeded+1)) || true
    fi
  done
  # Drivers: %12% = system32\\drivers — MountMgr ServiceBinary
  local drv="$sys32/drivers"
  ensure_dir "$drv" || mkdir -p "$drv" 2>/dev/null || true
  local sys_seeded=0
  for d in mountmgr.sys winebus.sys winehid.sys wineusb.sys hidclass.sys hidparse.sys \
           nsiproxy.sys ndis.sys netio.sys win32k.sys cng.sys ksecdd.sys \
           dxgkrnl.sys dxgmms1.sys fltmgr.sys; do
    if [ -f "$pe_dir/$d" ]; then
      if cp -f "$pe_dir/$d" "$drv/$d" 2>/dev/null; then
        seeded=$((seeded+1)); sys_seeded=$((sys_seeded+1))
      fi
      # Also keep a copy in system32 for loaders that probe both
      cp -f "$pe_dir/$d" "$sys32/$d" 2>/dev/null || true
    fi
  done
  chmod -R u+rx "$sys32" 2>/dev/null || true

  local k32=no n32=no kb=no
  [ -f "$sys32/kernel32.dll" ] && k32=yes
  [ -f "$sys32/ntdll.dll" ] && n32=yes
  [ -f "$sys32/kernelbase.dll" ] && kb=yes
  local ndll
  ndll=$(ls "$sys32"/*.dll 2>/dev/null | wc -l | tr -d ' \n')
  ndll=${ndll:-0}
  local wx=no rpc=no mm=no
  [ -f "$sys32/winex11.drv" ] && wx=yes
  [ -f "$sys32/rpcss.exe" ] && rpc=yes
  [ -f "$drv/mountmgr.sys" ] && mm=yes
  log "PREFIX_EXEC seed pe→sys32 seeded=$seeded skip=$skipped kernel32=$k32 ntdll=$n32 kernelbase=$kb winex11=$wx rpcss=$rpc mountmgr=$mm sys_files=$sys_seeded dlls=$ndll sys32=$sys32"
  ls -l "$sys32/kernel32.dll" "$sys32/ntdll.dll" "$sys32/kernelbase.dll" "$sys32/winex11.drv" "$sys32/rpcss.exe" "$drv/mountmgr.sys" 2>&1 | while read -r line; do
    log "PREFIX_EXEC ls[seed]: $line"
  done || true

  if [ "$k32" != "yes" ] || [ "$n32" != "yes" ]; then
    log "FATAL: PREFIX_EXEC seed missing kernel32/ntdll after copy (k32=$k32 ntdll=$n32 pe_dir=$pe_dir)"
    exit 1
  fi

  # 0.1.80: also seed VS CRT into sys32 + SysWOW64 (idempotent)
  ensure_vs_crt_seed
  # 0.1.84: seed d3dcompiler_* into sys32 + SysWOW64 (idempotent)
  ensure_d3dcompiler_seed
  # 0.1.86: seed vulkan-1 + winevulkan into sys32 + SysWOW64 (idempotent)
  ensure_vulkan_seed
  # 0.1.87: wow64 thunk + core i386 SysWOW64 (idempotent; needed for 0x014c)
  ensure_wow64_seed

  # pe mmap RX probe is informational only — NTDLL_ANON_PE makes Wine use anon/pread
  local pe_state=no
  if [ -f "$pe_ntdll" ] && probe_pe_exec "$pe_ntdll"; then pe_state=yes; fi
  log "PREFIX_EXEC ok mode=seed-copy sys32=$sys32/kernel32.dll pe_probe=$pe_state (NTDLL_ANON_PE; no system32 bind)"
  echo "mode=seed-copy pe_probe=$pe_state k32=yes" > "$ok_marker"
  return 0
}

patch_ntdll_anon_pe

ensure_prefix_exec

# 自證：unix wine 必須存在
if [ ! -f /opt/wine/lib/wine/x86_64-unix/wine ]; then
  log "FATAL: /opt/wine/lib/wine/x86_64-unix/wine missing"
  exit 127
fi

# 強制安全預設（覆蓋 Kotlin env）
# 0.1.36: DYNAREC=1（dynarecSafe）。0.1.29–35 用 interpreter 擋 SIGILL，但 wine64k
# 過 granularity 後 wine --version 在 DYNAREC=0 下會活掛（Boss pid 仍在、無 exit）。
# bookworm x64lib + NOHOSTEXT 已否證「dynarec 必 SIGILL」；改回 dynarec 讓冒煙／wineserver 可結束。
export BOX64_DYNAREC_NOHOSTEXT=1
export BOX64_DYNAREC_NATIVEFLAGS=0
export BOX64_AVX=0
export BOX64_SSE42=0
export BOX64_AES=0
export BOX64_SHAEXT=0
export BOX64_PCLMULQDQ=0
export BOX64_DYNAREC="${BOX64_DYNAREC:-1}"
export BOX64_DYNAREC_STRONGMEM="${BOX64_DYNAREC_STRONGMEM:-1}"
export BOX64_DYNAREC_SAFEFLAGS="${BOX64_DYNAREC_SAFEFLAGS:-2}"
export BOX64_DYNAREC_BIGBLOCK="${BOX64_DYNAREC_BIGBLOCK:-0}"
export BOX64_DYNAREC_CALLRET="${BOX64_DYNAREC_CALLRET:-0}"
export BOX64_DYNAREC_FASTNAN="${BOX64_DYNAREC_FASTNAN:-0}"
export BOX64_DYNAREC_FASTROUND="${BOX64_DYNAREC_FASTROUND:-0}"
export BOX64_DYNAREC_X87DOUBLE="${BOX64_DYNAREC_X87DOUBLE:-1}"
export BOX64_DYNAREC_WAIT="${BOX64_DYNAREC_WAIT:-1}"
export BOX64_DYNAREC_ALIGNED_ATOMICS="${BOX64_DYNAREC_ALIGNED_ATOMICS:-1}"
export BOX64_CPUTYPE="${BOX64_CPUTYPE:-0}"
export BOX64_NORCFILES=1
export BOX64_X11GLX=1
export BOX64_LOG="${BOX64_LOG:-0}"
export BOX64_SHOWSEGV="${BOX64_SHOWSEGV:-0}"
export BOX64_SHOWBT="${BOX64_SHOWBT:-0}"
export BOX64_ROLLING_LOG="${BOX64_ROLLING_LOG:-0}"
export BOX64_NOBANNER="${BOX64_NOBANNER:-1}"
log "BOX64_FLAGS NOHOSTEXT=$BOX64_DYNAREC_NOHOSTEXT AVX=$BOX64_AVX SSE42=$BOX64_SSE42 AES=$BOX64_AES DYNAREC=$BOX64_DYNAREC SAFEFLAGS=$BOX64_DYNAREC_SAFEFLAGS CALLRET=$BOX64_DYNAREC_CALLRET BIGBLOCK=$BOX64_DYNAREC_BIGBLOCK NATIVEFLAGS=$BOX64_DYNAREC_NATIVEFLAGS CPUTYPE=$BOX64_CPUTYPE X87DOUBLE=$BOX64_DYNAREC_X87DOUBLE WAIT=$BOX64_DYNAREC_WAIT ALIGNED_ATOMICS=$BOX64_DYNAREC_ALIGNED_ATOMICS MMAP32=$BOX64_MMAP32"

# ---- 0.1.44 hole-fit: mid after STACK_HEAD so Wine TEB/stack != mid base ----
# Evidence: WINEPRELOADRESERVE=100000-1 occupied 0x100000; addr-outer first-success
# missed larger holes; failed-all still continued PE smoke with last bad patch -> SIGABRT.
# 0.1.40: unset WPR; size-outer (prefer largest); stop on first useful mid; FATAL if all fail;
# never treat 0x*:0x1 as mid OK; marker stamped with SCRIPT_STAMP.
# 0.1.42: NEVER use mid overlapping Wine default stack [0x800000,0xa00000).
# 0.1.43: mid 0x1000000 -> stack followed to 0x1000ec0 (0x1000000-0x1200000) + still noexec.
# 0.1.44: hole base B -> patch mid at B+STACK_HEAD (2MiB), size S-STACK_HEAD; leave
# [B, B+2MiB) free for TEB/stack; also keep classic [0x800000,0xa00000) free.
# Prefer larger/higher holes; reject sticky mid with addr < 0x1200000.
box64_find_prereserve_mid_off() {
  python3 - "$1" <<'PY'
import sys
from pathlib import Path
data = Path(sys.argv[1]).read_bytes()
dos = (0x10000).to_bytes(8,"little") + (0x10000).to_bytes(8,"little")
hi = (0x7f000000).to_bytes(8,"little") + (0x3000000).to_bytes(8,"little")
idx = 0
found = []
while True:
    i = data.find(dos, idx)
    if i < 0:
        break
    if data[i+32:i+48] == hi:
        found.append(i)
    idx = i + 1
if len(found) != 1:
    sys.exit(2)
base = found[0]
print(base + 16, base + 24)
PY
}

box64_mid_matches() {
  python3 - "$1" "$2" "$3" <<'PY'
import struct, sys
from pathlib import Path
path = Path(sys.argv[1])
want_a = int(sys.argv[2], 0)
want_s = int(sys.argv[3], 0)
data = path.read_bytes()
dos = (0x10000).to_bytes(8,"little") + (0x10000).to_bytes(8,"little")
hi = (0x7f000000).to_bytes(8,"little") + (0x3000000).to_bytes(8,"little")
idx = 0
found = []
while True:
    i = data.find(dos, idx)
    if i < 0:
        break
    if data[i+32:i+48] == hi:
        found.append(i)
    idx = i + 1
if len(found) != 1:
    sys.exit(1)
base = found[0]
a, s = struct.unpack_from("<QQ", data, base + 16)
sys.exit(0 if (a == want_a and s == want_s) else 1)
PY
}

box64_patch_prereserve_mid() {
  python3 - "$1" "$2" "$3" <<'PY'
import struct, sys
from pathlib import Path
path = Path(sys.argv[1])
addr = int(sys.argv[2], 0)
size = int(sys.argv[3], 0)
data = bytearray(path.read_bytes())
dos = (0x10000).to_bytes(8,"little") + (0x10000).to_bytes(8,"little")
hi = (0x7f000000).to_bytes(8,"little") + (0x3000000).to_bytes(8,"little")
idx = 0
found = []
while True:
    i = data.find(dos, idx)
    if i < 0:
        break
    if data[i+32:i+48] == hi:
        found.append(i)
    idx = i + 1
if len(found) != 1:
    raise SystemExit("prereserve table not unique: %r" % (found,))
base = found[0]
struct.pack_into("<QQ", data, base + 16, addr, size)
path.write_bytes(data)
print("patched mid -> %#x:%#x @ file_off %#x" % (addr, size, base + 16))
PY
}

# Probe: WINEPRELOADRESERVE must be unset. Success = useful mid (not DOS/hi, size>0x1000) done.
# Always re-parse $out after kill (fixes race where loop missed buffered lines).
box64_probe_mid_done() {
  local expect="$1"
  local out=/mnt/data/wine-prereserve-probe.out
  : > "$out"
  local oldlog="${BOX64_LOG-}"
  local saved_wpr="${WINEPRELOADRESERVE-}"
  unset WINEPRELOADRESERVE
  export BOX64_LOG=1
  set +e
  if command -v stdbuf >/dev/null 2>&1; then
    stdbuf -oL -eL "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine --version >"$out" 2>&1 &
  else
    "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine --version >"$out" 2>&1 &
  fi
  local pid=$!
  local t=0
  while kill -0 "$pid" 2>/dev/null; do
    if grep -q 'WINE prereserve of 0x7f000000' "$out" 2>/dev/null; then
      sleep 0.2
      break
    fi
    if [ -n "$expect" ] && grep -Eq "WINE prereserve of ${expect}:0x[0-9a-fA-F]+ done" "$out" 2>/dev/null; then
      sleep 0.2
      break
    fi
    t=$((t+1))
    if [ "$t" -ge 10 ]; then
      break
    fi
    sleep 0.5
  done
  kill -KILL "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  "$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
  python3 - "$out" "$expect" <<'PY'
import re, sys
from pathlib import Path
text = Path(sys.argv[1]).read_text(errors="replace")
expect = sys.argv[2].strip() if len(sys.argv) > 2 else ""
pat = re.compile(r"WINE prereserve of (0x[0-9a-fA-F]+):(0x[0-9a-fA-F]+) done")
dos, hi = 0x10000, 0x7F000000
expect_i = int(expect, 0) if expect else None
best = None
for m in pat.finditer(text):
    addr = int(m.group(1), 16)
    size = int(m.group(2), 16)
    if addr in (dos, hi):
        continue
    if size <= 0x1000:  # ignore tiny env reserves like 0x1
        continue
    if expect_i is not None and addr != expect_i:
        continue
    best = (addr, size)
    break
for line in text.splitlines():
    if "is not free" in line or "Wine64 detected" in line or "WINE prereserve of" in line:
        print(line)
if best is None:
    sys.exit(1)
print("PROBE_MID_OK %s:%s" % (hex(best[0]), hex(best[1])))
sys.exit(0)
PY
  local rc=$?
  if [ -n "$saved_wpr" ]; then
    export WINEPRELOADRESERVE="$saved_wpr"
  else
    unset WINEPRELOADRESERVE
  fi
  if [ -n "$oldlog" ]; then export BOX64_LOG="$oldlog"; else export BOX64_LOG=0; fi
  grep -E 'prereserve|Wine64 detected|is not free|PROBE_MID_OK' "$out" 2>/dev/null | tee -a /mnt/data/gpu-stack.log >/dev/null || true
  set -e
  return $rc
}

box64_holefit_mid() {
  local marker=/usr/local/bin/.box64-wine64k-holefit
  local box=/usr/local/bin/box64
  local STACK_HEAD=0x200000
  if [ ! -x "$box" ]; then
    log "FATAL: holefit — no $box"
    return 1
  fi
  if ! command -v python3 >/dev/null 2>&1; then
    log "FATAL: holefit — python3 missing"
    return 1
  fi
  if ! box64_find_prereserve_mid_off "$box" >/tmp/box64-mid-off.txt 2>/dev/null; then
    log "FATAL: holefit — cannot locate my_wine_reserve mid in $box"
    return 1
  fi
  # Size outer (largest first). cand_addrs = hole *bases*; mid = base+STACK_HEAD.
  # 0.1.44: leave >=2MiB after hole base for Wine TEB/stack (Boss 0.1.43: stack follows mid).
  local cand_sizes="0x10000000 0x8000000 0x4000000 0x2000000"
  # Prefer high base first so classic 0x800000 stack stays free.
  local cand_addrs="0x10000000 0x4000000 0x2000000 0x1000000"
  # Reject mid overlapping classic stack OR mid addr < 0x1200000 (stack-head zone)
  holefit_bad_mid() {
    python3 - "$1" "$2" <<'PYINNER'
import sys
a=int(sys.argv[1],0); s=int(sys.argv[2],0)
lo, hi = 0x800000, 0xa00000
if a < 0x1200000:
    sys.exit(0)  # bad
if a < hi and (a + s) > lo:
    sys.exit(0)  # bad overlap classic stack
sys.exit(1)  # ok
PYINNER
  }
  if [ -f "$marker" ]; then
    # shellcheck disable=SC1090
    . "$marker" 2>/dev/null || true
    if [ "${SCRIPT_STAMP:-}" != "$WINE_LAUNCH_STAMP" ]; then
      log "HOLEFIT marker stamp mismatch (${SCRIPT_STAMP:-none} != $WINE_LAUNCH_STAMP) — clearing"
      rm -f "$marker"
      unset HOLEFIT_ADDR HOLEFIT_SIZE SCRIPT_STAMP
    elif [ -n "${HOLEFIT_ADDR:-}" ] && [ -n "${HOLEFIT_SIZE:-}" ]; then
      if holefit_bad_mid "$HOLEFIT_ADDR" "$HOLEFIT_SIZE"; then
        log "HOLEFIT marker bad mid ($HOLEFIT_ADDR:$HOLEFIT_SIZE; need mid>=0x1200000, skip 0x800000-0xa00000) — clearing"
        rm -f "$marker"
        unset HOLEFIT_ADDR HOLEFIT_SIZE SCRIPT_STAMP
      else
        log "HOLEFIT reuse marker addr=$HOLEFIT_ADDR size=$HOLEFIT_SIZE"
        # Fast path: if binary already has this mid, skip wine --version probe
        if box64_mid_matches "$box" "$HOLEFIT_ADDR" "$HOLEFIT_SIZE"; then
          log "HOLEFIT ok (cached+binary) addr=$HOLEFIT_ADDR size=$HOLEFIT_SIZE — skip probe"
          export HOLEFIT_ADDR HOLEFIT_SIZE
          unset WINEPRELOADRESERVE
          return 0
        fi
        if box64_patch_prereserve_mid "$box" "$HOLEFIT_ADDR" "$HOLEFIT_SIZE" 2>&1 | tee -a /mnt/data/gpu-stack.log; then
          if box64_probe_mid_done "$HOLEFIT_ADDR"; then
            log "HOLEFIT ok (cached) WINE prereserve of $HOLEFIT_ADDR:$HOLEFIT_SIZE"
            export HOLEFIT_ADDR HOLEFIT_SIZE
            unset WINEPRELOADRESERVE
            return 0
          fi
          log "WARN: holefit marker stale — re-probing"
          rm -f "$marker"
        fi
      fi
    fi
  fi
  log "HOLEFIT begin (no WINEPRELOADRESERVE; STACK_HEAD=$STACK_HEAD; skip 0x800000-0xa00000; size-outer; mid=base+head)"
  local base s mid_a mid_s n=0
  for s in $cand_sizes; do
    for base in $cand_addrs; do
      mid_a=$(python3 - "$base" "$s" "$STACK_HEAD" <<'PYINNER'
import sys
base=int(sys.argv[1],0); size=int(sys.argv[2],0); head=int(sys.argv[3],0)
stack_lo, stack_hi = 0x800000, 0xa00000
if base & 0xFFFF: sys.exit(1)
if size & 0xFFFF: sys.exit(1)
if head & 0xFFFF: sys.exit(1)
if size <= head: sys.exit(1)
mid_a = base + head
mid_s = size - head
if mid_a < 0x1200000: sys.exit(1)
if mid_a + mid_s > 0x7f000000: sys.exit(1)
if mid_a + mid_s <= mid_a: sys.exit(1)
if mid_a < stack_hi and (mid_a + mid_s) > stack_lo: sys.exit(1)
# hole base itself must not sit inside classic stack either
if base < stack_hi and (base + size) > stack_lo: sys.exit(1)
print("%#x %#x" % (mid_a, mid_s))
PYINNER
) || continue
      mid_s=${mid_a#* }
      mid_a=${mid_a%% *}
      n=$((n+1))
      log "HOLEFIT try $n/16 base=$base head=$STACK_HEAD mid=$mid_a size=$mid_s"
      if ! box64_patch_prereserve_mid "$box" "$mid_a" "$mid_s" 2>&1 | tee -a /mnt/data/gpu-stack.log; then
        continue
      fi
      if box64_probe_mid_done "$mid_a"; then
        printf 'SCRIPT_STAMP=%s\nHOLEFIT_ADDR=%s\nHOLEFIT_SIZE=%s\nHOLEFIT_BASE=%s\n' \
          "$WINE_LAUNCH_STAMP" "$mid_a" "$mid_s" "$base" > "$marker"
        log "HOLEFIT ok addr=$mid_a size=$mid_s base=$base stack_head=$STACK_HEAD (saved $marker)"
        export HOLEFIT_ADDR="$mid_a" HOLEFIT_SIZE="$mid_s"
        unset WINEPRELOADRESERVE
        return 0
      fi
    done
  done
  log "FATAL: HOLEFIT failed all candidates — mid prereserve still missing (will not continue with last patch)"
  unset WINEPRELOADRESERVE
  return 1
}

if ! box64_holefit_mid; then
  log "FATAL: hole-fit failed — install 0.1.46+ script + wine64k box64; mid>=0x1200000 (STACK_HEAD 2MiB); avoid 0x800000-0xa00000; clear sticky marker"
  exit 134
fi

# 冒煙 0.1.37：
# 1) wine.inf 版號（不觸發 wineboot；Wine11 unix stub 無 --version 短路）
# 2) 短 PE 探測（可選）：wine --version／wineboot；對齊 prereserve；偵測 LdrInitializeThunk SIGSEGV 迴圈
SMOKE_TIMEOUT="${WINE_VERSION_SMOKE_TIMEOUT:-90}"
SMOKE_PREFIX="/tmp/wine-smoke-$$"
mkdir -p "$SMOKE_PREFIX"
: > /mnt/data/wine-version.out

WINE_INF_VER=""
if [ -f /opt/wine/share/wine/wine.inf ]; then
  WINE_INF_VER=$(grep -m1 -E '^;; Version: Wine ' /opt/wine/share/wine/wine.inf 2>/dev/null | sed 's/^;; Version: //' | tr -d '\r')
fi
if [ -z "$WINE_INF_VER" ]; then
  WINE_INF_VER=$(strings /opt/wine/lib/wine/x86_64-windows/ntdll.dll 2>/dev/null | grep -m1 -E '^Wine [0-9]|^wine-[0-9]' || true)
fi
log "WINE_VERSION_SMOKE wine-inf='${WINE_INF_VER:-missing}'"

SMOKE_OK=0
if echo "$WINE_INF_VER" | grep -qE 'Wine [0-9]|wine-[0-9]'; then
  SMOKE_OK=1
  log "WINE_VERSION_SMOKE ok (from wine.inf / ntdll strings: $WINE_INF_VER)"
else
  log "WARN: wine.inf version probe failed — will require PE --version output"
fi

# PE 短探測：驗證 prereserve + 能否走過 loader（空 prefix 仍可能 wineboot）
# 0.1.41: WINEDEBUG must NOT be -all — that hid wineboot/err (c0000018 etc.) behind exit=1
log "WINE_PE_SMOKE begin timeout=${SMOKE_TIMEOUT}s dynarec=$BOX64_DYNAREC prefix=$SMOKE_PREFIX (expect prereserve mid holefit=${HOLEFIT_ADDR:-0x100000}:${HOLEFIT_SIZE:-?})"
SMOKE_WINEDEBUG_SAVE="${WINEDEBUG-}"
SMOKE_PREFIX_SAVE="${WINEPREFIX-}"
SMOKE_LOG_SAVE="${BOX64_LOG-}"
export WINEDEBUG="${WINE_PE_SMOKE_WINEDEBUG:-err+all,fix+all}"
log "WINE_PE_SMOKE WINEDEBUG=$WINEDEBUG"
export WINEPREFIX="$SMOKE_PREFIX"
export WINEARCH=win64
mkdir -p "$WINEPREFIX"
ensure_prefix_exec
# PE 探測開短暫 LOG 以便抓 LdrInitializeThunk／prereserve
export BOX64_LOG="${BOX64_PE_SMOKE_LOG:-1}"
export BOX64_SHOWSEGV="${BOX64_PE_SMOKE_SHOWSEGV:-0}"
set +e
"$BOX64" /opt/wine/lib/wine/x86_64-unix/wine --version >>/mnt/data/wine-version.out 2>&1 &
SMOKE_PID=$!
SMOKE_BYTES=0
SMOKE_ELAPSED=0
WV=""
PE_FATAL=""
while kill -0 "$SMOKE_PID" 2>/dev/null; do
  SZ=$(wc -c < /mnt/data/wine-version.out 2>/dev/null | tr -d ' \n' || echo 0)
  SZ=${SZ:-0}
  if [ "$SZ" -gt "$SMOKE_BYTES" ] 2>/dev/null; then
    dd if=/mnt/data/wine-version.out bs=1 skip="$SMOKE_BYTES" count=$((SZ-SMOKE_BYTES)) 2>/dev/null | tee -a /mnt/data/gpu-stack.log
    SMOKE_BYTES=$SZ
  fi
  # 早退：已印出版本字串
  if grep -qE 'wine-[0-9]|Wine [0-9]' /mnt/data/wine-version.out 2>/dev/null; then
    # 再等最多 3s 讓行程自己結束
    for _ in 1 2 3; do
      kill -0 "$SMOKE_PID" 2>/dev/null || break
      sleep 1
      SMOKE_ELAPSED=$((SMOKE_ELAPSED+1))
    done
    if kill -0 "$SMOKE_PID" 2>/dev/null; then
      kill -TERM "$SMOKE_PID" 2>/dev/null || true
      sleep 1
      kill -KILL "$SMOKE_PID" 2>/dev/null || true
    fi
    WV=0
    break
  fi
  # 早退：LdrInitializeThunk / KiUserExceptionDispatcher 迴圈
  SEGVC=$(grep -c 'Emit Signal 11' /mnt/data/wine-version.out 2>/dev/null || echo 0)
  SEGVC=$(echo "$SEGVC" | tr -d ' \n')
  if [ "${SEGVC:-0}" -ge 3 ] 2>/dev/null; then
    PE_FATAL="LdrInitializeThunk/KiUserExceptionDispatcher SIGSEGV loop (Emit Signal 11 x${SEGVC})"
    log "WINE_PE_SMOKE FATAL: $PE_FATAL"
    kill -KILL "$SMOKE_PID" 2>/dev/null || true
    "$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
    pkill -9 -f "/tmp/wine-smoke-" 2>/dev/null || true
    WV=139
    break
  fi
  # Soft-pass while still running: Wine11 --version triggers full wineboot; live hang ≠ dead hang
  # Require real PE progress (not merely wineboot.exe argv) — services/explorer/rundll32/wine-staging/system.reg
  PE_PROGRESS=0
  if [ -f "$SMOKE_PREFIX/system.reg" ]; then
    PE_PROGRESS=1
  elif grep -qE 'Detected running wine with "(services|explorer|rundll32)\.exe"|wine-staging [0-9]|InstallHinfSection' /mnt/data/wine-version.out 2>/dev/null; then
    PE_PROGRESS=1
  fi
  if [ "$PE_PROGRESS" = "1" ]; then
    log "WINE_PE_SMOKE progress soft-pass t=${SMOKE_ELAPSED}s (boot markers / services|explorer) — kill smoke, continue WINE_RUN"
    kill -TERM "$SMOKE_PID" 2>/dev/null || true
    sleep 1
    kill -KILL "$SMOKE_PID" 2>/dev/null || true
    "$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
    pkill -9 -f "/tmp/wine-smoke-" 2>/dev/null || true
    WV=0
    break
  fi
  SMOKE_ELAPSED=$((SMOKE_ELAPSED+1))
  if [ "$SMOKE_ELAPSED" -ge "$SMOKE_TIMEOUT" ]; then
    log "WINE_PE_SMOKE TIMEOUT after ${SMOKE_TIMEOUT}s — killing pid=$SMOKE_PID"
    kill -TERM "$SMOKE_PID" 2>/dev/null || true
    sleep 1
    kill -KILL "$SMOKE_PID" 2>/dev/null || true
    "$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
    pkill -9 -f "/tmp/wine-smoke-" 2>/dev/null || true
    WV=124
    break
  fi
  if [ $((SMOKE_ELAPSED % 10)) -eq 0 ]; then
    log "WINE_PE_SMOKE still running t=${SMOKE_ELAPSED}s out_bytes=${SMOKE_BYTES} pid=$SMOKE_PID"
  fi
  sleep 1
done
if [ -z "$WV" ]; then
  wait "$SMOKE_PID"
  WV=$?
fi
SZ=$(wc -c < /mnt/data/wine-version.out 2>/dev/null | tr -d ' \n' || echo 0)
SZ=${SZ:-0}
if [ "$SZ" -gt "$SMOKE_BYTES" ] 2>/dev/null; then
  dd if=/mnt/data/wine-version.out bs=1 skip="$SMOKE_BYTES" 2>/dev/null | tee -a /mnt/data/gpu-stack.log
fi
"$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
wait "$SMOKE_PID" 2>/dev/null || true
# 0.1.41: did wineboot actually create a prefix? (--version often exit=1 after wineboot on Wine11)
PE_BOOT_OK=0
if [ -f "$SMOKE_PREFIX/system.reg" ]; then
  PE_BOOT_OK=1
  log "WINE_PE_SMOKE wineboot progressed (system.reg exists)"
elif [ -d "$SMOKE_PREFIX/drive_c/windows" ]; then
  PE_BOOT_OK=1
  log "WINE_PE_SMOKE wineboot progressed (drive_c/windows exists)"
else
  log "WINE_PE_SMOKE wineboot NO prefix files (system.reg missing) — early loader/wineboot fail"
fi
ls -la "$SMOKE_PREFIX" 2>/dev/null | head -20 | tee -a /mnt/data/gpu-stack.log || true
# Kill smoke wineserver under smoke prefix, then wipe (avoid locks bleeding into main)
export WINEPREFIX="$SMOKE_PREFIX"
"$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
pkill -9 -f "/tmp/wine-smoke-" 2>/dev/null || true
rm -rf "$SMOKE_PREFIX" 2>/dev/null || true
sleep 0.3
# 0.1.51: light WINE_RUN debug — err+all,fix+all + BOX64_LOG=1 made explorer glacial
export WINEDEBUG="${WINE_RUN_WINEDEBUG:-err+all,+seh,+loaddll,+ole,+rpcss,+x11drv,+msgbox}"
if [ -n "$SMOKE_PREFIX_SAVE" ]; then export WINEPREFIX="$SMOKE_PREFIX_SAVE"; else export WINEPREFIX=/home/xuser/.wine; fi
export BOX64_LOG="${BOX64_RUN_LOG:-0}"
export BOX64_SHOWSEGV=0
export BOX64_SHOWBT=0
set -e

log "WINE_PE_SMOKE exit=$WV boot_ok=$PE_BOOT_OK out_bytes=$(wc -c </mnt/data/wine-version.out 2>/dev/null | tr -d ' ')"
log "--- WINE_PE_SMOKE key lines ---"
grep -E 'wine:|err:|fix:|warn:|Assertion|c0000|SIG|Signal|prereserve|wineboot|version|Illegal|FATAL|shared user|granularity|LdrInitialize|KiUser|Emulated|Emit Signal' /mnt/data/wine-version.out 2>/dev/null | head -120 | tee -a /mnt/data/gpu-stack.log || true
log "--- WINE_PE_SMOKE output tail ---"
tail -80 /mnt/data/wine-version.out 2>/dev/null | tee -a /mnt/data/gpu-stack.log || true
log "--- WINE_PE_SMOKE output end ---"

# prereserve 期待：DOS + mid(holefit useful size>0x1000) + hi — never treat 0x*:0x1 as mid OK
EXPECT_MID_ADDR="${HOLEFIT_ADDR:-}"
EXPECT_MID_SIZE="${HOLEFIT_SIZE:-}"
MID_OK_LINE=$(python3 - /mnt/data/wine-version.out "${EXPECT_MID_ADDR}" "${EXPECT_MID_SIZE}" <<'PY'
import re, sys
from pathlib import Path
text = Path(sys.argv[1]).read_text(errors="replace") if Path(sys.argv[1]).is_file() else ""
want_a = sys.argv[2].strip()
want_s = sys.argv[3].strip()
pat = re.compile(r"WINE prereserve of (0x[0-9a-fA-F]+):(0x[0-9a-fA-F]+) done")
dos, hi = 0x10000, 0x7F000000
for m in pat.finditer(text):
    addr, size = int(m.group(1), 16), int(m.group(2), 16)
    if addr in (dos, hi) or size <= 0x1000:
        continue
    if want_a and addr != int(want_a, 0):
        continue
    if want_s and size != int(want_s, 0):
        continue
    print(m.group(0))
    break
PY
)
if [ -n "$MID_OK_LINE" ]; then
  log "WINE_PRERESERVE mid OK ($MID_OK_LINE)"
else
  log "WARN: missing useful WINE prereserve mid done (size>0x1000; ignore 0x1) — holefit/stamp"
  if grep -q 'prereserve of 0x110000' /mnt/data/wine-version.out 2>/dev/null; then
    log "HINT: still using old 0x110000 table — cover-install 0.1.40+ and reinstall runtime box64"
  fi
  if grep -qE 'prereserve of 0x100000:0x1 done' /mnt/data/wine-version.out 2>/dev/null; then
    log "HINT: saw 0x100000:0x1 only — NOT a real mid; need HOLEFIT ok with size>0x1000"
  fi
  if grep -q 'prereserve of .* is not free' /mnt/data/wine-version.out 2>/dev/null; then
    log "HINT: mid prereserve not free under 39-bit VA — holefit should shrink/move; paste probe lines"
  fi
fi

if grep -q 'WINE prereserve of 0x10000:0x10000 done' /mnt/data/wine-version.out 2>/dev/null; then
  log "WINE_PRERESERVE dos=0x10000:0x10000 OK"
fi

PE_OK=0
if [ "$WV" = "0" ]; then PE_OK=1; fi
if grep -qE 'wine-[0-9]|Wine [0-9]' /mnt/data/wine-version.out 2>/dev/null; then PE_OK=1; SMOKE_OK=1; fi
# Wine11 unix stub has no --version short-circuit: wineboot may succeed then exit 1 on "--version" argv
if [ "${PE_BOOT_OK:-0}" = "1" ] && [ -n "$MID_OK_LINE" ]; then PE_OK=1; fi

if [ -n "$PE_FATAL" ] || [ "$WV" = "139" ]; then
  log "FATAL: Wine PE loader SIGSEGV under Box64 ($PE_FATAL)."
  log "HINT: need Box64 wine64k-align-0x100000 (low reserve 0x100000 not 0x110000). Reinstall runtime."
  log "HINT: crash site LdrInitializeThunk → KiUserExceptionDispatcher loop = bad prereserve / Wine11 granularity."
  [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
  [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
  exit 139
fi
if [ "$WV" = "132" ]; then
  log "FATAL: wine PE smoke SIGILL (exit 132). Emulated ntdll／libgcc／loader／dynarec."
  [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
  [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
  exit 132
fi
if [ "$WV" = "134" ]; then
  log "FATAL: wine PE smoke SIGABRT (exit 134). Often granularity_mask — need wine64k-align stamp."
  [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
  [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
  exit 134
fi

# 閘門：必須有版號（inf 或 PE）；SIGSEGV 已在上面 FATAL
# 0.1.50: timeout alone is NOT FATAL if wineboot progressed (system.reg / services / explorer)
if [ "$WV" = "124" ]; then
  if [ "${PE_BOOT_OK:-0}" = "1" ] || grep -qE 'Detected running wine with "(services|explorer|rundll32)\.exe"|wine-staging [0-9]' /mnt/data/wine-version.out 2>/dev/null; then
    log "WINE_PE_SMOKE hang soft-continue (timeout ${SMOKE_TIMEOUT}s but boot progressed boot_ok=${PE_BOOT_OK:-0}) — continue WINE_RUN"
    PE_OK=1
    SMOKE_OK=1
    WV=0
  elif [ -n "$MID_OK_LINE" ] && [ "${SMOKE_OK:-0}" = "1" ]; then
    # mid prereserve + wine.inf known, but no boot files: still soft-warn (empty prefix wineboot may be slow)
    log "WARN: WINE_PE_SMOKE timeout ${SMOKE_TIMEOUT}s with mid OK / inf — soft-continue (no FATAL 124); watch WINEBOOT_INIT"
    PE_OK=1
    WV=0
  else
    log "FATAL: WINE_PE_SMOKE HANG (timeout ${SMOKE_TIMEOUT}s, exit 124, no boot progress)."
    log "HINT: expect prereserve DOS 0x10000:0x10000 + mid holefit + hi 0x7f000000:0x3000000"
    [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
    [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
    exit 124
  fi
fi
if [ "$SMOKE_OK" = "1" ]; then
  if [ "$PE_OK" = "1" ]; then
    log "WINE_VERSION_SMOKE ok (inf=$WINE_INF_VER pe_exit=$WV)"
  else
    # 非 0／無 version 行，但非 hang／SEGV：警告後仍嘗試真實啟動（prefix 可能已半初始化）
    log "WARN: WINE_PE_SMOKE exit=$WV (no version line, boot_ok=${PE_BOOT_OK:-0}) — continuing with wine.inf; watch WINE_START"
    log "WINE_VERSION_SMOKE ok (inf-only=$WINE_INF_VER pe_exit=$WV boot_ok=${PE_BOOT_OK:-0})"
  fi
else
  log "FATAL: wine version unknown (no wine.inf) and PE smoke failed (exit=$WV)."
  [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
  [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
  exit 1
fi

# 0.1.52: complete-prefix — wipe half-init; seed PE; FATAL on c0000135 / incomplete
mkdir -p "$WINEPREFIX"
ensure_prefix_exec
export WINEDEBUG="${WINE_RUN_WINEDEBUG:-err+all,+seh,+loaddll,+ole,+rpcss,+x11drv,+msgbox}"
export BOX64_LOG="${BOX64_RUN_LOG:-0}"
export WINEARCH=win64
log "WINE_RUN WINEDEBUG=$WINEDEBUG BOX64_LOG=$BOX64_LOG WINEPREFIX=$WINEPREFIX WINEARCH=$WINEARCH DISPLAY=${DISPLAY:-unset}"


# ---- 0.1.53: Wine graphics driver (winex11) — registry + probe --------------------
# explorer load_graphics_driver: LoadLibrary("winex11.drv"); ERROR_MOD_NOT_FOUND →
# "The graphics driver is missing. Check your build!" (nodrv black screen).
ensure_graphics_x11_reg() {
  # 0.1.59: never invent user.reg before wineboot. Patch only valid Wine regs.
  set +e
  wine_reg_helper patch-graphics > /mnt/data/wine-gfx.out 2>&1
  GFX_RC=$?
  set -e
  if [ -f /mnt/data/wine-gfx.out ]; then
    while read -r line; do log "DRIVER_REG $line"; done < /mnt/data/wine-gfx.out || true
  fi
  # 0.1.71: DllOverrides backup so wineboot children keep DXVK (or wined3d bisect)
  _dll_mode=native
  case "${X86_DXWRAPPER:-dxvk}" in
    wined3d|WINED3D) _dll_mode=builtin ;;
  esac
  set +e
  wine_reg_helper patch-dll-overrides "$_dll_mode" > /mnt/data/wine-dll.out 2>&1
  DLL_RC=$?
  set -e
  if [ -f /mnt/data/wine-dll.out ]; then
    while read -r line; do log "DRIVER_REG $line"; done < /mnt/data/wine-dll.out || true
  fi
  unset _dll_mode
  # 0.1.72/74: Direct3D VideoMemorySize/csmt + X11 Grab/FS/depth (UseXRandR/ScreenDepth/Decorated)
  set +e
  wine_reg_helper patch-present-knobs > /mnt/data/wine-present.out 2>&1
  PRESENT_RC=$?
  set -e
  if [ -f /mnt/data/wine-present.out ]; then
    while read -r line; do log "DRIVER_REG $line"; done < /mnt/data/wine-present.out || true
  fi
  if [ "$GFX_RC" = "0" ]; then
    return 0
  fi
  log "DRIVER_REG skip (user.reg not ready rc=$GFX_RC dll_rc=${DLL_RC:-} present_rc=${PRESENT_RC:-}) — wineboot must create regs first"
  return 0
}


driver_diag() {
  local pe=/opt/wine/lib/wine/x86_64-windows/winex11.drv
  local so=/opt/wine/lib/wine/x86_64-unix/winex11.so
  local so_wl=/opt/wine/lib/wine/x86_64-unix/winewayland.so
  local sys="$WINEPREFIX/drive_c/windows/system32/winex11.drv"
  local x11=/usr/lib/aarch64-linux-gnu/libX11.so.6
  local xext=/usr/lib/aarch64-linux-gnu/libXext.so.6
  local xcursor=/usr/lib/aarch64-linux-gnu/libXcursor.so.1
  local xi=/usr/lib/aarch64-linux-gnu/libXi.so.6
  local gline
  gline=$(grep -m1 '"Graphics"=' "$WINEPREFIX/user.reg" 2>/dev/null || echo 'Graphics=unset')
  log "DRIVER_DIAG pe=$([ -f "$pe" ] && echo yes || echo NO) so=$([ -f "$so" ] && echo yes || echo NO) wayland_so=$([ -f "$so_wl" ] && echo yes || echo no) sys32_drv=$([ -f "$sys" ] && echo yes || echo NO) libX11=$([ -e "$x11" ] && echo yes || echo NO) libXext=$([ -e "$xext" ] && echo yes || echo NO) libXcursor=$([ -e "$xcursor" ] && echo yes || echo no) libXi=$([ -e "$xi" ] && echo yes || echo no)"
  log "DRIVER_DIAG DISPLAY=${DISPLAY:-unset} WINEDLLPATH=${WINEDLLPATH:-unset} BOX64_PREFER_WRAPPED=${BOX64_PREFER_WRAPPED:-unset} $gline"
  ls -la "$pe" "$so" "$sys" "$x11" "$xext" 2>&1 | while IFS= read -r line; do
    log "DRIVER_DIAG ls: $line"
  done || true
  if command -v python3 >/dev/null 2>&1; then
    if python3 -c "import ctypes; ctypes.CDLL('libX11.so.6'); ctypes.CDLL('libXext.so.6'); print('ok')" >/tmp/x11probe.out 2>/tmp/x11probe.err; then
      log "DRIVER_DIAG dlopen_libX11_Xext=ok"
    else
      log "DRIVER_DIAG dlopen_libX11_Xext=FAIL $(head -c 240 /tmp/x11probe.err 2>/dev/null | tr '\\n' ' ')"
    fi
  else
    log "DRIVER_DIAG dlopen_libX11_Xext=skip (no python3)"
  fi
}

# 0.1.55: size helpers — stub vs large usable prefix
prefix_reg_bytes() {
  local p="$1"
  local ssz=0
  if [ -f "$p/system.reg" ]; then
    ssz=$(wc -c < "$p/system.reg" 2>/dev/null | tr -d ' \n')
  fi
  echo "${ssz:-0}"
}

# True stub: missing or tiny system.reg (smoke-era ~1–2KB). Safe to wipe.
prefix_is_stub() {
  local p="$1"
  local ssz
  ssz=$(prefix_reg_bytes "$p")
  [ "$ssz" -lt 20000 ]
}

# Large usable prefix: keep it even if RpcSs reg keys missing (seed bins + PRESTART).
prefix_is_large() {
  local p="$1"
  local ssz
  ssz=$(prefix_reg_bytes "$p")
  [ "$ssz" -ge 100000 ] || return 1
  [ -f "$p/drive_c/windows/system32/kernel32.dll" ] || return 1
  [ -f "$p/drive_c/windows/system32/ntdll.dll" ] || return 1
  return 0
}

# Complete enough to skip wineboot --init. Do NOT require RpcSs reg keys
# (0.1.54 over-complete caused wipe of 1.6MB 0.1.53-era prefixes).
prefix_is_complete() {
  local p="$1"
  [ -f "$p/system.reg" ] || return 1
  local ssz usz ndll
  ssz=$(wc -c < "$p/system.reg" 2>/dev/null | tr -d ' \n')
  ssz=${ssz:-0}
  # Real wineboot system.reg is typically 100KB+; accept >=20KB as non-stub
  [ "$ssz" -ge 20000 ] || return 1
  [ -f "$p/user.reg" ] || return 1
  usz=$(wc -c < "$p/user.reg" 2>/dev/null | tr -d ' \n')
  usz=${usz:-0}
  [ "$usz" -ge 400 ] || return 1
  [ -f "$p/drive_c/windows/system32/kernel32.dll" ] || return 1
  [ -f "$p/drive_c/windows/system32/ntdll.dll" ] || return 1
  ndll=$(ls "$p/drive_c/windows/system32"/*.dll 2>/dev/null | wc -l | tr -d ' \n')
  ndll=${ndll:-0}
  [ "$ndll" -ge 15 ] || return 1
  # Prefer .update-timestamp, but large+seeded without it is still reusable
  if [ ! -f "$p/.update-timestamp" ]; then
    [ "$ssz" -ge 100000 ] || return 1
  fi
  return 0
}

# ---- 0.1.59: win64 via valid Wine regs only (no stub pre-create) ----------------
ensure_win64_prefix() {
  # Wine 11: system.reg must start with "WINE REGISTRY Version 2" (UTF-8).
  # Invalid/missing → do NOT invent stubs; purge garbage and signal need_wineboot.
  local reason="${1:-heal}"
  export WINEARCH=win64
  mkdir -p "$WINEPREFIX/drive_c/windows/system32" "$WINEPREFIX/drive_c/windows/syswow64" 2>/dev/null || true
  mkdir -p /mnt/data 2>/dev/null || true
  : > /mnt/data/wine-arch.out

  set +e
  wine_reg_helper check > /mnt/data/wine-arch.out 2>&1
  CHK_RC=$?
  set -e
  while read -r line; do log "WINE_REG $line"; done < /mnt/data/wine-arch.out || true
  log "WINE_ARCH precheck reason=$reason WINEARCH=$WINEARCH check_rc=$CHK_RC"

  # 2 = invalid files present; 3 = system.reg missing; 4 = arch!=win64; 0 = ready
  if [ "$CHK_RC" = "2" ]; then
    log "WINE_REG invalid detected — purge (never patch garbage)"
    set +e
    wine_reg_helper purge-invalid >> /mnt/data/wine-arch.out 2>&1
    set -e
    while read -r line; do log "WINE_REG $line"; done < /mnt/data/wine-arch.out || true
    log "WINE_ARCH ensure done offline=yes need_wineboot=1 reason=invalid-purged"
    return 2
  fi

  set +e
  wine_reg_helper heal-arch > /mnt/data/wine-arch.out 2>&1
  HEAL_RC=$?
  set -e
  while read -r line; do log "WINE_ARCH.py: $line"; done < /mnt/data/wine-arch.out || true

  if [ "$HEAL_RC" = "0" ]; then
    log "WINE_ARCH ensure done offline=yes #arch=win64 encoding=utf8 need_wineboot=0 py_rc=0 WINEARCH=$WINEARCH"
    return 0
  fi
  log "WINE_ARCH ensure done offline=yes need_wineboot=1 heal_rc=$HEAL_RC WINEARCH=$WINEARCH"
  return 2
}

wine_arch_auto_repair_once() {
  # Wipe → DO NOT create regs → wineboot --init with WINEARCH=win64 → then patch.
  # Marker lives outside prefix so wipe cannot clear the once-guard.
  marker="/mnt/data/.wine-arch-repair-done"
  if [ -f "$marker" ]; then
    log "WINE_ARCH FATAL 32bit-prefix (already repaired once this session) — refuse"
    return 1
  fi
  log "WINE_ARCH FATAL 32bit-prefix/invalid-reg — auto-repair wipe+wineboot once (no stub regs)"
  mkdir -p /mnt/data 2>/dev/null || true
  : > "$marker"
  wipe_wine_prefix "$WINEPREFIX" "arch-reg-repair"
  export WINEARCH=win64
  mkdir -p "$WINEPREFIX/drive_c/windows/system32" "$WINEPREFIX/drive_c/windows/syswow64" 2>/dev/null || true
  # Explicitly ensure no leftover regs after wipe
  rm -f "$WINEPREFIX/system.reg" "$WINEPREFIX/user.reg" "$WINEPREFIX/userdef.reg" 2>/dev/null || true
  ensure_prefix_exec
  # wineboot creates valid UTF-8 Version 2 regs
  run_wineboot_init "arch-repair" 180
  # Now heal arch + inject services on Wine-written files
  ensure_win64_prefix "post-wineboot" || true
  ensure_rpcss_mountmgr_service_reg || true
  ensure_win64_prefix "post-rpcss" || true
  # Verify
  set +e
  wine_reg_helper check > /mnt/data/wine-arch.out 2>&1
  CHK_RC=$?
  set -e
  while read -r line; do log "WINE_REG $line"; done < /mnt/data/wine-arch.out || true
  if [ "$CHK_RC" != "0" ]; then
    log "WINE_ARCH FATAL repair left invalid/missing reg check_rc=$CHK_RC"
    return 1
  fi
  return 0
}

wipe_wine_prefix() {
  local p="${1:-$WINEPREFIX}"
  local reason="${2:-incomplete}"
  log "WINEBOOT_WIPE begin reason=$reason path=$p"
  "$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
  # Drop stale system32 bind if any
  if path_is_mounted "$p/drive_c/windows/system32"; then
    umount "$p/drive_c/windows/system32" 2>/dev/null || umount -l "$p/drive_c/windows/system32" 2>/dev/null || true
  fi
  # Keep parent dir; wipe prefix contents (regs + drive_c + markers)
  if [ -d "$p" ]; then
    rm -rf "$p/drive_c" "$p/dosdevices" "$p/system.reg" "$p/user.reg" "$p/userdef.reg" \
      "$p/.update-timestamp" "$p/.prefix-exec-ok" 2>/dev/null || true
    # Catch leftovers (Android/busy files: retry timestamp explicitly)
    find "$p" -mindepth 1 -maxdepth 1 ! -name '.' -exec rm -rf {} + 2>/dev/null || true
    rm -f "$p/.update-timestamp" "$p/system.reg" "$p/user.reg" "$p/userdef.reg" 2>/dev/null || true
    if [ -e "$p/.update-timestamp" ] || [ -e "$p/system.reg" ]; then
      log "WARN: WINEBOOT_WIPE leftover markers still present after rm — forcing"
      : > "$p/.update-timestamp" 2>/dev/null || true
      rm -f "$p/.update-timestamp" "$p/system.reg" 2>/dev/null || true
    fi
  fi
  mkdir -p "$p/drive_c/windows/system32" 2>/dev/null || true
  # Re-seed DXVK if present
  if [ -d /mnt/gpu/dxvk/x64 ]; then
    for dll in d3d8 d3d9 d3d10core d3d11 dxgi; do
      if [ -f "/mnt/gpu/dxvk/x64/${dll}.dll" ]; then
        cp -f "/mnt/gpu/dxvk/x64/${dll}.dll" "$p/drive_c/windows/system32/" 2>/dev/null || true
      fi
    done
    _d11="$p/drive_c/windows/system32/d3d11.dll"
    _dxgi="$p/drive_c/windows/system32/dxgi.dll"
    log "DXVK_DIAG reseed d3d11=$([ -f "$_d11" ] && wc -c < "$_d11" || echo MISSING) dxgi=$([ -f "$_dxgi" ] && wc -c < "$_dxgi" || echo MISSING) prefix=$p"
    unset _d11 _dxgi
  fi
  ensure_prefix_exec
  log "WINEBOOT_WIPE done path=$p"
}

run_wineboot_init() {
  # 0.1.60: soft-continue when system.reg >=50KB stable~15s, OR >=30KB + boot
  # progress markers (explorer/services/winex11/rundll32) stable~15s + timestamp.
  # 0.1.55: NEVER soft-kill on early markers alone (exit=143 before system.reg).
  # Hard timeout still 180s; tiny/missing reg on timeout = FAIL.
  local label="${1:-init}"
  local wb_timeout="${2:-180}"
  local soft_stable_need="${WINEBOOT_SOFT_STABLE_SECS:-15}"
  local soft_reg_need="${WINEBOOT_SOFT_REG_BYTES:-50000}"
  local soft_progress_reg="${WINEBOOT_SOFT_PROGRESS_REG_BYTES:-30000}"
  log "WINEBOOT_INIT begin ($label) timeout=${wb_timeout}s soft-continue>=${soft_reg_need}B|progress>=${soft_progress_reg}B+markers stable=${soft_stable_need}s (no early soft-kill)"
  "$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
  rm -f "$WINEPREFIX/.update-timestamp" 2>/dev/null || true
  : > /mnt/data/wineboot-init.out
  set +e
  "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine wineboot --init >>/mnt/data/wineboot-init.out 2>&1 &
  WB_PID=$!
  WB_T=0
  WB_BYTES=0
  WB_SAW_REG=0
  WB_SAW_TS=0
  WB_LAST_REG=-1
  WB_STABLE_SECS=0
  WB_SOFT=0
  while kill -0 "$WB_PID" 2>/dev/null; do
    WB_T=$((WB_T+1))
    SZ=$(wc -c < /mnt/data/wineboot-init.out 2>/dev/null | tr -d ' \n' || echo 0)
    SZ=${SZ:-0}
    if [ "$SZ" -gt "$WB_BYTES" ] 2>/dev/null; then
      dd if=/mnt/data/wineboot-init.out bs=1 skip="$WB_BYTES" count=$((SZ-WB_BYTES)) 2>/dev/null | tee -a /mnt/data/gpu-stack.log >/dev/null
      WB_BYTES=$SZ
    fi
    REG_B=$(prefix_reg_bytes "$WINEPREFIX")
    if [ "$REG_B" -ge 400 ] 2>/dev/null; then
      if [ "$WB_SAW_REG" = "0" ]; then
        log "WINEBOOT_INIT system.reg appeared t=${WB_T}s bytes=$REG_B — waiting for natural exit or soft-continue"
        WB_SAW_REG=1
      fi
    fi
    if [ -f "$WINEPREFIX/.update-timestamp" ] && [ "$WB_SAW_TS" = "0" ]; then
      log "WINEBOOT_INIT .update-timestamp appeared t=${WB_T}s system.reg=${REG_B}B — NO early soft-kill"
      WB_SAW_TS=1
    fi
    # Track registry stability for soft-continue
    if [ "$REG_B" -eq "$WB_LAST_REG" ] 2>/dev/null; then
      WB_STABLE_SECS=$((WB_STABLE_SECS+1))
    else
      WB_STABLE_SECS=0
      WB_LAST_REG=$REG_B
    fi
    if [ $((WB_T % 15)) -eq 0 ]; then
      log "WINEBOOT_INIT hb t=${WB_T}s pid=$WB_PID out_bytes=$SZ system.reg=${REG_B}B ts=$([ -f "$WINEPREFIX/.update-timestamp" ] && echo yes || echo no) stable=${WB_STABLE_SECS}s"
    fi
    # Soft-continue: large stable reg + ts — wineboot often never natural-exits
    # (RpcSs OpenService during init / MountMgr). Distinct from 0.1.54 5s kill.
    # 0.1.60: also accept >=30KB + real boot markers (explorer/services/winex11).
    WB_BOOT_PROGRESS=0
    if [ "$REG_B" -ge "$soft_progress_reg" ] 2>/dev/null; then
      if grep -qE 'services\.exe|explorer\.exe|winex11|rundll32|winedevice\.exe|mountmgr' /mnt/data/wineboot-init.out 2>/dev/null; then
        WB_BOOT_PROGRESS=1
      fi
    fi
    SOFT_HIT=0
    SOFT_REASON=""
    if [ "$REG_B" -ge "$soft_reg_need" ] 2>/dev/null \
       && [ "$WB_STABLE_SECS" -ge "$soft_stable_need" ] \
       && [ -f "$WINEPREFIX/.update-timestamp" ]; then
      SOFT_HIT=1
      SOFT_REASON="reg>=${soft_reg_need}"
    elif [ "$WB_BOOT_PROGRESS" = "1" ] \
       && [ "$WB_STABLE_SECS" -ge "$soft_stable_need" ] \
       && [ -f "$WINEPREFIX/.update-timestamp" ]; then
      SOFT_HIT=1
      SOFT_REASON="progress-markers reg>=${soft_progress_reg}"
    fi
    if [ "$SOFT_HIT" = "1" ]; then
      log "WINEBOOT_INIT soft-continue t=${WB_T}s system.reg=${REG_B}B stable=${WB_STABLE_SECS}s ts=yes reason=$SOFT_REASON — TERM wineboot (keep wineserver, NO wipe)"
      WB_SOFT=1
      kill -TERM "$WB_PID" 2>/dev/null || true
      sleep 3
      if kill -0 "$WB_PID" 2>/dev/null; then
        kill -KILL "$WB_PID" 2>/dev/null || true
      fi
      # Do NOT wineserver -k — RPCSS_PRESTART needs it
      break
    fi
    # Early abort on c0000135 — no point waiting full timeout
    if grep -q 'could not load kernel32.dll, status c0000135' /mnt/data/wineboot-init.out 2>/dev/null; then
      log "WINEBOOT_INIT detected c0000135 t=${WB_T}s — killing"
      kill -TERM "$WB_PID" 2>/dev/null || true
      sleep 1
      kill -KILL "$WB_PID" 2>/dev/null || true
      "$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
      break
    fi
    if [ "$WB_T" -ge "$wb_timeout" ]; then
      REG_B=$(prefix_reg_bytes "$WINEPREFIX")
      if [ "$REG_B" -ge 20000 ] 2>/dev/null; then
        log "WINEBOOT_INIT TIMEOUT t=${WB_T}s but system.reg=${REG_B}B — TERM then accept if reg survives"
        kill -TERM "$WB_PID" 2>/dev/null || true
        sleep 3
        if kill -0 "$WB_PID" 2>/dev/null; then
          kill -KILL "$WB_PID" 2>/dev/null || true
        fi
      else
        log "WINEBOOT_INIT TIMEOUT t=${WB_T}s system.reg=${REG_B}B — killing (no usable reg)"
        kill -TERM "$WB_PID" 2>/dev/null || true
        sleep 1
        kill -KILL "$WB_PID" 2>/dev/null || true
        "$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
      fi
      break
    fi
    sleep 1
  done
  wait "$WB_PID" 2>/dev/null
  WB_RC=$?
  set -e
  REG_B=$(prefix_reg_bytes "$WINEPREFIX")
  if [ "$WB_SOFT" = "1" ]; then
    log "WINEBOOT_INIT soft-continue accepted exit=$WB_RC system.reg=${REG_B}B (proceed RPCSS/WINE_START)"
  fi
  # exit 143 (SIGTERM) is NEVER success unless system.reg already usable
  if [ "$WB_RC" = "143" ] && [ "$REG_B" -lt 20000 ] 2>/dev/null; then
    log "WINEBOOT_INIT exit=143 with tiny/missing system.reg=${REG_B}B — treat as FAIL (soft-kill regression guard)"
  fi
  log "WINEBOOT_INIT exit=$WB_RC system.reg=$([ -f "$WINEPREFIX/system.reg" ] && echo yes || echo no) bytes=$REG_B complete=$(prefix_is_complete "$WINEPREFIX" && echo yes || echo no) ts=$([ -f "$WINEPREFIX/.update-timestamp" ] && echo yes || echo no) soft=$WB_SOFT"
  log "--- WINEBOOT_INIT key lines ---"
  grep -E 'wine:|err:|fix:|warn:|Assertion|c0000|SIG|Signal|prereserve|wineboot|Illegal|FATAL|shared user|granularity|LdrInitialize|KiUser|Emit Signal|noexec|map_image|kernel32|RpcSs|MountMgr' /mnt/data/wineboot-init.out 2>/dev/null | head -120 | tee -a /mnt/data/gpu-stack.log || true
  log "--- WINEBOOT_INIT tail ---"
  tail -60 /mnt/data/wineboot-init.out 2>/dev/null | tee -a /mnt/data/gpu-stack.log || true
}

run_wineboot_update() {
  # Soft update on a large reused prefix — never wipe; never soft-kill early
  local label="${1:-update}"
  local wb_timeout="${2:-90}"
  log "WINEBOOT_UPDATE begin ($label) timeout=${wb_timeout}s"
  : > /mnt/data/wineboot-update.out
  set +e
  "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine wineboot -u >>/mnt/data/wineboot-update.out 2>&1 &
  WB_PID=$!
  WB_T=0
  WB_BYTES=0
  while kill -0 "$WB_PID" 2>/dev/null; do
    WB_T=$((WB_T+1))
    SZ=$(wc -c < /mnt/data/wineboot-update.out 2>/dev/null | tr -d ' \n' || echo 0)
    SZ=${SZ:-0}
    if [ "$SZ" -gt "$WB_BYTES" ] 2>/dev/null; then
      dd if=/mnt/data/wineboot-update.out bs=1 skip="$WB_BYTES" count=$((SZ-WB_BYTES)) 2>/dev/null | tee -a /mnt/data/gpu-stack.log >/dev/null
      WB_BYTES=$SZ
    fi
    if [ $((WB_T % 15)) -eq 0 ]; then
      log "WINEBOOT_UPDATE hb t=${WB_T}s pid=$WB_PID out_bytes=$SZ"
    fi
    if [ "$WB_T" -ge "$wb_timeout" ]; then
      log "WINEBOOT_UPDATE TIMEOUT — TERM (prefix kept)"
      kill -TERM "$WB_PID" 2>/dev/null || true
      sleep 2
      kill -KILL "$WB_PID" 2>/dev/null || true
      break
    fi
    sleep 1
  done
  wait "$WB_PID" 2>/dev/null
  WB_RC=$?
  set -e
  log "WINEBOOT_UPDATE exit=$WB_RC system.reg_bytes=$(prefix_reg_bytes "$WINEPREFIX")"
  grep -E 'wine:|err:|fix:|RpcSs|MountMgr|c0000|FATAL' /mnt/data/wineboot-update.out 2>/dev/null | head -40 | while read -r line; do
    log "WINEBOOT_UPDATE.out: $line"
  done || true
}

# 0.1.59: validate/purge BEFORE wineboot — never leave invalid stubs in place
"$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
FORCE_FRESH_WINEBOOT=0
if ! ensure_win64_prefix "pre-wineboot"; then
  log "WINE_ARCH/WINE_REG need fresh wineboot — forcing stub wipe path"
  FORCE_FRESH_WINEBOOT=1
  rm -f "$WINEPREFIX/system.reg" "$WINEPREFIX/user.reg" "$WINEPREFIX/userdef.reg" \
    "$WINEPREFIX/.update-timestamp" 2>/dev/null || true
fi

# Invalid/UTF-8-stub/missing-BOM → full wipe once so wineboot writes real Version 2 regs
if [ "$FORCE_FRESH_WINEBOOT" = "1" ]; then
  log "WINEBOOT_INIT force-fresh (invalid reg purged) — wipe+wineboot --init WINEARCH=win64"
  wipe_wine_prefix "$WINEPREFIX" "force-fresh-invalid-reg"
  export WINEARCH=win64
  mkdir -p "$WINEPREFIX/drive_c/windows/system32" "$WINEPREFIX/drive_c/windows/syswow64" 2>/dev/null || true
  rm -f "$WINEPREFIX/system.reg" "$WINEPREFIX/user.reg" "$WINEPREFIX/userdef.reg" 2>/dev/null || true
  ensure_prefix_exec
  run_wineboot_init "force-fresh" 180
  REG_B=$(prefix_reg_bytes "$WINEPREFIX")
  if [ "$REG_B" -lt 400 ] 2>/dev/null; then
    log "FATAL: force-fresh wineboot did not create system.reg (bytes=$REG_B)"
    [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
    [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
    exit "${WB_RC:-1}"
  fi
  ensure_win64_prefix "post-force-fresh" || true
# 0.1.55 wipe policy:
# - complete → skip init
# - large (>=100KB) but not "complete" → REUSE (seed bins + wineboot -u); NEVER wipe for missing RpcSs keys
# - stub (tiny/missing) → wipe + wineboot --init (no soft-kill)
# - after init: FATAL only if system.reg still missing/tiny; large prefix proceeds even if complete=no
elif prefix_is_complete "$WINEPREFIX"; then
  log "WINEBOOT_INIT skip (prefix complete: system.reg=$(prefix_reg_bytes "$WINEPREFIX")B dlls=$(ls "$WINEPREFIX/drive_c/windows/system32"/*.dll 2>/dev/null | wc -l | tr -d ' ') ts=$([ -f "$WINEPREFIX/.update-timestamp" ] && echo yes || echo no))"
  ensure_prefix_exec
elif prefix_is_large "$WINEPREFIX"; then
  REG_B=$(prefix_reg_bytes "$WINEPREFIX")
  log "WINEBOOT_INIT reuse large prefix (system.reg=${REG_B}B) — NO wipe; seed bins + wineboot -u + RPCSS_PRESTART"
  ensure_prefix_exec
  run_wineboot_update "reuse-large" 90
  # Ensure timestamp marker so future skips are quiet (best-effort)
  if [ ! -f "$WINEPREFIX/.update-timestamp" ] && [ -f /opt/wine/share/wine/wine.inf ]; then
    date +%s > "$WINEPREFIX/.update-timestamp" 2>/dev/null || true
    log "WINEBOOT_INIT wrote .update-timestamp after reuse-large"
  fi
elif prefix_is_stub "$WINEPREFIX"; then
  if [ -f "$WINEPREFIX/system.reg" ]; then
    log "WINEBOOT_INIT wipe stub (system.reg=$(prefix_reg_bytes "$WINEPREFIX")B <20KB)"
    wipe_wine_prefix "$WINEPREFIX" "stub-before-init"
  else
    log "WINEBOOT_INIT fresh prefix (no system.reg)"
    ensure_prefix_exec
  fi
  run_wineboot_init "first" 180
  NEED_RETRY=0
  REG_B=$(prefix_reg_bytes "$WINEPREFIX")
  if grep -q 'could not load kernel32.dll, status c0000135' /mnt/data/wineboot-init.out 2>/dev/null; then
    NEED_RETRY=1
    log "WINEBOOT_INIT c0000135 on first try — wipe+retry once"
  elif [ "$REG_B" -lt 20000 ] 2>/dev/null; then
    NEED_RETRY=1
    log "WINEBOOT_INIT no usable system.reg after first try (bytes=$REG_B exit=$WB_RC) — wipe+retry once"
  fi
  if [ "$NEED_RETRY" = "1" ]; then
    wipe_wine_prefix "$WINEPREFIX" "retry-after-fail"
    run_wineboot_init "retry" 180
  fi
  REG_B=$(prefix_reg_bytes "$WINEPREFIX")
  if [ "$REG_B" -lt 400 ] 2>/dev/null; then
    log "FATAL: wineboot --init did not create system.reg (exit=$WB_RC bytes=$REG_B). See WINEBOOT_INIT key lines."
    [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
    [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
    exit "${WB_RC:-1}"
  fi
  if grep -q 'could not load kernel32.dll, status c0000135' /mnt/data/wineboot-init.out 2>/dev/null; then
    log "FATAL: wineboot c0000135 (kernel32.dll) after wipe+retry — refusing WINE_START"
    [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
    [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
    exit 53
  fi
  if prefix_is_complete "$WINEPREFIX"; then
    log "WINEBOOT_INIT complete=yes after init"
  elif [ "$REG_B" -ge 20000 ] 2>/dev/null; then
    log "WINEBOOT_INIT soft-ok system.reg=${REG_B}B (complete=no) — proceed with seed/RPCSS (no FATAL)"
    ensure_prefix_exec
  else
    log "FATAL: WINEBOOT_INIT left tiny system.reg=${REG_B}B — refusing WINE_START"
    log "FATAL detail: kernel32=$([ -f "$WINEPREFIX/drive_c/windows/system32/kernel32.dll" ] && echo yes || echo no) ntdll=$([ -f "$WINEPREFIX/drive_c/windows/system32/ntdll.dll" ] && echo yes || echo no) update-ts=$([ -f "$WINEPREFIX/.update-timestamp" ] && echo yes || echo no) dlls=$(ls "$WINEPREFIX/drive_c/windows/system32"/*.dll 2>/dev/null | wc -l | tr -d ' ')"
    [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
    [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
    exit "${WB_RC:-1}"
  fi
else
  # Mid-size odd prefix (20KB–100KB): treat as incomplete but prefer update over wipe
  REG_B=$(prefix_reg_bytes "$WINEPREFIX")
  log "WINEBOOT_INIT mid-size prefix (system.reg=${REG_B}B) — seed + wineboot -u (no wipe)"
  ensure_prefix_exec
  run_wineboot_update "mid-size" 120
fi

# Gate: kernel32 must exist before explorer
if [ ! -f "$WINEPREFIX/drive_c/windows/system32/kernel32.dll" ]; then
  log "FATAL: kernel32.dll missing in system32 before WINE_START — refuse launch"
  [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
  [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
  exit 53
fi
# Re-seed right before start (covers skip-wineboot path) + force Graphics=x11
ensure_prefix_exec
ensure_graphics_x11_reg
# Re-copy winex11.drv + GUI DLLs (0.1.56 smoke: after X death start.exe c0000135 missing win32u/user32)
if [ -f /opt/wine/lib/wine/x86_64-windows/winex11.drv ]; then
  cp -f /opt/wine/lib/wine/x86_64-windows/winex11.drv "$WINEPREFIX/drive_c/windows/system32/winex11.drv" 2>/dev/null || true
fi
if [ -f /opt/wine/lib/wine/x86_64-windows/actxprxy.dll ]; then
  cp -f /opt/wine/lib/wine/x86_64-windows/actxprxy.dll "$WINEPREFIX/drive_c/windows/system32/actxprxy.dll" 2>/dev/null || true
fi
for _gui in win32u.dll user32.dll gdi32.dll shell32.dll shlwapi.dll shcore.dll imm32.dll; do
  if [ -f "/opt/wine/lib/wine/x86_64-windows/$_gui" ]; then
    cp -f "/opt/wine/lib/wine/x86_64-windows/$_gui" "$WINEPREFIX/drive_c/windows/system32/$_gui" 2>/dev/null || true
  fi
done
log "GUI_SEED win32u=$([ -f "$WINEPREFIX/drive_c/windows/system32/win32u.dll" ] && echo yes || echo NO) user32=$([ -f "$WINEPREFIX/drive_c/windows/system32/user32.dll" ] && echo yes || echo NO) gdi32=$([ -f "$WINEPREFIX/drive_c/windows/system32/gdi32.dll" ] && echo yes || echo NO)"
# 0.1.54: ensure RpcSs / MountMgr binaries even on skip-wineboot path
if [ -f /opt/wine/lib/wine/x86_64-windows/rpcss.exe ]; then
  cp -f /opt/wine/lib/wine/x86_64-windows/rpcss.exe "$WINEPREFIX/drive_c/windows/system32/rpcss.exe" 2>/dev/null || true
fi
mkdir -p "$WINEPREFIX/drive_c/windows/system32/drivers" 2>/dev/null || true
if [ -f /opt/wine/lib/wine/x86_64-windows/mountmgr.sys ]; then
  cp -f /opt/wine/lib/wine/x86_64-windows/mountmgr.sys "$WINEPREFIX/drive_c/windows/system32/drivers/mountmgr.sys" 2>/dev/null || true
fi
for _svc in svchost.exe plugplay.exe services.exe sc.exe winedevice.exe ntoskrnl.exe; do
  if [ -f "/opt/wine/lib/wine/x86_64-windows/$_svc" ]; then
    cp -f "/opt/wine/lib/wine/x86_64-windows/$_svc" "$WINEPREFIX/drive_c/windows/system32/$_svc" 2>/dev/null || true
  fi
done
log "WINE_PRESTART kernel32=yes ntdll=$([ -f "$WINEPREFIX/drive_c/windows/system32/ntdll.dll" ] && echo yes || echo no) winex11=$([ -f "$WINEPREFIX/drive_c/windows/system32/winex11.drv" ] && echo yes || echo NO) rpcss=$([ -f "$WINEPREFIX/drive_c/windows/system32/rpcss.exe" ] && echo yes || echo NO) mountmgr=$([ -f "$WINEPREFIX/drive_c/windows/system32/drivers/mountmgr.sys" ] && echo yes || echo NO) dlls=$(ls "$WINEPREFIX/drive_c/windows/system32"/*.dll 2>/dev/null | wc -l | tr -d ' ') complete=$(prefix_is_complete "$WINEPREFIX" && echo yes || echo no)"

# Display + driver diagnose before START
X0_STATE=missing
if [ -S /tmp/.X11-unix/X0 ] || [ -e /tmp/.X11-unix/X0 ]; then X0_STATE=ok; fi
log "DISPLAY_DIAG DISPLAY=${DISPLAY:-unset} X0=$X0_STATE backend=${BACKEND:-gpu} desktop=${W}x${H}"
driver_diag

# ---- 0.1.59: RpcSs/MountMgr offline inject on VALID Wine Version 2 regs only ------

# 0.1.94 H4: offline .exe ProgId so ShellExecute/start fallback can open PE
ensure_exe_assoc() {
  mkdir -p /mnt/data 2>/dev/null || true
  set +e
  wine_reg_helper inject-exe-assoc > /mnt/data/wine-exe-assoc.out 2>&1
  _ea_rc=$?
  set -e
  while read -r line; do log "EXE_ASSOC $line"; done < /mnt/data/wine-exe-assoc.out 2>/dev/null || true
  if [ "$_ea_rc" = "0" ]; then
    log "EXE_ASSOC ensure done ok=1"
  else
    log "WARN: EXE_ASSOC ensure rc=$_ea_rc (ShellExecute may still NOASSOC)"
  fi
  unset _ea_rc
  return 0
}

ensure_rpcss_mountmgr_service_reg() {
  local sys32="$WINEPREFIX/drive_c/windows/system32"
  local drv="$sys32/drivers"
  mkdir -p /mnt/data 2>/dev/null || true
  : > /mnt/data/wine-rpcss-reg.out

  "$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
  sleep 0.3

  log "RPCSS_REG precheck rpcss_bin=$([ -f "$sys32/rpcss.exe" ] && echo yes || echo NO) winedevice=$([ -f "$sys32/winedevice.exe" ] && echo yes || echo NO) mountmgr=$([ -f "$drv/mountmgr.sys" ] && echo yes || echo NO)"

  set +e
  wine_reg_helper check > /mnt/data/wine-rpcss-reg.out 2>&1
  CHK_RC=$?
  set -e
  while read -r line; do log "RPCSS_REG $line"; done < /mnt/data/wine-rpcss-reg.out || true

  # 2=invalid 3=missing → cannot inject; 0=ready 4=arch!=win64 → heal then inject
  if [ "$CHK_RC" = "2" ] || [ "$CHK_RC" = "3" ]; then
    log "RPCSS_REG ensure done offline=yes skipped=1 reason=reg-not-ready check_rc=$CHK_RC (need valid wineboot regs first)"
    return 2
  fi

  set +e
  wine_reg_helper heal-arch > /mnt/data/wine-rpcss-reg.out 2>&1
  HEAL_RC=$?
  set -e
  while read -r line; do log "RPCSS_REG.heal: $line"; done < /mnt/data/wine-rpcss-reg.out || true
  if [ "$HEAL_RC" != "0" ]; then
    log "RPCSS_REG ensure done offline=yes skipped=1 reason=heal-failed heal_rc=$HEAL_RC"
    return 2
  fi

  set +e
  wine_reg_helper inject-rpcss > /mnt/data/wine-rpcss-reg.out 2>&1
  PY_RC=$?
  set -e
  while read -r line; do log "RPCSS_REG.py: $line"; done < /mnt/data/wine-rpcss-reg.out || true

  local reg_rpc=no reg_mm=no
  if [ -f "$WINEPREFIX/system.reg" ]; then
    # grep works on UTF-8 Wine regs
    grep -qF 'Services\\RpcSs]' "$WINEPREFIX/system.reg" 2>/dev/null && reg_rpc=yes || true
    grep -qF 'Services\\MountMgr]' "$WINEPREFIX/system.reg" 2>/dev/null && reg_mm=yes || true
  fi
  log "RPCSS_REG ensure done offline=yes file_rpcss=$reg_rpc file_mountmgr=$reg_mm py_rc=$PY_RC"
  if [ "$reg_rpc" != "yes" ]; then
    log "WARN: RPCSS_REG inject did not materialize RpcSs"
    return 2
  fi
  return 0
}

rpcss_diag() {
  local sys32="$WINEPREFIX/drive_c/windows/system32"
  local drv="$sys32/drivers"
  local rpc=no mm=no svc=no wd=no reg_rpc=no reg_mm=no
  [ -f "$sys32/rpcss.exe" ] && rpc=yes
  [ -f "$drv/mountmgr.sys" ] && mm=yes
  [ -f "$sys32/services.exe" ] && svc=yes
  [ -f "$sys32/winedevice.exe" ] && wd=yes
  if [ -f "$WINEPREFIX/system.reg" ]; then
    grep -qF 'Services\\RpcSs]' "$WINEPREFIX/system.reg" 2>/dev/null && reg_rpc=yes || true
    grep -qF 'Services\\MountMgr]' "$WINEPREFIX/system.reg" 2>/dev/null && reg_mm=yes || true
  fi
  local img="(none)"
  if [ -f "$WINEPREFIX/system.reg" ]; then
    img=$(grep -F 'rpcss.exe' "$WINEPREFIX/system.reg" 2>/dev/null | head -3 | tr '\n' '|' | head -c 200)
    img=${img:-"(no ImagePath line)"}
  fi
  log "RPCSS_DIAG rpcss=$rpc mountmgr=$mm winedevice=$wd services_exe=$svc reg_rpcss=$reg_rpc reg_mountmgr=$reg_mm dlls=$(ls "$sys32"/*.dll 2>/dev/null | wc -l | tr -d ' ') drivers=$(ls "$drv"/*.sys 2>/dev/null | wc -l | tr -d ' ')"
  log "RPCSS_DIAG imagepath_snip=$img"
  ls -l "$sys32/rpcss.exe" "$drv/mountmgr.sys" "$sys32/services.exe" "$sys32/winedevice.exe" 2>&1 | while read -r line; do
    log "RPCSS_DIAG ls: $line"
  done || true
}


# ---- 0.1.61 RpcSs process helpers (top-level; dash-safe) --------------------
# Uses globals: RPCSS_PID, RPCSS_LAUNCHES, BOX64
rpcss_proc_alive() {
  # 0.1.91: skip vanished /proc/*/cmdline (dash 'cannot open' race under set -e paths)
  for c in /proc/[0-9]*/cmdline; do
    [ -r "$c" ] || continue
    tr '\0' ' ' < "$c" 2>/dev/null | grep -q 'rpcss\.exe' && return 0
  done
  [ -n "${RPCSS_PID:-}" ] && kill -0 "$RPCSS_PID" 2>/dev/null && return 0
  return 1
}

launch_rpcss_once() {
  if [ "${RPCSS_LAUNCHES:-0}" -ge 2 ]; then
    log "RPCSS_PRESTART launch cap reached (2) — not relaunching"
    return 1
  fi
  if rpcss_proc_alive; then
    log "RPCSS_PRESTART rpcss already in /proc — skip launch"
    return 0
  fi
  "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine rpcss.exe >>/mnt/data/wine-rpcss.out 2>&1 &
  RPCSS_PID=$!
  RPCSS_LAUNCHES=$((RPCSS_LAUNCHES+1))
  sleep 2
  if kill -0 "$RPCSS_PID" 2>/dev/null || rpcss_proc_alive; then
    log "RPCSS_PRESTART rpcss_launch#$RPCSS_LAUNCHES pid=$RPCSS_PID alive=1"
    return 0
  fi
  log "RPCSS_PRESTART rpcss_launch#$RPCSS_LAUNCHES pid=$RPCSS_PID exited early"
  return 1
}

rpcss_prestart() {
  # 0.1.61: NEVER mark ready if rpcss process is dead.
  # 0.1.60 false-ready: sc-query-running while alive=0 → black Present.
  # Require: (a) rpcss process alive AND (b) sc STATE 4 RUNNING or OLE probe OK.
  # No relaunch storm (max 2 launches). 1056 = already running = OK.
  # DEMAND_START only — no AUTO 1053 fight. MountMgr bg AFTER true ready.
  local sys32="$WINEPREFIX/drive_c/windows/system32"
  : > /mnt/data/wine-rpcss.out
  : > /mnt/data/wine-rpcss-probe.out
  if [ ! -f "$sys32/rpcss.exe" ]; then
    log "RPCSS_PRESTART skip (rpcss.exe missing)"
    return 0
  fi
  log "RPCSS_PRESTART begin (0.1.61 alive+OLE/sc4; max 2 launches; no AUTO race)"
  set +e
  export WINEARCH=win64
  NET_RC=1
  SC_RC=1
  SERVICES_PID=""
  RPCSS_PID=""
  RPCSS_LAUNCHES=0

  RPC_T0=$(date +%s 2>/dev/null || echo 0)
  if [ -f "$sys32/services.exe" ]; then
    "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine services.exe >>/mnt/data/wine-rpcss.out 2>&1 &
    SERVICES_PID=$!
    # 0.1.92 P1: sleep 4→2; proceed early when alive
    ST_SVC=0
    SVC_ALIVE=0
    while [ "$ST_SVC" -lt 2 ]; do
      sleep 1
      ST_SVC=$((ST_SVC+1))
      if kill -0 "$SERVICES_PID" 2>/dev/null; then SVC_ALIVE=1; break; fi
    done
    kill -0 "$SERVICES_PID" 2>/dev/null && SVC_ALIVE=1 || true
    log "RPCSS_PRESTART services.exe pid=$SERVICES_PID alive=$SVC_ALIVE wait=${ST_SVC}s"
  else
    log "RPCSS_PRESTART WARN services.exe missing"
  fi

  # 0.1.93: NEVER early-stop/TERM sc just because rpcss appeared in /proc
  # (0.1.92 crush: appear@t=1 → TERM sc → sc_rc=124 → ready=0; 091 waited sc rc=0 ~15s).
  # Appear only logs; wait for sc natural exit, RUNNING/1056, or longer soft-cap.
  RPC_ALREADY=0
  rpcss_proc_alive && RPC_ALREADY=1 || true
  if [ "$RPC_ALREADY" = "1" ]; then
    log "RPCSS_PRESTART rpcss already in /proc — still run sc start (need RUNNING/1056; no skip)"
  fi
  "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine sc.exe start RpcSs >>/mnt/data/wine-rpcss.out 2>&1 &
  SC_PID=$!
  # overlap launch_rpcss_once while sc start runs
  launch_rpcss_once || true
  ST=0
  APPEARED_LOGGED=0
  # 0.1.93: wait for sc natural exit (091 ~15s rc=0). Appear only logs — NEVER TERM sc.
  while kill -0 "$SC_PID" 2>/dev/null && [ "$ST" -lt 20 ]; do
    sleep 1
    ST=$((ST+1))
    if [ "$APPEARED_LOGGED" = "0" ] && rpcss_proc_alive; then
      APPEARED_LOGGED=1
      log "RPCSS_PRESTART rpcss appeared during sc start t=${ST}s — keep waiting for sc exit (no TERM)"
    fi
  done
  if kill -0 "$SC_PID" 2>/dev/null; then
    # soft-cap: DETACH only — do not TERM (SCM may still flip RpcSs to RUNNING)
    SC_RC=124
    log "RPCSS_PRESTART sc start soft-timeout t=${ST}s (cap 20; DETACH no TERM — let SCM finish)"
  else
    wait "$SC_PID" 2>/dev/null
    SC_RC=$?
    log "RPCSS_PRESTART sc start exited rc=$SC_RC t=${ST}s"
  fi
  unset APPEARED_LOGGED
  if grep -qE '1056|already been started|already running' /mnt/data/wine-rpcss.out 2>/dev/null; then
    log "RPCSS_PRESTART sc 1056/already-running — treat as OK"
    SC_RC=0
  fi
  unset SC_EARLY_RUNNING
  # 0.1.94: sc start output often already has STATE RUNNING — remember it (probe may
  # miss due to WINEDEBUG pollution / 3s kill before sc query prints).
  SC_START_RUNNING=0
  if grep -qE 'STATE[[:space:]]*:[[:space:]]*4|[[:space:]]RUNNING' /mnt/data/wine-rpcss.out 2>/dev/null; then
    SC_START_RUNNING=1
    log "RPCSS_PRESTART sc start output has STATE RUNNING (parser will treat ready)"
  fi

  RPC_READY=0
  RPC_REASON="timeout"
  PT=0
  while [ "$PT" -lt 35 ]; do
    PROC_ALIVE=0
    rpcss_proc_alive && PROC_ALIVE=1 || true

    : > /mnt/data/wine-rpcss-probe.out
    SC_RUNNING=0
    OLE_OK=0

    # 0.1.94: quiet WINEDEBUG so sc query can print STATE before 3s soft-kill
    WINEDEBUG=-all,-fix "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine sc.exe query RpcSs >>/mnt/data/wine-rpcss-probe.out 2>&1 &
    PQ=$!
    QT=0
    while kill -0 "$PQ" 2>/dev/null && [ "$QT" -lt 5 ]; do
      sleep 1
      QT=$((QT+1))
      # early exit if STATE already visible
      if grep -qE 'STATE[[:space:]]*:[[:space:]]*4|[[:space:]]RUNNING' /mnt/data/wine-rpcss-probe.out 2>/dev/null; then
        break
      fi
    done
    if kill -0 "$PQ" 2>/dev/null; then
      kill -TERM "$PQ" 2>/dev/null || true
      sleep 0.4
    else
      wait "$PQ" 2>/dev/null
    fi
    # 0.1.94: STATE RUNNING from probe OR sc-start out OR sc_rc=0+alive — never stuck on ole_ok=0
    if grep -qE 'RUNNING|STATE[[:space:]]*:[[:space:]]*4' /mnt/data/wine-rpcss-probe.out 2>/dev/null        || grep -qE 'STATE[[:space:]]*:[[:space:]]*4|[[:space:]]RUNNING' /mnt/data/wine-rpcss.out 2>/dev/null        || [ "${SC_START_RUNNING:-0}" = "1" ]; then
      SC_RUNNING=1
    fi
    if [ "$SC_RUNNING" != "1" ] && [ "${SC_RC:-1}" = "0" ] && [ "$PROC_ALIVE" = "1" ]; then
      SC_RUNNING=1
      log "RPCSS_PRESTART sc_running=1 via sc_rc=0+proc_alive (STATE probe quiet)"
    fi

    # 0.1.92: skip OLE probe when sc already RUNNING or rpcss alive (Boss: 3×10s ole wasted)
    if [ "$SC_RUNNING" = "1" ] && [ "$PROC_ALIVE" = "1" ]; then
      : # fall through to ready check below
    elif [ $((PT % 5)) -eq 2 ] && [ -f "$sys32/actxprxy.dll" ] && [ "$SC_RUNNING" != "1" ]; then
      : > /mnt/data/wine-rpcss-ole.out
      WINEDEBUG=err+ole,err+rpcss "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine regsvr32.exe /s actxprxy.dll >>/mnt/data/wine-rpcss-ole.out 2>&1 &
      PR=$!
      RT=0
      while kill -0 "$PR" 2>/dev/null && [ "$RT" -lt 5 ]; do
        sleep 1
        RT=$((RT+1))
      done
      if kill -0 "$PR" 2>/dev/null; then
        kill -TERM "$PR" 2>/dev/null || true
        log "RPCSS_PRESTART ole-probe soft-timeout t=5s"
      else
        wait "$PR" 2>/dev/null
        REGSVR_RC=$?
        if [ "${REGSVR_RC:-1}" = "0" ] \
           && ! grep -qE 'RPC_S_SERVER_UNAVAILABLE|start_rpcss Failed|err:ole:start_rpcss|0x6ba|err:ole:' /mnt/data/wine-rpcss-ole.out 2>/dev/null; then
          OLE_OK=1
        fi
        log "RPCSS_PRESTART ole-probe rc=${REGSVR_RC:-?} ole_ok=$OLE_OK err_snip=$(tr '\n' '|' < /mnt/data/wine-rpcss-ole.out 2>/dev/null | head -c 120)"
      fi
    fi

    if [ "$PROC_ALIVE" = "1" ] && [ "$SC_RUNNING" = "1" ]; then
      RPC_READY=1
      RPC_REASON="alive+sc-running"
      break
    fi
    if [ "$PROC_ALIVE" = "1" ] && [ "$OLE_OK" = "1" ]; then
      # Re-verify process still alive after probe (shell pid may have exited)
      if rpcss_proc_alive; then
        RPC_READY=1
        RPC_REASON="alive+ole-probe"
        break
      fi
      log "RPCSS_PRESTART ole-probe ok but rpcss vanished — not ready"
    fi

    if [ "$PROC_ALIVE" = "0" ] && [ "$RPCSS_LAUNCHES" -lt 2 ]; then
      log "RPCSS_PRESTART no rpcss process — one more launch"
      launch_rpcss_once || true
    fi

    RPC_WALL=0
    if [ -n "${RPC_T0:-}" ] && [ "$RPC_T0" != "0" ]; then
      _now=$(date +%s 2>/dev/null || echo 0)
      RPC_WALL=$((_now - RPC_T0))
      unset _now
    fi
    if [ $((PT % 5)) -eq 0 ]; then
      SNIP=$(tr '\n' '|' < /mnt/data/wine-rpcss-probe.out 2>/dev/null | head -c 140)
      log "RPCSS_PRESTART heartbeat t=${PT}s wall=${RPC_WALL}s proc_alive=$PROC_ALIVE sc_running=$SC_RUNNING ole_ok=$OLE_OK launches=$RPCSS_LAUNCHES sc_snip=$SNIP"
      log "RPCSS_PRESTART probe t=${PT}s wall=${RPC_WALL}s proc_alive=$PROC_ALIVE sc_running=$SC_RUNNING ole_ok=$OLE_OK launches=$RPCSS_LAUNCHES sc_snip=$SNIP"
    fi
    # 0.1.91 K3b + 0.1.92 wall-clock; 0.1.93: also cut tail when launches exhausted + dead
    if [ "$RPC_READY" != "1" ]; then
      if [ "$PROC_ALIVE" = "0" ] && [ "$RPCSS_LAUNCHES" -ge 2 ] && [ "${RPC_WALL:-0}" -ge 20 ]; then
        log "RPCSS_PRESTART soft-continue t=${PT}s wall=${RPC_WALL}s ready=0 proc_alive=0 launches=$RPCSS_LAUNCHES sc_running=$SC_RUNNING — R3 early (dead after 2 launches)"
        PT=$((PT+1))
        break
      fi
      if [ "$PT" -ge 30 ] || [ "${RPC_WALL:-0}" -ge 45 ]; then
        log "RPCSS_PRESTART soft-continue t=${PT}s wall=${RPC_WALL}s ready=0 proc_alive=$PROC_ALIVE sc_running=$SC_RUNNING ole_ok=$OLE_OK — proceed WINE_START"
        PT=$((PT+1))
        break
      fi
    fi
    PT=$((PT+1))
    sleep 1
  done

  PROC_ALIVE=0
  rpcss_proc_alive && PROC_ALIVE=1 || true
  if [ "$RPC_READY" = "1" ] && [ "$PROC_ALIVE" != "1" ]; then
    log "RPCSS_PRESTART REJECT false-ready (reason was $RPC_REASON but proc_alive=0)"
    RPC_READY=0
    RPC_REASON="false-ready-dead"
  fi

  if [ "$RPC_READY" = "1" ] && [ -f "$sys32/winedevice.exe" ] && [ -f "$sys32/drivers/mountmgr.sys" ]; then
    "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine winedevice.exe MountMgr >>/mnt/data/wine-rpcss.out 2>&1 &
    log "RPCSS_PRESTART MountMgr/winedevice bg pid=$! (after ready)"
  else
    log "RPCSS_PRESTART MountMgr deferred (ready=$RPC_READY)"
  fi

  # tracked shell pid may exit while SCM-owned rpcss stays in /proc — proc_alive is authoritative
  kill -0 "${RPCSS_PID:-0}" 2>/dev/null && tracked=1 || tracked=0
  set -e
  RPCSS_DONE_LOGGED=1
  log "RPCSS_PRESTART done ready=$RPC_READY reason=$RPC_REASON proc_alive=$PROC_ALIVE shell_pid_alive=$tracked rpcss_pid=${RPCSS_PID:-none} launches=$RPCSS_LAUNCHES sc_rc=$SC_RC services_pid=${SERVICES_PID:-none} probe_t=${PT}s out_bytes=$(wc -c </mnt/data/wine-rpcss.out 2>/dev/null | tr -d ' ')"
  if [ -f /mnt/data/wine-rpcss.out ]; then
    grep -E 'wine:|err:|fix:|RpcSs|rpcss|MountMgr|service|c0000|FATAL|1053|1056|RUNNING|STATE|OpenService|RPC_S|already' /mnt/data/wine-rpcss.out 2>/dev/null | head -40 | while read -r line; do
      log "RPCSS_PRESTART.out: $line"
    done || true
  fi
  if [ "$RPC_READY" != "1" ]; then
    log "WARN: RPCSS_PRESTART not ready — enabling +ole/+rpcss/+x11drv for WINE_START diag"
    export WINEDEBUG="err+all,+seh,+loaddll,+process,+module,+ole,+rpcss,+x11drv,+msgbox"
  else
    log "RPCSS_PRESTART RpcSs ready — safe for WINE_START"
  fi
  return 0
}


ensure_rpcss_mountmgr_service_reg
ensure_exe_assoc
# wineserver was killed during offline RpcSs patch — re-assert #arch=win64 before PRESTART
ensure_win64_prefix "pre-prestart" || log "WARN: WINE_ARCH pre-prestart heal soft-fail"
rpcss_diag
rpcss_prestart

# If PRESTART hit 32-bit wall OR invalid registry, auto-repair once then retry
if grep -qE "is a 32-bit installation|not a valid registry file" /mnt/data/wine-rpcss.out /mnt/data/wineboot-init.out 2>/dev/null; then
  log "WINE_ARCH FATAL 32bit-prefix/invalid-reg detected in RPCSS_PRESTART"
  if wine_arch_auto_repair_once; then
    ensure_prefix_exec
    ensure_graphics_x11_reg
    rpcss_diag
    rpcss_prestart
  else
    log "FATAL: WINE_ARCH 32bit-prefix unrepaired — refuse WINE_START"
    [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
    [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
    exit 32
  fi
fi

# 0.1.53: /desktop=name,WxH,x11 — skip mac/wayland probe; LoadLibrary winex11.drv only
# 0.1.62: NEVER start.exe /unix (ShellExecuteEx failed). Seed PE into C:/App/app.exe
# and pass Windows path to explorer /desktop=… (0.1.60 unix argv also kept as fallback).
# 0.1.63: APP_WIN uses forward slashes so log()/echo never emits BEL (\a).
# Force +ole/+rpcss/+x11drv until first Present / 60s (overrides earlier WINE_RUN default).
export WINEARCH=win64
export WINEDEBUG="${WINE_START_WINEDEBUG:-err+all,+seh,+loaddll,+process,+module,+ole,+rpcss,+x11drv,+msgbox}"
# 0.1.91 K1b: best-effort stop orphan start.exe writers from prior cycle (stale wine-run)
# 0.1.92: NEVER match services.exe/rpcss/wineserver/winedevice (Boss 091: orphan-reap
# falsely TERM pid=services.exe right after RpcSs ready → COM/ShellExecute brittle).
# Require start.exe as an argv path token; skip SCM pids.
for _c in /proc/[0-9]*/cmdline; do
  [ -r "$_c" ] || continue
  _cmd=$(tr '\0' ' ' < "$_c" 2>/dev/null || true)
  [ -n "$_cmd" ] || continue
  case "$_cmd" in
    *services.exe*|*rpcss.exe*|*wineserver*|*winedevice.exe*|*svchost.exe*)
      continue ;;
  esac
  _opid=${_c#/proc/}; _opid=${_opid%/cmdline}
  if [ -n "${SERVICES_PID:-}" ] && [ "$_opid" = "$SERVICES_PID" ]; then
    continue
  fi
  if [ -n "${RPCSS_PID:-}" ] && [ "$_opid" = "$RPCSS_PID" ]; then
    continue
  fi
  # token match: .../start.exe or ...\start.exe or "start.exe" as argv word
  case "$_cmd" in
    *" start.exe "*|*" start.exe"|*/start.exe\ *|*/start.exe|"start.exe "*|*"\start.exe"*|*/system32/start.exe*|*/syswow64/start.exe*)
      kill -TERM "$_opid" 2>/dev/null || true
      log "WINE_RUN_OUT orphan-reap start.exe pid=$_opid cmd=$(printf '%s' "$_cmd" | head -c 120)"
      ;;
  esac
done
unset _c _cmd _opid
WINE_START_REACHED=1
log "WINE_START begin (WINEARCH=$WINEARCH WINEDEBUG=$WINEDEBUG argv=$* )"
: > /mnt/data/wine-run.out
stamp_wine_run "WINE_RUN_OUT cleared at WINE_START stamp=$WINE_LAUNCH_STAMP"
stamp_wine_run "WINE_START begin argv=$*"
set +e

# Ensure Z: -> / so unix paths are visible if needed
# 0.1.90: also force C: -> ../drive_c (wineboot usually makes it; wipe/partial can drop it)
mkdir -p "$WINEPREFIX/dosdevices" 2>/dev/null || true
ln -sfn / "$WINEPREFIX/dosdevices/z:" 2>/dev/null || true
ln -sfn ../drive_c "$WINEPREFIX/dosdevices/c:" 2>/dev/null || true
_c_link=$(readlink "$WINEPREFIX/dosdevices/c:" 2>/dev/null || echo missing)
_z_link0=$(readlink "$WINEPREFIX/dosdevices/z:" 2>/dev/null || echo missing)
log "APP_DOSDEVICES c=${_c_link} z=${_z_link0}"
stamp_wine_run "APP_DOSDEVICES c=${_c_link} z=${_z_link0}"
unset _c_link _z_link0

# Resolve first .exe arg (guest path, typically /mnt/app/app.exe)
APP_UNIX=""
for _a in "$@"; do
  case "$_a" in
    *.exe|*.EXE|*.com|*.COM|*.bat|*.BAT) APP_UNIX="$_a"; break ;;
  esac
done
APP_WIN=""
APP_MODE="none"
if [ -n "$APP_UNIX" ] && [ -f "$APP_UNIX" ]; then
  mkdir -p "$WINEPREFIX/drive_c/App" 2>/dev/null || true
  if cp -f "$APP_UNIX" "$WINEPREFIX/drive_c/App/app.exe" 2>/dev/null; then
    APP_WIN='C:/App/app.exe'
    APP_MODE="seed-copy"
  elif ln -sfn "$APP_UNIX" "$WINEPREFIX/drive_c/App/app.exe" 2>/dev/null; then
    APP_WIN='C:/App/app.exe'
    APP_MODE="seed-symlink"
  else
    APP_WIN=""
    APP_MODE="unix-fallback"
  fi
  log "WINE_START app_map mode=$APP_MODE unix=$APP_UNIX win=${APP_WIN:-none} bytes=$(wc -c < "$APP_UNIX" 2>/dev/null | tr -d ' ')"
else
  log "WINE_START WARN no .exe arg or missing file (argv=$*)"
fi

# 0.1.64: explorer /desktop alone, then separate APP_LOAD (explorer argv never loaded app.exe in 0.1.63)
log "WINE_START cmdline: explorer /desktop=x86,${W}x${H},x11 (desktop only)"
"$BOX64" /opt/wine/lib/wine/x86_64-unix/wine explorer /desktop=x86,${W}x${H},x11 >>/mnt/data/wine-run.out 2>&1 &
WINE_PID=$!
log "WINE_START pid=$WINE_PID (explorer desktop; app via APP_LOAD)"

# 0.1.75: wait for host DesktopHelper MapWindow raise → /mnt/data/.x86-x11-ready
# so APP_LOAD CreateSwapChain does not race explorer desktop UNMAPPED.
MAP_WAIT_MAX="${X86_MAP_WAIT_MAX:-150}"
MAP_WAIT_I=0
# Marker cleared by Session/DesktopHelper before X start — do not rm after explorer
# (desktop may already have mapped and written it).
log "MAP_WAIT desktop marker .x86-x11-ready (max ${MAP_WAIT_MAX}x0.1s)"
while [ ! -f /mnt/data/.x86-x11-ready ] && [ "$MAP_WAIT_I" -lt "$MAP_WAIT_MAX" ]; do
  # explorer died early?
  if ! kill -0 "$WINE_PID" 2>/dev/null; then
    log "MAP_WAIT abort explorer dead pid=$WINE_PID t=${MAP_WAIT_I}0ms"
    break
  fi
  sleep 0.1
  MAP_WAIT_I=$((MAP_WAIT_I + 1))
done
if [ -f /mnt/data/.x86-x11-ready ]; then
  log "MAP_WAIT desktop ready t=${MAP_WAIT_I}0ms marker=$(head -c 120 /mnt/data/.x86-x11-ready 2>/dev/null | tr '\n' ' ')"
else
  log "MAP_WAIT desktop timeout t=${MAP_WAIT_I}0ms — APP_LOAD anyway (raise may still land)"
fi

APP_PID=""
APP_RELAUNCHED=0
APP_LADDER_HANG_STEP=0
APP_TARGET=""
APP_LOAD_MODE=""
APP_LOADED_STRICT=0
APP_HWND_SEEN=0
# 0.1.67: unix CreateProcess ladder — absolute → relative → start /unix. Never C:/App (amd64).
# 0.1.87: i386 (0x014c) exception — WoW64 via wine start C:/App/app.exe; unix absolute known-fail.
# 0.1.77: strict Loaded = loaddll "Loaded L"…app.exe""; bare path mention is NOT Loaded.
app_load_has_loaded() {
  grep -qE 'Loaded L".*app\.exe' /mnt/data/wine-run.out 2>/dev/null
}
app_load_path_seen() {
  grep -qE '/mnt/app/app\.exe|App[/\\]+app\.exe' /mnt/data/wine-run.out 2>/dev/null
}
app_load_marker_app_hwnd() {
  # Ready marker body: mapped=1 desk=0|1 app=0|1 hwnd=0x… id=0x…
  # App HWND ready when app=1 OR (hwnd nonzero AND desk=0).
  [ -f /mnt/data/.x86-x11-ready ] || return 1
  _m=$(head -c 200 /mnt/data/.x86-x11-ready 2>/dev/null | tr '\n' ' ')
  case "$_m" in
    *" app=1 "*) return 0 ;;
    *" app=1"*) return 0 ;;
  esac
  # hwnd=0x0 or missing → not ready; desk-only with hwnd=0x0 is desktop map
  case "$_m" in
    *" desk=0 "*" hwnd=0x"[1-9a-fA-F]*) return 0 ;;
    *" hwnd=0x"[1-9a-fA-F]*" app=1"*) return 0 ;;
  esac
  return 1
}
# 0.1.90: kill stuck APP_PID before ladder advance / relaunch (mirror CUI relaunch)
app_load_kill_app() {
  _kap_why="${1:-ladder}"
  if [ -n "${APP_PID:-}" ] && kill -0 "$APP_PID" 2>/dev/null; then
    log "APP_LOAD kill pid=$APP_PID mode=${APP_LOAD_MODE:-?} reason=$_kap_why"
    kill -TERM "$APP_PID" 2>/dev/null || true
    sleep 1
    if kill -0 "$APP_PID" 2>/dev/null; then
      kill -KILL "$APP_PID" 2>/dev/null || true
      sleep 0.2
    fi
    wait "$APP_PID" 2>/dev/null || true
  fi
  unset _kap_why
}
# 0.1.92: per-mode wine-run slice — stop explorer-desktop / prior-mode false positives
APP_LOAD_RUN_OFF=0
app_load_mark_slice() {
  APP_LOAD_RUN_OFF=$(wc -c < /mnt/data/wine-run.out 2>/dev/null | tr -d ' ' || echo 0)
  [ -n "$APP_LOAD_RUN_OFF" ] || APP_LOAD_RUN_OFF=0
  stamp_wine_run "APP_LOAD_SLICE off=$APP_LOAD_RUN_OFF mode=${APP_LOAD_MODE:-?} pid=${APP_PID:-?}"
}
app_load_slice_file() {
  # stdout = wine-run bytes after APP_LOAD_RUN_OFF
  if [ ! -f /mnt/data/wine-run.out ]; then
    return 0
  fi
  _off=${APP_LOAD_RUN_OFF:-0}
  dd if=/mnt/data/wine-run.out bs=1 skip="$_off" 2>/dev/null || true
  unset _off
}
app_load_fail_sig_present() {
  # Only current mode slice; ShellExecuteEx alone is noise for wow64_direct unless
  # this slice also has err:start (our start /exec child) or explicit fail strings.
  app_load_slice_file | grep -qE 'ShellExecuteEx failed|Application could not be started|c0000135|c000007b|err:module|err:process|noexec filesystem|FormatMessage failed|STATUS_DLL_NOT_FOUND|STATUS_INVALID_IMAGE_FORMAT|err:start:fatal_error' 2>/dev/null
}
app_load_dump_fail_sigs() {
  _slice=$(app_load_slice_file)
  if printf '%s' "$_slice" | grep -qE 'ShellExecuteEx failed|Application could not be started|c0000135|c000007b|err:module|err:process|noexec filesystem|FormatMessage failed|STATUS_DLL_NOT_FOUND|STATUS_INVALID_IMAGE_FORMAT|err:start' 2>/dev/null; then
    printf '%s\n' "$_slice" | grep -E 'ShellExecuteEx failed|Application could not be started|c0000135|c000007b|err:module|err:process|noexec filesystem|FormatMessage failed|STATUS_DLL_NOT_FOUND|STATUS_INVALID_IMAGE_FORMAT|err:start' 2>/dev/null | head -10 | while IFS= read -r ln; do
      log "APP_LOAD fail_sig mode=${APP_LOAD_MODE:-?} wow64=${APP_WOW64:-0} slice_off=${APP_LOAD_RUN_OFF:-0}: $ln"
    done || true
  fi
  # 0.1.92: real GetLastError / HRESULT only — no bare pointer scrap from wine traces
  # 0.1.93: do NOT match 'failed: 0118' wine tid (ShellExecuteEx failed: 0118:err:…)
  # 0.1.94: exclude map_image section Characteristic false positives (flags c0000040/c0000080)
  _hr=$(printf '%s\n' "$_slice" | grep -vE 'map_image_into_view|flags c00000' 2>/dev/null |     grep -E 'ShellExecuteEx failed|Application could not be started|err:start|err:module|err:process|FormatMessage|GetLastError|STATUS_' 2>/dev/null |     grep -oE 'GetLastError[=: ]+[0-9]+|hr[=: ]+0x[89a-fA-F][0-9a-fA-F]{7}|HRESULT[=: ]+0x[0-9a-fA-F]+|status[=: ]+[cC][0-9a-fA-F]+|SE_ERR_[A-Z_]+|0x8[0-9a-fA-F]{7}|STATUS_[A-Z_]+|c0000135|c000007b|c0000142|c0000005' 2>/dev/null | head -8 | tr '\n' ' ' || true)
  if [ -n "${_hr:-}" ]; then
    log "APP_LOAD fail_hr=[${_hr}] (slice GetLastError/HRESULT; not pointer dump)"
  else
    log "APP_LOAD fail_hr=[] (no GetLastError/HRESULT in slice off=${APP_LOAD_RUN_OFF:-0} mode=${APP_LOAD_MODE:-?})"
  fi
  # Dig aid: first Loaded PE in this slice
  _first_pe=$(printf '%s\n' "$_slice" | grep -oE 'Loaded L"[^"]+\.exe"' 2>/dev/null | head -1 || true)
  [ -n "${_first_pe:-}" ] && log "APP_LOAD slice_first_pe=${_first_pe} mode=${APP_LOAD_MODE:-?}" || true
  # 0.1.96: NtCreateUserProcess / BinaryType / machine / mscoree dig stamps
  _nt_app=$(printf '%s\n' "$_slice" | grep -E 'NtCreateUserProcess .*App\\\\app\.exe|NtCreateUserProcess .*App/app\.exe|NtCreateUserProcess .*App\\\\app' 2>/dev/null | head -4 || true)
  _nt_started=$(printf '%s\n' "$_slice" | grep -cE 'CreateProcessInternalW started process pid|NtCreateUserProcess .* pid ' 2>/dev/null || echo 0)
  _nt_mach=$(printf '%s\n' "$_slice" | grep -oE 'NtCreateUserProcess [^ ]+ image [^ ]+ cmdline [^ ]+ parent [^ ]+ machine [0-9a-fx]+' 2>/dev/null | head -3 | tr '\n' '|' || true)
  _bt_w=$(printf '%s\n' "$_slice" | grep -E 'GetBinaryTypeW L"' 2>/dev/null | head -4 | tr '\n' '|' || true)
  _msc=$(printf '%s\n' "$_slice" | grep -cE 'mscoree' 2>/dev/null || echo 0)
  _dbl=$(printf '%s\n' "$_slice" | grep -cE 'App\\\\\\\\app|App\\\\app\.exe"' 2>/dev/null || echo 0)
  if [ -n "${_nt_app:-}" ]; then
    log "APP_LOAD ntcreate_app mode=${APP_LOAD_MODE:-?}: $_nt_app"
  else
    log "APP_LOAD ntcreate_app=none mode=${APP_LOAD_MODE:-?} (no NtCreateUserProcess for App\\app.exe in slice)"
  fi
  log "APP_LOAD ntcreate_diag started_pid_lines=${_nt_started} mscoree_hits=${_msc} dblslash_hits=${_dbl} mode=${APP_LOAD_MODE:-?}"
  [ -n "${_nt_mach:-}" ] && log "APP_LOAD ntcreate_machine slice: $_nt_mach" || true
  [ -n "${_bt_w:-}" ] && log "APP_LOAD GetBinaryTypeW slice: $_bt_w" || true
  # Prefer explicit NTSTATUS / warn:process near App create
  _nst=$(printf '%s\n' "$_slice" | grep -iE 'warn:process|err:process|STATUS_INVALID_IMAGE|STATUS_DLL_NOT_FOUND|c000007b|c0000135|invalid image|machine 014c|machine 0x14c' 2>/dev/null | head -8 | tr '\n' '|' || true)
  [ -n "${_nst:-}" ] && log "APP_LOAD ntstatus_slice: $_nst" || log "APP_LOAD ntstatus_slice=[] mode=${APP_LOAD_MODE:-?}"
  unset _hr _slice _first_pe _nt_app _nt_started _nt_mach _bt_w _msc _dbl _nst
}
app_load_alive() {
  [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null
}
app_load_wait_rc() {
  if [ -n "$APP_PID" ] && ! kill -0 "$APP_PID" 2>/dev/null; then
    wait "$APP_PID" 2>/dev/null
    APP_RC=$?
    # 0.1.95: stamp WTERMSIG for SIGILL (rc=132=128+4) / SIGSEGV / SIGABRT
    if [ -n "${APP_RC:-}" ] && [ "$APP_RC" -gt 128 ] 2>/dev/null; then
      _sig=$((APP_RC - 128))
      case "$_sig" in
        4) _sn=SIGILL ;;
        11) _sn=SIGSEGV ;;
        6) _sn=SIGABRT ;;
        7) _sn=SIGBUS ;;
        9) _sn=SIGKILL ;;
        *) _sn=sig$_sig ;;
      esac
      log "APP_LOAD WTERMSIG=$_sig ($_sn) rc=$APP_RC pid=$APP_PID mode=${APP_LOAD_MODE:-?} (128+N; SIGILL=4 → do not use i386 PE as wine MAIN)"
      stamp_wine_run "APP_LOAD WTERMSIG=$_sig $_sn rc=$APP_RC mode=${APP_LOAD_MODE:-?}"
      # Pull BOX64 SIGILL PC / illegal opcode lines from current slice if any
      _ill=$(app_load_slice_file 2>/dev/null | grep -iE 'SIGILL|Illegal instruction|Emulation of|Unhandled signal|Rip=|RIP=|PC=0x|dynarec.*ill' 2>/dev/null | head -6 | tr '\n' '|' || true)
      if [ -n "${_ill:-}" ]; then
        log "APP_LOAD SIGILL_PC slice: $_ill"
        stamp_wine_run "APP_LOAD SIGILL_PC: $_ill"
      fi
      unset _sig _sn _ill
    fi
    return 0
  fi
  return 1
}
# 0.1.91 K2: seed gate — require non-empty C:/App/app.exe + dosdevices/c: before wow64_*
app_load_seed_gate() {
  SEED="${SEED:-$WINEPREFIX/drive_c/App/app.exe}"
  mkdir -p "$WINEPREFIX/drive_c/App" "$WINEPREFIX/dosdevices" 2>/dev/null || true
  ln -sfn ../drive_c "$WINEPREFIX/dosdevices/c:" 2>/dev/null || true
  ln -sfn / "$WINEPREFIX/dosdevices/z:" 2>/dev/null || true
  if [ ! -s "$SEED" ] && [ -n "${APP_UNIX:-}" ] && [ -f "$APP_UNIX" ]; then
    cp -f "$APP_UNIX" "$SEED" 2>/dev/null && log "APP_SEED gate=re-cp path=$SEED bytes=$(wc -c < "$SEED" 2>/dev/null | tr -d ' ')" || true
  fi
  _c=$(readlink "$WINEPREFIX/dosdevices/c:" 2>/dev/null || echo missing)
  if [ -s "$SEED" ]; then
    APP_WIN="${APP_WIN:-C:/App/app.exe}"
    log "APP_SEED gate=OK path=$SEED bytes=$(wc -c < "$SEED" 2>/dev/null | tr -d ' ') win=$APP_WIN c=$_c"
    stamp_wine_run "APP_SEED gate=OK bytes=$(wc -c < "$SEED" 2>/dev/null | tr -d ' ') c=$_c"
    unset _c
    return 0
  fi
  log "APP_SEED gate=FAIL path=$SEED unix=${APP_UNIX:-?} c=$_c — refuse wow64_start / blind ShellExecute"
  log "APP_LOAD_FATAL seed_gate path=$SEED unix=${APP_UNIX:-missing}"
  stamp_wine_run "APP_SEED gate=FAIL APP_LOAD_FATAL seed_gate"
  unset _c
  return 1
}
app_load_spawn() {
  # $1 = mode tag: absolute|relative|start_unix|wineconsole|wow64_direct|wow64_unix_seed|wow64_explorer|wow64_start
  # 0.1.87/89: wow64_* use APP_WIN (C:/App/app.exe) for i386 under win64 — unix absolute fails.
  # 0.1.89: default wow64_direct (CreateProcess); explorer backup; start.exe ShellExecute LAST.
  # 0.1.91 K2: wow64_start refused when seed_gate FAIL (no «no application associated» on missing C:/App).
  APP_LOAD_MODE="$1"
  case "$1" in
    absolute)
      log "APP_LOAD cmdline: wine $APP_TARGET (unix CreateProcess; no C:/App, no start.exe)"
      app_load_mark_slice
      "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine "$APP_TARGET" >>/mnt/data/wine-run.out 2>&1 &
      ;;
    relative)
      APP_DIR=$(dirname "$APP_TARGET")
      APP_BASE=$(basename "$APP_TARGET")
      log "APP_LOAD retry: cd $APP_DIR && wine ./$APP_BASE (unix relative; no C:/)"
      (
        cd "$APP_DIR" || exit 1
        "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine "./$APP_BASE" >>/mnt/data/wine-run.out 2>&1
      ) &
      ;;
    start_unix)
      # Wine start /unix — distinct from Windows-path start.exe/ShellExecuteEx path
      log "APP_LOAD retry: wine start /unix $APP_TARGET (unix start; no C:/App)"
      "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine start /unix "$APP_TARGET" >>/mnt/data/wine-run.out 2>&1 &
      ;;
    wow64_direct)
      # 0.1.98: Dig 097 — SIGILL was unset WINELOADERNOEXEC, not MMAP32. Primary =
      # 095-style 64-bit cmd //c CreateProcess; KEEP WINELOADERNOEXEC=1.
      #   (1) MAIN = 64-bit cmd.exe; CreateProcess via `cmd //c`
      #   (2) KEEP WINELOADERNOEXEC=1 (never unset — 094/096/097 SIGILL class)
      #   (3) forward-slash C:/App/app.exe (096 path knife)
      #   (4) mmap32_parent=0 — NEVER export BOX64_MMAP32=1 on parent
      #   (5) //c avoids Wine unix-argv mangling /c into a DOS path
      #   (6) stamp BinaryType_host + noexec=kept; BOX64_SHOWSEGV for residual
      #   (7) never i386 MAIN
      # Child-only MMAP32 / Wow64 0x014c (A2) deferred — follow-up after A1 smoke.
      app_load_seed_gate || log "APP_SEED gate=FAIL mode=direct — still try cmd64 CreateProcess (A1 keep wow64)"
      _w64_win='C:/App/app.exe'
      # Belt: .cmd stub runs seed via 64-bit cmd (never i386 MAIN)
      _stub="$WINEPREFIX/drive_c/App/x86_launch.cmd"
      printf '%s\n' '@echo off' 'C:/App/app.exe' > "$_stub" 2>/dev/null || true
      case "${PE_MACH}" in
        14c|014c|0x14c|0x014c) _bt=SCS_32BIT_BINARY ;;
        8664|0x8664) _bt=SCS_64BIT_BINARY ;;
        *) _bt=UNKNOWN ;;
      esac
      log "APP_LOAD BinaryType_host=$_bt machine=0x${PE_MACH:-?} pe_sub=${PE_SUB:-?} (host PE fingerprint; Wine GetBinaryType should match)"
      stamp_wine_run "APP_LOAD BinaryType_host=$_bt machine=0x${PE_MACH:-?}"
      log "APP_LOAD wow64=1 machine=0x${PE_MACH:-?} mode=direct_cmd64 win=$_w64_win seed=${SEED:-} stub=$_stub mmap32_parent=0 noexec=kept"
      log "APP_LOAD cmdline: wine cmd //c $_w64_win (64-bit cmd CreateProcess; mmap32_parent=0; WINELOADERNOEXEC kept; no i386 MAIN; machine=0x${PE_MACH:-?})"
      stamp_wine_run "APP_LOAD cmdline: wine cmd //c $_w64_win (wow64 direct_cmd64 mmap32_parent=0 noexec=kept)"
      app_load_mark_slice
      (
        # Keep WINELOADERNOEXEC=1 — do NOT unset (094/096/097 SIGILL)
        # 0.1.97/098: keep inherited BOX64_MMAP32=0 — parent MMAP32=1 caused SIGILL
        export BOX64_SHOWSEGV=1
        export BOX64_LOG="${BOX64_APP_LOG:-1}"
        # Prefer +warn for process/module so NtCreateUserProcess NTSTATUS surfaces
        export WINEDEBUG="${WINE_START_WINEDEBUG:-err+all,warn+process,warn+module,+seh,+loaddll,+process,+module,+ole,+rpcss,+x11drv,+msgbox}"
        "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine cmd //c "$_w64_win" >>/mnt/data/wine-run.out 2>&1
      ) &
      unset _stub _bt
      ;;
    wow64_unix_seed)
      # 0.1.98 ladder-2: 64-bit start //exec //wait CreateProcess; NOEXEC kept.
      # (primary is cmd //c; start is backup). Forward-slash path; never i386 MAIN.
      app_load_seed_gate || log "APP_SEED gate=FAIL mode=start_exec — still try"
      _w64_win='C:/App/app.exe'
      log "APP_LOAD wow64=1 machine=0x${PE_MACH:-?} mode=start_exec64 win=$_w64_win seed=${SEED:-} mmap32_parent=0 noexec=kept"
      log "APP_LOAD cmdline: wine start //exec //wait $_w64_win (64-bit start CreateProcess; mmap32_parent=0; WINELOADERNOEXEC kept; //exec; machine=0x${PE_MACH:-?})"
      stamp_wine_run "APP_LOAD cmdline: wine start //exec //wait $_w64_win (wow64 start_exec64 mmap32_parent=0 noexec=kept)"
      app_load_mark_slice
      (
        # Keep WINELOADERNOEXEC=1 — do NOT unset (097 SIGILL)
        # 0.1.97/098: keep inherited BOX64_MMAP32=0
        export BOX64_SHOWSEGV=1
        export BOX64_LOG="${BOX64_APP_LOG:-1}"
        export WINEDEBUG="${WINE_START_WINEDEBUG:-err+all,warn+process,warn+module,+seh,+loaddll,+process,+module,+ole,+rpcss,+x11drv,+msgbox}"
        "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine start //exec //wait "$_w64_win" >>/mnt/data/wine-run.out 2>&1
      ) &
      ;;
    wow64_explorer)
      # 0.1.89 backup: explorer /desktop=… C:/App/app.exe (still Windows path / WoW64)
      app_load_seed_gate || log "APP_SEED gate=FAIL mode=explorer — still try"
      _w64_win="${APP_WIN:-C:/App/app.exe}"
      log "APP_LOAD wow64=1 machine=0x${PE_MACH:-?} mode=explorer win=$_w64_win unix=${APP_UNIX:-}"
      log "APP_LOAD cmdline: wine explorer /desktop=x86,${W}x${H},x11 $_w64_win (wow64 explorer; machine=0x${PE_MACH:-?})"
      stamp_wine_run "APP_LOAD cmdline: wine explorer … $_w64_win (wow64 explorer)"
      app_load_mark_slice
      # 0.1.92: join existing desktop name; file open still may ShellExecute — ladder diag only
      "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine explorer /desktop=x86,${W}x${H},x11 "$_w64_win" >>/mnt/data/wine-run.out 2>&1 &
      ;;
    wow64_start)
      # 0.1.89 LAST: wine start C:/App (ShellExecuteEx — Boss 087 failed; keep for HRESULT diag)
      # 0.1.91 K2: refuse blind start when C:/App/app.exe missing (root of «no application associated»)
      if ! app_load_seed_gate; then
        log "APP_LOAD_FATAL seed_gate — refuse wow64_start (no ShellExecute on missing C:/App)"
        APP_PID=""
        return 1
      fi
      _w64_win="${APP_WIN:-C:/App/app.exe}"
      log "APP_LOAD wow64=1 machine=0x${PE_MACH:-?} mode=start win=$_w64_win unix=${APP_UNIX:-}"
      log "APP_LOAD cmdline: wine start $_w64_win (wow64 start LAST; machine=0x${PE_MACH:-?})"
      stamp_wine_run "APP_LOAD cmdline: wine start $_w64_win (wow64 start LAST)"
      app_load_mark_slice
      "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine start "$_w64_win" >>/mnt/data/wine-run.out 2>&1 &
      ;;
    wineconsole)
      # 0.1.79: user-backend console → CreateWindow HWND Present can see (CUI)
      # 0.1.87: i386 CUI also needs Windows path so WoW64 engages
      if [ "${APP_WOW64:-0}" = "1" ] && [ -n "${APP_WIN:-}" ]; then
        _wc_tgt="$APP_WIN"
        log "APP_LOAD wow64=1 machine=0x${PE_MACH:-?} mode=wineconsole win=$_wc_tgt"
      else
        _wc_tgt="$APP_TARGET"
      fi
      log "APP_LOAD cmdline: wine wineconsole --backend=user $_wc_tgt (CUI wrap; HWND)"
      log "APP_CUI_WRAP=wineconsole backend=user target=$_wc_tgt"
      APP_CUI_WRAPPED=1
      "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine wineconsole --backend=user "$_wc_tgt" >>/mnt/data/wine-run.out 2>&1 &
      ;;
    *)
      log "APP_LOAD_FATAL unknown mode=$1"
      return 1
      ;;
  esac
  APP_PID=$!
  log "APP_LOAD pid=$APP_PID mode=$APP_LOAD_MODE target=$APP_TARGET win=${APP_WIN:-none} wow64=${APP_WOW64:-0} cui_wrapped=${APP_CUI_WRAPPED:-0}"
}

# 0.1.79: classify why Loaded never produced CreateWindow / HWND
# 0.1.80: also capture CRT / missing-DLL signature → APP_CRT_SIG
# 0.1.84: APP_CRT_SIG also matches d3dcompiler_* (seed via ensure_d3dcompiler_seed)
# 0.1.81: +msgbox channel → reason=messagebox + APP_MSGBOX_DETECT source=wine
app_load_classify_nocreate() {
  APP_MISS_REASON=no_createwindow
  APP_CRT_SIG=
  if [ "${PE_SUB:-}" = "3" ] || [ "${PE_SUB_NAME:-}" = "CUI" ]; then
    if [ "${APP_CUI_WRAPPED:-0}" = "1" ]; then
      APP_MISS_REASON=cui_wrapped_no_hwnd
    else
      APP_MISS_REASON=cui
    fi
  fi
  if [ -f /mnt/data/wine-run.out ]; then
    if grep -qEi 'MessageBox[AW]?|TaskDialog|msgbox:|#32770' /mnt/data/wine-run.out 2>/dev/null; then
      APP_MISS_REASON=messagebox
      _mb_hit=$(grep -oiE 'MessageBox[AW]?|TaskDialog|msgbox:[^ ]+|#32770' /mnt/data/wine-run.out 2>/dev/null | head -1 | tr ' ' '_' || true)
      log "APP_MSGBOX_DETECT source=wine hit=${_mb_hit:-MessageBox}"
    elif grep -qEi 'err:module:|c0000135|STATUS_DLL_NOT_FOUND|Cannot load|Module not found|msvcp|vcruntime|ucrtbase|concrt|d3dcompiler' /mnt/data/wine-run.out 2>/dev/null; then
      APP_MISS_REASON=missing_dll
      # Prefer a concrete CRT / d3dcompiler / module name for KEY_LOG
      APP_CRT_SIG=$(grep -oiE 'd3dcompiler_[0-9]+(\.[Dd][Ll][Ll])?|msvcp[0-9_]+(\.[Dd][Ll][Ll])?|vcruntime[0-9_]+(\.[Dd][Ll][Ll])?|concrt[0-9_]+(\.[Dd][Ll][Ll])?|ucrtbase(\.[Dd][Ll][Ll])?|msvcr[0-9_]+(\.[Dd][Ll][Ll])?' /mnt/data/wine-run.out 2>/dev/null | head -1 | tr 'A-Z' 'a-z' || true)
      if [ -z "${APP_CRT_SIG:-}" ]; then
        APP_CRT_SIG=$(grep -oiE 'err:module:[^:]+:[^ ]+|STATUS_DLL_NOT_FOUND|c0000135' /mnt/data/wine-run.out 2>/dev/null | head -1 | tr ' ' '_' || true)
      fi
      [ -n "${APP_CRT_SIG:-}" ] && log "APP_CRT_SIG=$APP_CRT_SIG"
    elif grep -qEi 'Application could not be started|ShellExecuteEx failed|entry point' /mnt/data/wine-run.out 2>/dev/null; then
      APP_MISS_REASON=launch_fail
    fi
  fi
  if [ -n "${APP_PID:-}" ] && ! kill -0 "$APP_PID" 2>/dev/null; then
    # Prefer early_exit unless a stronger signature already set
    case "$APP_MISS_REASON" in
      no_createwindow|cui|cui_wrapped_no_hwnd) APP_MISS_REASON=early_exit ;;
    esac
  fi
  _alive=0
  if [ -n "${APP_PID:-}" ] && kill -0 "$APP_PID" 2>/dev/null; then _alive=1; fi
  log "APP_CREATEWINDOW_MISS reason=$APP_MISS_REASON pe_sub=${PE_SUB:-?} (${PE_SUB_NAME:-?}) cui_wrapped=${APP_CUI_WRAPPED:-0} alive=${_alive} crt_sig=${APP_CRT_SIG:-none} target=${APP_TARGET:-}"
}
if [ -n "$APP_UNIX" ] && [ -f "$APP_UNIX" ]; then
  SEED="$WINEPREFIX/drive_c/App/app.exe"
  if [ -f "$SEED" ]; then
    log "APP_SEED path=$SEED bytes=$(wc -c < "$SEED" 2>/dev/null | tr -d ' ') mode=${APP_MODE:-?} (seed kept; 0.1.87 i386 uses C:/App)"
  else
    log "APP_SEED MISSING path=$SEED — will try unix=$APP_UNIX (i386 needs seed for WoW64)"
  fi
  sleep 3
  APP_TARGET="$APP_UNIX"
  # PE machine + subsystem (0.1.77): 0x8664=amd64, 0x14c/0x014c=i386; subsystem 2=GUI 3=CUI
  PE_OFF=$(od -An -t u4 -N 4 -j 60 "$APP_TARGET" 2>/dev/null | tr -d ' \n' || echo 0)
  PE_OFF=${PE_OFF:-0}
  PE_MACH="?"
  PE_MAGIC="?"
  PE_SUB="?"
  APP_WOW64=0
  PE_ARCH=unknown
  if [ "$PE_OFF" -gt 0 ] 2>/dev/null; then
    PE_MACH=$(od -An -t x2 -N 2 -j $((PE_OFF + 4)) "$APP_TARGET" 2>/dev/null | tr -d ' \n' || echo '?')
    PE_MAGIC=$(od -An -t x2 -N 2 -j $((PE_OFF + 24)) "$APP_TARGET" 2>/dev/null | tr -d ' \n' || echo '?')
    # OptionalHeader.Subsystem at +0x44 from optional start (= PE_OFF+24+68 = PE_OFF+92)
    PE_SUB=$(od -An -t u2 -N 2 -j $((PE_OFF + 92)) "$APP_TARGET" 2>/dev/null | tr -d ' \n' || echo '?')
    case "${PE_SUB}" in
      2) PE_SUB_NAME=GUI ;;
      3) PE_SUB_NAME=CUI ;;
      1) PE_SUB_NAME=NATIVE ;;
      *) PE_SUB_NAME=other ;;
    esac
    log "APP_PE path=$APP_TARGET pe_off=$PE_OFF machine=0x${PE_MACH:-?} magic=0x${PE_MAGIC:-?} subsystem=${PE_SUB:-?} (${PE_SUB_NAME}) (8664=amd64 14c/014c=i386; 2=GUI 3=CUI)"
    # 0.1.87: od -t x2 prints 014c for i386 — accept both 14c and 014c (was false WARN)
    case "${PE_MACH}" in
      8664|08664) PE_ARCH=amd64 ;;
      14c|014c|14C|014C) PE_ARCH=i386 ;;
      *) log "APP_PE WARN unexpected machine=0x${PE_MACH} — CreateProcess may fail (c000007b)" ;;
    esac
    if [ "${PE_SUB}" = "3" ]; then
      log "APP_PE WARN subsystem=3 CUI — bare wine has no HWND; will wrap wineconsole --backend=user unless X86_CUI_WRAP=0"
    fi
  else
    log "APP_PE WARN pe_off invalid path=$APP_TARGET — not a PE?"
  fi
  if [ ! -r "$APP_TARGET" ]; then
    log "APP_LOAD WARN target not readable path=$APP_TARGET"
  fi
  LS_LINE=$(ls -l "$APP_TARGET" 2>/dev/null | head -1 || true)
  [ -n "$LS_LINE" ] && log "APP_LOAD ls: $LS_LINE"
  # Best-effort noexec hint (proot mounts often opaque)
  if grep -E ' /mnt/app |noexec' /proc/mounts 2>/dev/null | grep -q noexec; then
    log "APP_LOAD WARN /proc/mounts hints noexec near /mnt/app — PE map may fail"
  fi

  # 0.1.87/89: i386 under win64 → WoW64 Windows-path launch (unix absolute known-fail)
  # 0.1.89: prefer CreateProcess (wow64_direct); do NOT lead with start.exe ShellExecute.
  if [ "$PE_ARCH" = "i386" ]; then
    # 0.1.90 A1: NEVER demote APP_WOW64=0 — unix absolute is known-fail for 0x014c
    APP_WOW64=1
    # Ensure C: before any C:/App use (also forced earlier; re-assert here)
    mkdir -p "$WINEPREFIX/dosdevices" "$WINEPREFIX/drive_c/App" 2>/dev/null || true
    ln -sfn ../drive_c "$WINEPREFIX/dosdevices/c:" 2>/dev/null || true
    ln -sfn / "$WINEPREFIX/dosdevices/z:" 2>/dev/null || true
    # 0.1.90 A2: CRT/d3dcompiler/vulkan/wow64 into SysWOW64 before CreateProcess
    ensure_vs_crt_seed
    ensure_d3dcompiler_seed
    ensure_vulkan_seed
    ensure_wow64_seed
    # Seed repair: always try re-cp if missing or empty
    if [ -z "${APP_WIN:-}" ] || [ ! -f "$SEED" ] || [ ! -s "$SEED" ]; then
      if cp -f "$APP_UNIX" "$WINEPREFIX/drive_c/App/app.exe" 2>/dev/null; then
        APP_WIN='C:/App/app.exe'
        SEED="$WINEPREFIX/drive_c/App/app.exe"
        log "APP_SEED repair=re-copy path=$SEED bytes=$(wc -c < "$SEED" 2>/dev/null | tr -d ' ')"
      else
        log "APP_SEED repair=FAIL cp from $APP_UNIX — keep wow64=1; still try C:/App"
        APP_WIN='C:/App/app.exe'
        SEED="$WINEPREFIX/drive_c/App/app.exe"
      fi
    fi
    _seed_vis=no
    [ -f "$SEED" ] && [ -s "$SEED" ] && _seed_vis=yes
    _seed_bytes=$(wc -c < "$SEED" 2>/dev/null | tr -d ' ' || echo 0)
    _c_link=$(readlink "$WINEPREFIX/dosdevices/c:" 2>/dev/null || echo missing)
    _z_link=$(readlink "$WINEPREFIX/dosdevices/z:" 2>/dev/null || echo missing)
    _app_ls=$(ls -la "$WINEPREFIX/drive_c/App" 2>/dev/null | tr '\n' ' ' | head -c 240 || echo missing)
    log "APP_SEED visible=${_seed_vis} path=$SEED bytes=${_seed_bytes} win=${APP_WIN:-C:/App/app.exe} c=${_c_link} z=${_z_link} dir=[${_app_ls}]"
    # 0.1.90 P1: winepath optional + timeout — never block APP_LOAD (wineserver may be busy)
    if command -v timeout >/dev/null 2>&1; then
      _wp=$(timeout 2 "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine winepath -u "${APP_WIN:-C:/App/app.exe}" 2>/dev/null | head -1 | tr -d '\r' || true)
    else
      _wp=
    fi
    if [ -n "${_wp:-}" ]; then
      log "APP_SEED winepath=${APP_WIN:-C:/App/app.exe} → ${_wp}"
      # If winepath resolved but not to our seed file, re-cp once
      if [ -f "$SEED" ] && [ "$_wp" != "$SEED" ]; then
        case "$_wp" in
          *"/drive_c/App/app.exe"*|*/App/app.exe) ;;
          *)
            cp -f "$APP_UNIX" "$SEED" 2>/dev/null && log "APP_SEED repair=winepath-mismatch re-copy" || true
            ;;
        esac
      fi
    fi
    unset _seed_vis _seed_bytes _c_link _z_link _app_ls _wp
    # Keep Windows path even if visible=no — refuse unix absolute for i386
    APP_WIN="${APP_WIN:-C:/App/app.exe}"
    if [ ! -f "$SEED" ] || [ ! -s "$SEED" ]; then
      log "APP_SEED visible=no path=${SEED:-?} win=$APP_WIN — keep wow64=1 (no unix demote); CreateProcess may still fail"
    fi
    ensure_exe_assoc || true
    log "APP_LOAD wow64=1 machine=0x${PE_MACH} decide=direct_cmd64 win=$APP_WIN unix=$APP_UNIX seed=${SEED:-} (64-bit cmd //c CreateProcess; mmap32_parent=0; WINELOADERNOEXEC kept; no i386 MAIN; no /mnt/app mmap)"
    stamp_wine_run "decide=direct_cmd64 win=$APP_WIN seed=${SEED:-} mmap32_parent=0 noexec=kept"
  else
    log "APP_LOAD wow64=0 machine=0x${PE_MACH:-?} arch=${PE_ARCH} (unix ladder)"
  fi

  # 0.1.79: decide CUI wrap (default on for subsystem=3; X86_CUI_WRAP=0/1 override)
  APP_CUI_WRAPPED=0
  if [ "${APP_WOW64:-0}" = "1" ]; then
    APP_SPAWN_MODE=wow64_direct
  else
    APP_SPAWN_MODE=absolute
  fi
  _cui_wrap_mode="${X86_CUI_WRAP:-auto}"
  case "$_cui_wrap_mode" in
    0|off|no|false|FALSE) _do_cui_wrap=0 ;;
    1|on|yes|true|TRUE|wineconsole) _do_cui_wrap=1 ;;
    *)
      if [ "${PE_SUB}" = "3" ]; then _do_cui_wrap=1; else _do_cui_wrap=0; fi
      ;;
  esac
  if [ "$_do_cui_wrap" = "1" ]; then
    APP_SPAWN_MODE=wineconsole
    log "APP_CUI_WRAP decide=yes pe_sub=${PE_SUB:-?} X86_CUI_WRAP=${_cui_wrap_mode} mode=wineconsole wow64=${APP_WOW64:-0}"
  else
    log "APP_CUI_WRAP decide=no pe_sub=${PE_SUB:-?} X86_CUI_WRAP=${_cui_wrap_mode}"
  fi

  app_load_spawn "$APP_SPAWN_MODE"
  sleep 2
  if app_load_wait_rc; then
    log "APP_LOAD failed: process exited quickly pid=$APP_PID rc=${APP_RC:-?} mode=$APP_LOAD_MODE target=$APP_TARGET wow64=${APP_WOW64:-0}"
    app_load_dump_fail_sigs
    if [ "${APP_WOW64:-0}" = "1" ]; then
      # 0.1.89 i386 ladder: wow64_direct → wow64_explorer → wow64_start (still C:/).
      # Do NOT fall to unix absolute. start.exe is LAST (ShellExecute diag only).
      if [ "$APP_LOAD_MODE" = "wineconsole" ]; then
        APP_CUI_WRAPPED=0
        log "APP_CUI_WRAP WARN wineconsole quick-exit — falling back to wow64_direct"
        app_load_spawn wow64_direct
        sleep 2
        if ! app_load_wait_rc; then
          log "APP_LOAD wow64 alive after 2s pid=$APP_PID mode=$APP_LOAD_MODE (after wineconsole fail)"
        fi
      fi
      if app_load_wait_rc || [ -z "${APP_PID:-}" ] || ! kill -0 "$APP_PID" 2>/dev/null; then
        if [ "$APP_LOAD_MODE" = "wow64_direct" ]; then
          log "APP_LOAD wow64 direct quick-exit — trying start_exec64 ladder-2 (C:/App; mmap32_parent=0; noexec=kept)"
          app_load_spawn wow64_unix_seed
          sleep 2
        fi
        if app_load_wait_rc || [ -z "${APP_PID:-}" ] || ! kill -0 "$APP_PID" 2>/dev/null; then
          if [ "$APP_LOAD_MODE" = "wow64_unix_seed" ]; then
            log "APP_LOAD wow64 unix_seed quick-exit — trying explorer /desktop + C:/App"
            app_load_spawn wow64_explorer
            sleep 2
          fi
        fi
        if app_load_wait_rc || [ -z "${APP_PID:-}" ] || ! kill -0 "$APP_PID" 2>/dev/null; then
          if [ "$APP_LOAD_MODE" != "wow64_start" ]; then
            log "APP_LOAD wow64 explorer/direct/unix_seed quick-exit — trying start.exe LAST (ShellExecute diag)"
            app_load_spawn wow64_start
            sleep 2
            if app_load_wait_rc; then
              log "APP_LOAD_FATAL rc=${APP_RC:-?} pe_target=$APP_TARGET pe_machine=0x${PE_MACH} (direct+explorer+start exited quickly; no Loaded)"
              log "APP_LOAD wow64 failed: FATAL — check APP_WOW64_SEED / APP_SEED visible / wine-run.out (c0000135/c000007b/ShellExecute)"
              app_load_dump_fail_sigs
            else
              log "APP_LOAD retry alive after 2s pid=$APP_PID mode=wow64_start"
            fi
          else
            log "APP_LOAD_FATAL rc=${APP_RC:-?} pe_target=$APP_TARGET pe_machine=0x${PE_MACH} (wow64_start exited quickly; no Loaded)"
            app_load_dump_fail_sigs
          fi
        else
          log "APP_LOAD retry alive after 2s pid=$APP_PID mode=$APP_LOAD_MODE"
        fi
      fi
    else
      # amd64: keep 0.1.67 unix ladder — refuse C:/App
      log "APP_LOAD unix failed: refusing C:/App Windows path fallback (0.1.67; amd64)"
      if [ "$APP_LOAD_MODE" = "wineconsole" ]; then
        APP_CUI_WRAPPED=0
        log "APP_CUI_WRAP WARN wineconsole quick-exit — falling back to bare unix spawn"
        app_load_spawn absolute
        sleep 2
        if ! app_load_wait_rc; then
          log "APP_LOAD unix alive after 2s pid=$APP_PID mode=$APP_LOAD_MODE (after wineconsole fail)"
        fi
      fi
      if app_load_wait_rc || [ -z "${APP_PID:-}" ] || ! kill -0 "$APP_PID" 2>/dev/null; then
        APP_DIR=$(dirname "$APP_TARGET")
        APP_BASE=$(basename "$APP_TARGET")
        if [ -d "$APP_DIR" ] && [ -f "$APP_DIR/$APP_BASE" ]; then
          app_load_spawn relative
          sleep 2
          if app_load_wait_rc; then
            log "APP_LOAD unix failed: relative exited quickly pid=$APP_PID rc=${APP_RC:-?}"
            app_load_dump_fail_sigs
            app_load_spawn start_unix
            sleep 2
            if app_load_wait_rc; then
              log "APP_LOAD_FATAL rc=${APP_RC:-?} pe_target=$APP_TARGET pe_machine=0x${PE_MACH} (absolute+relative+start_unix all exited quickly; no Loaded)"
              log "APP_LOAD unix failed: FATAL — check APP_PE machine= and wine-run.out (c0000135/c000007b)"
              app_load_dump_fail_sigs
            else
              log "APP_LOAD retry alive after 2s pid=$APP_PID mode=start_unix"
            fi
          else
            log "APP_LOAD retry alive after 2s pid=$APP_PID mode=relative"
          fi
        else
          log "APP_LOAD_FATAL rc=${APP_RC:-?} pe_target=$APP_TARGET (no retry dir)"
        fi
      fi
    fi
  else
    log "APP_LOAD alive after 2s pid=$APP_PID mode=$APP_LOAD_MODE wow64=${APP_WOW64:-0}"
  fi
elif [ -n "$APP_WIN" ]; then
  log "APP_LOAD WARN no unix file — have Windows path $APP_WIN only"
  log "APP_LOAD unix failed: APP_UNIX missing or not a file (argv had Windows-only seed)"
  stamp_wine_run "APP_LOAD skip WARN APP_UNIX missing win=$APP_WIN"
else
  log "APP_LOAD_FATAL skip reason=no_APP_UNIX argv=$* mnt_app=[$(ls /mnt/app 2>/dev/null | tr '\n' ',' | head -c 200)]"
  stamp_wine_run "APP_LOAD_FATAL skip reason=no_APP_UNIX"
fi

# 0.1.77/78: after APP_LOAD, wait for app HWND in .x86-x11-ready (Loaded-but-no-HWND)
# 0.1.78: on timeout after strict Loaded → write .x86-nohwnd-req, backoff wait, AppActivate nudge
app_load_nohwnd_request() {
  # $1 = basename hint (PE file name)
  _bn="${1:-app.exe}"
  printf 'basename=%s\nwant=mapraise\nstamp=0.1.81\n' "$_bn" > /mnt/data/.x86-nohwnd-req
  log "NOHWND_MAP req written basename=$_bn path=/mnt/data/.x86-nohwnd-req"
}
app_load_marker_nohwnd_scan() {
  [ -f /mnt/data/.x86-x11-ready ] || return 1
  _m=$(head -c 400 /mnt/data/.x86-x11-ready 2>/dev/null | tr '\n' ' ')
  case "$_m" in
    *" nohwnd_scan="*) return 0 ;;
  esac
  return 1
}
app_load_appactivate_nudge() {
  # Best-effort: WScript AppActivate may trigger winex11 ShowWindow/SetForeground path.
  # 0.1.81: also try common MessageBox titles (Error/Warning/錯誤/警告).
  _title="${1:-}"
  _bn="${2:-app.exe}"
  _extra="${3:-}"
  _vbs=/mnt/data/.x86-appactivate.vbs
  _stem=$(printf '%s' "$_bn" | sed 's/[.][Ee][Xx][Ee]$//')
  cat > "$_vbs" << VBS
On Error Resume Next
Set sh = CreateObject("WScript.Shell")
ok = sh.AppActivate("${_bn}")
If Not ok Then ok = sh.AppActivate("${_stem}")
If Not ok And Len("${_title}") > 0 Then ok = sh.AppActivate("${_title}")
If Not ok Then ok = sh.AppActivate("Error")
If Not ok Then ok = sh.AppActivate("Warning")
If Not ok Then ok = sh.AppActivate("錯誤")
If Not ok Then ok = sh.AppActivate("警告")
If Not ok Then ok = sh.AppActivate("Microsoft Visual C++")
If Not ok And Len("${_extra}") > 0 Then ok = sh.AppActivate("${_extra}")
WScript.Echo "AppActivate=" & ok
VBS
  log "NOHWND_MAP AppActivate nudge basename=$_bn title=${_title:-} extra=${_extra:-}"
  set +e
  "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine cscript //nologo "Z:\mnt\data\.x86-appactivate.vbs" >>/mnt/data/wine-run.out 2>&1
  # Also try unix path form if Z: map differs
  "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine start /unix "$_vbs" >>/mnt/data/wine-run.out 2>&1
  set -e
}

if [ -n "${APP_TARGET:-}" ] && [ -n "${APP_PID:-}" ]; then
  HWND_WAIT_MAX="${X86_HWND_WAIT_MAX:-80}"
  HWND_WAIT_I=0
  APP_BASE_NAME=$(basename "$APP_TARGET")
  APP_BASE_NAME=${APP_BASE_NAME:-app.exe}
  log "APP_HWND_WAIT marker for app HWND (max ${HWND_WAIT_MAX}x0.1s) basename=$APP_BASE_NAME"
  while [ "$HWND_WAIT_I" -lt "$HWND_WAIT_MAX" ]; do
    if app_load_has_loaded; then
      APP_LOADED_STRICT=1
    fi
    if app_load_marker_app_hwnd; then
      APP_HWND_SEEN=1
      log "APP_HWND_WAIT ready t=${HWND_WAIT_I}0ms loaded=${APP_LOADED_STRICT} marker=$(head -c 160 /mnt/data/.x86-x11-ready 2>/dev/null | tr '\n' ' ')"
      break
    fi
    # App process died early — stop waiting
    if [ -n "$APP_PID" ] && ! kill -0 "$APP_PID" 2>/dev/null; then
      log "APP_HWND_WAIT abort app dead pid=$APP_PID t=${HWND_WAIT_I}0ms loaded=${APP_LOADED_STRICT}"
      break
    fi
    sleep 0.1
    HWND_WAIT_I=$((HWND_WAIT_I + 1))
  done
  if [ "$APP_HWND_SEEN" != "1" ]; then
    if app_load_has_loaded || [ "$APP_LOADED_STRICT" = "1" ]; then
      APP_LOADED_STRICT=1
      log "APP_LOAD WARN Loaded-but-no-HWND t=${HWND_WAIT_I}0ms target=$APP_TARGET (strict Loaded; marker still desk-only or missing)"
      # 0.1.78: request host rescan + backoff + guest AppActivate
      app_load_nohwnd_request "$APP_BASE_NAME"
      app_load_appactivate_nudge "" "$APP_BASE_NAME"
      HWND_BACKOFF_MAX="${X86_HWND_BACKOFF_MAX:-60}"
      HWND_BACKOFF_I=0
      log "APP_HWND_WAIT backoff after nohwnd_req (max ${HWND_BACKOFF_MAX}x0.2s)"
      while [ "$HWND_BACKOFF_I" -lt "$HWND_BACKOFF_MAX" ]; do
        if app_load_marker_app_hwnd; then
          APP_HWND_SEEN=1
          log "APP_HWND_WAIT ready after NOHWND_MAP t_backoff=${HWND_BACKOFF_I}x0.2s marker=$(head -c 200 /mnt/data/.x86-x11-ready 2>/dev/null | tr '\n' ' ')"
          break
        fi
        if [ -n "$APP_PID" ] && ! kill -0 "$APP_PID" 2>/dev/null; then
          log "APP_HWND_WAIT backoff abort app dead"
          break
        fi
        # Re-touch req every ~2s if still no scan response
        if [ $((HWND_BACKOFF_I % 10)) -eq 0 ] && ! app_load_marker_nohwnd_scan; then
          app_load_nohwnd_request "$APP_BASE_NAME"
        fi
        sleep 0.2
        HWND_BACKOFF_I=$((HWND_BACKOFF_I + 1))
      done
      if [ "$APP_HWND_SEEN" != "1" ]; then
        if app_load_marker_nohwnd_scan; then
          log "APP_LOAD WARN Loaded-but-no-HWND after NOHWND_MAP scan=$(head -c 200 /mnt/data/.x86-x11-ready 2>/dev/null | tr '\n' ' ')"
        else
          log "APP_LOAD WARN Loaded-but-no-HWND after NOHWND_MAP (no host scan response)"
        fi
        # 0.1.79: classify CreateWindow miss; one CUI wineconsole relaunch if not yet wrapped
        # 0.1.80: ensure VS CRT on disk before any relaunch (idempotent)
        app_load_classify_nocreate
        ensure_vs_crt_seed
        ensure_d3dcompiler_seed
        ensure_vulkan_seed
        ensure_wow64_seed
        _scan_body=$(head -c 240 /mnt/data/.x86-x11-ready 2>/dev/null | tr '\n' ' ')
        _scan_none=0
        case "$_scan_body" in
          *"nohwnd_scan=none"*|*"nohwnd_scan=NONE"*) _scan_none=1 ;;
        esac
        if ! app_load_marker_nohwnd_scan; then _scan_none=1; fi
        if [ "${APP_CUI_RELAUNCHED:-0}" = "0" ] && [ "${APP_CUI_WRAPPED:-0}" != "1" ] \
            && { [ "${PE_SUB}" = "3" ] || [ "${APP_MISS_REASON}" = "cui" ] || [ "${X86_CUI_WRAP:-}" = "1" ]; }; then
          APP_CUI_RELAUNCHED=1
          log "APP_CUI_WRAP relaunch once reason=${APP_MISS_REASON} scan_none=${_scan_none}"
          if [ -n "${APP_PID:-}" ] && kill -0 "$APP_PID" 2>/dev/null; then
            kill -TERM "$APP_PID" 2>/dev/null || true
            sleep 1
            kill -KILL "$APP_PID" 2>/dev/null || true
            wait "$APP_PID" 2>/dev/null || true
          fi
          app_load_spawn wineconsole
          sleep 2
          if app_load_wait_rc; then
            log "APP_CUI_WRAP relaunch quick-exit rc=${APP_RC:-?}"
            app_load_classify_nocreate
          else
            log "APP_CUI_WRAP relaunch alive pid=$APP_PID"
            HWND_CUI_I=0
            HWND_CUI_MAX="${X86_HWND_CUI_WAIT_MAX:-50}"
            while [ "$HWND_CUI_I" -lt "$HWND_CUI_MAX" ]; do
              if app_load_marker_app_hwnd; then
                APP_HWND_SEEN=1
                log "APP_HWND_WAIT ready after CUI wineconsole t=${HWND_CUI_I}x0.2s marker=$(head -c 160 /mnt/data/.x86-x11-ready 2>/dev/null | tr '\n' ' ')"
                break
              fi
              if [ -n "$APP_PID" ] && ! kill -0 "$APP_PID" 2>/dev/null; then
                log "APP_HWND_WAIT CUI abort app dead"
                break
              fi
              # Re-request host map if console HWND created unmapped
              if [ $((HWND_CUI_I % 10)) -eq 0 ]; then
                app_load_nohwnd_request "$APP_BASE_NAME"
              fi
              sleep 0.2
              HWND_CUI_I=$((HWND_CUI_I + 1))
            done
            if [ "$APP_HWND_SEEN" != "1" ]; then
              app_load_classify_nocreate
              log "APP_LOAD WARN Loaded-but-no-HWND after CUI wineconsole relaunch"
            fi
          fi
        elif [ "${APP_CRT_RELAUNCHED:-0}" = "0" ]             && { [ "${APP_MISS_REASON}" = "missing_dll" ] || [ -n "${APP_CRT_SIG:-}" ]; }; then
          # 0.1.80: GUI missing CRT/DLL — seed VS CRT + one relaunch (mirror CUI pattern)
          APP_CRT_RELAUNCHED=1
          ensure_vs_crt_seed
          ensure_d3dcompiler_seed
          ensure_vulkan_seed
          ensure_wow64_seed
          log "APP_CRT_RELAUNCH once reason=${APP_MISS_REASON} sig=${APP_CRT_SIG:-none} scan_none=${_scan_none} pe_mach=0x${PE_MACH:-?}"
          if [ -n "${APP_PID:-}" ] && kill -0 "$APP_PID" 2>/dev/null; then
            kill -TERM "$APP_PID" 2>/dev/null || true
            sleep 1
            kill -KILL "$APP_PID" 2>/dev/null || true
            wait "$APP_PID" 2>/dev/null || true
          fi
          # Respawn same mode (wineconsole if wrapped; else wow64_direct for i386 / absolute for amd64)
          if [ "${APP_CUI_WRAPPED:-0}" = "1" ]; then
            app_load_spawn wineconsole
          elif [ "${APP_WOW64:-0}" = "1" ]; then
            app_load_spawn wow64_direct
          else
            app_load_spawn absolute
          fi
          sleep 2
          if app_load_wait_rc; then
            log "APP_CRT_RELAUNCH quick-exit rc=${APP_RC:-?}"
            app_load_classify_nocreate
          else
            log "APP_CRT_RELAUNCH alive pid=$APP_PID"
            HWND_CRT_I=0
            HWND_CRT_MAX="${X86_HWND_CRT_WAIT_MAX:-50}"
            while [ "$HWND_CRT_I" -lt "$HWND_CRT_MAX" ]; do
              if app_load_marker_app_hwnd; then
                APP_HWND_SEEN=1
                log "APP_HWND_WAIT ready after CRT seed relaunch t=${HWND_CRT_I}x0.2s marker=$(head -c 160 /mnt/data/.x86-x11-ready 2>/dev/null | tr '\n' ' ')"
                break
              fi
              if [ -n "$APP_PID" ] && ! kill -0 "$APP_PID" 2>/dev/null; then
                log "APP_HWND_WAIT CRT abort app dead"
                break
              fi
              if [ $((HWND_CRT_I % 10)) -eq 0 ]; then
                app_load_nohwnd_request "$APP_BASE_NAME"
              fi
              sleep 0.2
              HWND_CRT_I=$((HWND_CRT_I + 1))
            done
            if [ "$APP_HWND_SEEN" != "1" ]; then
              app_load_classify_nocreate
              log "APP_LOAD WARN Loaded-but-no-HWND after CRT seed relaunch"
              # 0.1.81: CRT fixed load but MessageBox may still be the only window
              if [ "${APP_MISS_REASON}" = "messagebox" ] || grep -qEi 'MessageBox[AW]?|TaskDialog|msgbox:|#32770' /mnt/data/wine-run.out 2>/dev/null; then
                if [ "${APP_MSGBOX_NUDGED:-0}" = "0" ]; then
                  APP_MSGBOX_NUDGED=1
                  APP_MISS_REASON=messagebox
                  log "APP_MSGBOX_DETECT source=wine action=title_nudge_after_crt basename=$APP_BASE_NAME"
                  app_load_appactivate_nudge "Error" "$APP_BASE_NAME" "Warning"
                  app_load_nohwnd_request "$APP_BASE_NAME"
                  sleep 2
                  if app_load_marker_app_hwnd; then
                    APP_HWND_SEEN=1
                    log "APP_HWND_WAIT ready after MSGBOX nudge post-CRT marker=$(head -c 160 /mnt/data/.x86-x11-ready 2>/dev/null | tr '\n' ' ')"
                  fi
                fi
              fi
            fi
          fi
        elif { [ "${APP_MISS_REASON}" = "messagebox" ] || grep -qEi 'MessageBox[AW]?|TaskDialog|msgbox:|#32770' /mnt/data/wine-run.out 2>/dev/null; } \
            && [ "${APP_MSGBOX_NUDGED:-0}" = "0" ]; then
          # 0.1.81: MessageBox still up after CRT/CUI — title AppActivate + host rescan
          APP_MSGBOX_NUDGED=1
          APP_MISS_REASON=messagebox
          log "APP_MSGBOX_DETECT source=wine action=title_nudge basename=$APP_BASE_NAME"
          app_load_appactivate_nudge "Error" "$APP_BASE_NAME" "Warning"
          app_load_nohwnd_request "$APP_BASE_NAME"
          HWND_MB_I=0
          HWND_MB_MAX="${X86_HWND_MSGBOX_WAIT_MAX:-40}"
          while [ "$HWND_MB_I" -lt "$HWND_MB_MAX" ]; do
            if app_load_marker_app_hwnd; then
              APP_HWND_SEEN=1
              log "APP_HWND_WAIT ready after MSGBOX title nudge t=${HWND_MB_I}x0.2s marker=$(head -c 160 /mnt/data/.x86-x11-ready 2>/dev/null | tr '\n' ' ')"
              break
            fi
            if [ -n "$APP_PID" ] && ! kill -0 "$APP_PID" 2>/dev/null; then
              log "APP_HWND_WAIT MSGBOX abort app dead"
              break
            fi
            if [ $((HWND_MB_I % 10)) -eq 0 ]; then
              app_load_nohwnd_request "$APP_BASE_NAME"
              app_load_appactivate_nudge "錯誤" "$APP_BASE_NAME" "警告"
            fi
            sleep 0.2
            HWND_MB_I=$((HWND_MB_I + 1))
          done
          if [ "$APP_HWND_SEEN" != "1" ]; then
            app_load_classify_nocreate
            log "APP_LOAD WARN Loaded-but-no-HWND after MSGBOX title nudge reason=${APP_MISS_REASON}"
          fi
        elif [ "$_scan_none" = "1" ]; then
          log "APP_CREATEWINDOW_MISS scan=none (no X candidates) — not a Present/Turnip map miss"
        fi
      fi
    elif app_load_path_seen; then
      log "APP_HWND_WAIT timeout t=${HWND_WAIT_I}0ms path_seen=1 loaded=0 (argv echo ≠ Loaded — wait for loaddll)"
    else
      log "APP_HWND_WAIT timeout t=${HWND_WAIT_I}0ms loaded=0 path_seen=0"
    fi
  fi
fi

# X window tree diagnostic (best-effort) after a short settle
sleep 2
if command -v xwininfo >/dev/null 2>&1; then
  xwininfo -root -tree 2>/dev/null | head -40 | while IFS= read -r ln; do
    log "XWININFO: $ln"
  done || true
elif command -v xdpyinfo >/dev/null 2>&1; then
  xdpyinfo -display :0 2>/dev/null | head -20 | while IFS= read -r ln; do
    log "XDPYINFO: $ln"
  done || true
else
  log "XWININFO skip (no xwininfo/xdpyinfo in guest)"
fi
# Early app-load fingerprint (0.1.77: strict Loaded; path_seen is soft-only)
if grep -qE 'ShellExecuteEx failed|Application could not be started' /mnt/data/wine-run.out 2>/dev/null; then
  log "WARN: WINE_START app launch failure signature in wine-run.out"
fi
if app_load_has_loaded; then
  APP_LOADED_STRICT=1
  log "APP_LOAD fingerprint seen early (strict Loaded L\"…app.exe\")"
elif app_load_path_seen; then
  log "APP_LOAD path_seen early (argv/path echo only — NOT Loaded yet)"
fi
# 0.1.92: start.exe expected with /exec; only WARN on ShellExecuteEx without Loaded app
if app_load_fail_sig_present 2>/dev/null; then
  log "WARN: APP_LOAD fail_sig in current slice (see APP_LOAD fail_sig lines)"
elif grep -qE 'ShellExecuteEx failed' /mnt/data/wine-run.out 2>/dev/null && ! app_load_has_loaded; then
  log "WARN: APP_LOAD ShellExecuteEx in wine-run (may be prior-mode / desktop pollution — check slice)"
fi
# Early 32-bit detect (2s) — repair once rather than silent WINE_EXIT=1
sleep 2
if grep -qE "is a 32-bit installation|not a valid registry file" /mnt/data/wine-run.out 2>/dev/null; then
  log "WINE_ARCH FATAL 32bit-prefix/invalid-reg detected in WINE_START"
  kill -TERM "$WINE_PID" 2>/dev/null || true
  sleep 1
  kill -KILL "$WINE_PID" 2>/dev/null || true
  wait "$WINE_PID" 2>/dev/null || true
  if wine_arch_auto_repair_once; then
    ensure_prefix_exec
    ensure_graphics_x11_reg
    rpcss_prestart
    : > /mnt/data/wine-run.out
    export WINEDEBUG="${WINE_START_WINEDEBUG:-err+all,+seh,+loaddll,+process,+module,+ole,+rpcss,+x11drv,+msgbox}"
    "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine explorer /desktop=x86,${W}x${H},x11 >>/mnt/data/wine-run.out 2>&1 &
    WINE_PID=$!
    log "WINE_START retry-after-arch-repair pid=$WINE_PID (desktop only)"
    if [ -n "${APP_UNIX:-}" ] && [ -f "$APP_UNIX" ]; then
      sleep 3
      APP_TARGET="$APP_UNIX"
      log "APP_LOAD retry cmdline: wine $APP_TARGET (unix)"
      "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine "$APP_TARGET" >>/mnt/data/wine-run.out 2>&1 &
      APP_PID=$!
      log "APP_LOAD retry pid=$APP_PID"
    fi
    sleep 2
    if grep -qE "is a 32-bit installation|not a valid registry file" /mnt/data/wine-run.out 2>/dev/null; then
      log "FATAL: WINE_ARCH 32bit-prefix/invalid-reg persists after repair"
      kill -KILL "$WINE_PID" 2>/dev/null || true
      [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
      [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
      exit 32
    fi
  else
    log "FATAL: WINE_ARCH 32bit-prefix unrepaired — refuse"
    [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
    [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
    exit 32
  fi
fi
HB=0
LAST_BYTES=0
while kill -0 "$WINE_PID" 2>/dev/null; do
  HB=$((HB+1))
  BYTES=$(wc -c < /mnt/data/wine-run.out 2>/dev/null | tr -d ' \n' || echo 0)
  BYTES=${BYTES:-0}
  # Stream new wine-run bytes into KEY log so Session is not stuck at START
  if [ "$BYTES" -gt "$LAST_BYTES" ] 2>/dev/null; then
    dd if=/mnt/data/wine-run.out bs=1 skip="$LAST_BYTES" count=$((BYTES-LAST_BYTES)) 2>/dev/null | tee -a /mnt/data/gpu-stack.log >/dev/null
    LAST_BYTES=$BYTES
  fi
  DO_HB=0
  # 0.1.90: also tick 8/12/15/20/45 so hang-alive ladder + APP_RELAUNCH fire on time
  # (pre-090: only 10/30/60 → "t>=15 relaunch" actually waited until HB=30)
  case "$HB" in
    8|10|12|15|20|30|45|60) DO_HB=1 ;;
  esac
  if [ $((HB % 30)) -eq 0 ]; then DO_HB=1; fi
  if [ "$DO_HB" = "1" ]; then
    X0_NOW=no
    [ -S /tmp/.X11-unix/X0 ] || [ -e /tmp/.X11-unix/X0 ] && X0_NOW=ok
    GROW=$((BYTES-LAST_BYTES))
    # LAST_BYTES already caught up above; report absolute size
    APP_ALIVE=0
    # 0.1.77: app_seen / APP_ALIVE require strict Loaded (or wine "Detected running" with app.exe)
    if app_load_has_loaded \
       || grep -qE 'Detected running wine with ".*app\.exe"|Loaded L".*app\.exe' /mnt/data/wine-run.out 2>/dev/null; then
      APP_ALIVE=1
      APP_LOADED_STRICT=1
    fi
    # Soft path_seen for DIAG only (does NOT set APP_ALIVE)
    APP_PATH_SEEN=0
    if app_load_path_seen; then APP_PATH_SEEN=1; fi
    if app_load_marker_app_hwnd; then APP_HWND_SEEN=1; fi
    # 0.1.90: hang-alive ladder advance (wow64) — do not wait for ≤2s death.
    # Boss 087: fail without quick-exit → missing-Loaded. Kill stuck pid → next step.
    if [ "${APP_WOW64:-0}" = "1" ] && [ -n "${APP_WIN:-}" ] && [ "${APP_LADDER_HANG_STEP:-0}" -lt 4 ]; then
      if ! app_load_has_loaded; then
        _hang_adv=
        _from_mode="${APP_LOAD_MODE:-?}"
        if [ "$HB" -ge 8 ] && [ "$HB" -le 12 ]; then
          case "$_from_mode" in
            wow64_direct|wineconsole)
              if app_load_fail_sig_present || [ "$HB" -ge 12 ]; then
                if app_load_fail_sig_present; then _hang_adv=fail_sig; else _hang_adv=no_loaded_t; fi
                _to_mode=wow64_unix_seed
              fi
              ;;
            wow64_unix_seed)
              if [ "$HB" -ge 10 ]; then
                if app_load_fail_sig_present; then _hang_adv=fail_sig; else _hang_adv=no_loaded_t; fi
                _to_mode=wow64_explorer
              fi
              ;;
            wow64_explorer)
              if [ "$HB" -ge 10 ]; then
                if app_load_fail_sig_present; then _hang_adv=fail_sig; else _hang_adv=no_loaded_t; fi
                _to_mode=wow64_start
              fi
              ;;
            wow64_start)
              if [ "$HB" -ge 12 ]; then
                app_load_dump_fail_sigs
                log "APP_LOAD ladder advance exhausted mode=wow64_start t=${HB}s (no Loaded; fail_hr dumped)"
                APP_LADDER_HANG_STEP=4
              fi
              ;;
          esac
        fi
        if [ -n "${_hang_adv:-}" ] && [ -n "${_to_mode:-}" ]; then
          log "APP_LOAD ladder advance reason=${_hang_adv} t=${HB}s from=${_from_mode} to=${_to_mode} wow64=1"
          app_load_dump_fail_sigs
          app_load_kill_app "ladder_hang"
          app_load_spawn "$_to_mode"
          case "$_to_mode" in
            wow64_unix_seed) APP_LADDER_HANG_STEP=1 ;;
            wow64_explorer) APP_LADDER_HANG_STEP=2 ;;
            wow64_start) APP_LADDER_HANG_STEP=3 ;;
          esac
          sleep 2
          if app_load_wait_rc; then
            log "APP_LOAD ladder advance quick-exit pid=$APP_PID rc=${APP_RC:-?} mode=$APP_LOAD_MODE"
            app_load_dump_fail_sigs
          else
            log "APP_LOAD ladder advance alive after 2s pid=$APP_PID mode=$APP_LOAD_MODE"
          fi
        fi
        unset _hang_adv _from_mode _to_mode
      fi
    fi
    # 0.1.64/0.1.77: if app never strict-Loaded by 15s, relaunch once (path echo alone ≠ Loaded)
    if [ "${APP_RELAUNCHED:-0}" = "0" ] && [ -n "${APP_TARGET:-}" ] && [ "$HB" -ge 15 ]; then
      if ! app_load_has_loaded; then
        if grep -qE 'ShellExecuteEx failed' /mnt/data/wine-run.out 2>/dev/null; then
          log "APP_RELAUNCH note: ShellExecuteEx still in log — wow64=${APP_WOW64:-0} (i386 uses C:/App; amd64 unix)"
        fi
        if [ "$APP_PATH_SEEN" = "1" ]; then
          log "APP_RELAUNCH note: path_seen=1 but no strict Loaded — relaunch anyway"
        fi
        APP_RELAUNCHED=1
        APP_LADDER_HANG_STEP=0
        if [ "${APP_WOW64:-0}" = "1" ] && [ -n "${APP_WIN:-}" ]; then
          log "APP_RELAUNCH once target=$APP_WIN via wow64_direct (no strict Loaded by t=${HB}s; machine=0x${PE_MACH})"
          log "APP_LOAD wow64=1 relaunch: wine cmd //c C:/App/app.exe (APP_RELAUNCH direct_cmd64; mmap32_parent=0; WINELOADERNOEXEC kept)"
          app_load_dump_fail_sigs
          app_load_kill_app "APP_RELAUNCH"
          app_load_spawn wow64_direct
          sleep 2
          if app_load_wait_rc; then
            log "APP_LOAD wow64 failed: relaunch exited quickly pid=$APP_PID rc=${APP_RC:-?} mode=wow64_direct — try unix_seed"
            app_load_dump_fail_sigs
            app_load_spawn wow64_unix_seed
            sleep 2
            if app_load_wait_rc; then
              log "APP_LOAD wow64 failed: unix_seed quick-exit — explorer then start LAST"
              app_load_dump_fail_sigs
              app_load_spawn wow64_explorer
              sleep 2
              if app_load_wait_rc; then
                app_load_dump_fail_sigs
                app_load_spawn wow64_start
                sleep 2
                if app_load_wait_rc; then
                  log "APP_LOAD_FATAL rc=${APP_RC:-?} pe_target=$APP_TARGET (APP_RELAUNCH direct+unix_seed+explorer+start quick-exit)"
                  app_load_dump_fail_sigs
                fi
              fi
            fi
          fi
          log "APP_RELAUNCH pid=$APP_PID mode=$APP_LOAD_MODE wow64=1"
        else
          log "APP_RELAUNCH once target=$APP_TARGET via start /unix (no strict Loaded by t=${HB}s)"
          log "APP_LOAD unix failed: no Loaded fingerprint by t=${HB}s — relaunch start /unix (still no C:/App)"
          log "APP_LOAD retry: wine start /unix $APP_TARGET (APP_RELAUNCH)"
          "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine start /unix "$APP_TARGET" >>/mnt/data/wine-run.out 2>&1 &
          APP_PID=$!
          APP_LOAD_MODE=start_unix
          log "APP_RELAUNCH pid=$APP_PID mode=start_unix"
          sleep 2
          if [ -n "$APP_PID" ] && ! kill -0 "$APP_PID" 2>/dev/null; then
            wait "$APP_PID" 2>/dev/null
            APP_RC=$?
            log "APP_LOAD unix failed: relaunch exited quickly pid=$APP_PID rc=${APP_RC:-?} mode=start_unix (no C:/ fallback)"
            log "APP_LOAD_FATAL rc=${APP_RC:-?} pe_target=$APP_TARGET (APP_RELAUNCH start_unix quick-exit)"
          fi
        fi
      else
        APP_RELAUNCHED=1
        APP_LOADED_STRICT=1
        log "APP_LOAD confirmed by t=${HB}s (strict Loaded) — no relaunch"
        if [ "$APP_HWND_SEEN" != "1" ]; then
          log "APP_LOAD WARN Loaded-but-no-HWND at HB t=${HB}s (strict Loaded; marker desk-only?)"
          APP_BASE_NAME=${APP_BASE_NAME:-$(basename "${APP_TARGET:-app.exe}")}
          app_load_nohwnd_request "$APP_BASE_NAME"
        fi
      fi
    fi
    if grep -qE 'ShellExecuteEx failed|Application could not be started' /mnt/data/wine-run.out 2>/dev/null; then
      log "WINE_HB note: ShellExecute/app-start failure still in wine-run.out"
    fi
    # 0.1.66/0.1.77: strict Loaded required — path echo alone does NOT clear missing-Loaded
    if [ "$HB" -ge 45 ] && [ -n "${APP_TARGET:-}" ]; then
      if ! app_load_has_loaded; then
        log "APP_LOAD unix failed: no Loaded fingerprint by t=${HB}s target=${APP_TARGET} path_seen=${APP_PATH_SEEN} mode=${APP_LOAD_MODE:-?} wow64=${APP_WOW64:-0} ladder_hang=${APP_LADDER_HANG_STEP:-0}"
        log "APP_LOAD_FATAL missing-Loaded t=${HB}s (desktop may paint; guest app content absent)"
        app_load_dump_fail_sigs
      elif [ "$APP_HWND_SEEN" != "1" ]; then
        log "APP_LOAD WARN Loaded-but-no-HWND t=${HB}s target=${APP_TARGET} (strict Loaded; no app HWND in marker)"
      fi
    fi
    ERR_N=$(grep -cE '^[0-9a-f]+:err:' /mnt/data/wine-run.out 2>/dev/null || echo 0)
    ERR_N=$(echo "$ERR_N" | tr -d ' \n')
    log "WINE_HB t=${HB}s pid=$WINE_PID alive=1 wine-run_bytes=$BYTES x0=$X0_NOW app_seen=$APP_ALIVE loaded=${APP_LOADED_STRICT:-0} path_seen=${APP_PATH_SEEN:-0} hwnd=${APP_HWND_SEEN:-0} err_lines=${ERR_N:-0}"
    if [ "$BYTES" -eq 0 ]; then
      log "WINE_HB note: wine-run.out empty — Wine silent (debug light); Present may still be waiting for first drawable"
    fi
    # Tail recent wine-run for KEY panel
    tail -8 /mnt/data/wine-run.out 2>/dev/null | while IFS= read -r ln; do
      log "WINE_HB.tail: $ln"
    done || true
  fi
  # 0.1.90: every-second hang-alive probe (even when DO_HB=0) so we never miss the window
  if [ "$DO_HB" != "1" ] && [ "${APP_WOW64:-0}" = "1" ] && [ -n "${APP_WIN:-}" ] \
      && [ "${APP_LADDER_HANG_STEP:-0}" -lt 4 ] && [ -n "${APP_TARGET:-}" ]; then
    if [ "$HB" -ge 8 ] && ! app_load_has_loaded; then
      _hang_adv=
      _from_mode="${APP_LOAD_MODE:-?}"
      _to_mode=
      case "$_from_mode" in
        wow64_direct|wineconsole)
          if app_load_fail_sig_present || [ "$HB" -ge 12 ]; then
            if app_load_fail_sig_present; then _hang_adv=fail_sig; else _hang_adv=no_loaded_t; fi
            _to_mode=wow64_unix_seed
          fi
          ;;
        wow64_unix_seed)
          if [ "$HB" -ge 10 ]; then
            if app_load_fail_sig_present; then _hang_adv=fail_sig; else _hang_adv=no_loaded_t; fi
            _to_mode=wow64_explorer
          fi
          ;;
        wow64_explorer)
          if [ "$HB" -ge 10 ]; then
            if app_load_fail_sig_present; then _hang_adv=fail_sig; else _hang_adv=no_loaded_t; fi
            _to_mode=wow64_start
          fi
          ;;
        wow64_start)
          if [ "$HB" -ge 12 ] && [ "${APP_LADDER_HANG_STEP:-0}" -lt 4 ]; then
            app_load_dump_fail_sigs
            log "APP_LOAD ladder advance exhausted mode=wow64_start t=${HB}s (no Loaded; fail_hr dumped)"
            APP_LADDER_HANG_STEP=4
          fi
          ;;
      esac
      if [ -n "${_hang_adv:-}" ] && [ -n "${_to_mode:-}" ]; then
        log "APP_LOAD ladder advance reason=${_hang_adv} t=${HB}s from=${_from_mode} to=${_to_mode} wow64=1"
        app_load_dump_fail_sigs
        app_load_kill_app "ladder_hang"
        app_load_spawn "$_to_mode"
        case "$_to_mode" in
          wow64_unix_seed) APP_LADDER_HANG_STEP=1 ;;
          wow64_explorer) APP_LADDER_HANG_STEP=2 ;;
          wow64_start) APP_LADDER_HANG_STEP=3 ;;
        esac
        sleep 2
        if app_load_wait_rc; then
          log "APP_LOAD ladder advance quick-exit pid=$APP_PID rc=${APP_RC:-?} mode=$APP_LOAD_MODE"
          app_load_dump_fail_sigs
        else
          log "APP_LOAD ladder advance alive after 2s pid=$APP_PID mode=$APP_LOAD_MODE"
        fi
      fi
      unset _hang_adv _from_mode _to_mode
    fi
  fi
  sleep 1
done
wait "$WINE_PID" 2>/dev/null
RC=$?
set -e
# Flush remaining
BYTES=$(wc -c < /mnt/data/wine-run.out 2>/dev/null | tr -d ' \n' || echo 0)
BYTES=${BYTES:-0}
if [ "$BYTES" -gt "$LAST_BYTES" ] 2>/dev/null; then
  dd if=/mnt/data/wine-run.out bs=1 skip="$LAST_BYTES" 2>/dev/null | tee -a /mnt/data/gpu-stack.log >/dev/null
fi
log "WINE_EXIT=$RC wine-run_bytes=$BYTES hb_last=${HB:-0}s"
grep -E 'wine:|err:|fix:|warn:|Assertion|c0000|SIG|Signal|prereserve|Illegal|FATAL|shared user|granularity|LdrInitialize|KiUser|Emit Signal|Application could not|err:module|err:process|noexec|map_image|SHAPE|Xlib|winex11|nodrv|graphics driver|libX11|DISPLAY|RpcSs|rpcss|MountMgr|mountmgr|ole:start_rpcss|Failed to open RpcSs' /mnt/data/wine-run.out 2>/dev/null | head -80 | tee -a /mnt/data/gpu-stack.log || true

if [ "$RC" != "0" ]; then
  log "--- wine-run.out tail ---"
  tail -100 /mnt/data/wine-run.out 2>/dev/null | tee -a /mnt/data/gpu-stack.log || true
  log "--- gpu-stack.log tail ---"
  tail -40 /mnt/data/gpu-stack.log 2>/dev/null || true
fi

[ -n "$IPID" ] && kill $IPID 2>/dev/null || true
[ -n "$XPID" ] && kill $XPID 2>/dev/null || true
[ -n "$XPID" ] && wait $XPID 2>/dev/null || true
exit $RC
