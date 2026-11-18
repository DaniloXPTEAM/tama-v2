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
bundled = json.loads((assets.parent.parent / 'models-dev-api.json').read_text())
assert len(bundled) == 225
for vendor in ('nvidia', 'agnes', 'deepseek', 'ai-router', 'unorouter', 'agentrouter', 'fastrouter',
               'orcarouter', 'tokenrouter', 'trustedrouter'):
    assert bundled[vendor]['npm'] == '@ai-sdk/openai-compatible'
    assert bundled[vendor]['api'].startswith('https://')
assert {'agnes-2.5-pro-alpha', 'agnes-2.0-flash', 'agnes-2.5-flash'} == set(bundled['agnes']['models'])
assert all('image' not in m['modalities']['output'] for m in bundled['agnes']['models'].values())
assert providers['groq']['base_url'] == 'https://api.groq.com/openai/v1'
assert providers['openrouter']['base_url'] == 'https://openrouter.ai/api/v1'
assert providers['groq']['key_hint'] == 'gsk_…'
assert providers['openrouter']['key_hint'] == 'sk-or-v1-…'
form = (main / 'com/openminis/app/ui/settings/AddProviderScreen.kt').read_text()
assert 'ai.tama.providers.KnownProviderSection(onSelect = onSelectVoiceTemplate)' in form
assert 'voiceTemplate?.baseURL ?: if (nmParallel) ai.tama.providers.ProviderEndpoints.editableDefault(providerType)' in form
assert 'ai.tama.providers.ProviderEndpoints.storageBase(providerType, trimmedBase, appendV1Suffix)' in form
assert 'enabled = nmDestinationAllowed &&' in form
assert 'OpenRouterEndpointSection()' in form
assert 'onApiKeyChange = onApiKeyChange' in form
assert 'KeyDestinationGuard.permits(apiKey, nmDestination)' in form
provider = (main / 'com/openminis/app/provider/openai/OpenAIProvider.kt').read_text()
assert provider.index('KeyDestinationGuard.requireAllowed(') < provider.index('val body = if (isCodexImageModel)')
repo = (main / 'com/openminis/app/data/repository/ProviderRepository.kt').read_text()
start_refresh = repo.index('private suspend fun nmRunRefresh(')
assert repo.index('KeyDestinationGuard.permits(refresh.key.key, base)', start_refresh) < repo.index('nmRefreshFreeModels(refresh)', start_refresh)
chat = (main / 'com/openminis/app/ui/chat/ChatViewModel.kt').read_text()
assert 'actual is ai.tama.providers.KeyDestinationGuard.Blocked -> context.getString(R.string.nm_key_destination_blocked)' in chat
assert 'if (recipientBlocked && !nmOwnKeyTurn) throw actual' in chat
for name in ('anthropic/AnthropicProvider.kt', 'gemini/GeminiProvider.kt'):
    provider_source = (main / 'com/openminis/app/provider' / name).read_text()
    assert provider_source.count('KeyDestinationGuard.requireAllowed(') == 2
    assert provider_source.count('if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") {\n            ai.tama.providers.KeyDestinationGuard.requireAllowed(') == 2
assert 'if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") {\n            ai.tama.providers.KeyDestinationGuard.requireAllowed(' in provider
record = (main / 'io/github/nanomuse/cloud/OwnTurns.kt').read_text()
assert record.count('KeyDestinationGuard.causedByBlock(e)') == 4
ui = (main / 'ai/tama/providers/ProviderSetupUi.kt').read_text()
assert 'KeyDestinationGuard.confirm(key, endpoint)' in ui
assert 'remember(key, endpoint)' in ui
detail = (main / 'com/openminis/app/ui/settings/ProviderDetailScreen.kt').read_text()
assert 'KeyDestinationReview(nmReviewedKey, nmLiveDestination)' in detail
assert 'KeyDestinationGuard.permits(editKeyValue, nmLiveDestination)' in detail
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
  "$MAIN/ai/tama/providers/KeyDestinationGuard.kt" \
  "$MAIN/ai/tama/providers/KeyProbePlan.kt" \
  "$MAIN/io/github/nanomuse/cloud/OwnKeyPresets.kt"
java -jar "$OUT/endpoints.jar"
