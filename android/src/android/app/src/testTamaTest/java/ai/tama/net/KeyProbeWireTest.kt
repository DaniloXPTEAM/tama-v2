package ai.tama.net

import ai.tama.providers.KeyProbe
import ai.tama.providers.KeyProbePlan
import ai.tama.providers.KeyProbePlan.Outcome
import com.openminis.app.data.model.ProviderType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class KeyProbeWireTest {
    @Test fun readOnlyProbeUsesTheResponseForThisKeyWithoutRedirectOrReplay() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = QueueDispatcher().apply { setFailFast(true) }
            server.start()
            val official = checkNotNull(KeyProbePlan.create(ProviderType.openRouter, "https://openrouter.ai/api/v1"))
            val plan = official.copy(url = server.url("/api/v1/key").toString())
            val fixtures = listOf(
                Triple(200, """{"data":{"limit":0}}""", Outcome.ZERO_SPEND),
                Triple(200, """{"data":{"limit":null,"limit_remaining":null}}""", Outcome.ACCEPTED),
                Triple(200, """{"data":{"limit":5,"limit_remaining":0}}""", Outcome.ACCEPTED),
                Triple(200, """{"data":{}}""", Outcome.UNAVAILABLE),
                Triple(401, "{}", Outcome.REJECTED),
                Triple(402, """{"error":{"metadata":{"limit_source":"openrouter_credits"}}}""", Outcome.NO_CREDIT),
                Triple(402, """{"error":{"metadata":{"limit_source":"openrouter_key_limit"}}}""", Outcome.DENIED),
                Triple(403, "{}", Outcome.DENIED),
                Triple(302, "", Outcome.UNAVAILABLE),
                Triple(503, "", Outcome.UNAVAILABLE),
            )
            for ((index, fixture) in fixtures.withIndex()) {
                val (status, body, outcome) = fixture
                server.enqueue(MockResponse().setResponseCode(status).setBody(body).apply {
                    if (status == 302) setHeader("Location", server.url("/unexpected"))
                    if (status == 503) setHeader("Retry-After", "0")
                })
                assertEquals(outcome, KeyProbe.check(plan, "fixture-key").outcome)
                assertEquals("HTTP $status must use one request", index + 1, server.requestCount)
                val request = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) { "Missing probe request" }
                assertEquals("GET", request.method)
                assertEquals("/api/v1/key", request.path)
                assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
                assertEquals(0L, request.bodySize)
            }
            assertEquals(fixtures.size, server.requestCount)
        }
    }
    @Test fun groqRequiresNormalModelsPayloadAndDoesNotTreatQuotasAsPayment() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val official = checkNotNull(KeyProbePlan.create(ProviderType.openAI, "https://api.groq.com/openai/v1"))
            val plan = official.copy(url = server.url("/openai/v1/models").toString())
            val rows = listOf(
                Triple(200, """{"data":[{"id":"openai/gpt-oss-120b"}]}""", Outcome.ACCEPTED),
                Triple(200, """{"data":[{"name":"not a models row"}]}""", Outcome.UNAVAILABLE),
                Triple(200, "<html>not a models list</html>", Outcome.UNAVAILABLE),
                Triple(402, "{}", Outcome.NO_CREDIT),
                Triple(403, """{"error":{"message":"This model requires a paid plan"}}""", Outcome.NO_CREDIT),
                Triple(403, """{"error":{"message":"No permission for this key"}}""", Outcome.DENIED),
                Triple(429, """{"error":{"code":"insufficient_credits"}}""", Outcome.UNAVAILABLE),
            )
            for ((status, json, expectation) in rows) {
                server.enqueue(MockResponse().setResponseCode(status).setBody(json))
                val checked = KeyProbe.check(plan, "fixture-key")
                assertEquals(expectation, checked.outcome)
                assertEquals(if (expectation == Outcome.ACCEPTED) setOf("openai/gpt-oss-120b") else null, checked.availableModels)
            }
            assertEquals(rows.size, server.requestCount)
        }
    }
}
