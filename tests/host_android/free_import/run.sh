#!/usr/bin/env bash
# Local policy test with in-memory JSON and model stubs; no network or service.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
MAIN="$ROOT/android/src/android/app/src/main/java/ai/tama/pricing"
HERE="$ROOT/tests/host_android/free_import"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
kotlinc -nowarn -include-runtime -d "$OUT/check.jar" \
  "$HERE/JsonStubs.kt" "$HERE/ModelStubs.kt" "$HERE/FreeImportHostTest.kt" \
  "$MAIN/OpenRouterPricing.kt" "$MAIN/FreeModelImport.kt" "$MAIN/FreeModelBulk.kt"
java -jar "$OUT/check.jar"
