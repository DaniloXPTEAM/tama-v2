package ai.tama.providers

import ai.tama.pricing.DocumentedFreeChat
import ai.tama.router.CodeModelPreference

/** Descriptions of published models on exact routes; no promotion to a new route or permission. */
internal object ReviewedModelFeatures {
    fun coding(base: String?, modelId: String): Boolean = when (base) {
        DocumentedFreeChat.NVIDIA_BASE -> modelId in setOf(
            // Google Gemma 4 model card; NVIDIA Nemotron 3 Nano/Super model cards.
            "google/gemma-4-31b-it", "nvidia/nemotron-3-nano-30b-a3b", "nvidia/nemotron-3-super-120b-a12b",
        ) || CodeModelPreference.rank(base, modelId) > 0
        DocumentedFreeChat.OPENROUTER_BASE -> modelId in setOf(
            "google/gemma-4-31b-it", "nvidia/nemotron-3-nano-30b-a3b", "nvidia/nemotron-3-super-120b-a12b",
            "nvidia/nemotron-3-super-120b-a12b:free",
        ) || CodeModelPreference.rank(base, modelId) > 0
        else -> CodeModelPreference.rank(base, modelId) > 0
    }
}
