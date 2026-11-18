#!/usr/bin/env bash
# Real selection policy with synthetic candidates; no network or Android.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
APP="$ROOT/android/src/android/app/src/main/java/ai/tama"
MAIN="$APP/router"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
kotlinc -nowarn -include-runtime -d "$OUT/router.jar" \
  "$ROOT/tests/host_android/provider_endpoints/RouterHostTest.kt" \
  "$APP/net/OwnKeyFailureSignals.kt" "$APP/providers/OwnKeyScope.kt" \
  "$MAIN/Profile.kt" "$MAIN/TurnRouter.kt" "$MAIN/ModelHealth.kt" \
  "$MAIN/ModelRouter.kt" "$MAIN/AutoModelTurn.kt"
java -jar "$OUT/router.jar"
