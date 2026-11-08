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

# 0.1.38: Box64 wine64k stamp (align-0x100000) + runtime hole-fit for mid prereserve
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
# 0.1.38: mid 0x100000:0x30000000 often silently dropped under 39-bit/proot
# (Box64 isBlockFree fails; "is not free" only prints when WINEPRELOADRESERVE parses).
# Tiny env enables warnings; hole-fit patches mid addr/size to a free hole.
export WINEPRELOADRESERVE="${WINEPRELOADRESERVE:-100000-1}"
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

# ---- 0.1.38 hole-fit: patch Box64 my_wine_reserve mid so isBlockFree can succeed ----
# Evidence (0.1.37): asset table IS {0x100000,0x30000000} but runtime only prints
# DOS+hi; mid removed silently when 768MiB hole is not free under 39-bit VA.
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

box64_probe_mid_done() {
  local expect="$1"
  local out=/mnt/data/wine-prereserve-probe.out
  : > "$out"
  local oldlog="${BOX64_LOG-}"
  export BOX64_LOG=1
  set +e
  "$BOX64" /opt/wine/lib/wine/x86_64-unix/wine --version >"$out" 2>&1 &
  local pid=$!
  local t=0
  local ok=1
  while kill -0 "$pid" 2>/dev/null; do
    if grep -q "WINE prereserve of ${expect}:" "$out" 2>/dev/null; then
      sleep 0.3
      if grep -q "WINE prereserve of ${expect}:.* done" "$out" 2>/dev/null; then
        ok=0
      fi
      kill -KILL "$pid" 2>/dev/null || true
      break
    fi
    if grep -q 'WINE prereserve of 0x7f000000' "$out" 2>/dev/null; then
      if grep -q "WINE prereserve of ${expect}:.* done" "$out" 2>/dev/null; then
        ok=0
      fi
      kill -KILL "$pid" 2>/dev/null || true
      break
    fi
    t=$((t+1))
    if [ "$t" -ge 5 ]; then
      kill -KILL "$pid" 2>/dev/null || true
      break
    fi
    sleep 1
  done
  wait "$pid" 2>/dev/null || true
  "$BOX64" /opt/wine/bin/wineserver -k 2>/dev/null || true
  if [ -n "$oldlog" ]; then export BOX64_LOG="$oldlog"; else export BOX64_LOG=0; fi
  grep -E 'prereserve|Wine64 detected|is not free' "$out" 2>/dev/null | tee -a /mnt/data/gpu-stack.log || true
  set -e
  return $ok
}

box64_holefit_mid() {
  local marker=/usr/local/bin/.box64-wine64k-holefit
  local box=/usr/local/bin/box64
  if [ ! -x "$box" ]; then
    log "WARN: holefit skip — no $box"
    return 0
  fi
  if ! command -v python3 >/dev/null 2>&1; then
    log "WARN: holefit skip — python3 missing"
    return 0
  fi
  if ! box64_find_prereserve_mid_off "$box" >/tmp/box64-mid-off.txt 2>/dev/null; then
    log "WARN: holefit skip — cannot locate my_wine_reserve mid in $box"
    return 0
  fi
  local cand_addrs="0x100000 0x800000 0x1000000 0x10000000"
  local cand_sizes="0x10000000 0x8000000 0x4000000 0x1000000 0x300000"
  if [ -f "$marker" ]; then
    # shellcheck disable=SC1090
    . "$marker" 2>/dev/null || true
    if [ -n "${HOLEFIT_ADDR:-}" ] && [ -n "${HOLEFIT_SIZE:-}" ]; then
      log "HOLEFIT reuse marker addr=$HOLEFIT_ADDR size=$HOLEFIT_SIZE"
      if box64_patch_prereserve_mid "$box" "$HOLEFIT_ADDR" "$HOLEFIT_SIZE" 2>&1 | tee -a /mnt/data/gpu-stack.log; then
        if box64_probe_mid_done "$HOLEFIT_ADDR"; then
          log "HOLEFIT ok (cached) WINE prereserve of $HOLEFIT_ADDR:$HOLEFIT_SIZE"
          export HOLEFIT_ADDR HOLEFIT_SIZE
          return 0
        fi
        log "WARN: holefit marker stale — re-probing"
      fi
    fi
  fi
  log "HOLEFIT begin (mid silently dropped under 39-bit if 768MiB not free)"
  local a s
  for a in $cand_addrs; do
    for s in $cand_sizes; do
      python3 - "$a" "$s" <<'PY' || continue
import sys
a=int(sys.argv[1],0); s=int(sys.argv[2],0)
if a & 0xFFFF: sys.exit(1)
if s & 0xFFFF: sys.exit(1)
if a < 0x100000: sys.exit(1)
if a + s > 0x7f000000: sys.exit(1)
if a + s <= a: sys.exit(1)
sys.exit(0)
PY
      log "HOLEFIT try addr=$a size=$s"
      if ! box64_patch_prereserve_mid "$box" "$a" "$s" 2>&1 | tee -a /mnt/data/gpu-stack.log; then
        continue
      fi
      if box64_probe_mid_done "$a"; then
        printf 'HOLEFIT_ADDR=%s\nHOLEFIT_SIZE=%s\n' "$a" "$s" > "$marker"
        log "HOLEFIT ok addr=$a size=$s (saved $marker)"
        export HOLEFIT_ADDR="$a" HOLEFIT_SIZE="$s"
        return 0
      fi
    done
  done
  log "WARN: HOLEFIT failed all candidates — mid prereserve still missing"
  return 0
}

box64_holefit_mid



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
log "WINE_PE_SMOKE begin timeout=${SMOKE_TIMEOUT}s dynarec=$BOX64_DYNAREC prefix=$SMOKE_PREFIX (expect prereserve mid holefit=${HOLEFIT_ADDR:-0x100000}:${HOLEFIT_SIZE:-?})"
SMOKE_WINEDEBUG_SAVE="${WINEDEBUG-}"
SMOKE_PREFIX_SAVE="${WINEPREFIX-}"
SMOKE_LOG_SAVE="${BOX64_LOG-}"
export WINEDEBUG="${WINEDEBUG:--all}"
export WINEPREFIX="$SMOKE_PREFIX"
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
rm -rf "$SMOKE_PREFIX" 2>/dev/null || true
if [ -n "$SMOKE_WINEDEBUG_SAVE" ]; then export WINEDEBUG="$SMOKE_WINEDEBUG_SAVE"; else export WINEDEBUG="-all"; fi
if [ -n "$SMOKE_PREFIX_SAVE" ]; then export WINEPREFIX="$SMOKE_PREFIX_SAVE"; else export WINEPREFIX=/home/xuser/.wine; fi
if [ -n "$SMOKE_LOG_SAVE" ]; then export BOX64_LOG="$SMOKE_LOG_SAVE"; else export BOX64_LOG=0; fi
export BOX64_SHOWSEGV=0
export BOX64_SHOWBT=0
set -e

log "WINE_PE_SMOKE exit=$WV"
log "--- WINE_PE_SMOKE output begin (head) ---"
head -60 /mnt/data/wine-version.out 2>/dev/null | tee -a /mnt/data/gpu-stack.log || true
log "--- WINE_PE_SMOKE output end ---"

# prereserve 期待：DOS + mid(holefit) + hi
EXPECT_MID_ADDR="${HOLEFIT_ADDR:-0x100000}"
if grep -qE "WINE prereserve of ${EXPECT_MID_ADDR}:0x[0-9a-fA-F]+ done" /mnt/data/wine-version.out 2>/dev/null; then
  MID_LINE=$(grep -E "WINE prereserve of ${EXPECT_MID_ADDR}:0x[0-9a-fA-F]+ done" /mnt/data/wine-version.out 2>/dev/null | head -1)
  log "WINE_PRERESERVE mid OK ($MID_LINE)"
else
  log "WARN: missing WINE prereserve of ${EXPECT_MID_ADDR}:* done — holefit/stamp; see is not free"
  if grep -q 'prereserve of 0x110000' /mnt/data/wine-version.out 2>/dev/null; then
    log "HINT: still using old 0x110000 table — cover-install 0.1.38+ and reinstall runtime box64"
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
    log "WARN: WINE_PE_SMOKE exit=$WV (no version line) — continuing with wine.inf; watch WINE_START"
    log "WINE_VERSION_SMOKE ok (inf-only=$WINE_INF_VER pe_exit=$WV)"
  fi
else
  log "FATAL: wine version unknown (no wine.inf) and PE smoke failed (exit=$WV)."
  [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
  [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
  exit 1
fi

log "WINE_START /opt/wine/lib/wine/x86_64-unix/wine explorer /desktop=x86,${W}x${H} $*"
set +e
"$BOX64" /opt/wine/lib/wine/x86_64-unix/wine explorer /desktop=x86,${W}x${H} "$@"
RC=$?
set -e
log "WINE_EXIT=$RC"

if [ "$RC" != "0" ]; then
  log "--- gpu-stack.log tail ---"
  tail -60 /mnt/data/gpu-stack.log 2>/dev/null || true
fi

[ -n "$IPID" ] && kill $IPID 2>/dev/null || true
[ -n "$XPID" ] && kill $XPID 2>/dev/null || true
[ -n "$XPID" ] && wait $XPID 2>/dev/null || true
exit $RC
