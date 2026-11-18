package ai.tama.pricing

import java.time.LocalDate
import java.time.ZoneOffset

/** Official endpoint + exact model IDs, not a key prefix, marketing name or quota header. */
internal object DocumentedFreeChat {
    const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
    const val REVIEWED = "2026-11-18"
    private val recheckAfter = LocalDate.parse("2027-02-18").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    enum class Status { FREE, PAID, FREE_PLAN_UNCONFIRMED, UNKNOWN, PAYMENT_REQUIRED }
    internal data class Offer(val source: String, val models: Set<String>, val noPaidTier: Boolean = false)

    val offers = mapOf(
        GEMINI_BASE to listOf(
            Offer("https://ai.google.dev/gemini-api/docs/pricing", setOf("gemma-4-31b-it", "gemma-4-26b-a4b-it"), noPaidTier = true),
            Offer("https://ai.google.dev/gemini-api/docs/pricing", setOf(
                "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-3.6-flash",
            )),
        ),
        "https://api.groq.com/openai/v1" to listOf(Offer("https://console.groq.com/docs/rate-limits", setOf(
            "openai/gpt-oss-120b", "openai/gpt-oss-20b",
        ))),
    )

    val offeredBases = offers.keys + setOf(
        "https://integrate.api.nvidia.com/v1", "https://api.mistral.ai/v1", "https://api.cerebras.ai/v1",
    )

    fun status(base: String?, id: String, now: Long, listedPricePositive: Boolean): Status {
        val offer = offers[base]?.firstOrNull { id in it.models }
        return when {
            // For an exact offer guaranteed to have no paid tier, the published
            // plan contract is stronger than old bundled list prices.
            offer?.noPaidTier == true && now in 0 until recheckAfter -> Status.FREE
            offer != null -> Status.FREE_PLAN_UNCONFIRMED
            listedPricePositive -> Status.PAID
            else -> Status.UNKNOWN
        }
    }
}
