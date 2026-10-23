#!/usr/bin/env bash
# Start the Minecraft server (Fabric 26.3 + mc-sionna). Run from anywhere.
cd "$(dirname "$0")"
exec ../.tools/jdk-25.0.5+8/bin/java -Xms2G -Xmx8G -jar fabric-server-launch.jar nogui
