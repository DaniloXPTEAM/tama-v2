package ai.tama.net

import ai.tama.pricing.ModelCostGuard
import ai.tama.router.AutoModelFailure
import ai.tama.router.ModelHealth
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
