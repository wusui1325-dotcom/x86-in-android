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

# 0.1.57: OFFLINE patch system.reg for RpcSs/MountMgr (never wine reg — 0.1.56 hung).
# Direct rpcss.exe + winedevice MountMgr with hard timeouts; re-seed win32u/user32.
# 0.1.56: soft-continue + winedevice seed (reg-add hung at RPCSS_REG precheck).
# 0.1.55: no soft-kill on markers; soften wipe. 0.1.54: seed rpcss/mountmgr.
# 0.1.53: winex11. 0.1.52: seed-copy + NTDLL_ANON_PE.
WINE_LAUNCH_STAMP=0.1.57
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
# 0.1.53: winex11.so needs aarch64 libX11/libXext via Box64 native wrap
export LD_LIBRARY_PATH="/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
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

  path_is_mounted() {
    local s="$1"
    awk -v m="$s" '$2==m {found=1} END{exit !found}' /proc/mounts 2>/dev/null
  }

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
  local seed_list="ntdll.dll kernel32.dll kernelbase.dll advapi32.dll bcrypt.dll
    sechost.dll ucrtbase.dll msvcrt.dll rpcrt4.dll ws2_32.dll dnsapi.dll
    user32.dll gdi32.dll win32u.dll imm32.dll setupapi.dll cfgmgr32.dll
    shell32.dll shlwapi.dll shcore.dll ole32.dll oleaut32.dll combase.dll coml2.dll
    actxprxy.dll wininet.dll mpr.dll cryptbase.dll userenv.dll
    comctl32.dll uxtheme.dll winex11.drv winewayland.drv
    winmm.dll version.dll wsock32.dll iphlpapi.dll netapi32.dll secur32.dll
    authz.dll"
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
           rpcss.exe svchost.exe plugplay.exe conhost.exe cmd.exe sc.exe \
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
export WINEDEBUG="${WINE_RUN_WINEDEBUG:-err+all,+seh,+loaddll}"
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
export WINEDEBUG="${WINE_RUN_WINEDEBUG:-err+all,+seh,+loaddll}"
export BOX64_LOG="${BOX64_RUN_LOG:-0}"
log "WINE_RUN WINEDEBUG=$WINEDEBUG BOX64_LOG=$BOX64_LOG WINEPREFIX=$WINEPREFIX DISPLAY=${DISPLAY:-unset}"


# ---- 0.1.53: Wine graphics driver (winex11) — registry + probe --------------------
# explorer load_graphics_driver: LoadLibrary("winex11.drv"); ERROR_MOD_NOT_FOUND →
# "The graphics driver is missing. Check your build!" (nodrv black screen).
ensure_graphics_x11_reg() {
  local ureg="$WINEPREFIX/user.reg"
  mkdir -p "$WINEPREFIX"
  if [ ! -f "$ureg" ]; then
    printf '%s\n' 'WINE REGISTRY Version 2' '' \
      '[Software\\Wine\\Drivers]' \
      '"Graphics"="x11"' > "$ureg"
    log "DRIVER_REG created user.reg Graphics=x11"
    return 0
  fi
  if grep -q '"Graphics"="x11"' "$ureg" 2>/dev/null; then
    log "DRIVER_REG Graphics=x11 already set"
    return 0
  fi
  # Drop any prior Graphics= under Drivers (corrupt/empty/wayland/mac)
  if command -v sed >/dev/null 2>&1; then
    sed -i '/^"Graphics"=/d' "$ureg" 2>/dev/null || true
  fi
  if grep -q '\[Software\\Wine\\Drivers\]' "$ureg" 2>/dev/null; then
    # Append value after first Drivers section header via temp rewrite
    awk '
      BEGIN{done=0}
      /^\[Software\\Wine\\Drivers\]/ && !done {
        print
        print "\"Graphics\"=\"x11\""
        done=1
        next
      }
      {print}
      END{
        if (!done) {
          print ""
          print "[Software\\Wine\\Drivers]"
          print "\"Graphics\"=\"x11\""
        }
      }
    ' "$ureg" > "$ureg.tmp" 2>/dev/null && mv -f "$ureg.tmp" "$ureg" || \
      printf '\n[Software\\Wine\\Drivers]\n"Graphics"="x11"\n' >> "$ureg"
  else
    printf '\n[Software\\Wine\\Drivers]\n"Graphics"="x11"\n' >> "$ureg"
  fi
  log "DRIVER_REG set Graphics=x11"
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

wipe_wine_prefix() {
  local p="${1:-$WINEPREFIX}"
  local reason="${2:-incomplete}"
  log "WINEBOOT_WIPE begin reason=$reason path=$p"
  "$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
  # Drop stale system32 bind if any
  if awk -v m="$p/drive_c/windows/system32" '$2==m {found=1} END{exit !found}' /proc/mounts 2>/dev/null; then
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
  fi
  ensure_prefix_exec
  log "WINEBOOT_WIPE done path=$p"
}

run_wineboot_init() {
  # 0.1.56: wait for natural exit OR soft-continue when system.reg is large+stable.
  # 0.1.55: NEVER soft-kill on early markers (that caused exit=143 before system.reg).
  # Soft-continue here requires system.reg >=100KB, size unchanged for 20s, and
  # .update-timestamp — then TERM wineboot, keep wineserver, proceed to RPCSS_PRESTART.
  # Hard timeout still 180s; tiny/missing reg on timeout = FAIL.
  local label="${1:-init}"
  local wb_timeout="${2:-180}"
  local soft_stable_need="${WINEBOOT_SOFT_STABLE_SECS:-20}"
  local soft_reg_need="${WINEBOOT_SOFT_REG_BYTES:-100000}"
  log "WINEBOOT_INIT begin ($label) timeout=${wb_timeout}s soft-continue>=${soft_reg_need}B stable=${soft_stable_need}s (no early soft-kill)"
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
    if [ "$REG_B" -ge "$soft_reg_need" ] 2>/dev/null \
       && [ "$WB_STABLE_SECS" -ge "$soft_stable_need" ] \
       && [ -f "$WINEPREFIX/.update-timestamp" ]; then
      log "WINEBOOT_INIT soft-continue t=${WB_T}s system.reg=${REG_B}B stable=${WB_STABLE_SECS}s ts=yes — TERM wineboot (keep wineserver, NO wipe)"
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

# 0.1.55 wipe policy:
# - complete → skip init
# - large (>=100KB) but not "complete" → REUSE (seed bins + wineboot -u); NEVER wipe for missing RpcSs keys
# - stub (tiny/missing) → wipe + wineboot --init (no soft-kill)
# - after init: FATAL only if system.reg still missing/tiny; large prefix proceeds even if complete=no
if prefix_is_complete "$WINEPREFIX"; then
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

# ---- 0.1.57: OFFLINE system.reg RpcSs/MountMgr + timed PRESTART --------------
ensure_rpcss_mountmgr_service_reg() {
  # 0.1.57: OFFLINE text-patch system.reg — NEVER wine/reg.exe (0.1.56 hung: chicken-egg
  # OpenService/RpcSs needed by reg.exe itself). Kill wineserver first so next wine start
  # reloads the patched file. Always heal when file keys missing even if prefix_is_complete.
  local sys32="$WINEPREFIX/drive_c/windows/system32"
  local drv="$sys32/drivers"
  local regf="$WINEPREFIX/system.reg"
  mkdir -p /mnt/data 2>/dev/null || true
  : > /mnt/data/wine-rpcss-reg.out

  # Drop live wineserver so registry file edits are not overwritten / ignored
  "$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
  sleep 0.5

  local reg_rpc=no reg_mm=no
  if [ -f "$regf" ]; then
    grep -qF 'Services\\RpcSs]' "$regf" 2>/dev/null && reg_rpc=yes || true
    grep -qF 'Services\\MountMgr]' "$regf" 2>/dev/null && reg_mm=yes || true
  fi
  log "RPCSS_REG precheck file_rpcss=$reg_rpc file_mountmgr=$reg_mm rpcss_bin=$([ -f "$sys32/rpcss.exe" ] && echo yes || echo NO) winedevice=$([ -f "$sys32/winedevice.exe" ] && echo yes || echo NO) mountmgr=$([ -f "$drv/mountmgr.sys" ] && echo yes || echo NO)"

  # Pure-Python offline inject into Wine registry file format (no wine process)
  set +e
  python3 - "$regf" <<'PY' | tee -a /mnt/data/wine-rpcss-reg.out
import os, sys, time, re
path = sys.argv[1]
os.makedirs(os.path.dirname(path) or ".", exist_ok=True)

def block(name_cs, name_cc, typ, start, image, display, group=None, desc=None, obj="LocalSystem"):
    t = format(int(time.time() * 1e7), "x")
    lines = []
    for hive in ("CurrentControlSet", "ControlSet001"):
        lines.append("[System\\\\%s\\\\Services\\\\%s]" % (hive, name_cs))
        lines.append("#time=%s" % t)
        lines.append('"Type"=dword:%08x' % typ)
        lines.append('"Start"=dword:%08x' % start)
        lines.append('"ErrorControl"=dword:00000001')
        lines.append('"ImagePath"=str(2):"%s"' % image)
        lines.append('"DisplayName"="%s"' % display)
        if obj:
            lines.append('"ObjectName"="%s"' % obj)
        if group:
            lines.append('"Group"="%s"' % group)
        if desc:
            lines.append('"Description"="%s"' % desc)
        lines.append("")
    return "\n".join(lines)

def has_svc(text, name):
    return ("Services\\\\" + name + "]") in text

def strip_svc_blocks(text, name):
    pat = re.compile(
        r"(?ms)^\[System\\\\(?:CurrentControlSet|ControlSet001)\\\\Services\\\\"
        + re.escape(name)
        + r"\][^\[]*"
    )
    return pat.sub("", text)

if os.path.isfile(path):
    raw = open(path, "r", encoding="utf-8", errors="replace").read()
else:
    raw = "WINE REGISTRY File\n;; offline-created by x86-in-android ensure\n#arch=win64\n\n"

had_rpc = has_svc(raw, "RpcSs")
had_mm = has_svc(raw, "MountMgr")
raw2 = strip_svc_blocks(raw, "RpcSs")
raw2 = strip_svc_blocks(raw2, "MountMgr")
if not raw2.lstrip().startswith("WINE REGISTRY File"):
    raw2 = "WINE REGISTRY File\n;; offline-patched\n#arch=win64\n\n" + raw2
if not raw2.endswith("\n"):
    raw2 += "\n"

body = []
body.append(";; --- x86-in-android offline RpcSs/MountMgr inject ---")
body.append(block(
    "RpcSs", "RpcSs", 0x20, 0x3,
    "%SystemRoot%\\\\system32\\\\rpcss.exe",
    "Remote Procedure Call (RPC)",
    desc="RPC service",
))
body.append(block(
    "MountMgr", "MountMgr", 0x1, 0x0,
    "%SystemRoot%\\\\system32\\\\drivers\\\\mountmgr.sys",
    "Mount Manager",
    group="System Bus Extender",
    desc="Device mounting service",
    obj=None,
))
raw2 += "\n" + "\n".join(body) + "\n"

tmp = path + ".rpcss-tmp"
with open(tmp, "w", encoding="utf-8") as f:
    f.write(raw2)
    f.flush()
    os.fsync(f.fileno())
os.replace(tmp, path)

final = open(path, encoding="utf-8", errors="replace").read()
ok_rpc = has_svc(final, "RpcSs")
ok_mm = has_svc(final, "MountMgr")
print("offline had_rpc=%s had_mm=%s now_rpc=%s now_mm=%s bytes=%d" % (
    had_rpc, had_mm, ok_rpc, ok_mm, os.path.getsize(path)))
sys.exit(0 if ok_rpc and ok_mm else 2)
PY
  PY_RC=$?
  set -e

  reg_rpc=no; reg_mm=no
  if [ -f "$regf" ]; then
    grep -qF 'Services\\RpcSs]' "$regf" 2>/dev/null && reg_rpc=yes || true
    grep -qF 'Services\\MountMgr]' "$regf" 2>/dev/null && reg_mm=yes || true
  fi
  if [ -f /mnt/data/wine-rpcss-reg.out ]; then
    while read -r line; do log "RPCSS_REG.py: $line"; done < /mnt/data/wine-rpcss-reg.out || true
  fi
  log "RPCSS_REG ensure done offline=yes file_rpcss=$reg_rpc file_mountmgr=$reg_mm py_rc=$PY_RC"
  if [ -f "$regf" ]; then
    grep -F 'Services\\RpcSs]' -A6 "$regf" 2>/dev/null | head -12 | while read -r line; do
      log "RPCSS_REG.snip: $line"
    done || true
    grep -F 'Services\\MountMgr]' -A6 "$regf" 2>/dev/null | head -12 | while read -r line; do
      log "RPCSS_REG.snip: $line"
    done || true
  fi
  if [ "$reg_rpc" != "yes" ]; then
    log "WARN: RPCSS_REG offline inject failed to materialize RpcSs key — PRESTART may still try direct rpcss.exe"
  fi
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

rpcss_prestart() {
  # After offline keys: prefer DIRECT rpcss.exe (registers IRPCSS). net/sc best-effort
  # with short hard timeouts. MountMgr via winedevice with timeout. Never hang forever.
  local sys32="$WINEPREFIX/drive_c/windows/system32"
  : > /mnt/data/wine-rpcss.out
  if [ ! -f "$sys32/rpcss.exe" ]; then
    log "RPCSS_PRESTART skip (rpcss.exe missing)"
    return 0
  fi
  log "RPCSS_PRESTART begin (direct rpcss.exe first; net/sc best-effort; hard timeouts)"
  set +e

  # 1) Direct rpcss.exe — most important path
  "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine rpcss.exe >>/mnt/data/wine-rpcss.out 2>&1 &
  RPCSS_PID=$!
  sleep 2
  local alive=0
  kill -0 "$RPCSS_PID" 2>/dev/null && alive=1 || true
  log "RPCSS_PRESTART rpcss_direct pid=$RPCSS_PID alive=$alive"

  # 2) Best-effort MountMgr via winedevice (8s hard timeout)
  if [ -f "$sys32/winedevice.exe" ] && [ -f "$sys32/drivers/mountmgr.sys" ]; then
    "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine winedevice.exe MountMgr >>/mnt/data/wine-rpcss.out 2>&1 &
    MM_PID=$!
    MT=0
    while kill -0 "$MM_PID" 2>/dev/null && [ "$MT" -lt 8 ]; do
      sleep 1
      MT=$((MT+1))
    done
    if kill -0 "$MM_PID" 2>/dev/null; then
      kill -TERM "$MM_PID" 2>/dev/null || true
      sleep 0.5
      kill -KILL "$MM_PID" 2>/dev/null || true
      log "RPCSS_PRESTART MountMgr/winedevice TIMEOUT t=8s (non-fatal)"
    else
      wait "$MM_PID" 2>/dev/null
      log "RPCSS_PRESTART MountMgr/winedevice exited rc=$?"
    fi
  else
    log "RPCSS_PRESTART MountMgr skip (winedevice or mountmgr.sys missing)"
  fi

  # 3) net start RpcSs — 6s hard timeout
  "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine net.exe start RpcSs >>/mnt/data/wine-rpcss.out 2>&1 &
  NET_PRE_PID=$!
  NT=0
  while kill -0 "$NET_PRE_PID" 2>/dev/null && [ "$NT" -lt 6 ]; do
    sleep 1
    NT=$((NT+1))
  done
  if kill -0 "$NET_PRE_PID" 2>/dev/null; then
    kill -TERM "$NET_PRE_PID" 2>/dev/null || true
    sleep 0.4
    kill -KILL "$NET_PRE_PID" 2>/dev/null || true
    NET_RC=124
    log "RPCSS_PRESTART net start TIMEOUT t=6s"
  else
    wait "$NET_PRE_PID" 2>/dev/null
    NET_RC=$?
  fi

  # 4) sc start — 5s hard timeout
  "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine sc.exe start RpcSs >>/mnt/data/wine-rpcss.out 2>&1 &
  SC_PID=$!
  ST=0
  while kill -0 "$SC_PID" 2>/dev/null && [ "$ST" -lt 5 ]; do
    sleep 1
    ST=$((ST+1))
  done
  if kill -0 "$SC_PID" 2>/dev/null; then
    kill -TERM "$SC_PID" 2>/dev/null || true
    sleep 0.3
    kill -KILL "$SC_PID" 2>/dev/null || true
    SC_RC=124
    log "RPCSS_PRESTART sc start TIMEOUT t=5s"
  else
    wait "$SC_PID" 2>/dev/null
    SC_RC=$?
  fi

  # Re-check rpcss; relaunch if needed
  alive=0
  kill -0 "$RPCSS_PID" 2>/dev/null && alive=1 || true
  if [ "$alive" = "0" ]; then
    "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine rpcss.exe >>/mnt/data/wine-rpcss.out 2>&1 &
    RPCSS_PID=$!
    sleep 2
    kill -0 "$RPCSS_PID" 2>/dev/null && alive=1 || true
    log "RPCSS_PRESTART rpcss_relaunch pid=$RPCSS_PID alive=$alive"
  fi
  set -e
  log "RPCSS_PRESTART net_rc=$NET_RC sc_rc=$SC_RC rpcss_pid=$RPCSS_PID alive=$alive out_bytes=$(wc -c </mnt/data/wine-rpcss.out 2>/dev/null | tr -d ' ')"
  if [ -f /mnt/data/wine-rpcss.out ]; then
    grep -E 'wine:|err:|fix:|RpcSs|rpcss|MountMgr|service|c0000|FATAL|started|START|OpenService|RPC_S' /mnt/data/wine-rpcss.out 2>/dev/null | head -40 | while read -r line; do
      log "RPCSS_PRESTART.out: $line"
    done || true
  fi
}

ensure_rpcss_mountmgr_service_reg
rpcss_diag
rpcss_prestart

# 0.1.53: /desktop=name,WxH,x11 — skip mac/wayland probe; LoadLibrary winex11.drv only
log "WINE_START /opt/wine/lib/wine/x86_64-unix/wine explorer /desktop=x86,${W}x${H},x11 $*"
: > /mnt/data/wine-run.out
set +e
"$BOX64" /opt/wine/lib/wine/x86_64-unix/wine explorer /desktop=x86,${W}x${H},x11 "$@" >>/mnt/data/wine-run.out 2>&1 &
WINE_PID=$!
log "WINE_START pid=$WINE_PID (heartbeat on)"
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
  case "$HB" in
    10|30|60) DO_HB=1 ;;
  esac
  if [ $((HB % 30)) -eq 0 ]; then DO_HB=1; fi
  if [ "$DO_HB" = "1" ]; then
    X0_NOW=no
    [ -S /tmp/.X11-unix/X0 ] || [ -e /tmp/.X11-unix/X0 ] && X0_NOW=ok
    GROW=$((BYTES-LAST_BYTES))
    # LAST_BYTES already caught up above; report absolute size
    APP_ALIVE=0
    if grep -qE 'Detected running wine with |explorer\.exe|app\.exe' /mnt/data/wine-run.out 2>/dev/null; then APP_ALIVE=1; fi
    ERR_N=$(grep -cE '^[0-9a-f]+:err:' /mnt/data/wine-run.out 2>/dev/null || echo 0)
    ERR_N=$(echo "$ERR_N" | tr -d ' \n')
    log "WINE_HB t=${HB}s pid=$WINE_PID alive=1 wine-run_bytes=$BYTES x0=$X0_NOW app_seen=$APP_ALIVE err_lines=${ERR_N:-0}"
    if [ "$BYTES" -eq 0 ]; then
      log "WINE_HB note: wine-run.out empty — Wine silent (debug light); Present may still be waiting for first drawable"
    fi
    # Tail recent wine-run for KEY panel
    tail -8 /mnt/data/wine-run.out 2>/dev/null | while IFS= read -r ln; do
      log "WINE_HB.tail: $ln"
    done || true
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
