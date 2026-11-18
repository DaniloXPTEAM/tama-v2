package com.openminis.app.data.model

enum class ProviderType { openRouter, openAI }
data class LLMModel(val id: String)
data class ModelEntry(val providerInstanceId: String, val baseModel: LLMModel)
