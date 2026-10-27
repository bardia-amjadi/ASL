#!/bin/sh
# ASL: installs a VNC server plus a desktop environment inside an apt-based distro (Debian/Ubuntu).
# usage: asl-desktop-install.sh <package> [<package> ...]
# Runs under PRoot as fake root. Output is shown to the user as-is.
set -e
export DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 LC_ALL=C.UTF-8

if ! command -v apt-get >/dev/null 2>&1; then
  echo "This distribution has no apt-get; desktop installation is only supported on Debian/Ubuntu."
  exit 2
fi

# Package postinst scripts try to start services; there is no init system under PRoot.
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod 755 /usr/sbin/policy-rc.d

echo "== apt-get update"
apt-get update

echo "== VNC server and basics"
apt-get install -y --no-install-recommends \
  tigervnc-standalone-server tigervnc-tools dbus-x11 x11-xserver-utils xauth fonts-dejavu-core

echo "== desktop packages: $*"
apt-get install -y "$@"

echo "ASL-INSTALL-OK"
