package ai.tama.pricing

import org.json.JSONArray
import org.json.JSONObject

/** Known generateContent payload only; hosted tools and newly added fields need a cost review. */
internal object DocumentedGeminiBody {
    private val requestFields = setOf("contents", "systemInstruction", "generationConfig", "tools")
    private val configFields = setOf("maxOutputTokens", "temperature", "thinkingConfig")
    private val thinkingFields = setOf("thinkingBudget", "thinkingLevel", "includeThoughts")
    private val imageTypes = setOf("image/jpeg", "image/png", "image/webp")

    private fun JSONObject.fieldsOnly(fields: Set<String>): Boolean = keys().asSequence().all { it in fields }
    private fun JSONArray.objectsMatch(predicate: (JSONObject) -> Boolean): Boolean =
        (0 until length()).all { index -> optJSONObject(index)?.let(predicate) == true }

    private fun partAllowed(part: JSONObject): Boolean {
        if (part.length() != 1) return false
        return part.opt("text") is String || part.optJSONObject("functionCall") != null ||
            part.optJSONObject("functionResponse") != null ||
            part.optJSONObject("inlineData")?.let {
                it.fieldsOnly(setOf("mimeType", "data")) && it.optString("mimeType") in imageTypes && it.opt("data") is String
            } == true
    }

    fun permits(body: JSONObject): Boolean {
        if (!body.fieldsOnly(requestFields)) return false
        val config = body.optJSONObject("generationConfig") ?: return false
        if (!config.fieldsOnly(configFields)) return false
        if (config.has("thinkingConfig") && config.optJSONObject("thinkingConfig")?.fieldsOnly(thinkingFields) != true) return false
        if (body.has("tools") && body.optJSONArray("tools")?.objectsMatch {
                it.length() == 1 && it.optJSONArray("function_declarations") != null
            } != true) return false
        if (body.has("systemInstruction") && body.optJSONObject("systemInstruction")?.let { instruction ->
                instruction.fieldsOnly(setOf("parts")) && instruction.optJSONArray("parts")?.objectsMatch {
                    it.length() == 1 && it.opt("text") is String
                } == true
            } != true) return false
        return body.optJSONArray("contents")?.objectsMatch { content ->
            content.fieldsOnly(setOf("role", "parts")) && content.optJSONArray("parts")?.objectsMatch(::partAllowed) == true
        } == true
    }
}
