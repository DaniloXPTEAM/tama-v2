package ai.tama.router

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

    /** No replay, no paid route, and no switch after a partial scoped reply. */
    internal fun canSwitchFreeChat(decision: Decision?, ownKeyAutomatic: Boolean, receivedChunk: Boolean): Boolean =
        ownKeyAutomatic && !receivedChunk && decision is Decision.Cooldown && decision.reason in setOf(
            ModelHealth.Failure.RATE_LIMIT, ModelHealth.Failure.QUOTA, ModelHealth.Failure.FORBIDDEN,
            ModelHealth.Failure.SERVER, ModelHealth.Failure.NETWORK, ModelHealth.Failure.NO_CREDIT,
            ModelHealth.Failure.KEY_SPEND_LIMIT, ModelHealth.Failure.SPEND_LIMIT,
        )

    fun classify(
        error: Throwable,
        sentTools: Boolean,
        switchOnAmbiguousAccessDenied: Boolean = true,
        ownKeyTurn: Boolean = false,
        ownKeyAutomatic: Boolean = false,
    ): Decision? {
        if (ownKeyTurn && error is java.net.SocketTimeoutException) return Decision.Cooldown(ModelHealth.Failure.NETWORK)
        val llm = error as? LLMError ?: return null
        if (llm is LLMError.Cancelled) return null
        // A relay refusal can be a safety/policy refusal. Only an explicit allowance
        // exhaustion permits switching, never an unclassified relay error.
        llm.nmRelayError?.let { relay ->
            return if (relay.exhausted != null) Decision.Cooldown(ModelHealth.Failure.QUOTA) else null
        }
        val reach = llm.nmProviderReach
        // This explicit key limit may reject a paid model while free siblings still work.
        // Do not turn it into an account-wide no-credit cooldown.
        if (!switchOnAmbiguousAccessDenied && ai.tama.net.OpenRouterSpendLimit.keyLimitIsZero(reach)) {
            return Decision.Cooldown(ModelHealth.Failure.KEY_SPEND_LIMIT)
        }
        if (ownKeyTurn) when (llm.tamaOwnKeyHint) {
            ai.tama.net.OwnKeyFailureSignals.Hint.MODEL_UNAVAILABLE ->
                return Decision.Cooldown(ModelHealth.Failure.MODEL_UNAVAILABLE, reach?.retryAfterS, reach?.retryAfterPresent == true)
            ai.tama.net.OwnKeyFailureSignals.Hint.SPEND_LIMIT ->
                return Decision.Cooldown(ModelHealth.Failure.SPEND_LIMIT, reach?.retryAfterS, reach?.retryAfterPresent == true)
            ai.tama.net.OwnKeyFailureSignals.Hint.POLICY_REFUSAL -> return null
            null -> if (reach?.httpStatus == 408) return Decision.Cooldown(ModelHealth.Failure.NETWORK)
        }
        // Auto may switch away from a real access denial on a personal route.
        // It is not a bad key, credit issue or an HTTP status invented from SSE.
        if (!switchOnAmbiguousAccessDenied && reach != null &&
            (reach.kind == ProviderReach.Kind.ACCESS_DENIED ||
                (reach.httpStatus == 403 && reach.kind != ProviderReach.Kind.NO_CREDIT &&
                    reach.kind != ProviderReach.Kind.QUOTA))) {
            return if (ownKeyTurn && ownKeyAutomatic && reach.httpStatus == 403 &&
                reach.kind == ProviderReach.Kind.ACCESS_DENIED &&
                !ai.tama.net.OwnKeyFailureSignals.policyRefusal(emptyList(), reach.originalMessage)) {
                Decision.Cooldown(ModelHealth.Failure.FORBIDDEN, reach.retryAfterS, reach.retryAfterPresent)
            } else null
        }
        val message = reach?.originalMessage?.takeIf { it.isNotBlank() }
            ?: (llm as? LLMError.ProviderError)?.detail
        if (sentTools && CapabilityEvidence.explicitlyUnsupportedTools(reach?.httpStatus, message)) {
            return Decision.UnsupportedTools
        }
        val reason = when (reach?.kind) {
            ProviderReach.Kind.QUOTA -> ModelHealth.Failure.QUOTA
            ProviderReach.Kind.NO_CREDIT -> ModelHealth.Failure.NO_CREDIT
            ProviderReach.Kind.SIGNED_OUT, ProviderReach.Kind.AUTH_REJECTED -> ModelHealth.Failure.AUTH
            // A generic 403 might be a moderation or access-policy refusal, not credit.
            // The isolated build does not silently forward it to another provider.
            ProviderReach.Kind.ACCESS_DENIED -> if (switchOnAmbiguousAccessDenied) ModelHealth.Failure.AUTH else null
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
