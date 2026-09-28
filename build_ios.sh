#!/bin/bash
# Builds the iOS static library (output/ios/liboflux.a + liboflux.h) from
# the OpenFlux core in the core/ submodule: its mobile/ios C API, on the
# same package the Android library uses. The app therefore speaks exactly
# what every other client speaks (Session, classic fallback, one context
# rule, both codecs, the same link reading); nothing of the core is copied
# here.
#
#   git submodule update --init core   # once, and after each core bump
#   ./build_ios.sh
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
CORE="$ROOT/core"
OUTPUT_DIR="$ROOT/output/ios"

if [ ! -f "$CORE/build_ios.sh" ]; then
    git -C "$ROOT" submodule update --init core
fi
if [ ! -d "$CORE/mobile/ios" ]; then
    echo "core/ has no mobile/ios: the submodule is too old (git submodule update core)"
    exit 1
fi

"$CORE/build_ios.sh"

mkdir -p "$OUTPUT_DIR"
cp "$CORE/output/ios/liboflux.a" "$CORE/output/ios/liboflux.h" "$OUTPUT_DIR/"
echo "core $(git -C "$CORE" describe --always --dirty) -> $OUTPUT_DIR/liboflux.a"
