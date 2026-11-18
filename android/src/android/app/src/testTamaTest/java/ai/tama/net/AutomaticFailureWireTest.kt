package ai.tama.net

import ai.tama.pricing.ModelCostGuard
import ai.tama.router.AutoModelFailure
import ai.tama.router.AutoModelTurn
import ai.tama.router.ModelHealth
import ai.tama.router.ModelRouter
import ai.tama.router.Need
import ai.tama.router.Profile
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import io.github.nanomuse.net.ProviderReach
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real provider transport on loopback, synthetic data only. Not a ViewModel or billing test. */
class AutomaticFailureWireTest {
    @Before fun allowFixture() { ModelCostGuard.setAllowedForCurrentProcess(true) }
    @After fun reset() { ModelCostGuard.setAllowedForCurrentProcess(false) }

    private suspend fun failure(server: MockWebServer): LLMError {
        val provider = OpenAIProvider("fixture-key", LLMModel.gpt4oMini, server.url("/v1").toString().trimEnd('/'))
        val thrown = try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "fixture")), null, 32)
            throw AssertionError("Expected a provider failure")
        } catch (error: Exception) { error }
        var cause: Throwable? = thrown
        while (cause != null && cause !is LLMError) cause = cause.cause
        return cause as? LLMError ?: throw AssertionError("Missing LLMError", thrown)
    }

    @Test fun httpFailuresKeepStatusAndDriveConservativeRouting() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val cases = listOf(429 to "busy", 403 to "access denied", 500 to "server failed")
            for ((status, message) in cases) {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "7")
                    .setBody("{\"error\":{\"message\":\"$message\"}}"))
                val error = failure(server)
                assertEquals(status, error.nmProviderReach?.httpStatus)
                val decision = AutoModelFailure.classify(error, false, switchOnAmbiguousAccessDenied = false)
                if (status == 403) {
                    assertNull(decision)
                    assertEquals(ProviderReach.Kind.ACCESS_DENIED, error.nmProviderReach?.kind)
                } else {
                    val cooldown = decision as AutoModelFailure.Decision.Cooldown
                    assertEquals(if (status == 429) ModelHealth.Failure.RATE_LIMIT else ModelHealth.Failure.SERVER, cooldown.reason)
                    if (status == 429) assertEquals(7, cooldown.retryAfterSeconds)
                }
            }
            assertEquals(cases.size, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun rateLimitFallbackUsesRealWireAndPreservesTheHistory() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7")
                .setBody("{\"error\":{\"message\":\"Rate limit reached\"}}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"fixture answer\"}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n",
            ))
            val health = ModelHealth { 1_000L }
            val turn = AutoModelTurn(health, modelScopedRateLimits = true)
            fun candidate(id: String, approved: Boolean, order: Int) = ModelRouter.Candidate(
                entryId = id, providerId = "fixture-account", capabilities = setOf(Need.CHAT),
                profile = Profile.LEAN, inputTokens = 100, outputTokens = 32,
                automaticApproved = approved, automaticCostAllowed = true, preference = order,
            )
            val first = candidate("first", true, 0)
            val unapproved = candidate("unapproved", false, 1)
            val second = candidate("second", true, 2)
            val candidates = listOf(first, unapproved, second)
            fun pick(sticky: String?) = (turn.pick(candidates, setOf(Need.CHAT), sticky) as ModelRouter.Choice.Selected).candidate
            val history = listOf(LLMMessage(LLMMessage.Role.USER, "prior question"),
                LLMMessage(LLMMessage.Role.ASSISTANT, "prior answer"), LLMMessage(LLMMessage.Role.USER, "continue"))
            val base = server.url("/v1").toString().trimEnd('/')
            fun provider(id: String) = OpenAIProvider("fixture-key", LLMModel.gpt4oMini.copy(id = id), base)
            val chosen = pick("first")
            assertEquals("first", chosen.entryId)
            val firstTicket = (turn.reserve(chosen, 100, 32) as ModelHealth.Reservation.Granted).ticket
            val thrown = try {
                provider(chosen.entryId).sendMessage(history, null, 32)
                throw AssertionError("Expected a rate limit")
            } catch (error: Exception) { error }
            health.finish(firstTicket)
            var cause: Throwable? = thrown
            while (cause != null && cause !is LLMError) cause = cause.cause
            val decision = AutoModelFailure.classify(checkNotNull(cause), false, switchOnAmbiguousAccessDenied = false)
                as AutoModelFailure.Decision.Cooldown
            assertEquals(ModelHealth.Failure.RATE_LIMIT, decision.reason)
            turn.fail(chosen.entryId, chosen.providerId, decision.reason, decision.retryAfterSeconds, decision.retryAfterPresent)
            val fallback = pick(null)
            assertEquals("second", fallback.entryId)
            assertEquals(8_000L, health.snapshot("first", "fixture-account").cooldown?.untilMillis)
            val secondTicket = (turn.reserve(fallback, 100, 32) as ModelHealth.Reservation.Granted).ticket
            val response = try { provider(fallback.entryId).sendMessage(history, null, 32) }
                finally { health.finish(secondTicket) }
            assertEquals("fixture answer", response.text)
            assertEquals(2, server.requestCount)
            val firstBody = org.json.JSONObject(server.takeRequest().body.readUtf8())
            val secondBody = org.json.JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("first", firstBody.getString("model"))
            assertEquals("second", secondBody.getString("model"))
            assertEquals(firstBody.getJSONArray("messages").toString(), secondBody.getJSONArray("messages").toString())
            assertEquals("continue", secondBody.getJSONArray("messages").getJSONObject(2).getString("content"))
            assertEquals(3, history.size) // transport did not mutate the caller's history
        } finally { server.shutdown() }
    }

    @Test fun inlineErrorDoesNotPretendToBeAnHttpStatus() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"error\":{\"code\":403,\"message\":\"access denied\"}}\n\n"))
            val error = failure(server)
            assertNull(error.nmProviderReach?.httpStatus)
            assertEquals(ProviderReach.Kind.ACCESS_DENIED, error.nmProviderReach?.kind)
            assertNull(AutoModelFailure.classify(error, false, switchOnAmbiguousAccessDenied = false))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
