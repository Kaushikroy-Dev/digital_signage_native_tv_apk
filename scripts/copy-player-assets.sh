#!/usr/bin/env bash
# Copy frontend production build into Android assets for offline cold-boot.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/frontend/dist"
DEST="$ROOT/android-tv-app/app/src/main/assets/player"

if [[ ! -f "$SRC/index.html" ]]; then
  echo "ERROR: $SRC/index.html not found. Run: cd frontend && npm run build"
  exit 1
fi

rm -rf "$DEST"
mkdir -p "$DEST"
cp -R "$SRC/"* "$DEST/"

echo "Copied web player to $DEST"
echo "Next: set USE_BUNDLED_PLAYER=true in android-tv-app/app/build.gradle and rebuild APK."
