package com.openminis.app.data.model
sealed class LLMStreamChunk {
    data class Text(val text: String) : LLMStreamChunk()
    data class ToolUseStart(val id: String, val name: String) : LLMStreamChunk()
    data class Finished(val stopReason: String?) : LLMStreamChunk()
}
