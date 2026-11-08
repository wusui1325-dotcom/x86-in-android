#!/bin/sh
set -e
export DISPLAY=:0
export WINEDLLOVERRIDES="mscoree,mshtml="
W="${WINE_DESKTOP_W:-1280}"
H="${WINE_DESKTOP_H:-720}"
mkdir -p /mnt/data/xfb /tmp/.X11-unix
rm -f /mnt/data/xfb/* 2>/dev/null || true
Xvfb :0 -screen 0 ${W}x${H}x24 -ac -nolisten tcp -fbdir /mnt/data/xfb >/mnt/data/xvfb.log 2>&1 &
XPID=$!
i=0
while [ $i -lt 80 ]; do
  if [ -e /mnt/data/xfb/Xvfb_screen0 ] || [ -S /tmp/.X11-unix/X0 ]; then break; fi
  i=$((i+1)); sleep 0.1
done
/usr/local/bin/box64 /opt/wine/bin/wine explorer /desktop=x86,${W}x${H} "$@"
RC=$?
kill $XPID 2>/dev/null || true
wait $XPID 2>/dev/null || true
exit $RC
