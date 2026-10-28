#!/bin/sh
# ASL: starts an Xtigervnc server on :1 (VNC port 5901, loopback only, password protected) and a desktop session.
# usage: asl-desktop-start.sh <WIDTHxHEIGHT> <XDG_CURRENT_DESKTOP> <session command> [args...]
GEOM="$1"
XDG="$2"
shift 2

export HOME=/root USER=root LOGNAME=root LANG=C.UTF-8 LC_ALL=C.UTF-8
export XDG_RUNTIME_DIR=/tmp/runtime-root
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1

export DISPLAY=:1
export XDG_SESSION_TYPE=x11 XDG_CURRENT_DESKTOP="$XDG" GDK_BACKEND=x11 QT_QPA_PLATFORM=xcb
# No GPU under PRoot: force software rendering and quiet the accessibility bridge.
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe NO_AT_BRIDGE=1

if [ ! -f /root/.vnc/passwd ]; then
  echo "ASL-ERROR: VNC password file is missing"
  exit 1
fi

Xtigervnc :1 -geometry "$GEOM" -depth 24 -rfbport 5901 -localhost \
  -SecurityTypes VncAuth -PasswordFile /root/.vnc/passwd \
  -desktop ASL -nolisten tcp -AlwaysShared &
XPID=$!

TRIES=0
while [ ! -S /tmp/.X11-unix/X1 ] && [ "$TRIES" -lt 30 ]; do
  if ! kill -0 "$XPID" 2>/dev/null; then
    echo "ASL-ERROR: the X/VNC server exited during start-up"
    exit 1
  fi
  sleep 1
  TRIES=$((TRIES + 1))
done
if [ ! -S /tmp/.X11-unix/X1 ]; then
  echo "ASL-ERROR: the X server did not become ready within 30 s"
  kill "$XPID" 2>/dev/null
  exit 1
fi
echo "ASL-X-READY"

# dbus needs a machine id; create one if the image has none.
[ -s /etc/machine-id ] || dbus-uuidgen --ensure=/etc/machine-id 2>/dev/null
mkdir -p /var/run/dbus 2>/dev/null

echo "ASL: starting session: $*"
dbus-launch --exit-with-session "$@" &
SPID=$!

# Report loudly if the desktop session dies (otherwise you only see a black screen with a cursor).
(
  sleep 3
  while kill -0 "$SPID" 2>/dev/null; do sleep 2; done
  echo "ASL-SESSION-ENDED: the desktop session process exited. The screen will stay black. Read the lines above for the reason."
) &

wait "$XPID"
echo "ASL-X-EXITED"
