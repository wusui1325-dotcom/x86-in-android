#!/bin/sh
set -e
export DISPLAY=:0
BACKEND="${GRAPHICS_BACKEND:-gpu}"

W="${WINE_DESKTOP_W:-1280}"
H="${WINE_DESKTOP_H:-720}"
mkdir -p /mnt/data /tmp/.X11-unix /tmp/.sysvshm /tmp/.sound

# 同時寫 session.log（stdout／stderr → nativeExec）與 gpu-stack.log
log() {
  echo "$@"
  echo "$@" >> /mnt/data/gpu-stack.log
}

# 0.1.49: pe_ok mmap(RX) was false green vs Wine RW→mprotect(RX) (Android W^X).
# Patch Kron4ek ntdll.so virtual_map_image to force removable/anon pread (GameNative).
# Keep plain-dir scratch + pe_ok FATAL; require NTDLL_ANON_PE before Wine.
WINE_LAUNCH_STAMP=0.1.49
log "WINE_LAUNCH_STAMP=$WINE_LAUNCH_STAMP"

export WINEDLLOVERRIDES="${WINEDLLOVERRIDES:-winepulse.drv=d}"
if [ ! -f /opt/wine/share/wine/mono/wine-mono-10.1.0-x86.msi ] && \
   [ ! -d /opt/wine/share/wine/mono/wine-mono-10.1.0 ]; then
  export WINEDLLOVERRIDES="mscoree,mshtml=;$WINEDLLOVERRIDES"
fi

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
if [ -f /mnt/gpu/turnip/freedreno_icd.json ] && [ -f /mnt/gpu/turnip/libvulkan_freedreno.so ]; then
  export VK_ICD_FILENAMES="/mnt/gpu/turnip/freedreno_icd.json"
  export VK_DRIVER_FILES="/mnt/gpu/turnip/freedreno_icd.json"
  export LD_LIBRARY_PATH="/mnt/gpu/turnip${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  export TU_DEBUG="${TU_DEBUG:-noconform}"
  chmod +x /mnt/gpu/turnip/libvulkan_freedreno.so 2>/dev/null || true
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
mkdir -p "$WINEPREFIX"
log "NTDLL=ok $(ls -la "$NTDLL" 2>&1)"
log "BOX64_X64LIB=ok $(ls -la "$X64LIB_GCC" 2>&1)"
log "BOX64_LD_LIBRARY_PATH=$BOX64_LD_LIBRARY_PATH"
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


ensure_prefix_exec() {
  local p="${WINEPREFIX:-/home/xuser/.wine}"
  local pe_dir=/opt/wine/lib/wine/x86_64-windows
  local pe_ntdll="$pe_dir/ntdll.dll"
  local sys32="$p/drive_c/windows/system32"
  # Durable marker on prefix (not /tmp) so early-skip works across PRoot /tmp flakes
  local ok_marker="$p/.prefix-exec-ok"
  local scratch=""

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

  log_diag() {
    local label="$1"
    local path="$2"
    ls -ld "$path" 2>&1 | while read -r line; do log "PREFIX_EXEC ls[$label]: $line"; done || true
    if [ ! -e "$path" ]; then
      log "PREFIX_EXEC ls[$label]: missing $path"
    fi
    awk -v s="$path" '$2==s {print}' /proc/mounts 2>/dev/null | while read -r line; do
      log "PREFIX_EXEC mount[$label]: $line"
    done || true
    grep -E 'pe-exec|wine-pe' /proc/mounts 2>/dev/null | while read -r line; do
      log "PREFIX_EXEC mountgrep[$label]: $line"
    done || true
  }

  path_is_mounted() {
    local s="$1"
    awk -v m="$s" '$2==m {found=1} END{exit !found}' /proc/mounts 2>/dev/null
  }

  scratch_verify() {
    local s="$1"
    local need_mount="${2:-0}"
    if [ ! -d "$s" ]; then
      log "PREFIX_EXEC verify FAIL not-dir path=$s"
      return 1
    fi
    if ! touch "$s/.writetest" 2>/dev/null; then
      log "PREFIX_EXEC verify FAIL not-writable path=$s"
      return 1
    fi
    rm -f "$s/.writetest" 2>/dev/null || true
    if [ "$need_mount" = "1" ] && ! path_is_mounted "$s"; then
      log "PREFIX_EXEC verify FAIL not-in-/proc/mounts path=$s"
      return 1
    fi
    if ! mkdir "$s/.verify-mkdir" 2>/dev/null; then
      log "PREFIX_EXEC verify FAIL cannot mkdir under path=$s"
      return 1
    fi
    rmdir "$s/.verify-mkdir" 2>/dev/null || true
    # Drop a tiny probe file and mmap PROT_EXEC when python available (catches noexec early)
    if [ -f "$pe_ntdll" ]; then
      cp -f "$pe_ntdll" "$s/.pe-probe.dll" 2>/dev/null || true
      if [ -f "$s/.pe-probe.dll" ]; then
        if ! probe_pe_exec "$s/.pe-probe.dll"; then
          log "PREFIX_EXEC verify FAIL pe-probe noexec path=$s"
          rm -f "$s/.pe-probe.dll" 2>/dev/null || true
          return 1
        fi
        rm -f "$s/.pe-probe.dll" 2>/dev/null || true
      fi
    fi
    log "PREFIX_EXEC verify ok path=$s need_mount=$need_mount"
    return 0
  }

  # Prefer plain rootfs dirs (same FS as pe_ok /opt/wine). Guest mount -t tmpfs is broken
  # under this PRoot (claim success → path ENOENT). Optional: reuse already-live tmpfs.
  prepare_scratch() {
    local c
    # 1) Reuse an already-mounted + accessible pe-exec/tmpfs if sticky
    for c in /opt/wine-pe-exec /var/tmp/wine-pe-exec /home/xuser/wine-pe-exec \
             "$p/.pe-exec" /home/xuser/.wine-pe-exec /tmp/.wine-pe-exec /dev/shm/wine-pe-exec; do
      if path_is_mounted "$c"; then
        log "PREFIX_EXEC scratch try reuse-mounted path=$c"
        log_diag already-mounted "$c"
        if scratch_verify "$c" 1; then
          scratch="$c"
          log "PREFIX_EXEC scratch ok mode=reuse-mounted path=$scratch"
          return 0
        fi
        log "PREFIX_EXEC scratch mounted but stale — umount $c"
        umount "$c" 2>/dev/null || umount -l "$c" 2>/dev/null || true
      fi
    done

    # 2) Plain durable dirs on rootfs (NO mount -t tmpfs)
    for c in /opt/wine-pe-exec /var/tmp/wine-pe-exec /home/xuser/wine-pe-exec; do
      log "PREFIX_EXEC scratch try path=$c mode=plain-dir"
      case "$c" in
        */*) mkdir -p "${c%/*}" 2>/dev/null || true ;;
      esac
      if [ -e "$c" ] && [ ! -d "$c" ]; then
        rm -f "$c" 2>/dev/null || true
      fi
      if [ ! -d "$c" ]; then
        mkdir -p "$c" 2>/dev/null || true
      fi
      if [ ! -d "$c" ]; then
        log "PREFIX_EXEC scratch mkdir fail path=$c"
        log_diag mkdirfail "$c"
        continue
      fi
      log_diag pre-plain "$c"
      if scratch_verify "$c" 0; then
        scratch="$c"
        log "PREFIX_EXEC scratch ok mode=plain-dir path=$scratch"
        return 0
      fi
      log "PREFIX_EXEC scratch plain-dir verify fail path=$c — try next"
    done

    # 3) Last resort: guest tmpfs (known flaky) — only accept if post-mount verify sticks
    for c in /var/tmp/.wine-pe-exec-tmpfs /tmp/.wine-pe-exec; do
      log "PREFIX_EXEC scratch try path=$c mode=tmpfs-lastresort"
      case "$c" in
        */*) mkdir -p "${c%/*}" 2>/dev/null || true ;;
      esac
      if [ -e "$c" ] && [ ! -d "$c" ]; then
        rm -f "$c" 2>/dev/null || true
      fi
      if [ ! -d "$c" ]; then
        mkdir -p "$c" 2>/dev/null || true
      fi
      if [ ! -d "$c" ]; then
        log "PREFIX_EXEC scratch mkdir mountpoint fail path=$c"
        continue
      fi
      log_diag pre-mount "$c"
      local size_ok=""
      if mount -t tmpfs -o rw,exec,nosuid,mode=755,size=768M tmpfs "$c" 2>/dev/null; then
        size_ok=768M
      elif mount -t tmpfs -o rw,exec,nosuid,mode=755,size=512M tmpfs "$c" 2>/dev/null; then
        size_ok=512M
      else
        log "PREFIX_EXEC tmpfs mount syscall failed path=$c"
        continue
      fi
      log "PREFIX_EXEC tmpfs mount claimed size=$size_ok path=$c"
      log_diag post-mount "$c"
      if scratch_verify "$c" 1; then
        scratch="$c"
        log "PREFIX_EXEC scratch ok mode=tmpfs path=$scratch size=$size_ok"
        return 0
      fi
      log "PREFIX_EXEC scratch vanished after tmpfs mount — try next"
      umount "$c" 2>/dev/null || umount -l "$c" 2>/dev/null || true
    done

    log "FATAL: PREFIX_EXEC no durable scratch (plain-dir + tmpfs-lastresort all failed)"
    exit 1
  }

  ensure_dir "$sys32" || true
  ensure_dir "$p" || true

  sys32_ok() {
    [ -f "$sys32/ntdll.dll" ] || return 1
    probe_pe_exec "$sys32/ntdll.dll"
  }
  pe_ok() {
    [ -f "$pe_ntdll" ] && probe_pe_exec "$pe_ntdll"
  }

  if [ -f "$ok_marker" ] && pe_ok && sys32_ok; then
    log "PREFIX_EXEC skip (already ok) pe=$pe_ntdll sys32=$sys32/ntdll.dll"
    return 0
  fi

  # Remount: claimed only — never success
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

  if pe_ok && sys32_ok; then
    log "PREFIX_EXEC ok mode=probe pe=$pe_ntdll sys32=$sys32/ntdll.dll (fs already exec)"
    echo "mode=probe" > "$ok_marker" 2>/dev/null || true
    return 0
  fi
  if pe_ok && [ ! -f "$sys32/ntdll.dll" ]; then
    log "PREFIX_EXEC pe ok but sys32/ntdll missing — forcing tmpfs+bind before wineboot materializes PE"
  elif pe_ok && ! sys32_ok; then
    log "PREFIX_EXEC pe ok but sys32 noexec ($sys32/ntdll.dll) — forcing tmpfs+bind system32"
  else
    log "PREFIX_EXEC probe fail pe=$pe_ntdll — forcing tmpfs+bind for x86_64-windows + system32"
  fi

  prepare_scratch
  if [ -z "$scratch" ] || ! scratch_verify "$scratch" 0; then
    log "FATAL: PREFIX_EXEC scratch not verified after prepare path=${scratch:-unset}"
    log_diag fatal-scratch "${scratch:-/}"
    exit 1
  fi

  if [ ! -f "$scratch/x86_64-windows/ntdll.dll" ]; then
    if [ ! -d "$pe_dir" ]; then
      log "FATAL: PREFIX_EXEC missing $pe_dir"
      exit 1
    fi
    log "PREFIX_EXEC copying $pe_dir → tmpfs (one-time) scratch=$scratch"
    log_diag pre-copy "$scratch"
    if ! ensure_dir "$scratch/x86_64-windows"; then
      log "FATAL: PREFIX_EXEC cannot mkdir $scratch/x86_64-windows"
      log_diag mkdir-x64w "$scratch"
      log_diag mkdir-x64w-child "$scratch/x86_64-windows"
      exit 1
    fi
    if ! cp -a "$pe_dir"/. "$scratch/x86_64-windows"/; then
      log "FATAL: PREFIX_EXEC cp x86_64-windows failed"
      exit 1
    fi
    log "PREFIX_EXEC copy x86_64-windows done"
  else
    log "PREFIX_EXEC reuse tmpfs x86_64-windows copy scratch=$scratch"
  fi

  bind_pe_from_scratch() {
    if mount --bind "$scratch/x86_64-windows" "$pe_dir" 2>/dev/null \
       || mount -o bind "$scratch/x86_64-windows" "$pe_dir" 2>/dev/null; then
      log "PREFIX_EXEC bind x86_64-windows ok"
      log_diag bind-x64w "$pe_dir"
      return 0
    fi
    log "FATAL: PREFIX_EXEC bind x86_64-windows failed"
    log_diag bindfail-x64w "$pe_dir"
    exit 1
  }

  if pe_ok; then
    log "PREFIX_EXEC skip bind x86_64-windows (pe already exec)"
  elif path_is_mounted "$pe_dir"; then
    log "PREFIX_EXEC bind x86_64-windows already mounted — re-probe"
    log_diag already-pe "$pe_dir"
    if pe_ok; then
      log "PREFIX_EXEC existing pe bind still exec"
    else
      log "PREFIX_EXEC pe stale/noexec on existing bind — umount+rebind"
      umount "$pe_dir" 2>/dev/null || umount -l "$pe_dir" 2>/dev/null || true
      bind_pe_from_scratch
    fi
  else
    bind_pe_from_scratch
  fi

  # ALWAYS put system32 on tmpfs so wineboot copies / NT maps land on exec
  if ! ensure_dir "$scratch/system32"; then
    log "FATAL: PREFIX_EXEC cannot mkdir $scratch/system32"
    log_diag mkdir-sys32 "$scratch"
    exit 1
  fi
  if [ -d "$sys32" ]; then
    cp -a "$sys32"/. "$scratch/system32"/ 2>/dev/null || true
  fi
  cp -f "$scratch/x86_64-windows/ntdll.dll" "$scratch/system32/ntdll.dll" 2>/dev/null \
    || cp -f "$pe_ntdll" "$scratch/system32/ntdll.dll" 2>/dev/null || true
  touch "$scratch/system32/.copied" 2>/dev/null || true
  ensure_dir "$sys32" || true

  bind_sys32_from_scratch() {
    if mount --bind "$scratch/system32" "$sys32" 2>/dev/null \
       || mount -o bind "$scratch/system32" "$sys32" 2>/dev/null; then
      log "PREFIX_EXEC bind system32 ok path=$sys32"
      log_diag bind-sys32 "$sys32"
      return 0
    fi
    log "FATAL: PREFIX_EXEC bind system32 failed path=$sys32 — C:\\windows\\system32 will stay noexec"
    log_diag bindfail-sys32 "$sys32"
    exit 1
  }

  if path_is_mounted "$sys32"; then
    log "PREFIX_EXEC bind system32 already mounted path=$sys32 — re-probe"
    log_diag already-sys32 "$sys32"
    cp -f "$scratch/x86_64-windows/ntdll.dll" "$sys32/ntdll.dll" 2>/dev/null \
      || cp -f "$pe_ntdll" "$sys32/ntdll.dll" 2>/dev/null || true
    if ! sys32_ok; then
      log "PREFIX_EXEC sys32 stale/noexec on existing bind — umount+rebind"
      umount "$sys32" 2>/dev/null || umount -l "$sys32" 2>/dev/null || true
      bind_sys32_from_scratch
    else
      log "PREFIX_EXEC existing sys32 bind still exec"
    fi
  else
    bind_sys32_from_scratch
  fi

  chmod -R u+x "$scratch/x86_64-windows" "$scratch/system32" "$sys32" 2>/dev/null || true

  # Nested tmpfs sticky check after binds (P0: mount claimed then vanish)
  log_diag post-bind-scratch "$scratch"
  grep -E 'pe-exec|wine-pe|system32|x86_64-windows' /proc/mounts 2>/dev/null | while read -r line; do
    log "PREFIX_EXEC mountgrep[post-bind]: $line"
  done || true
  if ! scratch_verify "$scratch" 0; then
    log "FATAL: PREFIX_EXEC scratch vanished after bind path=$scratch"
    log_diag fatal-post-bind "$scratch"
    exit 1
  fi

  # Require pe PROT_EXEC — pe_ok=no must NEVER print PREFIX_EXEC ok (false green)
  if pe_ok; then pe_state=yes; else pe_state=no; fi
  if sys32_ok; then sys32_state=yes; else sys32_state=no; fi
  if probe_pe_wine_wx "$sys32/ntdll.dll" 2>/dev/null; then wine_wx=yes; else wine_wx=no; fi
  log "PREFIX_EXEC post-bind probe pe_ok=$pe_state sys32_ok=$sys32_state wine_wx=$wine_wx scratch=$scratch pe=$pe_ntdll sys32=$sys32/ntdll.dll"
  log "PREFIX_EXEC note wine_wx=no expected on Android W^X; NTDLL_ANON_PE makes Wine use anon/pread"

  if [ "$pe_state" = "yes" ] && [ "$sys32_state" = "yes" ]; then
    log "PREFIX_EXEC ok mode=scratch+bind sys32=$sys32/ntdll.dll pe_ok=yes scratch=$scratch"
    echo "mode=scratch+bind-sys32 scratch=$scratch pe_ok=yes" > "$ok_marker"
    return 0
  fi

  # preseed only if pe truly exec AND sys32 is bound (ntdll may appear after wineboot)
  if [ "$pe_state" = "yes" ] && [ ! -f "$sys32/ntdll.dll" ] && path_is_mounted "$sys32"; then
    log "PREFIX_EXEC ok mode=scratch+bind-preseed pe=$pe_ntdll sys32_bound=$sys32 scratch=$scratch pe_ok=yes (ntdll will land on exec scratch)"
    echo "mode=scratch+bind-preseed scratch=$scratch pe_ok=yes" > "$ok_marker"
    return 0
  fi

  if [ "$pe_state" = "no" ]; then
    log "FATAL: PREFIX_EXEC pe_ok=no after scratch+bind — refusing false green (pe=$pe_ntdll scratch=$scratch)"
    log_diag final-pe "$pe_dir"
    log_diag final-scratch "$scratch"
    exit 1
  fi

  log "FATAL: PREFIX_EXEC probe still fail after scratch+bind — noexec ntdll unresolved (sys32=$sys32 pe_ok=$pe_state sys32_ok=$sys32_state scratch=$scratch)"
  log_diag final-sys32 "$sys32"
  log_diag final-scratch "$scratch"
  exit 1
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
SMOKE_TIMEOUT="${WINE_VERSION_SMOKE_TIMEOUT:-45}"
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
rm -rf "$SMOKE_PREFIX" 2>/dev/null || true
# Keep err unmuted for real run until Present works (Kotlin WINEDEBUG=-all hid root cause)
export WINEDEBUG="${WINE_RUN_WINEDEBUG:-err+all,fix+all}"
if [ -n "$SMOKE_PREFIX_SAVE" ]; then export WINEPREFIX="$SMOKE_PREFIX_SAVE"; else export WINEPREFIX=/home/xuser/.wine; fi
# Real run: BOX64_LOG=1 until WINE_EXIT=0 (Parent: capture beyond truncated smoke)
export BOX64_LOG="${BOX64_RUN_LOG:-1}"
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

# 閘門：必須有版號（inf 或 PE）；PE hang／SIGSEGV 已在上面 FATAL
if [ "$WV" = "124" ]; then
  log "FATAL: WINE_PE_SMOKE HANG (timeout ${SMOKE_TIMEOUT}s, exit 124)."
  log "HINT: expect prereserve DOS 0x10000:0x10000 + mid holefit + hi 0x7f000000:0x3000000"
  [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
  [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
  exit 124
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

# 0.1.42: explicit wineboot on real prefix if empty — same path PE smoke hits; capture err
mkdir -p "$WINEPREFIX"
ensure_prefix_exec
export WINEDEBUG="${WINE_RUN_WINEDEBUG:-err+all,fix+all}"
export BOX64_LOG="${BOX64_RUN_LOG:-1}"
log "WINE_RUN WINEDEBUG=$WINEDEBUG BOX64_LOG=$BOX64_LOG WINEPREFIX=$WINEPREFIX"
if [ ! -f "$WINEPREFIX/system.reg" ]; then
  log "WINEBOOT_INIT begin (no system.reg) timeout=90s"
  : > /mnt/data/wineboot-init.out
  set +e
  "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine wineboot --init >>/mnt/data/wineboot-init.out 2>&1 &
  WB_PID=$!
  WB_T=0
  while kill -0 "$WB_PID" 2>/dev/null; do
    WB_T=$((WB_T+1))
    if [ "$WB_T" -ge 90 ]; then
      log "WINEBOOT_INIT TIMEOUT — killing"
      kill -TERM "$WB_PID" 2>/dev/null || true
      sleep 1
      kill -KILL "$WB_PID" 2>/dev/null || true
      "$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
      break
    fi
    sleep 1
  done
  wait "$WB_PID" 2>/dev/null
  WB_RC=$?
  set -e
  log "WINEBOOT_INIT exit=$WB_RC system.reg=$([ -f "$WINEPREFIX/system.reg" ] && echo yes || echo no)"
  log "--- WINEBOOT_INIT key lines ---"
  grep -E 'wine:|err:|fix:|warn:|Assertion|c0000|SIG|Signal|prereserve|wineboot|Illegal|FATAL|shared user|granularity|LdrInitialize|KiUser|Emit Signal' /mnt/data/wineboot-init.out 2>/dev/null | head -120 | tee -a /mnt/data/gpu-stack.log || true
  log "--- WINEBOOT_INIT tail ---"
  tail -60 /mnt/data/wineboot-init.out 2>/dev/null | tee -a /mnt/data/gpu-stack.log || true
  if [ ! -f "$WINEPREFIX/system.reg" ]; then
    log "FATAL: wineboot --init did not create system.reg (exit=$WB_RC). See WINEBOOT_INIT key lines."
    [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
    [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
    exit "${WB_RC:-1}"
  fi
else
  log "WINEBOOT_INIT skip (system.reg present)"
fi

log "WINE_START /opt/wine/lib/wine/x86_64-unix/wine explorer /desktop=x86,${W}x${H} $*"
: > /mnt/data/wine-run.out
set +e
"$BOX64" /opt/wine/lib/wine/x86_64-unix/wine explorer /desktop=x86,${W}x${H} "$@" >>/mnt/data/wine-run.out 2>&1
RC=$?
set -e
# live-ish: also append a short key extract to gpu-stack so Session sees it without opening wine-run.out
log "WINE_EXIT=$RC wine-run_bytes=$(wc -c </mnt/data/wine-run.out 2>/dev/null | tr -d ' ')"
grep -E 'wine:|err:|fix:|warn:|Assertion|c0000|SIG|Signal|prereserve|Illegal|FATAL|shared user|granularity|LdrInitialize|KiUser|Emit Signal|Application could not|err:module|err:process' /mnt/data/wine-run.out 2>/dev/null | head -80 | tee -a /mnt/data/gpu-stack.log || true

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
