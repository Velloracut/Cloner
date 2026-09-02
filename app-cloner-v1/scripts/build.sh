#!/usr/bin/env bash
set -euo pipefail
chmod +x ./gradlew
case "${1:-apk}" in
  apk) ./gradlew assembleRelease ;;
  aab) ./gradlew bundleRelease ;;
  debug) ./gradlew assembleDebug ;;
  *) echo "Usage: $0 apk|aab|debug"; exit 1 ;;
esac
