package ai.tama.pricing

import org.json.JSONObject

/** Price evidence from the exact bundled NVIDIA NIM catalogue, not a general-purpose NIM licence. */
internal object NvidiaChatPricing {
    // The bundled modalities only describe payloads. Some non-chat NIMs
    // also have text in/out; they belong to separate API families.
    // https://docs.api.nvidia.com/nim/reference/models-1
    private val nonChat = setOf(
        "baai/bge-m3", "nvidia/llama-nemotron-embed-vl-1b-v2", "nvidia/nv-embedcode-7b-v1", "nvidia/nv-embed-v1",
        "nvidia/llama-nemotron-rerank-vl-1b-v2", "nvidia/rerank-qa-mistral-4b", "nvidia/llama-3_2-nemoretriever-300m-embed-v1",
        "nvidia/studiovoice", "nvidia/nemotron-voicechat", "nvidia/riva-translate-4b-instruct-v1.1",
        "meta/esmfold", "meta/esm2-650m", "nvidia/gliner-pii", "nvidia/usdvalidate",
    )

    fun isChatId(id: String): Boolean = id !in nonChat

    fun isZero(cost: JSONObject?): Boolean = cost != null && cost.has("input") && cost.has("output") &&
        cost.keys().asSequence().all { name ->
            val value = cost.opt(name)
            (value is String || value is Number) && OpenRouterPricing.number(value.toString())?.signum() == 0
        }

    fun allows(base: String?, id: String, zero: Boolean, inputs: List<String>?, outputs: List<String>?, now: Long): Boolean =
        base == DocumentedFreeChat.NVIDIA_BASE && zero && DocumentedFreeChat.catalogueReviewCurrent(now) &&
            isChatId(id) && inputs?.contains("text") == true && outputs?.toSet() == setOf("text")
}
