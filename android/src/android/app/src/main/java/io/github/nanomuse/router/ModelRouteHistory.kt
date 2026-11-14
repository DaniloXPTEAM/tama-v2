package io.github.nanomuse.router

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * A provider receives a read-only view of history. Private reasoning blobs and Gemini thought
 * signatures belong to the exact originating instance and model; a matching model name on a
 * different endpoint is not proof. Old rows with no attribution are treated as unknown. Tool
 * calls, results, user text and attachments stay in history; nothing is executed by this mapper.
 */
object ModelRouteHistory {
    fun forProvider(messages: List<LLMMessage>, providerId: String?, modelId: String): List<LLMMessage> =
        messages.map { message ->
            val sameOrigin = !providerId.isNullOrBlank() && modelId.isNotBlank() &&
                message.originProviderInstanceId == providerId && message.originModelId == modelId
            if (message.role != LLMMessage.Role.ASSISTANT || sameOrigin) return@map message
            val hasSignature = message.contentParts.any { it is AgentContentPart.ToolUse && it.thoughtSignature != null }
            if (message.reasoningContent == null && !hasSignature) return@map message
            message.copy(
                reasoningContent = null,
                contentParts = message.contentParts.map { part ->
                    if (part is AgentContentPart.ToolUse && part.thoughtSignature != null)
                        part.copy(thoughtSignature = null) else part
                },
            )
        }
}
