package ai.tama.media

import ai.tama.pricing.DocumentedFreeChat
import com.openminis.app.R

/** Documentary evidence only. The image call still needs the normal cost and permission gates. */
internal object CustomImageRoute {
    fun agnes(base: String?): Boolean = ai.tama.providers.ProviderEndpoints.isAgnesBase(base)
    fun nvidia(base: String?): Boolean = base?.trimEnd('/') == DocumentedFreeChat.NVIDIA_BASE

    enum class Problem(val code: String, val message: Int) {
        CHAT_ONLY("image_chat_only", R.string.nm_image_chat_only),
        UNCONFIRMED("image_route_unconfirmed", R.string.nm_image_route_unconfirmed),
        NEEDS_KEY("image_key_missing", R.string.nm_image_key_missing),
        NVIDIA_NATIVE("image_nvidia_native", R.string.nm_image_nvidia_native),
        ENDPOINT_REJECTED("image_endpoint_rejected", R.string.nm_image_endpoint_rejected),
        AGNES_UNAVAILABLE("agnes_image_unavailable", R.string.nm_agnes_image_unavailable),
        AGNES_QUEUE("agnes_image_queue", R.string.nm_agnes_image_queue),
        AGNES_LIMIT("agnes_image_limit", R.string.nm_agnes_image_limit),
        AGNES_PAYMENT("agnes_image_payment", R.string.nm_agnes_image_payment),
        AGNES_AUTHENTICATION("agnes_image_authentication", R.string.nm_agnes_image_authentication),
        AGNES_ACCESS_DENIED("agnes_image_access_denied", R.string.nm_agnes_image_access_denied),
        AGNES_TIMEOUT("agnes_image_timeout", R.string.nm_agnes_image_timeout),
        AGNES_NO_IMAGE("agnes_image_missing", R.string.nm_agnes_image_missing),
        AGNES_REFERENCE_SIZE("agnes_image_reference_size", R.string.nm_agnes_image_reference_size),
        AGNES_REFERENCE_FORMAT("agnes_image_reference_format", R.string.nm_agnes_image_reference_format),
        AGNES_FAILED("agnes_image_failed", R.string.nm_image_detail_failed),
    }

    fun problem(error: AgnesImageApi.Failure): Problem = when (error.problem) {
        AgnesImageApi.Problem.QUEUE -> Problem.AGNES_QUEUE
        AgnesImageApi.Problem.LIMIT -> Problem.AGNES_LIMIT
        AgnesImageApi.Problem.PAYMENT -> Problem.AGNES_PAYMENT
        AgnesImageApi.Problem.AUTHENTICATION -> Problem.AGNES_AUTHENTICATION
        AgnesImageApi.Problem.ACCESS_DENIED -> Problem.AGNES_ACCESS_DENIED
        AgnesImageApi.Problem.TIMEOUT -> Problem.AGNES_TIMEOUT
        AgnesImageApi.Problem.NO_IMAGE -> Problem.AGNES_NO_IMAGE
        AgnesImageApi.Problem.REFERENCE_SIZE -> Problem.AGNES_REFERENCE_SIZE
        AgnesImageApi.Problem.REFERENCE_FORMAT -> Problem.AGNES_REFERENCE_FORMAT
        AgnesImageApi.Problem.FAILED -> Problem.AGNES_FAILED
    }

    class Rejected : Exception()

    fun missingModel(selected: String?, outputs: Map<String, Set<String>?>): Problem =
        if (selected != null && outputs[selected] == setOf("text") ||
            selected == null && outputs.isNotEmpty() && outputs.values.all { it == setOf("text") }) Problem.CHAT_ONLY
        else Problem.UNCONFIRMED
}
