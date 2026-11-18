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
    equal(false, ProviderKeySuggestion.mayFill(true, false, false))
    equal(false, ProviderKeySuggestion.mayFill(false, true, false))
    equal(true, ProviderKeySuggestion.mayFill(false, false, false))

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
    equal(true, KeyDestinationGuard.causedByBlock(IllegalStateException(KeyDestinationGuard.Blocked())))
    equal(false, KeyDestinationGuard.causedByBlock(IllegalStateException("fixture")))
    equal("https://api.anthropic.com", ProviderEndpoints.factoryBase(ProviderType.anthropic, null, true))
    equal("/v1", ProviderEndpoints.factoryBase(ProviderType.openAI, "", true))
    println("PASSOU: presets e confirmação de destinatário em lógica JVM; sem rede")
}
