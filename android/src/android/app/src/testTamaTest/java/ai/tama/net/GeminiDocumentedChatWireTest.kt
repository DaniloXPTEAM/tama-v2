package ai.tama.net

import ai.tama.pricing.DocumentedChatDispatch
import ai.tama.pricing.DocumentedFreeChat
import ai.tama.pricing.DocumentedGeminiBody
import ai.tama.pricing.ModelCostGuard
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.gemini.GeminiProvider
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.Proxy
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Real serializer, stream and preflight; transport redirected to loopback by the test only. */
class GeminiDocumentedChatWireTest {
    private val now = Instant.parse("2026-11-18T00:00:00Z").toEpochMilli()
    private val base = DocumentedFreeChat.GEMINI_BASE
    private val key = "fixture-key"
    private val id = "gemma-4-31b-it"
    private val history = listOf(LLMMessage(LLMMessage.Role.USER, "fixture question"))
    private val model = LLMModel(id, "Fixture Gemma", "Google")

    @Before fun blockGlobalConsent() { ModelCostGuard.setAllowedForCurrentProcess(false) }

    private fun client(server: MockWebServer) = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .callTimeout(5, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val call = chain.request()
            assertEquals("generativelanguage.googleapis.com", call.url.host)
            assertEquals("https", call.url.scheme)
            assertEquals(key, call.url.queryParameter("key"))
            assertTrue(call.body?.isOneShot() == true)
            val url = server.url(call.url.encodedPath).newBuilder().encodedQuery(call.url.encodedQuery).build()
            chain.proceed(call.newBuilder().url(url).build())
        }.build()

    @Test fun nativeGemmaUsesOnlyItsTicketAndStreamsToCompletion() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"fixture answer\"}]},\"finishReason\":\"STOP\"}]}\n\n",
            ))
            val provider = GeminiProvider(key, model, base, client(server)) { now }
            val tool = AgentToolDefinition("fixture_local", "fixture local function", emptyMap())
            val ticket = DocumentedChatDispatch(id, key) { true }
            val chunks = provider.streamMessage(history, "fixture instruction", 24, tools = listOf(tool))
                .flowOn(ticket).toList()
            assertEquals("fixture answer", chunks.filterIsInstance<LLMStreamChunk.Text>().single().text)
            assertEquals("end_turn", chunks.filterIsInstance<LLMStreamChunk.Finished>().single().stopReason)
            val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/v1beta/models/$id:streamGenerateContent", request.requestUrl?.encodedPath)
            val body = JSONObject(request.body.readUtf8())
            assertTrue(DocumentedGeminiBody.permits(body))
            assertEquals("fixture question", body.getJSONArray("contents").getJSONObject(0)
                .getJSONArray("parts").getJSONObject(0).getString("text"))
            assertEquals(1, server.requestCount)
            assertFalse(ModelCostGuard.permits())
        }
    }

    @Test fun missingMismatchedRevokedOrMediaTicketSubmitsNothing() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val provider = GeminiProvider(key, model, base, client(server)) { now }
            suspend fun blocked(ticket: DocumentedChatDispatch?) {
                val request = provider.streamMessage(history, null, 24)
                val result = runCatching { (ticket?.let { request.flowOn(it) } ?: request).toList() }
                assertTrue(ModelCostGuard.causedByBlock(checkNotNull(result.exceptionOrNull())))
            }
            blocked(null)
            blocked(DocumentedChatDispatch(id, "fixture-other") { true })
            var current = true
            val ticket = DocumentedChatDispatch(id, key) { current }
            provider.beforeDirectRequest = { current = false }
            blocked(ticket)
            provider.beforeDirectRequest = null
            current = true
            provider.model = model.copy(outputModalities = listOf("image"))
            blocked(ticket)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun stashedTicketCannotReplayOn503OrFollowRedirect() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val provider = GeminiProvider(key, model, base, client(server)) { now }
            val ticket = DocumentedChatDispatch(id, key) { true }
            val responses = listOf(
                MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("{}"),
                MockResponse().setResponseCode(307).setHeader("Location", server.url("/wrong").toString()),
            )
            responses.forEachIndexed { index, response ->
                server.enqueue(response)
                val failed = runCatching { provider.streamMessage(history, null, 24).flowOn(ticket).toList() }
                assertTrue(failed.isFailure)
                assertEquals(index + 1, server.requestCount)
            }
        }
    }

    @Test fun hostedExtrasFailClosed() {
        fun simple() = JSONObject("""{"contents":[{"role":"user","parts":[{"text":"fixture"}]}],
          "generationConfig":{"maxOutputTokens":24}}""")
        assertTrue(DocumentedGeminiBody.permits(simple()))
        for (field in listOf("cachedContent", "labels", "toolsConfig")) {
            assertFalse(DocumentedGeminiBody.permits(simple().put(field, "fixture")))
        }
        for (tool in listOf("googleSearch", "googleMaps", "urlContext", "codeExecution", "fileSearch")) {
            val body = simple().put("tools", org.json.JSONArray().put(JSONObject().put(tool, JSONObject())))
            assertFalse(DocumentedGeminiBody.permits(body))
        }
    }
}
