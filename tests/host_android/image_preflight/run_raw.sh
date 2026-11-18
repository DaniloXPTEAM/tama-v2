#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
COROUTINES="$(dirname "$(dirname "$(command -v kotlinc)")")/lib/kotlinx-coroutines-core-jvm.jar"
kotlinc -nowarn -include-runtime -classpath "$COROUTINES" -d "$OUT/test.jar" \
  "$ROOT/android/src/android/app/src/main/java/ai/tama/media/ImageRawToolFilter.kt" \
  "$ROOT/android/src/android/app/src/main/java/ai/tama/media/ImageToolStream.kt" \
  "$ROOT/tests/host_android/image_preflight/StreamChunkStub.kt" \
  "$ROOT/tests/host_android/image_preflight/RawToolHostTest.kt"
java -cp "$OUT/test.jar:$COROUTINES" ai.tama.media.RawToolHostTestKt
