#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

printf '%s\n' '== AdaptiveVPN local verification =='

if ! command -v java >/dev/null 2>&1; then
  echo 'ERROR: Java is missing. Install JDK 17.' >&2
  exit 2
fi
java -version 2>&1 | head -n 1

if command -v python3 >/dev/null 2>&1; then
  python3 scripts/check-libbox-contract.py
else
  echo 'NOTICE: Python 3 missing; libbox source-contract audit skipped.'
fi

if ! command -v kotlinc >/dev/null 2>&1; then
  echo 'ERROR: kotlinc is missing; core smoke test cannot run.' >&2
  exit 2
fi

mkdir -p build-local
kotlinc core/src/main/kotlin/com/adaptivevpn/core/*.kt tools-smoke.kt \
  -include-runtime -d build-local/core-smoke.jar
java -jar build-local/core-smoke.jar

kotlinc app/src/main/java/com/adaptivevpn/app/vpn/TrafficProbe.kt tools-traffic-probe-smoke.kt \
  -include-runtime -d build-local/traffic-probe-smoke.jar
java -jar build-local/traffic-probe-smoke.jar

scripts/run-core-tests-local.sh

if ! command -v gradle >/dev/null 2>&1; then
  echo 'NOTICE: Gradle is missing; Android compile/APK tests were NOT run.'
  echo 'Use GitHub Actions workflow .github/workflows/android-ci.yml or install Gradle 8.9+ and Android SDK 35.'
  exit 0
fi

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  echo 'NOTICE: Android SDK is missing; Android compile/APK tests were NOT run.'
  exit 0
fi

gradle --no-daemon testDebugUnitTest assembleDebug
