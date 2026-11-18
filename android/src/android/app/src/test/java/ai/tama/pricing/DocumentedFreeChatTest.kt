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
