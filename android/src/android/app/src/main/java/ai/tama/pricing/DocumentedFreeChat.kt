package ai.tama.pricing

import java.time.LocalDate
import java.time.ZoneOffset

/** Official endpoint + exact model IDs, not a key prefix, marketing name or quota header. */
internal object DocumentedFreeChat {
    const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
    const val GROQ_BASE = "https://api.groq.com/openai/v1"
    const val NVIDIA_BASE = "https://integrate.api.nvidia.com/v1"
    const val OPENROUTER_BASE = "https://openrouter.ai/api/v1"
    const val AGNES_BASE = "https://apihub.agnes-ai.com/v1"
    const val REVIEWED = "2026-11-18"
    private val recheckAfter = LocalDate.parse("2027-02-18").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    enum class Route(val base: String) {
        GEMINI(GEMINI_BASE), GROQ(GROQ_BASE), OPENROUTER(OPENROUTER_BASE), NVIDIA(NVIDIA_BASE),
    }

    enum class Status { FREE, PAID, FREE_PLAN_UNCONFIRMED, UNKNOWN, PAYMENT_REQUIRED }
    internal data class Offer(val source: String, val models: Set<String>, val noPaidTier: Boolean = false)

    val offers = mapOf(
        GEMINI_BASE to listOf(
            Offer("https://ai.google.dev/gemini-api/docs/pricing", setOf("gemma-4-31b-it", "gemma-4-26b-a4b-it"), noPaidTier = true),
            Offer("https://ai.google.dev/gemini-api/docs/pricing", setOf(
                "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-3.6-flash",
            )),
        ),
        // The published free/default tier does not verify a particular key or
        // guarantee that a promotional price holds at request time.
        AGNES_BASE to listOf(Offer("https://wiki.agnes-ai.com/en/docs/tokenplan", setOf("agnes-2.5-flash"))),
        GROQ_BASE to listOf(Offer("https://console.groq.com/docs/rate-limits", setOf(
            // Free Plan: 30 RPM, 1,000 RPD, 8,000 TPM, 200,000 TPD. This is the
            // chat subset, not the ASR, TTS, safety or hosted-tool models.
            "openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b",
        ))),
    )

    // A credit trial or an unrelated free web product is not an API free plan.
    fun route(base: String?, openAiChat: Boolean, gemini: Boolean, openRouter: Boolean): Route? = when (base) {
        OPENROUTER_BASE -> Route.OPENROUTER.takeIf { openRouter }
        GEMINI_BASE -> Route.GEMINI.takeIf { gemini }
        GROQ_BASE -> Route.GROQ.takeIf { openAiChat }
        NVIDIA_BASE -> Route.NVIDIA.takeIf { openAiChat }
        else -> null
    }

    fun hasCatalogue(base: String?, openAiChat: Boolean, gemini: Boolean, openRouter: Boolean): Boolean =
        route(base, openAiChat, gemini, openRouter) != null || (base == AGNES_BASE && openAiChat)

    fun catalogueReviewCurrent(now: Long): Boolean = now in 0 until recheckAfter

    fun groqOffers(id: String): Boolean = offers[GROQ_BASE].orEmpty().any { id in it.models }

    fun status(
        base: String?, id: String, now: Long, listedPricePositive: Boolean,
        availableGroqModels: Set<String>? = null,
    ): Status {
        val offer = offers[base]?.firstOrNull { id in it.models }
        return when {
            // For an exact offer guaranteed to have no paid tier, the published
            // plan contract is stronger than old bundled list prices.
            offer?.noPaidTier == true && catalogueReviewCurrent(now) -> Status.FREE
            // The developer list price is NOT the selected account's price.
            // The app combines the offer with the exact ID from the key probe;
            // neither that GET nor the quota table reveals the account's plan.
            base == GROQ_BASE && offer != null && catalogueReviewCurrent(now) &&
                availableGroqModels?.contains(id) == true -> Status.FREE
            offer != null -> Status.FREE_PLAN_UNCONFIRMED
            listedPricePositive -> Status.PAID
            else -> Status.UNKNOWN
        }
    }
}
