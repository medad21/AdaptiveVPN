#!/usr/bin/env bash
# Runs the pure-Kotlin core unit tests with kotlinc + a tiny local kotlin.test shim (no Gradle needed).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

command -v kotlinc >/dev/null 2>&1 || { echo 'ERROR: kotlinc is missing.' >&2; exit 2; }
command -v java >/dev/null 2>&1 || { echo 'ERROR: java is missing.' >&2; exit 2; }

TEST_DIR=core/src/test/kotlin/com/adaptivevpn/core
mkdir -p build-local
kotlinc -Xallow-kotlin-package scripts/local-test-shim/*.kt \
  core/src/main/kotlin/com/adaptivevpn/core/*.kt "$TEST_DIR"/*.kt \
  -include-runtime -d build-local/core-tests.jar 2> >(grep -v '^warning:' >&2)

CLASSES=$(grep -hoE '^class [A-Za-z0-9]+Test' "$TEST_DIR"/*.kt | awk '{print "com.adaptivevpn.core."$2}')
# shellcheck disable=SC2086
java -cp build-local/core-tests.jar RunnerKt $CLASSES
