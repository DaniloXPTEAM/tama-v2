package ai.tama.pricing

import org.json.JSONArray
import org.json.JSONObject

/** Text streaming and local function calls only. New hosted features require their own price review. */
internal object DocumentedOpenAIChatBody {
    private val fields = setOf("model", "stream", "messages", "max_tokens", "max_completion_tokens", "temperature",
        "stream_options", "tools", "tool_choice", "reasoning_effort", "reasoning", "thinking", "enable_thinking",
        "thinking_budget", "extra_body")
    private val messageFields = setOf("role", "content", "tool_calls", "tool_call_id", "reasoning_content")
    private val roles = setOf("system", "developer", "user", "assistant", "tool")

    private fun JSONObject.fieldsOnly(allowed: Set<String>): Boolean = keys().asSequence().all { it in allowed }
    private fun JSONArray.objectsMatch(predicate: (JSONObject) -> Boolean): Boolean =
        (0 until length()).all { index -> optJSONObject(index)?.let(predicate) == true }

    private fun permits(body: JSONObject): Boolean {
        if (!body.fieldsOnly(fields) || body.opt("stream") != true) return false
        if (body.has("tools") && body.optJSONArray("tools")?.objectsMatch {
                it.fieldsOnly(setOf("type", "function")) && it.opt("type") == "function" && it.optJSONObject("function") != null
            } != true) return false
        if (body.has("tool_choice") && body.opt("tool_choice") != "auto") return false
        if (body.has("reasoning") && body.optJSONObject("reasoning")?.fieldsOnly(setOf("effort")) != true) return false
        if (body.has("thinking") && body.optJSONObject("thinking")?.fieldsOnly(setOf("type")) != true) return false
        if (body.has("extra_body") && body.optJSONObject("extra_body")?.fieldsOnly(setOf("enable_thinking", "thinking_budget")) != true) return false
        if (body.has("stream_options") && body.optJSONObject("stream_options")?.fieldsOnly(setOf("include_usage")) != true) return false
        return body.optJSONArray("messages")?.objectsMatch { message ->
            val role = message.opt("role")
            val content = message.opt("content")
            val calls = message.optJSONArray("tool_calls")
            role in roles && message.fieldsOnly(messageFields) &&
                (!message.has("tool_calls") || (role == "assistant" && calls?.objectsMatch {
                    it.fieldsOnly(setOf("id", "type", "function")) && it.opt("type") == "function" &&
                        it.optJSONObject("function")?.fieldsOnly(setOf("name", "arguments")) == true
                } == true)) &&
                (content is String || (role == "assistant" && calls != null && (content == null || content == JSONObject.NULL)) ||
                    (content as? JSONArray)?.objectsMatch {
                        it.fieldsOnly(setOf("type", "text")) && it.opt("type") == "text" && it.opt("text") is String
                    } == true)
        } == true
    }

    fun restrict(body: JSONObject, route: DocumentedFreeChat.Route) {
        if (!permits(body)) throw ModelCostGuard.Blocked()
        when (route) {
            DocumentedFreeChat.Route.OPENROUTER -> {
                // Keep the same exact model ID and don't fall back to another endpoint.
                // The official max_price filters inference, not external service fees.
                body.put("provider", JSONObject().put("allow_fallbacks", false).put("max_price", JSONObject()
                    .put("prompt", 0).put("completion", 0).put("request", 0).put("image", 0)))
                body.put("plugins", JSONArray().put(JSONObject().put("id", "web").put("enabled", false)))
            }
            DocumentedFreeChat.Route.NVIDIA -> {
                // NIM's Chat Completions examples use max_tokens; not all NIMs
                // understand OpenAI's optional streaming usage object.
                body.remove("max_completion_tokens")?.let { body.put("max_tokens", it) }
                body.remove("stream_options")
            }
            DocumentedFreeChat.Route.GROQ -> Unit
            DocumentedFreeChat.Route.GEMINI -> throw ModelCostGuard.Blocked()
        }
    }
}
