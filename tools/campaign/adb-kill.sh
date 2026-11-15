#!/bin/sh
# Kills the app the way S5 and S6 of docs/testing/device-matrix.md ask and prints the kill instant, read from the
# phone's own clock, as epoch milliseconds: the value for `campaign evaluate ... --kill-at`.
#
#   adb-kill.sh force-stop   S5(a): the user's "Force stop" (the system does not restart the service by itself)
#   adb-kill.sh kill9        S5(b): a process death like the system's (debug or campaign build; needs run-as)
#   adb-kill.sh reboot       S6:    reboot the phone
#
# One phone attached (or ANDROID_SERIAL set). PKG is the app id (default dev.averyn.app). The instant is read just
# before the command, to the second: the matrix criterion (1 s) already allows for it.
set -eu

pkg="${PKG:-dev.averyn.app}"
mode="${1:?force-stop | kill9 | reboot}"

# toybox "date +%s" is whole seconds; the tester's clock reading is not more exact than that either.
seconds=$(adb shell date +%s | tr -d '\r')
case "$mode" in
force-stop) adb shell am force-stop "$pkg" ;;
kill9)
  pid=$(adb shell pidof "$pkg" | tr -d '\r' | awk '{print $1}')
  [ -n "$pid" ] || { echo "$pkg is not running" >&2; exit 1; }
  adb shell run-as "$pkg" kill -9 "$pid"
  ;;
reboot) adb reboot ;;
*) echo "unknown mode: $mode" >&2; exit 64 ;;
esac
echo "$((seconds * 1000))"
