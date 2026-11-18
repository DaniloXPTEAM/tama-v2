package ai.tama.providers

import com.openminis.app.data.model.ProviderType
import java.net.URI
import java.util.Locale

/** A read-only GET, only for known official API destinations. No credential is stored here. */
internal object KeyProbePlan {
    data class Plan(
        val url: String, val header: String, val anthropicVersion: Boolean = false, val openRouterKeyInfo: Boolean = false,
    )

    fun create(type: ProviderType, base: String?): Plan? {
        val uri = try { URI(base?.trim().orEmpty()) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", true) || uri.userInfo != null || uri.query != null || uri.fragment != null ||
            (uri.port != -1 && uri.port != 443)) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val path = uri.path.orEmpty().trimEnd('/')
        return when {
            type == ProviderType.openRouter && host == "openrouter.ai" && path == "/api/v1" ->
                Plan("https://openrouter.ai/api/v1/key", "Authorization", openRouterKeyInfo = true)
            type in setOf(ProviderType.openAI, ProviderType.openAIResponses) && host == "api.openai.com" && path == "/v1" ->
                Plan("https://api.openai.com/v1/models", "Authorization")
            type == ProviderType.openAI && host == "api.groq.com" && path == "/openai/v1" ->
                Plan("https://api.groq.com/openai/v1/models", "Authorization")
            type == ProviderType.xAI && host == "api.x.ai" && path == "/v1" ->
                Plan("https://api.x.ai/v1/models", "Authorization")
            type == ProviderType.anthropic && host == "api.anthropic.com" && path in setOf("", "/v1") ->
                Plan("https://api.anthropic.com/v1/models", "x-api-key", anthropicVersion = true)
            type == ProviderType.gemini && host == "generativelanguage.googleapis.com" && path == "/v1beta" ->
                Plan("https://generativelanguage.googleapis.com/v1beta/models", "x-goog-api-key")
            else -> null
        }
    }

    fun validKey(key: String): Boolean = key.length in 1..4096 && key.all { it.code in 33..126 }

    enum class Outcome { ACCEPTED, REJECTED, ZERO_SPEND, NO_CREDIT, DENIED, UNAVAILABLE }
    data class Result(val outcome: Outcome, val zeroSpendLimit: Boolean? = null)

    fun zeroSpendingLimit(value: Any?): Boolean? = when (value) {
        is Number -> value.toString().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.let { it.signum() == 0 }
        else -> null
    }

    fun classify(status: Int, zeroSpendLimit: Boolean? = null, noCredit: Boolean = false): Outcome = when {
        status == 401 -> Outcome.REJECTED
        status in 200..299 && zeroSpendLimit == true -> Outcome.ZERO_SPEND
        status in 200..299 -> Outcome.ACCEPTED
        status in setOf(402, 403) && noCredit -> Outcome.NO_CREDIT
        status in setOf(402, 403) -> Outcome.DENIED
        else -> Outcome.UNAVAILABLE
    }

    fun reportsNoCredit(code: String?, message: String?, limitSource: String?): Boolean {
        // Another budget (e.g. in-flight or per-key) is not proof of missing account credit.
        if (!limitSource.isNullOrBlank()) return limitSource == "openrouter_credits"
        return code?.lowercase(Locale.ROOT) in setOf("insufficient_credits", "insufficient_credit", "insufficient_funds") ||
            Regex("(?i)^(?:insufficient credits?|not enough credits?|negative (?:credit )?balance|account has no credits)")
                .containsMatchIn(message?.trim().orEmpty())
    }
}
