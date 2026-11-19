package com.openminis.app.provider

import android.content.Context
import com.openminis.app.data.model.LLMModel

// Disk cache is deliberately inert in the local wire tests.
internal class ProviderModelsCache(namespace: String) {
    fun load(context: Context, key: String): List<LLMModel>? = null
    fun save(context: Context, key: String, models: List<LLMModel>) = Unit
    fun invalidate(context: Context, key: String) = Unit
}
