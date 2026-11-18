#!/usr/bin/env bash
# Route mapper and image intent only; JSON parser and providers are stubbed/not exercised.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
MAIN="$ROOT/android/src/android/app/src/main/java"
HERE="$ROOT/tests/host_android/provider_endpoints"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
kotlinc -nowarn -include-runtime -d "$OUT/history.jar" \
  "$HERE/JsonHistoryStub.kt" "$HERE/SerializationStub.kt" "$HERE/RouteHistoryHostTest.kt" \
  "$MAIN/ai/tama/router/ModelRouteHistory.kt" "$MAIN/ai/tama/router/ShortcutParser.kt" \
  "$MAIN/com/openminis/app/data/model/AgentContentPart.kt" \
  "$MAIN/com/openminis/app/data/model/LLMMessage.kt" \
  "$MAIN/com/openminis/app/data/model/LLMUsage.kt"
java -jar "$OUT/history.jar"
