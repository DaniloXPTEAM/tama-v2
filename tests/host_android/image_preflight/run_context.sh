#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
kotlinc -nowarn -include-runtime -d "$OUT/test.jar" \
  "$ROOT/android/src/android/app/src/main/java/ai/tama/router/LargerContext.kt" \
  "$ROOT/android/src/android/app/src/main/java/io/github/nanomuse/chat/ContextErrors.kt" \
  "$ROOT/tests/host_android/image_preflight/ContextHostTest.kt"
java -jar "$OUT/test.jar"
