package ai.tama.net

import ai.tama.pricing.DocumentedChatDispatch
import ai.tama.pricing.DocumentedFreeChat.Route
import ai.tama.pricing.DocumentedFreeChat.Status
import ai.tama.pricing.DocumentedOpenAIChatBody
import ai.tama.pricing.FreeChatCatalogue
import ai.tama.pricing.FreeChatCatalogueEvidence
import ai.tama.pricing.ModelCostGuard
import ai.tama.pricing.NvidiaChatPricing
import ai.tama.providers.KeyProbe
import ai.tama.providers.KeyProbePlan
import ai.tama.router.AutoModelFailure
import ai.tama.router.AutoModelTurn
import ai.tama.router.ModelHealth
import ai.tama.router.ModelRouter
import ai.tama.router.Need
import ai.tama.router.Profile
import ai.tama.router.AutoModelStore
import ai.tama.router.AutomaticCostGuard
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openrouter.OpenRouterModelsApi
import io.github.nanomuse.net.ProviderReach
import io.github.nanomuse.pet.PetPrefsContext
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.Proxy
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Real check, price policy, serializer and SSE loop, using synthetic data and no external API. */
class FreeChatCompletionsWireTest {
    private val key = "fixture-key"
    private val groq = "openai/gpt-oss-120b"
    private val router = "vendor/zero-preview" // deliberately no :free suffix
    private val nvidia = "nvidia/nemotron-3-nano-30b-a3b"
    private val now = Instant.parse("2026-11-18T00:00:00Z").toEpochMilli()
    private val history = listOf(LLMMessage(LLMMessage.Role.USER, "Answer OK"))
    private val settings = PetPrefsContext()

    @Before fun disableBothCosts() {
        ModelCostGuard.setAllowedForCurrentProcess(false)
        AutoModelStore.setAllowUnverifiedAutomaticCost(settings, false)
        assertFalse(ModelCostGuard.permits())
        assertFalse(AutomaticCostGuard.permits(settings))
    }

    private fun local(server: MockWebServer, base: String) = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY).callTimeout(5, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            assertEquals("Bearer $key", req.header("Authorization"))
            assertTrue(req.url.toString().startsWith("$base/"))
            if (req.method == "POST") assertTrue(req.body?.isOneShot() == true)
            chain.proceed(req.newBuilder().url(server.url(req.url.encodedPath)).build())
        }.build()

    private fun server() = MockWebServer().apply {
        dispatcher = QueueDispatcher().apply { setFailFast(true) }
        start()
    }

    private fun answer() = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
    )

    private fun provider(route: Route, id: String, client: OkHttpClient) =
        OpenAIProvider(key, LLMModel(id, id, route.name, inputModalities = listOf("text", "image"),
            outputModalities = listOf("text")), route.base, client)

    private suspend fun assertAnswer(server: MockWebServer, route: Route, id: String, accepted: () -> Boolean) {
        server.enqueue(answer())
        assertTrue(accepted())
        val ticket = DocumentedChatDispatch(id, key, route, accepted)
        val chunks = provider(route, id, local(server, route.base)).streamMessage(history, null, 32).flowOn(ticket).toList()
        assertEquals("OK", chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text })
        assertEquals("stop", chunks.filterIsInstance<LLMStreamChunk.Finished>().single().stopReason)
        val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals(java.net.URI(route.base).path + "/chat/completions", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(id, body.getString("model"))
        assertEquals("Answer OK", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        if (route == Route.OPENROUTER) {
            val prefs = body.getJSONObject("provider")
            assertFalse(prefs.getBoolean("allow_fallbacks"))
            val cap = prefs.getJSONObject("max_price")
            for (dimension in listOf("prompt", "completion", "request", "image")) assertEquals(0, cap.getInt(dimension))
            assertFalse(body.has("models"))
            assertFalse(body.getJSONArray("plugins").getJSONObject(0).getBoolean("enabled"))
        } else assertFalse(body.has("provider"))
        if (route == Route.NVIDIA) {
            assertEquals(32, body.getInt("max_tokens"))
            assertFalse(body.has("max_completion_tokens"))
        }
        assertFalse(ModelCostGuard.permits())
        assertFalse(AutomaticCostGuard.permits(settings))
    }

    @Test fun groqKeyGetFollowedByDocumentedChatResponseWithBothCostPermissionsOff() = runBlocking {
        server().use { server ->
            val route = Route.GROQ
            server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"$groq","active":true},{"id":"fixture/unlisted"}]}"""))
            val plan = checkNotNull(KeyProbePlan.create(ProviderType.openAI, route.base))
            val checked = KeyProbe.check(plan, key, local(server, route.base))
            assertEquals(KeyProbePlan.Outcome.ACCEPTED, checked.outcome)
            val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/openai/v1/models", request.path)
            assertEquals(0L, request.bodySize)
            val result = FreeChatCatalogue.Groq(now, checkNotNull(checked.availableModels))
            val proof = FreeChatCatalogueEvidence("groq-route", 1L, result)
            assertEquals(Status.UNKNOWN, result.status("fixture/unlisted", now))
            assertAnswer(server, route, groq) { (proof.current("groq-route", 1, now) as? FreeChatCatalogue.Groq)?.status(groq, now) == Status.FREE }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun openRouterZeroQuoteWithoutFreeSuffixGetsAChatResponseWithBothCostPermissionsOff() = runBlocking {
        server().use { server ->
            val route = Route.OPENROUTER
            val client = local(server, route.base)
            server.enqueue(MockResponse().setBody("""{"data":[
                {"id":"$router","pricing":{"prompt":"0","completion":"0"},
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]},"endpoints":["/api/v1/chat/completions"]},
                {"id":"vendor/paid:free","pricing":{"prompt":"0","completion":"0.01"},
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]},"endpoints":["/api/v1/chat/completions"]}
            ]}"""))
            val checked = checkNotNull(OpenRouterModelsApi.fetchFreeCatalogue(OpenRouterModelsApi.catalogueRequest(key), client))
            assertEquals("GET", checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).method)
            val result = FreeChatCatalogue.OpenRouter(checked.prices)
            val proof = FreeChatCatalogueEvidence("or-route", 1, result)
            assertAnswer(server, route, router) {
                val time = System.currentTimeMillis()
                (proof.current("or-route", 1, time) as? FreeChatCatalogue.OpenRouter)?.status(router, time) == Status.FREE
            }
            assertEquals(Status.PAID, result.status("vendor/paid:free", System.currentTimeMillis()))
            val paid = provider(route, "vendor/paid:free", client)
            assertTrue(ModelCostGuard.causedByBlock(checkNotNull(runCatching { paid.streamMessage(history, null, 32).toList() }.exceptionOrNull())))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun nvidiaExactZeroRouteGetsAChatResponseWithBothCostPermissionsOff() = runBlocking {
        server().use { server ->
            val route = Route.NVIDIA
            val zero = NvidiaChatPricing.isZero(JSONObject("""{"input":0,"output":0}"""))
            assertAnswer(server, route, nvidia) {
                NvidiaChatPricing.allows(route.base, nvidia, zero, listOf("text"), listOf("text"), now)
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun mismatchedStaleMediaAuxiliaryAndOverrideRequestsSubmitNothing() = runBlocking {
        server().use { server ->
            val route = Route.GROQ
            val provider = provider(route, groq, local(server, route.base))
            var current = true
            val ticket = DocumentedChatDispatch(groq, key, route) { current }
            suspend fun blocked(call: suspend () -> Unit) {
                assertTrue(ModelCostGuard.causedByBlock(checkNotNull(runCatching { call() }.exceptionOrNull())))
            }
            blocked { provider.streamMessage(history, null, 32).toList() }
            blocked { provider.streamMessage(history, null, 32).flowOn(DocumentedChatDispatch(groq, "another-key", route) { true }).toList() }
            blocked { provider.streamMessage(history, null, 32).flowOn(DocumentedChatDispatch(groq, key, Route.NVIDIA) { true }).toList() }
            blocked { withContext(ticket) { provider.sendMessage(history, null, 32) } }
            provider.model = provider.model.copy(id = "fixture/different-model")
            blocked { provider.streamMessage(history, null, 32).flowOn(ticket).toList() }
            provider.model = provider.model.copy(id = groq, outputModalities = listOf("text"))
            provider.chatExtraBody = mapOf("builtin_tools" to listOf("browser_search"))
            blocked { provider.streamMessage(history, null, 32).flowOn(ticket).toList() }
            provider.chatExtraBody = emptyMap()
            provider.model = provider.model.copy(outputModalities = listOf("image"))
            blocked { provider.streamMessage(history, null, 32).flowOn(ticket).toList() }
            provider.model = provider.model.copy(outputModalities = listOf("text"))
            provider.beforeDirectRequest = { current = false }
            blocked { provider.streamMessage(history, null, 32).flowOn(ticket).toList() }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun openRouterPayloadCannotRequestHostedToolsOrMediaEvenWithAZeroQuote() {
        fun body() = JSONObject("""{"model":"$router","stream":true,"messages":[{"role":"user","content":"text"}]}""")
        for (field in listOf("plugins", "models", "provider", "cache_control", "web_search_options", "modalities", "service_tier")) {
            val blocked = runCatching { DocumentedOpenAIChatBody.restrict(body().put(field, true), Route.OPENROUTER) }.exceptionOrNull()
            assertTrue(field, blocked is ModelCostGuard.Blocked)
        }
        val hosted = body().put("tools", org.json.JSONArray().put(JSONObject().put("type", "openrouter:web_search")))
        assertTrue(runCatching { DocumentedOpenAIChatBody.restrict(hosted, Route.OPENROUTER) }.exceptionOrNull() is ModelCostGuard.Blocked)
    }

    @Test fun onlyExplicitPaymentRefusalsAreNoCreditAndNoResponseReplays() = runBlocking {
        server().use { server ->
            val route = Route.GROQ
            val provider = provider(route, groq, local(server, route.base))
            val ticket = DocumentedChatDispatch(groq, key, route) { true }
            val cases = listOf(
                Triple(402, "{}", ProviderReach.Kind.NO_CREDIT),
                Triple(403, """{"error":{"code":"payment_required","message":"Add credits"}}""", ProviderReach.Kind.NO_CREDIT),
                Triple(403, """{"error":{"message":"Access forbidden"}}""", ProviderReach.Kind.ACCESS_DENIED),
                Triple(429, """{"error":{"code":"rate_limit_exceeded","message":"Rate exceeded"}}""", ProviderReach.Kind.RATE_LIMITED),
                Triple(503, "{}", ProviderReach.Kind.SERVER_ERROR),
                Triple(307, "{}", ProviderReach.Kind.PROVIDER_ERROR),
            )
            for ((index, case) in cases.withIndex()) {
                val (code, body, expected) = case
                server.enqueue(MockResponse().setResponseCode(code).setBody(body).apply {
                    if (code == 503) setHeader("Retry-After", "0")
                    if (code == 307) setHeader("Location", server.url("/different"))
                })
                val failure = runCatching { provider.streamMessage(history, null, 32).flowOn(ticket).toList() }.exceptionOrNull()
                val diagnostic = generateSequence(failure) { it.cause }.filterIsInstance<LLMError>().first()
                assertEquals(expected, diagnostic.nmProviderReach?.kind)
                assertEquals(index + 1, server.requestCount)
            }
        }
    }

    @Test fun automaticFreeChatUsesOnlyAnotherApprovedFreeRouteAfterWireFailures() = runBlocking {
        val route = Route.GROQ
        val alternative = "openai/gpt-oss-20b"
        val catalogue = FreeChatCatalogue.Groq(now, setOf(groq, alternative))
        val models = listOf(groq, "fixture/paid", alternative)
        for ((code, reason) in mapOf(429 to ModelHealth.Failure.RATE_LIMIT, 403 to ModelHealth.Failure.FORBIDDEN,
                503 to ModelHealth.Failure.SERVER, 0 to ModelHealth.Failure.NETWORK)) {
            server().use { server ->
                val client = local(server, route.base).newBuilder().readTimeout(250, TimeUnit.MILLISECONDS).build()
                val turn = AutoModelTurn(ModelHealth { 1_000 }, modelScopedRateLimits = true)
                val candidates = models.map { id -> ModelRouter.Candidate(id, "same-account", setOf(Need.CHAT),
                    Profile.LEAN, 10, 32, automaticApproved = true,
                    automaticCostAllowed = catalogue.status(id, now) == Status.FREE) }
                fun pick() = turn.pick(candidates, setOf(Need.CHAT), null)
                suspend fun send(id: String) = provider(route, id, client).streamMessage(history, null, 32)
                    .flowOn(DocumentedChatDispatch(id, key, route) { catalogue.status(id, now) == Status.FREE }).toList()
                assertEquals(groq, (pick() as ModelRouter.Choice.Selected).candidate.entryId)
                server.enqueue(if (code == 0) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    else MockResponse().setResponseCode(code).setBody("""{"error":{"message":"fixture access denied"}}"""))
                val thrown = checkNotNull(runCatching { send(groq) }.exceptionOrNull())
                val failure = generateSequence(thrown) { it.cause }.firstOrNull {
                    it is LLMError || it is java.net.SocketTimeoutException
                } ?: thrown
                val decision = AutoModelFailure.classify(failure, false, switchOnAmbiguousAccessDenied = false,
                    ownKeyTurn = true, ownKeyAutomatic = true) as AutoModelFailure.Decision.Cooldown
                assertEquals(reason, decision.reason)
                assertTrue(AutoModelFailure.canSwitchFreeChat(decision, ownKeyAutomatic = true, receivedChunk = false))
                assertFalse(AutoModelFailure.canSwitchFreeChat(decision, ownKeyAutomatic = false, receivedChunk = false))
                assertFalse(AutoModelFailure.canSwitchFreeChat(decision, ownKeyAutomatic = true, receivedChunk = true))
                turn.fail(groq, "same-account", decision.reason, decision.retryAfterSeconds, decision.retryAfterPresent)
                assertEquals(alternative, (pick() as ModelRouter.Choice.Selected).candidate.entryId)
                val none = turn.pick(candidates.dropLast(1), setOf(Need.CHAT), null) as ModelRouter.Choice.None
                assertEquals(setOf(ModelRouter.Reason.EXCLUDED, ModelRouter.Reason.UNVERIFIED_COST), none.rejected.map { it.reason }.toSet())
                server.enqueue(answer())
                assertEquals("OK", send(alternative).filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text })
                assertEquals(2, server.requestCount)
                val requests = List(2) { JSONObject(checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).body.readUtf8()) }
                assertEquals(listOf(groq, alternative), requests.map { it.getString("model") })
                assertEquals(requests[0].getJSONArray("messages").toString(), requests[1].getJSONArray("messages").toString())
            }
        }
    }

    @Test fun real403SafetyOrSseDenialCannotTriggerAutomaticFreeFallback() = runBlocking {
        val route = Route.GROQ
        server().use { server ->
            val responses = listOf(
                MockResponse().setResponseCode(403).setBody("""{"error":{"code":"content_policy_violation","message":"Access denied"}}"""),
                MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"error\":{\"code\":403,\"message\":\"Access denied\"}}\n\n"),
            )
            responses.forEach { response ->
                server.enqueue(response)
                val error = runCatching { provider(route, groq, local(server, route.base)).streamMessage(history, null, 32)
                    .flowOn(DocumentedChatDispatch(groq, key, route) { true }).toList() }.exceptionOrNull()
                val failure = generateSequence(error) { it.cause }.filterIsInstance<LLMError>().first()
                assertNull(AutoModelFailure.classify(failure, false, switchOnAmbiguousAccessDenied = false,
                    ownKeyTurn = true, ownKeyAutomatic = true))
            }
            assertEquals(2, server.requestCount)
        }
    }

}
