package ai.tama.net

import java.util.Locale

/** Allowlist of explicit failures. A generic 403, 404 or "unsupported parameter" is not one. */
internal object OwnKeyFailureSignals {
    enum class Hint { SPEND_LIMIT, MODEL_UNAVAILABLE, POLICY_REFUSAL }

    private val missingModel = setOf(
        "model_not_found", "model_decommissioned", "model_not_available", "model_not_supported", "unsupported_model",
    )
    private val spendLimit = setOf("key_limit_exceeded", "spend_limit_exceeded", "budget_exceeded", "quota_exceeded")
    private val modelEndpoints = Regex("""(?i)^no endpoints found for [a-z0-9_./:-]+[.]?$""")
    private val explicitSpendLimit = Regex("""(?i)^(?:key (?:spend(?:ing)? )?limit exceeded|spend(?:ing)? limit (?:reached|exceeded)|budget (?:reached|exceeded))\b""")

    private val paymentCodes = setOf("payment_required", "billing_required", "insufficient_credits",
        "insufficient_credit", "insufficient_funds", "credit_required", "credits_required")
    private val explicitPayment = Regex(
        """(?i)^(?:payment (?:is )?required|billing (?:is )?required|insufficient (?:credits?|funds)|not enough credits?|negative (?:credit )?balance|account has no credits|(?:this (?:model|endpoint) )?requires? (?:payment|(?:more )?credits?|a paid (?:plan|subscription))|(?:you (?:must|need to) )?add (?:more )?credits)\b""",
    )

    // Explicit Payment Required (HTTP 402), or structured payment evidence on
    // a 403. Never reinterpret access policy, rate or usage limits as billing.
    fun reportsPaymentRequired(status: Int, codes: List<String>, message: String): Boolean =
        status == 402 || (status == 403 && (codes.any { it.lowercase(Locale.ROOT) in paymentCodes } ||
            explicitPayment.containsMatchIn(message.trim())))

    private val policy = Regex("""(?i)\b(?:moderation|safety|guardrails?|policy|content[ _-]filter|region|prohibited)\b""")
    fun policyRefusal(codes: List<String>, message: String): Boolean =
        policy.containsMatchIn(message) || codes.any { policy.containsMatchIn(it.replace('_', ' ')) }

    fun detect(status: Int, codes: List<String>, message: String): Hint? {
        val normalized = codes.map { it.lowercase(Locale.ROOT) }
        val sentence = message.trim()
        return when {
            status in setOf(400, 404, 410, 422, 503) &&
                (normalized.any { it in missingModel } || (status == 404 && modelEndpoints.matches(sentence))) ->
                Hint.MODEL_UNAVAILABLE
            status == 403 && (normalized.any { it in spendLimit } || explicitSpendLimit.containsMatchIn(sentence)) ->
                Hint.SPEND_LIMIT
            status == 403 && policyRefusal(normalized, sentence) -> Hint.POLICY_REFUSAL
            else -> null
        }
    }
}
