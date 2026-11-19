package ai.tama.pricing

import org.json.JSONObject

/** Price evidence from the exact bundled NVIDIA NIM catalogue, not a general-purpose NIM licence. */
internal object NvidiaChatPricing {
    // The catalogue describes these retrieval models as text output, but they
    // have embeddings/ranking endpoints, not /chat/completions.
    private val retrieval = setOf(
        "nvidia/llama-nemotron-embed-vl-1b-v2", "nvidia/nv-embedcode-7b-v1", "nvidia/nv-embed-v1",
        "nvidia/llama-nemotron-rerank-vl-1b-v2", "nvidia/rerank-qa-mistral-4b",
        "nvidia/llama-3_2-nemoretriever-300m-embed-v1",
    )

    fun isZero(cost: JSONObject?): Boolean = cost != null && cost.has("input") && cost.has("output") &&
        cost.keys().asSequence().all { name ->
            val value = cost.opt(name)
            (value is String || value is Number) && OpenRouterPricing.number(value.toString())?.signum() == 0
        }

    fun allows(base: String?, id: String, zero: Boolean, inputs: List<String>?, outputs: List<String>?, now: Long): Boolean =
        base == DocumentedFreeChat.NVIDIA_BASE && zero && DocumentedFreeChat.catalogueReviewCurrent(now) &&
            id !in retrieval && inputs?.contains("text") == true && outputs?.toSet() == setOf("text")
}
