#!/bin/sh
set -eu

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${1:-$PWD}"
SRC_DIR="${2:-$HOME/.local/share/JOSM}"
BUNDLE_DIR="$OUT_DIR/wayheatmaptracer-debug-$STAMP"

mkdir -p "$BUNDLE_DIR"

copy_if_exists() {
  if [ -e "$1" ]; then
    cp -R "$1" "$BUNDLE_DIR/"
  fi
}

copy_if_exists "$SRC_DIR/logs"
copy_if_exists "$SRC_DIR/wayheatmaptracer"
copy_if_exists "$SRC_DIR/screenshots"
copy_if_exists "$SRC_DIR/preferences.xml"

tar -C "$OUT_DIR" -czf "$OUT_DIR/wayheatmaptracer-debug-$STAMP.tar.gz" "wayheatmaptracer-debug-$STAMP"
rm -rf "$BUNDLE_DIR"

echo "$OUT_DIR/wayheatmaptracer-debug-$STAMP.tar.gz"

