#!/bin/sh
# 輪詢 /mnt/data/x11input.cmd，用 xdotool 注入 X11（比 FIFO 在 Android bind 上更穩）
export DISPLAY=:0
CMD=/mnt/data/x11input.cmd
WORK=/mnt/data/x11input.cmd.work
LOG=/mnt/data/x11input.log
mkdir -p /mnt/data
echo "input-agent start $(date)" >> "$LOG"
xdotool keyup Shift_L Control_L Alt_L Super_L 2>/dev/null || true
while true; do
  if [ -s "$CMD" ]; then
    mv "$CMD" "$WORK" 2>/dev/null || continue
    while IFS= read -r line || [ -n "$line" ]; do
      [ -z "$line" ] && continue
      echo "cmd: $line" >> "$LOG"
      set -- $line
      c=$1; shift
      case "$c" in
        move|mousemove) xdotool mousemove --sync "$1" "$2" 2>>"$LOG" || xdotool mousemove "$1" "$2" 2>>"$LOG" ;;
        down|mousedown) xdotool mousedown "${1:-1}" 2>>"$LOG" ;;
        up|mouseup) xdotool mouseup "${1:-1}" 2>>"$LOG" ;;
        click) xdotool click "${1:-1}" 2>>"$LOG" ;;
        key) xdotool key "$@" 2>>"$LOG" ;;
        keydown) xdotool keydown "$@" 2>>"$LOG" ;;
        keyup) xdotool keyup "$@" 2>>"$LOG" ;;
        type) xdotool type --clearmodifiers -- "$@" 2>>"$LOG" ;;
        *) echo "unknown: $c" >> "$LOG" ;;
      esac
    done < "$WORK"
    rm -f "$WORK"
  else
    sleep 0.01
  fi
done
