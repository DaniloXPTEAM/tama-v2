package ai.tama.providers

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import io.github.nanomuse.cloud.CatalogueProvider
import io.github.nanomuse.cloud.OwnKeyPresets

private fun equal(expected: Any?, actual: Any?) = check(expected == actual) {
    "Expected <$expected> but got <$actual>"
}

fun main() {
    val agnes = OwnKeyPresets.agnesPreset()
    val agnesInstance = ProviderInstance("fixture-agnes", agnes.name, agnes.providerType, ProviderCredential.apiKey,
        customBaseURL = agnes.baseURL, appendV1Suffix = agnes.appendV1)
    equal("Agnes", agnes.name)
    equal(ProviderEndpoints.AGNES_BASE, agnesInstance.effectiveBaseURL)
    equal(false, agnesInstance.allowsEmptyAPIKey)
    equal(true, agnesInstance.copy(customBaseURL = "https://local.example/v1").allowsEmptyAPIKey)
    equal(true, ProviderDirectory.validCustomAddress("HTTPS://APIHUB.AGNES-AI.COM/v1"))
    equal(true, ProviderEndpoints.isAgnesBase("HTTPS://APIHUB.AGNES-AI.COM/v1"))
    equal(false, ProviderEndpoints.isAgnesBase("https://apihub.agnes-ai.com.example/v1"))
    val groq = OwnKeyPresets.template(
        CatalogueProvider("groq", "openai", "https://api.groq.com/openai/v1"), false, false,
    )
    equal(ProviderType.openAI, groq.providerType)
    equal("https://api.groq.com/openai", groq.baseURL)
    equal(true, groq.appendV1)
    val groqStored = ProviderEndpoints.storageBase(groq.providerType, groq.baseURL, groq.appendV1)
    val instance = ProviderInstance("fixture", "fixture", groq.providerType, ProviderCredential.apiKey,
        customBaseURL = groqStored, appendV1Suffix = groq.appendV1)
    equal("https://api.groq.com/openai/v1", instance.effectiveBaseURL)
    equal("https://api.groq.com/openai/v1", extractedOpenAIBase(instance))

    val emptyStored = ProviderEndpoints.storageBase(ProviderType.openAI, "  ", true)
    equal(null, emptyStored)
    val empty = instance.copy(customBaseURL = emptyStored)
    equal(null, empty.effectiveBaseURL)
    equal("https://api.openai.com/v1", extractedOpenAIBase(empty))
    // An empty *persisted string* is different from the form's null, both suffix settings matter.
    equal("/v1", instance.copy(customBaseURL = "", appendV1Suffix = true).effectiveBaseURL)
    equal("", instance.copy(customBaseURL = "", appendV1Suffix = false).effectiveBaseURL)

    val router = OwnKeyPresets.template(
        CatalogueProvider("openrouter", "openai", "https://openrouter.ai/api/v1"), false, false,
    )
    equal(ProviderType.openRouter, router.providerType)
    equal("https://openrouter.ai/api", router.baseURL)
    equal(true, router.appendV1)
    equal("https://openrouter.ai/api/v1", ProviderEndpoints.effective(router.providerType, router.baseURL, router.appendV1))
    equal(null, ProviderEndpoints.storageBase(router.providerType, router.baseURL, router.appendV1))
    equal("https://api.groq.com/openai/v1", ProviderEndpoints.effective(ProviderType.openAI, "https://api.groq.com/openai/v1/", true))
    equal("https://api.groq.com/openai", ProviderEndpoints.effective(ProviderType.openAI, "https://api.groq.com/openai", false))

    val hints = listOf("groq" to "gsk_…", "router" to "sk-or-v1-…", "generic" to "sk-…")
    equal("groq", ProviderKeySuggestion.match("gsk_synthetic", hints))
    equal("router", ProviderKeySuggestion.match("sk-or-v1-synthetic", hints))
    equal(null, ProviderKeySuggestion.match("sk-synthetic", hints))
    equal(false, ProviderKeySuggestion.mayOfferPreset(true, false))
    equal(false, ProviderKeySuggestion.mayOfferPreset(false, true))
    equal(true, ProviderKeySuggestion.mayOfferPreset(false, false))

    // This is only the key-review policy, not the Cloud initialization or chat path.
    for (base in listOf(null, "", "https://cloud.example.invalid/v1")) {
        equal(null, KeyDestinationGuard.review(null, base))
        equal(true, KeyDestinationGuard.permits(null, base))
        KeyDestinationGuard.requireAllowed(true, null, base)
    }

    val groqKey = "gsk_synthetic"
    val routerKey = "sk-or-synthetic"
    val openAI = ProviderEndpoints.factoryBase(ProviderType.openAI, null, true)
    equal("https://api.openai.com/v1", openAI)
    equal(null, KeyDestinationGuard.review(groqKey, "https://api.groq.com/openai/v1"))
    equal(null, KeyDestinationGuard.review(routerKey, "https://openrouter.ai/api/v1"))
    equal(null, KeyDestinationGuard.review("ordinary-synthetic", openAI))
    equal(false, KeyDestinationGuard.permits(groqKey, openAI))
    equal(false, KeyDestinationGuard.permits(routerKey, null))
    equal(false, KeyDestinationGuard.confirm(routerKey, null))
    equal(false, KeyDestinationGuard.confirm(routerKey, "http://local.example/api/v1"))
    equal(false, KeyDestinationGuard.confirm(routerKey, "https://user:pass@other.example/v1"))
    equal(false, KeyDestinationGuard.confirm(routerKey, "https://other.example/v1?key=private"))
    equal(false, KeyDestinationGuard.permits(groqKey, "https://api.groq.com.example/openai/v1"))
    equal(false, KeyDestinationGuard.permits(groqKey, "https://api.groq.com/other/v1"))
    try {
        KeyDestinationGuard.requireAllowed(true, groqKey, openAI)
        error("Mismatched destination was not blocked")
    } catch (_: KeyDestinationGuard.Blocked) { }
    KeyDestinationGuard.requireAllowed(false, groqKey, openAI) // Normal app remains unchanged.
    equal(true, KeyDestinationGuard.confirm(groqKey, openAI))
    equal(true, KeyDestinationGuard.permits(groqKey, openAI))
    equal(false, KeyDestinationGuard.permits(groqKey + "2", openAI))
    equal(false, KeyDestinationGuard.permits(groqKey, "https://other.example/v1"))
    equal(false, KeyDestinationGuard.permits(routerKey, openAI))
    equal(false, KeyDestinationGuard.permits("sk-or-fixture", "https://api.groq.com/openai/v1"))
    equal(null, KeyDestinationGuard.review("sk-ant-fixture", "https://api.anthropic.com"))
    equal(null, KeyDestinationGuard.review("xai-fixture", "https://api.x.ai/v1"))
    equal(null, KeyDestinationGuard.review("AQ.fixture", "https://generativelanguage.googleapis.com/v1beta"))
    equal(false, KeyDestinationGuard.permits("AQ.fixture", openAI))
    equal(false, KeyDestinationGuard.permits("sk-ant-fixture", openAI))
    equal(false, KeyDestinationGuard.permits("xai-fixture", openAI))
    equal("gemini", ProviderKeySuggestion.match("AQ.fixture", listOf("gemini" to "AQ.…")))
    equal(null, ProviderKeySuggestion.match("AQ.fixture", listOf("other" to "AQ.…")))
    equal(true, KeyDestinationGuard.causedByBlock(IllegalStateException(KeyDestinationGuard.Blocked())))
    equal(false, KeyDestinationGuard.causedByBlock(IllegalStateException("fixture")))
    val probe = KeyProbePlan.create(ProviderType.openAI, "https://api.groq.com/openai/v1")
    equal("https://api.groq.com/openai/v1/models", probe?.url)
    equal("Authorization", probe?.header)
    equal("https://openrouter.ai/api/v1/key", KeyProbePlan.create(ProviderType.openRouter, "https://openrouter.ai/api/v1")?.url)
    equal("x-api-key", KeyProbePlan.create(ProviderType.anthropic, "https://api.anthropic.com")?.header)
    equal("x-goog-api-key", KeyProbePlan.create(ProviderType.gemini, "https://generativelanguage.googleapis.com/v1beta")?.header)
    equal(null, KeyProbePlan.create(ProviderType.openAI, "https://api.groq.com.example/openai/v1"))
    equal(null, KeyProbePlan.create(ProviderType.openAI, "http://api.groq.com/openai/v1"))
    equal(null, KeyProbePlan.create(ProviderType.openAI, "https://api.groq.com/openai/v1?key=fixture"))
    equal(null, KeyProbePlan.create(ProviderType.gemini, "https://api.groq.com/openai/v1"))
    equal(false, KeyProbePlan.validKey("fixture\nheader"))
    equal(true, KeyProbePlan.validKey("gsk_synthetic"))
    equal(KeyProbePlan.Outcome.ACCEPTED, KeyProbePlan.classify(200))
    equal(KeyProbePlan.Outcome.REJECTED, KeyProbePlan.classify(401))
    equal(KeyProbePlan.Outcome.DENIED, KeyProbePlan.classify(403))
    equal(KeyProbePlan.Outcome.UNAVAILABLE, KeyProbePlan.classify(429))
    equal("https://api.anthropic.com", ProviderEndpoints.factoryBase(ProviderType.anthropic, null, true))
    equal("/v1", ProviderEndpoints.factoryBase(ProviderType.openAI, "", true))
    equal("https://openrouter.ai/api", ProviderEndpoints.editableDefault(ProviderType.openRouter))
    equal("https://openrouter.ai/api/v1/key", KeyProbePlan.create(ProviderType.openRouter, "https://openrouter.ai/api/v1")?.url)
    equal(true, KeyProbePlan.zeroSpendingLimit(0.0))
    equal(false, KeyProbePlan.zeroSpendingLimit(2.0))
    equal(null, KeyProbePlan.zeroSpendingLimit("0"))
    equal(null, KeyProbePlan.zeroSpendingLimit(-1))
    equal(KeyProbePlan.Outcome.ZERO_SPEND, KeyProbePlan.classify(200, zeroSpendLimit = true))
    equal(KeyProbePlan.Outcome.NO_CREDIT, KeyProbePlan.classify(402, noCredit = true))
    equal(KeyProbePlan.Outcome.DENIED, KeyProbePlan.classify(402))
    equal(false, KeyProbePlan.reportsNoCredit("insufficient_credits", "", "openrouter_in_flight_budget"))
    equal(true, KeyProbePlan.reportsNoCredit("insufficient_credits", "", null))
    println("PASSOU: presets e confirmação de destinatário em lógica JVM; sem rede")
}
