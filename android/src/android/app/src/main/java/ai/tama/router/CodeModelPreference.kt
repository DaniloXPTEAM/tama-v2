package ai.tama.router

/** Explicit provider/model pairs, never a model-name test for eligibility. */
internal object CodeModelPreference {
    fun rank(base: String?, id: String): Int = when (base) {
        "https://integrate.api.nvidia.com/v1" -> when (id) {
            "qwen/qwen3-coder-480b-a35b-instruct" -> 3
            "qwen/qwen2.5-coder-32b-instruct" -> 2
            else -> 0
        }
        "https://api.groq.com/openai/v1" -> if (id == "openai/gpt-oss-120b") 2 else 0
        "https://generativelanguage.googleapis.com/v1beta" -> if (id == "gemma-4-31b-it") 1 else 0
        "https://openrouter.ai/api/v1" -> when (id) {
            "qwen/qwen3-coder", "qwen/qwen3-coder:free", "mistralai/devstral-2512", "openai/gpt-oss-120b:free" -> 2
            "google/gemma-4-31b-it:free" -> 1
            else -> 0
        }
        else -> 0
    }
}
