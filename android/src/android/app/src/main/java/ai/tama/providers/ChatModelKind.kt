package ai.tama.providers

import ai.tama.pricing.NvidiaChatPricing
import java.util.Locale

/** A chat picker is not the provider's full catalogue. The latter keeps every model for its own use. */
internal object ChatModelKind {
    // Some /models responses return only an ID. In that case keep legacy chat
    // models, but recognize dedicated API families, not arbitrary substrings.
    private val nonChatParts = setOf("embedding", "embeddings", "embed", "rerank", "reranker", "moderation", "moderations",
        "safety", "safeguard", "guard", "guardrails", "asr", "tts", "whisper", "transcribe")
    private val nonChatFamilies = Regex("^(?:bge|e5|jina-embeddings|text-embedding|omni-moderation|llamaguard|llama-guard)(?:[-_.:]|$)")

    fun accepts(id: String, input: List<String>?, output: List<String>?, knownMediaGenerator: Boolean): Boolean {
        val lower = id.lowercase(Locale.ROOT)
        val localId = lower.substringAfterLast('/')
        if (!NvidiaChatPricing.isChatId(lower) || nonChatFamilies.containsMatchIn(localId) ||
            localId.split('-', '_', '.', ':').any { it in nonChatParts }) return false
        fun modes(values: List<String>?) = values.orEmpty().map { it.lowercase(Locale.ROOT).removeSuffix("_input").removeSuffix("_output") }
        val inputs = modes(input)
        val outputs = modes(output)
        if (inputs.isNotEmpty() && "text" !in inputs) return false
        if (outputs.isNotEmpty()) return "text" in outputs && outputs.none { it in setOf("image", "video", "embedding", "moderation", "safety") }
        return !knownMediaGenerator
    }
}
