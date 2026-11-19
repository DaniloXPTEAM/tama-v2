package com.openminis.app.provider.openrouter

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.normalizeModalities
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.ProviderModelsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object OpenRouterModelsApi {
    private val client = OkHttpClient.Builder().proxyAuthenticator(io.github.nanomuse.net.OwnProviderProxy.authenticator).build() // nanoMuse: Settings → Network, a proxy with a password
    private val cache = ProviderModelsCache("openrouter")
    private val catalogueClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /**
     * Fetch OpenRouter's model catalog. OpenRouter returns rich metadata
     * alongside each model (context window, max output, reasoning support,
     * input/output modalities) — we parse it here instead of waiting for
     * `ModelsDevApi.enrichModels` to match, matching iOS `OpenRouterModelsAPI`.
     *
     * @param context When provided, results are cached for 7 days at
     *   `cacheDir/models-cache/openrouter/<sha256(apiKey)>.json`. Pass null to
     *   bypass the cache entirely (matches existing callers).
     * @param forceRefresh Skip the cache read but still write-through on success.
     */
    internal suspend fun fetchModels(
        apiKey: String,
        context: Context? = null,
        forceRefresh: Boolean = false,
        onTextCatalogue: ((ai.tama.pricing.OpenRouterPricing.Catalogue) -> Unit)? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val cacheKey = apiKey
        if (context != null && !forceRefresh && onTextCatalogue == null) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        val request = catalogueRequest(apiKey) // nanoMuse: same host for both catalogue paths

        val parallel = com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"
        val startedAt = System.currentTimeMillis()
        val (models, priceData) = (if (parallel) catalogueClient else client).newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return@withContext emptyList()
            if (!response.isSuccessful) {
                if (context != null && (response.code == 401 || response.code == 403)) cache.invalidate(context, cacheKey)
                return@withContext emptyList()
            }
            try {
                val data = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
                parseModels(data) to data
            } catch (_: Exception) {
                return@withContext emptyList()
            }
        }
        if (onTextCatalogue != null) onTextCatalogue(ai.tama.pricing.OpenRouterPricing.parse(priceData, startedAt))
        val enriched = if (!parallel) ModelsDevApi.enrichModels(models) else {
            // nanoMuse: /models defaults to text output. An independent
            // image query cannot erase a successful text catalogue.
            val images = fetchFreeCatalogue(catalogueRequest(apiKey, "image"), catalogueClient)
                ?.models.orEmpty().filter { "image" in it.outputModalities.orEmpty() }
            ModelsDevApi.enrichModelsForBase((images + models).distinctBy { it.id }, "https://openrouter.ai/api/v1")
        }
        if (context != null) cache.save(context, cacheKey, enriched)
        enriched
    }

    // nanoMuse: the ordinary seven-day model cache contains no price evidence.
    // Free-only import therefore uses a fresh response and keeps its models and
    // quotes together, never the process-global snapshot from another request.
    internal data class FreeCatalogue(
        val models: List<LLMModel>,
        val prices: ai.tama.pricing.OpenRouterPricing.Catalogue,
    )

    internal suspend fun fetchFreeCatalogue(apiKey: String): FreeCatalogue? =
        fetchFreeCatalogue(catalogueRequest(apiKey), catalogueClient)

    // nanoMuse: injectable transport for local, credential-free wire tests. The
    // production entry point above always builds the fixed OpenRouter request.
    internal suspend fun fetchFreeCatalogue(request: Request, httpClient: OkHttpClient): FreeCatalogue? = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val data = JSONObject(body).optJSONArray("data") ?: return@withContext null
                FreeCatalogue(parseModels(data), ai.tama.pricing.OpenRouterPricing.parse(data, startedAt))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    internal fun catalogueRequest(apiKey: String, outputModality: String? = null): Request = Request.Builder() // nanoMuse
        .url("https://openrouter.ai/api/v1/models".toHttpUrl().newBuilder().apply {
            if (outputModality != null) addQueryParameter("output_modalities", outputModality)
        }.build())
        .header("Authorization", "Bearer $apiKey")
        .header("HTTP-Referer", "https://github.com/OpenMinis/OpenMinis")
        .header("X-Title", "TAMA AI App")
        .applyUserAgentOverride(null)
        .build()

    private fun parseModels(data: JSONArray): List<LLMModel> {
        val models = mutableListOf<LLMModel>()
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val id = obj.optString("id")
            if (id.isEmpty()) continue
            val name = obj.optString("name", id)

            // OpenRouter exposes `architecture.input_modalities` / `output_modalities`
            // as arrays of short strings (e.g. "text", "image", "audio"). Fall back
            // to `[text, text]` when absent — most text-only models omit these.
            val arch = obj.optJSONObject("architecture")
            // OpenRouter returns modalities as `image_input` / `text_output` with suffixes;
            // the rest of the codebase (models.dev, capability fragments,
            // ModelEntryDetailScreen toggles) uses the bare form. Normalize at the parse
            // boundary so persisted overrides round-trip correctly through the toggles.
            val inputModalities = arch?.optJSONArray("input_modalities")?.toStringList().normalizeModalities()
            val outputModalities = arch?.optJSONArray("output_modalities")?.toStringList().normalizeModalities()

            val contextWindow = obj.optInt("context_length").takeIf { it > 0 }
            val maxOutputTokens = obj.optJSONObject("top_provider")
                ?.optInt("max_completion_tokens")?.takeIf { it > 0 }

            val supportedParams = obj.optJSONArray("supported_parameters")?.toStringList() ?: emptyList()
            val supportsReasoning = "reasoning" in supportedParams

            models.add(
                LLMModel(
                    id = id,
                    displayName = name,
                    provider = "OpenRouter",
                    contextWindow = contextWindow,
                    maxOutputTokens = maxOutputTokens,
                    supportsReasoning = if (supportsReasoning) true else null,
                    inputModalities = inputModalities,
                    outputModalities = outputModalities,
                )
            )
        }
        return models
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}
