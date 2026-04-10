#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <plugin-jar> <josm-plugin-dir>" >&2
  exit 1
fi

PLUGIN_JAR="$1"
PLUGIN_DIR="$2"

mkdir -p "$PLUGIN_DIR"
cp "$PLUGIN_JAR" "$PLUGIN_DIR/wayheatmaptracer.jar"

echo "Installed wayheatmaptracer.jar into $PLUGIN_DIR"

