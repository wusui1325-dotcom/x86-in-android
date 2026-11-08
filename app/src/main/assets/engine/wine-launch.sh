#!/bin/sh
set -e
export DISPLAY=:0
BACKEND="${GRAPHICS_BACKEND:-gpu}"

W="${WINE_DESKTOP_W:-1280}"
H="${WINE_DESKTOP_H:-720}"
mkdir -p /mnt/data /tmp/.X11-unix /tmp/.sysvshm

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
fi

# 輸入：過渡 xdotool 僅 SoftLegacy；GpuStack 待 WinHandler／inject
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

/usr/local/bin/box64 /opt/wine/bin/wine explorer /desktop=x86,${W}x${H} "$@"
RC=$?

[ -n "$IPID" ] && kill $IPID 2>/dev/null || true
[ -n "$XPID" ] && kill $XPID 2>/dev/null || true
[ -n "$XPID" ] && wait $XPID 2>/dev/null || true
exit $RC
