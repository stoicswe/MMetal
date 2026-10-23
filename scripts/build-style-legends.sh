#!/usr/bin/env bash
#
# Builds the example MMetal style plugin jar (Legends).
#   ./scripts/build-style-legends.sh
# Output: dist/mmetal-style-legends-1.0.0.jar
#
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SRC=styles/legends
LIB="$SRC/assets/mmetal_legends/metal/legends.metallib"
OUT=dist/mmetal-style-legends-1.0.0.jar

command -v xcrun >/dev/null || { echo "ERROR: Xcode command line tools (xcrun) required." >&2; exit 1; }
mkdir -p "$(dirname "$LIB")" dist

echo "Compiling Metal kernel..."
xcrun metal "$SRC/metal/legends.metal" -o "$LIB"

echo "Packaging $OUT..."
rm -f "$OUT"
( cd "$SRC" && zip -q -r "$ROOT/$OUT" fabric.mod.json mmetal assets )

echo "Built: $OUT"
echo "Install: drop it into .minecraft/mods next to the MMetal jar."
