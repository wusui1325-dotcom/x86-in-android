#!/bin/sh
set -e
export DISPLAY=:0
# 軟渲：無 GPU／DXVK 時用 Mesa softpipe／llvmpipe（見 docs/ENGINE.md）
export LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-1}"
export GALLIUM_DRIVER="${GALLIUM_DRIVER:-llvmpipe}"
export MESA_GL_VERSION_OVERRIDE="${MESA_GL_VERSION_OVERRIDE:-3.3}"
# 音訊：無 Pulse 伺服器 → 走 ALSA null（etc/asound.conf）；關掉 winepulse 避免卡死
export WINEDLLOVERRIDES="${WINEDLLOVERRIDES:-winepulse.drv=d}"
# 有 wine-mono／gecko 時勿再禁用 mscoree/mshtml
if [ ! -f /opt/wine/share/wine/mono/wine-mono-10.1.0-x86.msi ] && \
   [ ! -d /opt/wine/share/wine/mono/wine-mono-10.1.0 ]; then
  export WINEDLLOVERRIDES="mscoree,mshtml=;$WINEDLLOVERRIDES"
fi

W="${WINE_DESKTOP_W:-1280}"
H="${WINE_DESKTOP_H:-720}"
mkdir -p /mnt/data/xfb /tmp/.X11-unix /mnt/data
rm -f /mnt/data/xfb/* 2>/dev/null || true

# null ALSA fallback（overlay 也帶 /etc/asound.conf）
if [ ! -f /etc/asound.conf ]; then
  printf 'pcm.!default { type null }\nctl.!default { type null }\n' > /etc/asound.conf
fi

Xvfb :0 -screen 0 ${W}x${H}x24 -ac -nolisten tcp -fbdir /mnt/data/xfb >/mnt/data/xvfb.log 2>&1 &
XPID=$!
i=0
while [ $i -lt 80 ]; do
  if [ -e /mnt/data/xfb/Xvfb_screen0 ] || [ -S /tmp/.X11-unix/X0 ]; then break; fi
  i=$((i+1)); sleep 0.1
done

# 觸控／鍵鼠注入 agent（xdotool）
if [ -x /usr/bin/xdotool ] || command -v xdotool >/dev/null 2>&1; then
  if [ -x /usr/local/bin/x11-input-agent.sh ]; then
    /usr/local/bin/x11-input-agent.sh >/mnt/data/x11input-agent.out 2>&1 &
  elif [ -f /mnt/data/x11-input-agent.sh ]; then
    sh /mnt/data/x11-input-agent.sh >/mnt/data/x11input-agent.out 2>&1 &
  fi
  IPID=$!
else
  IPID=
  echo "xdotool missing" >> /mnt/data/x11input.log
fi

/usr/local/bin/box64 /opt/wine/bin/wine explorer /desktop=x86,${W}x${H} "$@"
RC=$?

[ -n "$IPID" ] && kill $IPID 2>/dev/null || true
kill $XPID 2>/dev/null || true
wait $XPID 2>/dev/null || true
exit $RC
