#!/usr/bin/env bash
# Host-only: compiles production endpoint/preset code and extracted production routing expressions.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HERE="$ROOT/tests/host_android/provider_endpoints"
MAIN="$ROOT/android/src/android/app/src/main/java"
command -v kotlinc >/dev/null
command -v java >/dev/null
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
python3 - "$MAIN" "$OUT/Extracted.kt" <<'PY'
from pathlib import Path
import json
import sys

main = Path(sys.argv[1])
assets = main.parent / 'assets/nanomuse/providers.json'
providers = {p['id']: p for p in json.loads(assets.read_text())['providers']}
assert providers['groq']['base_url'] == 'https://api.groq.com/openai/v1'
assert providers['openrouter']['base_url'] == 'https://openrouter.ai/api/v1'
assert providers['groq']['key_hint'] == 'gsk_…'
assert providers['openrouter']['key_hint'] == 'sk-or-v1-…'
form = (main / 'com/openminis/app/ui/settings/AddProviderScreen.kt').read_text()
assert 'ai.tama.providers.KnownProviderSection(onSelect = onSelectVoiceTemplate)' in form
assert 'voiceTemplate?.baseURL ?: if (nmParallel) ai.tama.providers.ProviderEndpoints.editableDefault(providerType)' in form
assert 'ai.tama.providers.ProviderEndpoints.storageBase(providerType, trimmedBase, appendV1Suffix)' in form
assert '(!nmParallel || !autoSuggested)' in form
source = (main / 'com/openminis/app/data/model/ProviderConfig.kt').read_text()
start = source.index('@Serializable\ndata class ProviderInstance(')
end = source.index('\n@Serializable\ndata class ModelOverrides(', start)
instance = source[start:end]
factory = (main / 'com/openminis/app/provider/ProviderFactory.kt').read_text()
base_path = 'val basePath = instance.effectiveBaseURL'
base = 'val base = basePath ?: "https://api.openai.com/v1"'
assert factory.count(base_path) == 1 and factory.count(base) == 1
assert factory.index('ProviderType.openAI, ProviderType.openAIResponses ->') < factory.index(base) < factory.index('ProviderType.openRouter ->')
Path(sys.argv[2]).write_text('''package com.openminis.app.data.model
import kotlinx.serialization.Serializable
''' + instance + '\nfun extractedOpenAIBase(instance: ProviderInstance): String {\n    ' + base_path + '\n    ' + base + '\n    return base\n}\n')
PY
# The helper is generated in the model package from the real factory statements.
# Import it in the test without rewriting the production files.
sed 's/import com.openminis.app.data.model.ProviderType/import com.openminis.app.data.model.ProviderType\nimport com.openminis.app.data.model.extractedOpenAIBase/' "$HERE/EndpointHostTest.kt" > "$OUT/EndpointHostTest.kt"
kotlinc -nowarn -include-runtime -d "$OUT/endpoints.jar" \
  "$HERE"/stubs/*.kt "$OUT/Extracted.kt" "$OUT/EndpointHostTest.kt" \
  "$MAIN/ai/tama/providers/ProviderEndpoints.kt" \
  "$MAIN/io/github/nanomuse/cloud/OwnKeyPresets.kt"
java -jar "$OUT/endpoints.jar"
