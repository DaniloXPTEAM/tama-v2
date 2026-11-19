package ai.tama.media

import ai.tama.pricing.DocumentedFreeChat
import com.openminis.app.R

/** Documentary evidence only. The image call still needs the normal cost and permission gates. */
internal object CustomImageRoute {
    const val AGNES_FLASH = "agnes-image-2.1-flash"

    // https://wiki.agnes-ai.com/en/docs/agnes-image-21-flash
    fun agnes(base: String?): Boolean = base?.trimEnd('/') == DocumentedFreeChat.AGNES_BASE
    fun nvidia(base: String?): Boolean = base?.trimEnd('/') == DocumentedFreeChat.NVIDIA_BASE

    enum class Problem(val code: String, val message: Int) {
        CHAT_ONLY("image_chat_only", R.string.nm_image_chat_only),
        UNCONFIRMED("image_route_unconfirmed", R.string.nm_image_route_unconfirmed),
        NEEDS_KEY("image_key_missing", R.string.nm_image_key_missing),
        NVIDIA_NATIVE("image_nvidia_native", R.string.nm_image_nvidia_native),
        ENDPOINT_REJECTED("image_endpoint_rejected", R.string.nm_image_endpoint_rejected),
    }

    class Rejected : Exception()

    fun missingModel(selected: String?, outputs: Map<String, Set<String>?>): Problem =
        if (selected != null && outputs[selected] == setOf("text") ||
            selected == null && outputs.isNotEmpty() && outputs.values.all { it == setOf("text") }) Problem.CHAT_ONLY
        else Problem.UNCONFIRMED
}
