package io.github.nanomuse.router

import com.openminis.app.data.model.LLMError
import io.github.nanomuse.net.ProviderReach

/** Only known recoverable failures may cause an automatic call to a different account. */
object AutoModelFailure {
    sealed interface Decision {
        data object UnsupportedTools : Decision
        data class Cooldown(
            val reason: ModelHealth.Failure,
            val retryAfterSeconds: Int? = null,
            val retryAfterPresent: Boolean = false,
        ) : Decision
    }

    fun classify(error: Throwable, sentTools: Boolean): Decision? {
        val llm = error as? LLMError ?: return null
        if (llm is LLMError.Cancelled) return null
        // A relay refusal can be a safety/policy refusal. Only an explicit allowance
        // exhaustion permits switching, never an unclassified relay error.
        llm.nmRelayError?.let { relay ->
            return if (relay.exhausted != null) Decision.Cooldown(ModelHealth.Failure.QUOTA) else null
        }
        val reach = llm.nmProviderReach
        val message = reach?.originalMessage?.takeIf { it.isNotBlank() }
            ?: (llm as? LLMError.ProviderError)?.detail
        if (sentTools && CapabilityEvidence.explicitlyUnsupportedTools(reach?.httpStatus, message)) {
            return Decision.UnsupportedTools
        }
        val reason = when (reach?.kind) {
            ProviderReach.Kind.QUOTA -> ModelHealth.Failure.QUOTA
            ProviderReach.Kind.NO_CREDIT -> ModelHealth.Failure.NO_CREDIT
            ProviderReach.Kind.SIGNED_OUT, ProviderReach.Kind.AUTH_REJECTED,
            ProviderReach.Kind.ACCESS_DENIED -> ModelHealth.Failure.AUTH
            ProviderReach.Kind.RATE_LIMITED -> ModelHealth.Failure.RATE_LIMIT
            ProviderReach.Kind.SERVER_ERROR -> ModelHealth.Failure.SERVER
            ProviderReach.Kind.UNREACHABLE -> ModelHealth.Failure.NETWORK
            ProviderReach.Kind.REQUEST_TOO_LARGE ->
                if (reach.httpStatus == 413) ModelHealth.Failure.REQUEST_TOO_LARGE else null
            // Never forward an arbitrary bad request, safety refusal, or rejected output
            // parameter to another account as though it were a transient error.
            ProviderReach.Kind.RESPONSE_LIMIT, ProviderReach.Kind.REGION_BLOCKED -> null
            else -> when (llm) {
                is LLMError.RateLimited -> ModelHealth.Failure.RATE_LIMIT
                is LLMError.InvalidApiKey -> ModelHealth.Failure.AUTH
                is LLMError.NetworkError -> ModelHealth.Failure.NETWORK
                is LLMError.TransientError -> ModelHealth.Failure.SERVER
                else -> null
            }
        } ?: return null
        return Decision.Cooldown(reason, reach?.retryAfterS, reach?.retryAfterPresent == true)
    }
}
