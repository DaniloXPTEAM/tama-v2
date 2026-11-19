package ai.tama.pricing

import com.openminis.app.BuildConfig
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant
import kotlin.coroutines.EmptyCoroutineContext

class DocumentedFreeChatTest {
    private val date = Instant.parse("2026-11-18T00:00:00Z").toEpochMilli()
    private val base = DocumentedFreeChat.GEMINI_BASE
    private val id = "gemma-4-31b-it"

    @Test fun `exact free-only offer does not need a list price`() {
        assertEquals(DocumentedFreeChat.Status.FREE, DocumentedFreeChat.status(base, id, date, false))
        assertEquals(com.openminis.app.R.string.nm_free_status_limited, freeStatusLabel(DocumentedFreeChat.Status.FREE))
        assertEquals(DocumentedFreeChat.Status.UNKNOWN, DocumentedFreeChat.status("https://example.com/v1beta", id, date, false))
        assertEquals(DocumentedFreeChat.Status.FREE_PLAN_UNCONFIRMED,
            DocumentedFreeChat.status(base, id, Instant.parse("2027-02-18T00:00:00Z").toEpochMilli(), false))
    }

    @Test fun `paid unknown and plan-not-confirmed routes are not approved`() {
        val image = DocumentedFreeChat.status(base, "gemini-2.5-flash-image", date, true)
        assertEquals(DocumentedFreeChat.Status.PAID, image)
        assertEquals(com.openminis.app.R.string.nm_free_paid_action, freeStatusAction(image))
        val groq = DocumentedFreeChat.status("https://api.groq.com/openai/v1", "openai/gpt-oss-120b", date, true)
        assertEquals(DocumentedFreeChat.Status.FREE_PLAN_UNCONFIRMED, groq)
        assertEquals(com.openminis.app.R.string.nm_free_plan_action, freeStatusAction(groq))
        val nvidia = DocumentedFreeChat.status("https://integrate.api.nvidia.com/v1", "qwen/qwen3-coder-480b-a35b-instruct", date, false)
        assertEquals(DocumentedFreeChat.Status.UNKNOWN, nvidia)
        assertEquals(com.openminis.app.R.string.nm_free_unknown_action, freeStatusAction(nvidia))
    }

    @Test fun `Groq combines documented offers with an accepted exact list and explicit payment only`() {
        val groq = DocumentedFreeChat.GROQ_BASE
        val offered = "openai/gpt-oss-120b"
        val undisclosed = "fixture/not-on-the-free-plan"
        val verified = FreeChatCatalogue.Groq(date, setOf(offered, undisclosed))
        assertEquals(DocumentedFreeChat.Status.FREE, verified.status(offered, date))
        assertEquals(DocumentedFreeChat.Status.FREE, DocumentedFreeChat.status(groq, offered, date, true, verified.models))
        assertEquals(DocumentedFreeChat.Status.PAID, DocumentedFreeChat.status(groq, undisclosed, date, true, verified.models))
        assertEquals(DocumentedFreeChat.Status.UNKNOWN, DocumentedFreeChat.status(groq, undisclosed, date, false, verified.models))
        assertEquals(DocumentedFreeChat.Status.FREE_PLAN_UNCONFIRMED, verified.status("openai/gpt-oss-20b", date))
        assertEquals(DocumentedFreeChat.Status.FREE_PLAN_UNCONFIRMED, verified.status(offered, date + OpenRouterPricing.MAX_AGE_MILLIS + 1))
        assertEquals(DocumentedFreeChat.Status.PAYMENT_REQUIRED,
            FreeChatCatalogue.Groq(date, emptySet(), paymentRequired = true).status(offered, date))
    }

    @Test fun `Groq and NVIDIA use the same visibility and fail-closed dispatch rules`() {
        val groq = DocumentedFreeChat.Route.GROQ
        val nvidia = DocumentedFreeChat.Route.NVIDIA
        for (route in listOf(groq, nvidia)) {
            assertEquals(route, DocumentedFreeChat.route(route.base, openAiChat = true, gemini = false, openRouter = false))
            assertNull(DocumentedFreeChat.route(route.base, openAiChat = false, gemini = false, openRouter = false))
        }
        assertNull(DocumentedFreeChat.route("https://api.openai.com/v1", openAiChat = true, gemini = false, openRouter = false))
        val entries = mapOf("free" to DocumentedFreeChat.Status.FREE, "paid" to DocumentedFreeChat.Status.PAID,
            "unknown" to DocumentedFreeChat.Status.UNKNOWN, "plan" to DocumentedFreeChat.Status.FREE_PLAN_UNCONFIRMED,
            "payment" to DocumentedFreeChat.Status.PAYMENT_REQUIRED)
        val visible = FreeModelList.filterForDisplay(entries.keys.toList(), true, entries::getValue)
        assertEquals(listOf("free", "unknown", "plan"), visible)
        for ((id, status) in entries) {
            assertEquals(id == "free", FreeModelList.canDispatch(status, restricted = true, supportedRoute = true))
        }
        // A stale chat binding or typed model ID can still address a hidden row.
        val paid = entries.getValue("paid")
        assertFalse("paid" in visible)
        assertFalse(FreeModelList.canDispatch(paid, restricted = true, supportedRoute = true))
        assertEquals(com.openminis.app.R.string.nm_free_paid_action, freeStatusAction(paid))
    }

    @Test fun `ticket applies only to matching Gemini text route and current account`() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        ModelCostGuard.setAllowedForCurrentProcess(false)
        var current = true
        val ticket = DocumentedChatDispatch(id, "fixture-key") { current }
        DocumentedChatDispatch.checkGemini(ticket, base, "fixture-key", id, true, date)
        assertFalse(ModelCostGuard.permits())
        fun blocked(call: () -> Unit) = assertTrue(runCatching(call).exceptionOrNull() is ModelCostGuard.Blocked)
        blocked { DocumentedChatDispatch.checkGemini(EmptyCoroutineContext, base, "fixture-key", id, true, date) }
        blocked { DocumentedChatDispatch.checkGemini(ticket, base, "fixture-other", id, true, date) }
        blocked { DocumentedChatDispatch.checkGemini(ticket, base, "fixture-key", "gemini-2.5-flash-image", true, date) }
        blocked { DocumentedChatDispatch.checkGemini(ticket, base, "fixture-key", id, false, date) }
        blocked { DocumentedChatDispatch.checkGemini(ticket, "https://example.com/v1beta", "fixture-key", id, true, date) }
        current = false
        blocked { DocumentedChatDispatch.checkGemini(ticket, base, "fixture-key", id, true, date) }
    }
}
