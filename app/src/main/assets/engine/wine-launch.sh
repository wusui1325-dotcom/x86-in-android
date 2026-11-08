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

# 0.1.30：aarch64 wine／wineserver 包裝（PRoot 下 raw execve 仍回 Box64）
WINE_REAL=/opt/wine/bin/wine
WINESERVER_REAL=/opt/wine/bin/wineserver
mkdir -p /usr/local/bin
if [ ! -x /usr/local/bin/wine ] || ! grep -q 'BOX64' /usr/local/bin/wine 2>/dev/null; then
  cat > /usr/local/bin/wine << 'WRAP'
#!/bin/sh
BOX64="${BOX64:-/usr/local/bin/box64}"
REAL="${WINE_REAL:-/opt/wine/bin/wine}"
exec "$BOX64" "$REAL" "$@"
WRAP
  chmod +x /usr/local/bin/wine
fi
if [ ! -x /usr/local/bin/wineserver ] || ! grep -q 'BOX64' /usr/local/bin/wineserver 2>/dev/null; then
  cat > /usr/local/bin/wineserver << 'WRAP'
#!/bin/sh
BOX64="${BOX64:-/usr/local/bin/box64}"
REAL="${WINESERVER_REAL:-/opt/wine/bin/wineserver}"
exec "$BOX64" "$REAL" "$@"
WRAP
  chmod +x /usr/local/bin/wineserver
fi
export WINE_REAL WINESERVER_REAL
# PATH：包裝優先於 /opt/wine/bin
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

export BOX64_LD_LIBRARY_PATH="/opt/wine/lib/wine/x86_64-unix:/usr/lib/box64-x86_64-linux-gnu:/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu${BOX64_LD_LIBRARY_PATH:+:$BOX64_LD_LIBRARY_PATH}"
export WINEDLLPATH="${WINEDLLPATH:-/opt/wine/lib/wine}"
# WINELOADER → aarch64 包裝，子行程 exec 仍進 Box64
export WINELOADER="${WINELOADER:-/usr/local/bin/wine}"
# 跳過 wine-preloader 再 exec（Box64 亦會偵測並略過 preloader）
export WINELOADERNOEXEC=1
log "NTDLL=ok $(ls -la "$NTDLL" 2>&1)"
log "BOX64_X64LIB=ok $(ls -la "$X64LIB_GCC" 2>&1)"
log "BOX64_LD_LIBRARY_PATH=$BOX64_LD_LIBRARY_PATH"
log "WINELOADER=$WINELOADER WINELOADERNOEXEC=$WINELOADERNOEXEC"

# 強制安全預設（覆蓋 Kotlin env）
# 0.1.30：DYNAREC=0（interpreter）已證非 dynarec 發射；保留 NOHOSTEXT 給日後切回
export BOX64_DYNAREC_NOHOSTEXT=1
export BOX64_DYNAREC_NATIVEFLAGS=0
export BOX64_AVX=0
export BOX64_SSE42=0
export BOX64_AES=0
export BOX64_SHAEXT=0
export BOX64_PCLMULQDQ=0
export BOX64_DYNAREC=0
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
export BOX64_LOG="${BOX64_LOG:-1}"
export BOX64_SHOWSEGV="${BOX64_SHOWSEGV:-1}"
export BOX64_SHOWBT="${BOX64_SHOWBT:-1}"
export BOX64_ROLLING_LOG="${BOX64_ROLLING_LOG:-1}"
export BOX64_NOBANNER=0
log "BOX64_FLAGS NOHOSTEXT=$BOX64_DYNAREC_NOHOSTEXT AVX=$BOX64_AVX SSE42=$BOX64_SSE42 AES=$BOX64_AES DYNAREC=$BOX64_DYNAREC SAFEFLAGS=$BOX64_DYNAREC_SAFEFLAGS CALLRET=$BOX64_DYNAREC_CALLRET BIGBLOCK=$BOX64_DYNAREC_BIGBLOCK NATIVEFLAGS=$BOX64_DYNAREC_NATIVEFLAGS CPUTYPE=$BOX64_CPUTYPE X87DOUBLE=$BOX64_DYNAREC_X87DOUBLE WAIT=$BOX64_DYNAREC_WAIT ALIGNED_ATOMICS=$BOX64_DYNAREC_ALIGNED_ATOMICS"

# 冒煙：wine --version（無 X／無 explorer／無 PE）— 隔離 ntdll／libgcc／包裝鏈
log "WINE_VERSION_SMOKE begin"
set +e
"$BOX64" "$WINE_REAL" --version >/mnt/data/wine-version.out 2>&1
WV=$?
set -e
log "WINE_VERSION_SMOKE exit=$WV"
head -40 /mnt/data/wine-version.out 2>/dev/null | tee -a /mnt/data/gpu-stack.log || true
if [ "$WV" = "132" ]; then
  log "FATAL: wine --version SIGILL (exit 132). Likely emulated ntdll／libgcc／loader — not dynarec, not GPU."
  log "HINT: reinstall runtime for bookworm x64lib; check BOX64_X64LIB_STAMP / WINE_VERSION_SMOKE log above."
  [ -n "$IPID" ] && kill $IPID 2>/dev/null || true
  [ -n "$XPID" ] && kill $XPID 2>/dev/null || true
  exit 132
fi
if [ "$WV" != "0" ]; then
  log "WARN: wine --version failed (exit=$WV); continuing to explorer anyway"
fi

log "WINE_START $WINE_REAL explorer /desktop=x86,${W}x${H} $*"
set +e
"$BOX64" "$WINE_REAL" explorer /desktop=x86,${W}x${H} "$@"
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
