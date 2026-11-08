#!/bin/sh
set -e
export DISPLAY=:0
BACKEND="${GRAPHICS_BACKEND:-gpu}"

W="${WINE_DESKTOP_W:-1280}"
H="${WINE_DESKTOP_H:-720}"
mkdir -p /mnt/data/xfb /tmp/.X11-unix /mnt/data
rm -f /mnt/data/xfb/* 2>/dev/null || true

# 音訊：無 Pulse → ALSA null；關掉 winepulse 避免卡死（GpuStack 真 AAudio 後再改）
export WINEDLLOVERRIDES="${WINEDLLOVERRIDES:-winepulse.drv=d}"
if [ ! -f /opt/wine/share/wine/mono/wine-mono-10.1.0-x86.msi ] && \
   [ ! -d /opt/wine/share/wine/mono/wine-mono-10.1.0 ]; then
  export WINEDLLOVERRIDES="mscoree,mshtml=;$WINEDLLOVERRIDES"
fi

if [ ! -f /etc/asound.conf ]; then
  printf 'pcm.!default { type null }\nctl.!default { type null }\n' > /etc/asound.conf
fi

# DXVK：若已 bind /mnt/gpu/dxvk，確保 system32 有 DLL（宿主也可能已 stage）
if [ -d /mnt/gpu/dxvk/x64 ]; then
  mkdir -p /home/xuser/.wine/drive_c/windows/system32
  for dll in d3d8 d3d9 d3d10core d3d11 dxgi; do
    if [ -f "/mnt/gpu/dxvk/x64/${dll}.dll" ]; then
      cp -f "/mnt/gpu/dxvk/x64/${dll}.dll" /home/xuser/.wine/drive_c/windows/system32/ 2>/dev/null || true
    fi
  done
fi

# Turnip ICD hint（Present 橋落地前僅備料；真正可用需宿主 Vulkan／thunk）
if [ -f /mnt/gpu/turnip/freedreno_icd.json ]; then
  export VK_ICD_FILENAMES="/mnt/gpu/turnip/freedreno_icd.json"
fi

XPID=
IPID=

if [ "$BACKEND" = "soft" ]; then
  # —— SoftLegacy（工程保底；非產品預設）——
  export LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-1}"
  export GALLIUM_DRIVER="${GALLIUM_DRIVER:-llvmpipe}"
  export MESA_GL_VERSION_OVERRIDE="${MESA_GL_VERSION_OVERRIDE:-3.3}"
  Xvfb :0 -screen 0 ${W}x${H}x24 -ac -nolisten tcp -fbdir /mnt/data/xfb >/mnt/data/xvfb.log 2>&1 &
  XPID=$!
  i=0
  while [ $i -lt 80 ]; do
    if [ -e /mnt/data/xfb/Xvfb_screen0 ] || [ -S /tmp/.X11-unix/X0 ]; then break; fi
    i=$((i+1)); sleep 0.1
  done
  if [ -x /usr/bin/xdotool ] || command -v xdotool >/dev/null 2>&1; then
    if [ -x /usr/local/bin/x11-input-agent.sh ]; then
      /usr/local/bin/x11-input-agent.sh >/mnt/data/x11input-agent.out 2>&1 &
    elif [ -f /mnt/data/x11-input-agent.sh ]; then
      sh /mnt/data/x11-input-agent.sh >/mnt/data/x11input-agent.out 2>&1 &
    fi
    IPID=$!
  else
    echo "xdotool missing" >> /mnt/data/x11input.log
  fi
else
  # —— GpuStack：禁止預設軟渲；DISPLAY 由進程內 X／Present 橋提供（尚未就緒則不應進到這裡）——
  unset LIBGL_ALWAYS_SOFTWARE || true
  unset GALLIUM_DRIVER || true
  echo "GRAPHICS_BACKEND=gpu turnip=${VK_ICD_FILENAMES:-none}" > /mnt/data/gpu-stack.log
  if [ ! -S /tmp/.X11-unix/X0 ]; then
    echo "FATAL: GpuStack needs in-app X / Present bridge (no Xvfb fallback)" >> /mnt/data/gpu-stack.log
    exit 91
  fi
fi

/usr/local/bin/box64 /opt/wine/bin/wine explorer /desktop=x86,${W}x${H} "$@"
RC=$?

[ -n "$IPID" ] && kill $IPID 2>/dev/null || true
[ -n "$XPID" ] && kill $XPID 2>/dev/null || true
[ -n "$XPID" ] && wait $XPID 2>/dev/null || true
exit $RC
