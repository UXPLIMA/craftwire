#!/bin/sh
# Starts the Paper server with the Craftwire plugin. The Minecraft EULA is the user's to accept: the server only starts
# when they set EULA=true for this service themselves (https://aka.ms/MinecraftEULA).
set -eu
cd /data
if [ "${EULA:-}" != "true" ]; then
  echo "The Minecraft EULA is not accepted. Read https://aka.ms/MinecraftEULA, then set EULA=true for the paper service." >&2
  exit 1
fi
[ -f eula.txt ] || echo "eula=true" > eula.txt
# Bots and hidden test clients join offline-mode servers; the first start writes that unless ONLINE_MODE=true.
if [ ! -f server.properties ]; then
  printf 'online-mode=%s\nserver-port=%s\n' "${ONLINE_MODE:-false}" "${SERVER_PORT:-25565}" > server.properties
fi
mkdir -p plugins
rm -f plugins/craftwire-paper-*.jar
cp /opt/craftwire/craftwire-paper-*.jar plugins/
exec java -Xms"${MEMORY:-2G}" -Xmx"${MEMORY:-2G}" -jar /opt/paper.jar --nogui
