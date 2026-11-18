package ai.tama.net

import java.util.Locale

/** Allowlist of explicit failures. A generic 403, 404 or "unsupported parameter" is not one. */
internal object OwnKeyFailureSignals {
    enum class Hint { SPEND_LIMIT, MODEL_UNAVAILABLE }

    private val missingModel = setOf(
        "model_not_found", "model_decommissioned", "model_not_available", "model_not_supported", "unsupported_model",
    )
    private val spendLimit = setOf("key_limit_exceeded", "spend_limit_exceeded", "budget_exceeded", "quota_exceeded")
    private val modelEndpoints = Regex("""(?i)^no endpoints found for [a-z0-9_./:-]+[.]?$""")
    private val explicitSpendLimit = Regex("""(?i)^(?:key (?:spend(?:ing)? )?limit exceeded|spend(?:ing)? limit (?:reached|exceeded)|budget (?:reached|exceeded))\b""")

    fun detect(status: Int, codes: List<String>, message: String): Hint? {
        val normalized = codes.map { it.lowercase(Locale.ROOT) }
        val sentence = message.trim()
        return when {
            status in setOf(400, 404, 410, 422, 503) &&
                (normalized.any { it in missingModel } || (status == 404 && modelEndpoints.matches(sentence))) ->
                Hint.MODEL_UNAVAILABLE
            status == 403 && (normalized.any { it in spendLimit } || explicitSpendLimit.containsMatchIn(sentence)) ->
                Hint.SPEND_LIMIT
            else -> null
        }
    }
}
