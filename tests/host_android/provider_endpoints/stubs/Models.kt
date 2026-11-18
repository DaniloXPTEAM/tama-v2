package com.openminis.app.data.model

enum class ProviderType { openAI, openAIResponses, anthropic, gemini, openRouter, xAI, kimiCode, antigravity, unsupported;
    val displayName: String get() = name
    val isUsable: Boolean get() = this != antigravity && this != unsupported
}
enum class ProviderCredential { apiKey, oauth }
enum class ImageEndpointMode { auto }
class VoiceProviderTemplate(
    val id: String,
    val name: String,
    val providerType: ProviderType,
    val baseURL: String,
    val appendV1: Boolean,
    val capability: Capability,
    val baseURLMarkers: List<String>,
    val mockModels: List<String>,
) { enum class Capability { BOTH } }
