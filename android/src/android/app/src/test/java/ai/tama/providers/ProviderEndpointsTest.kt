package ai.tama.providers

import com.openminis.app.data.model.ProviderType
import org.junit.Assert.*
import org.junit.Test

class ProviderEndpointsTest {
    @Test fun `known API key defaults match the actual base for requests`() {
        val expected = mapOf(
            ProviderType.openAI to "https://api.openai.com/v1",
            ProviderType.openAIResponses to "https://api.openai.com/v1",
            ProviderType.anthropic to "https://api.anthropic.com/v1",
            ProviderType.gemini to "https://generativelanguage.googleapis.com/v1beta",
            ProviderType.openRouter to "https://openrouter.ai/api/v1",
            ProviderType.xAI to "https://api.x.ai/v1",
            ProviderType.kimiCode to "https://api.kimi.com/coding/v1",
        )
        for ((type, full) in expected) {
            val append = type != ProviderType.gemini
            val input = ProviderEndpoints.editableDefault(type)
            assertEquals(type.name, full, ProviderEndpoints.effective(type, input, append))
            // Official Anthropic must stay on x-api-key auth, not custom Bearer auth.
            assertNull(type.name, ProviderEndpoints.storageBase(type, input, append))
            assertEquals(type.name, full, ProviderEndpoints.effective(type, null, append))
        }
    }

    @Test fun `custom endpoint remains custom and version suffix is applied once`() {
        val type = ProviderType.openAI
        assertEquals("https://service.example/v1", ProviderEndpoints.effective(type, " https://service.example/v1/ ", true))
        assertEquals("https://service.example", ProviderEndpoints.storageBase(type, " https://service.example ", true))
        assertEquals("https://service.example/custom", ProviderEndpoints.effective(type, "https://service.example/custom", false))
        assertEquals("https://service.example/anthropic/v1", ProviderEndpoints.effective(ProviderType.anthropic, "https://service.example/anthropic/v1/", false))
        assertEquals("https://service.example/anthropic/v1", ProviderEndpoints.storageBase(ProviderType.anthropic, "https://service.example/anthropic/v1", false))
        assertEquals("https://openrouter.ai/api/v1", ProviderEndpoints.effective(ProviderType.openRouter, "https://service.example", false))
        assertNull(ProviderEndpoints.storageBase(ProviderType.openRouter, "https://service.example", false))
    }

    @Test fun `custom HTTPS scheme is case insensitive without accepting URLs with credentials`() {
        val base = "HTTPS://APIHUB.AGNES-AI.COM/v1"
        assertTrue(ProviderDirectory.validCustomAddress(base))
        assertTrue(ProviderEndpoints.isAgnesBase(base))
        for (bad in listOf("http://apihub.agnes-ai.com/v1", "$base?k=synthetic", "$base#fragment", "$base/gateway",
            "https://apihub.agnes-ai.com.example/v1", "https://apihub.agnes-ai.com:567/v1", "https://user@apihub.agnes-ai.com/v1")) {
            assertFalse(bad, ProviderEndpoints.isAgnesBase(bad))
        }
        assertFalse(ProviderDirectory.validCustomAddress("HTTPS://user@apihub.agnes-ai.com/v1"))
    }

    @Test fun `official OpenAI fallback requires the exact HTTPS base`() {
        assertTrue(ProviderEndpoints.isOfficialOpenAIBase("https://api.openai.com/v1/"))
        assertTrue(ProviderEndpoints.isOfficialOpenAIBase("https://CHATGPT.COM"))
        for (base in listOf(
            "https://api.openai.com.example/v1", "https://api.openai.com@example.org/v1",
            "http://api.openai.com/v1", "https://api.openai.com:444/v1",
            "https://api.openai.com/custom/v1", "https://api.openai.com/v1?route=other", "not a url",
        )) assertFalse(base, ProviderEndpoints.isOfficialOpenAIBase(base))
    }

    @Test fun `mismatched key needs exact process confirmation without changing the normal app`() {
        val key = "gsk_test_only"
        val otherKey = "gsk_test_other"
        val groq = "https://api.groq.com/openai/v1"
        val openai = ProviderEndpoints.factoryBase(ProviderType.openAI, null, true)
        assertNull(KeyDestinationGuard.review(key, groq))
        assertFalse(KeyDestinationGuard.permits(key, openai))
        assertFalse(KeyDestinationGuard.confirm(key, "http://example.invalid/v1"))
        try {
            KeyDestinationGuard.requireAllowed(true, key, openai)
            fail("expected a local refusal")
        } catch (_: KeyDestinationGuard.Blocked) { }
        KeyDestinationGuard.requireAllowed(false, key, openai)
        assertTrue(KeyDestinationGuard.confirm(key, openai))
        assertTrue(KeyDestinationGuard.permits(key, openai))
        assertFalse(KeyDestinationGuard.permits(otherKey, openai))
        assertFalse(KeyDestinationGuard.permits(key, "https://example.invalid/v1"))
        assertFalse(KeyDestinationGuard.permits("sk-or-test-only", groq))
        assertTrue(KeyDestinationGuard.causedByBlock(IllegalStateException(KeyDestinationGuard.Blocked())))
    }

    @Test fun `key probe targets only official model list endpoints and does not mislabel 403`() {
        val groq = KeyProbePlan.create(ProviderType.openAI, "https://api.groq.com/openai/v1")
        assertEquals("https://api.groq.com/openai/v1/models", groq?.url)
        assertEquals("Authorization", groq?.header)
        assertEquals("x-api-key", KeyProbePlan.create(ProviderType.anthropic, "https://api.anthropic.com")?.header)
        assertEquals("x-goog-api-key", KeyProbePlan.create(ProviderType.gemini, "https://generativelanguage.googleapis.com/v1beta")?.header)
        assertNull(KeyProbePlan.create(ProviderType.openAI, "http://api.groq.com/openai/v1"))
        assertNull(KeyProbePlan.create(ProviderType.openAI, "https://api.groq.com.example/openai/v1"))
        assertNull(KeyProbePlan.create(ProviderType.openAI, "https://api.groq.com/openai/v1?key=fixture"))
        assertNull(KeyProbePlan.create(ProviderType.gemini, "https://api.groq.com/openai/v1"))
        assertFalse(KeyProbePlan.validKey("fixture\nheader"))
        assertEquals(KeyProbePlan.Outcome.ACCEPTED, KeyProbePlan.classify(200))
        assertEquals(KeyProbePlan.Outcome.REJECTED, KeyProbePlan.classify(401))
        assertEquals(KeyProbePlan.Outcome.DENIED, KeyProbePlan.classify(403))
        assertEquals(KeyProbePlan.Outcome.UNAVAILABLE, KeyProbePlan.classify(429))
    }

    @Test fun `no own key does not activate the key destination review`() {
        // This tests only the key guard, not Cloud initialization or a real conversation.
        for (base in listOf(null, "", "https://cloud.example.invalid/v1")) {
            assertNull(KeyDestinationGuard.review(null, base))
            assertTrue(KeyDestinationGuard.permits(null, base))
            KeyDestinationGuard.requireAllowed(true, null, base)
        }
    }

    @Test fun `only unique informative hints match and manual endpoints are never overwritten`() {
        val hints = listOf(
            "router" to "sk-or-v1-…", "groq" to "gsk_…", "one" to "sk-…",
            "two" to "sk-…", "none" to "xxxxxxxx.xxxxxxxx", "jwt" to "eyJ…",
        )
        assertEquals("router", ProviderKeySuggestion.match(" sk-or-v1-synthetic ", hints))
        assertEquals("groq", ProviderKeySuggestion.match("gsk_synthetic", hints))
        assertNull(ProviderKeySuggestion.match("sk-synthetic", hints))
        assertNull(ProviderKeySuggestion.match("eyJsynthetic", hints))
        assertNull(ProviderKeySuggestion.match("anything", hints))
        assertNull(ProviderKeySuggestion.match("gsk_synthetic", hints + ("other" to "gsk_…")))
        assertTrue(ProviderKeySuggestion.mayOfferPreset(endpointEdited = false, hasSelectedVendor = false))
        assertFalse(ProviderKeySuggestion.mayOfferPreset(endpointEdited = true, hasSelectedVendor = false))
        assertFalse(ProviderKeySuggestion.mayOfferPreset(endpointEdited = false, hasSelectedVendor = true))
    }
}
