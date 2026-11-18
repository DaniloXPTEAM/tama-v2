package ai.tama.net

import ai.tama.providers.KeyProbe
import ai.tama.providers.KeyProbePlan
import ai.tama.providers.KeyProbePlan.Outcome
import com.openminis.app.data.model.ProviderType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyProbeWireTest {
    @Test fun readOnlyProbeUsesTheResponseForThisKeyAndNeverFollowsRedirects() = runBlocking {
        MockWebServer().use { server ->
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
            )
            for ((status, body, outcome) in fixtures) {
                server.enqueue(MockResponse().setResponseCode(status).setBody(body).apply {
                    if (status == 302) setHeader("Location", server.url("/unexpected"))
                })
                assertEquals(outcome, KeyProbe.check(plan, "fixture-key").outcome)
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/api/v1/key", request.path)
                assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
                assertEquals(0L, request.bodySize)
            }
            assertEquals(fixtures.size, server.requestCount)
        }
    }
}
