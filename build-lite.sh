#!/bin/sh
# Использует существующие Gradle wrapper, Android SDK и общий кэш.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
mode=${1:-apk}
case "$mode" in
  apk) shift_if_present=1; set -- :app:assembleRelease ;;
  check) shift_if_present=1; set -- :app:assembleRelease :app:testDebugUnitTest :app:lintRelease ;;
  *) printf '%s\n' 'Использование: ./build-lite.sh [apk|check]' >&2; exit 2 ;;
esac
# По умолчанию никакой сети. Для первого заполнения кэша: ALLOW_DOWNLOADS=1.
if [ "${ALLOW_DOWNLOADS:-0}" = 1 ]; then
  exec ./gradlew --no-daemon --max-workers=1 --console=plain "$@"
else
  exec ./gradlew --offline --no-daemon --max-workers=1 --console=plain "$@"
fi
