#!/bin/sh
# ASL: stores the VNC password (read from stdin) in the obfuscated format Xtigervnc expects.
set -e
mkdir -p /root/.vnc
IFS= read -r PASSWORD
if [ -z "$PASSWORD" ]; then
  echo "empty password" >&2
  exit 1
fi
printf '%s\n' "$PASSWORD" | vncpasswd -f > /root/.vnc/passwd
chmod 600 /root/.vnc/passwd
echo "ASL-VNC-PASSWD-OK"
