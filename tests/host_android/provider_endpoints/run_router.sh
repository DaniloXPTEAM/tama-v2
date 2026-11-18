#!/usr/bin/env bash
# Real selection policy with synthetic candidates; no network or Android.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
MAIN="$ROOT/android/src/android/app/src/main/java/ai/tama/router"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
kotlinc -nowarn -include-runtime -d "$OUT/router.jar" \
  "$ROOT/tests/host_android/provider_endpoints/RouterHostTest.kt" \
  "$MAIN/Profile.kt" "$MAIN/TurnRouter.kt" "$MAIN/ModelHealth.kt" \
  "$MAIN/ModelRouter.kt" "$MAIN/AutoModelTurn.kt"
java -jar "$OUT/router.jar"
