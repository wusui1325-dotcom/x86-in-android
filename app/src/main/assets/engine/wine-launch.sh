#!/bin/sh
set -e
export DISPLAY=:0
BACKEND="${GRAPHICS_BACKEND:-gpu}"

W="${WINE_DESKTOP_W:-1280}"
H="${WINE_DESKTOP_H:-720}"
mkdir -p /mnt/data/xfb /tmp/.X11-unix /mnt/data
rm -f /mnt/data/xfb/* 2>/dev/null || true

export WINEDLLOVERRIDES="${WINEDLLOVERRIDES:-winepulse.drv=d}"
if [ ! -f /opt/wine/share/wine/mono/wine-mono-10.1.0-x86.msi ] && \
   [ ! -d /opt/wine/share/wine/mono/wine-mono-10.1.0 ]; then
  export WINEDLLOVERRIDES="mscoree,mshtml=;$WINEDLLOVERRIDES"
fi

if [ ! -f /etc/asound.conf ]; then
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

if [ -f /mnt/gpu/turnip/freedreno_icd.json ]; then
  export VK_ICD_FILENAMES="/mnt/gpu/turnip/freedreno_icd.json"
fi

XPID=
IPID=

start_x_stub() {
  # X 協定 stub：提供 DISPLAY unix socket。畫面由宿主 Present（AHB）吃 shm／XWD feeder。
  # 非 SoftLegacy 產品軟渲路徑（不強制 LIBGL_ALWAYS_SOFTWARE）。
  if [ ! -x /usr/bin/Xvfb ] && ! command -v Xvfb >/dev/null 2>&1; then
    echo "FATAL: X protocol stub (Xvfb) missing" >> /mnt/data/gpu-stack.log
    exit 92
  fi
  Xvfb :0 -screen 0 ${W}x${H}x24 -ac -nolisten tcp -fbdir /mnt/data/xfb >/mnt/data/xvfb.log 2>&1 &
  XPID=$!
  i=0
  while [ $i -lt 80 ]; do
    if [ -e /mnt/data/xfb/Xvfb_screen0 ] || [ -S /tmp/.X11-unix/X0 ]; then break; fi
    i=$((i+1)); sleep 0.1
  done
  echo "X_PROTOCOL_STUB=xvfb pid=$XPID" >> /mnt/data/gpu-stack.log
}

if [ "$BACKEND" = "soft" ]; then
  export LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-1}"
  export GALLIUM_DRIVER="${GALLIUM_DRIVER:-llvmpipe}"
  export MESA_GL_VERSION_OVERRIDE="${MESA_GL_VERSION_OVERRIDE:-3.3}"
  start_x_stub
  if [ -x /usr/bin/xdotool ] || command -v xdotool >/dev/null 2>&1; then
    if [ -x /usr/local/bin/x11-input-agent.sh ]; then
      /usr/local/bin/x11-input-agent.sh >/mnt/data/x11input-agent.out 2>&1 &
    elif [ -f /mnt/data/x11-input-agent.sh ]; then
      sh /mnt/data/x11-input-agent.sh >/mnt/data/x11input-agent.out 2>&1 &
    fi
    IPID=$!
  fi
else
  # GpuStack：不強制軟渲；X stub 只為 DISPLAY；Present 在宿主
  unset LIBGL_ALWAYS_SOFTWARE || true
  unset GALLIUM_DRIVER || true
  echo "GRAPHICS_BACKEND=gpu turnip=${VK_ICD_FILENAMES:-none} present_shm=${PRESENT_SHM:-/mnt/data/present.shm}" > /mnt/data/gpu-stack.log
  start_x_stub
  # 觸控：過渡仍可用 xdotool（WinHandler 級輸入下一檔）
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

/usr/local/bin/box64 /opt/wine/bin/wine explorer /desktop=x86,${W}x${H} "$@"
RC=$?

[ -n "$IPID" ] && kill $IPID 2>/dev/null || true
[ -n "$XPID" ] && kill $XPID 2>/dev/null || true
[ -n "$XPID" ] && wait $XPID 2>/dev/null || true
exit $RC
