package io.github.nanomuse.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.openminis.app.MinisApp
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.media.RelayMediaPolicy
import ai.tama.media.AgnesImageApi
import ai.tama.media.AgnesImages
import ai.tama.media.CustomImageRoute
import ai.tama.media.ImageDispatch
import ai.tama.media.OwnKeyImagePolicy
import ai.tama.pricing.DocumentedFreeChat
import ai.tama.providers.KeyDestinationGuard
import ai.tama.providers.ProviderEndpoints
import ai.tama.router.AutoModelStore
import ai.tama.net.singleUseImageBody
import ai.tama.net.singleUseImageClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Text-to-image and image-edit through the user's own providers, using the same instances, keys and
 * model entries OpenMinis' `model-use` CLI drives. Generation and OpenAI-style edits go through
 * [OpenAIProvider.generateImage] / [OpenAIProvider.editImage] (Azure paths, header overrides and
 * the `response_format` retry included). Native Gemini uses generateContent with text and image
 * response modalities for both drawing and editing. Alibaba Model Studio's compatible host has neither
 * `images/generations` nor `images/edits`; there both drawing and posing go through DashScope's
 * native multimodal endpoint with the same qwen-image-3.x / wan-image (or a `qwen-image-edit-*`)
 * model on the same host and key.
 */
object ImageGen {
    private const val TAG = "ImageGen"
    private const val PREFS = "nanomuse"
    private const val KEY_INSTANCE = "avatar.provider_id"
    private const val KEY_MODEL = "avatar.model"
    // nanoMuse: a choice changed away and back must not revive an in-flight image request.
    private var imageChoiceRevision = 0L
    @Synchronized internal fun imageChoiceRevision(): Long = imageChoiceRevision
    private val parallel: Boolean get() = com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"

    private fun customOpenAi(instance: ProviderInstance): Boolean = parallel &&
        instance.credentialType == ProviderCredential.apiKey && instance.customBaseURL != null &&
        instance.providerType in setOf(ProviderType.openAI, ProviderType.openAIResponses) &&
        !instance.azureMode && !speaksDashScope(baseUrlOf(instance)) &&
        !ai.tama.providers.ProviderEndpoints.isOfficialOpenAIBase(baseUrlOf(instance))

    private fun advanceImageChoice() {
        check(imageChoiceRevision < Long.MAX_VALUE) { "image choice version exhausted" }
        imageChoiceRevision++
    }

    data class Endpoint(
        val instance: ProviderInstance,
        val apiKey: String,
        val model: String,
    ) {
        val instanceId: String get() = instance.id
        val label: String get() = instance.label
        val baseUrl: String get() = baseUrlOf(instance)
        val isDashScope: Boolean get() = baseUrl.contains("aliyuncs.com") || baseUrl.contains("dashscope")
    }

    class ImageGenException(message: String) : IOException(message)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Preserve the configured proxy's CONNECT authentication before a
            // billable POST; the one-shot client still refuses body replay.
            .proxyAuthenticator(io.github.nanomuse.net.OwnProviderProxy.authenticator)
            .build()
    }

    /**
     * API-key providers that speak the OpenAI images API or Gemini's native image API,
     * and, of the vendors the catalogue knows, only those with image models
     * (contract C11, [io.github.nanomuse.cloud.Capabilities]): a DeepSeek or Groq key draws
     * nothing, and a ChatGPT plan signed in through the Codex OAuth is chat and vision only.
     */
    // nanoMuse: scoped to personal keys in Test. A free chat filter is not
    // evidence that a different endpoint (image creation) is free.
    internal fun freeImagesOnly(context: Context, instanceId: String? = null): Boolean {
        if (com.openminis.app.BuildConfig.APPLICATION_ID != "ai.tama.assistant.tamav2test") return false
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return false
        val id = instanceId ?: io.github.nanomuse.models.ModelSlots.ownChatInstance(context)?.id ?: return false
        return repo.instance(id)?.let(repo::tamaFreeChatOnly) == true
    }

    internal data class SavedChoice(val instanceId: String, val model: String)

    internal fun savedChoice(context: Context): SavedChoice? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val instanceId = prefs.getString(KEY_INSTANCE, null) ?: return null
        return SavedChoice(instanceId, prefs.getString(KEY_MODEL, null).orEmpty().trim())
    }

    internal fun agnesAccounts(context: Context): List<ProviderInstance> =
        (context.applicationContext as? MinisApp)?.providerRepositoryOrNull?.config?.value?.instances
            .orEmpty().filter { it.isEnabled && AgnesImages.account(it) }

    internal fun hasDeclaredFreeImage(context: Context): Boolean {
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return false
        return repo.config.value.instances.any { AgnesImages.route(it) && availableModels(context, it).isNotEmpty() }
    }

    internal fun noFreeImageAvailable(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val picked = prefs.getString(KEY_INSTANCE, null)
        if (freeImagesOnly(context) || (picked != null && freeImagesOnly(context, picked))) {
            if (picked == null) return !hasDeclaredFreeImage(context)
            val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return true
            val inst = repo.instance(picked) ?: return true
            return !AgnesImages.route(inst) || prefs.getString(KEY_MODEL, null) !in availableModels(context, inst)
        }
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return false
        return repo.config.value.instances.any { it.isEnabled && repo.tamaFreeChatOnly(it) } && eligibleInstances(context).isEmpty()
    }

    /** No exemption for another base, model, cloud account, key, or learned payment refusal. */
    internal fun declaredFree(context: Context, ep: Endpoint): Boolean {
        if (!AgnesImages.route(ep.instance) || AgnesImages.model(ep.model) == null || ep.apiKey.isBlank()) return false
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return false
        val live = repo.config.value.instances.singleOrNull { it.id == ep.instanceId } ?: return false
        return live.isEnabled && AgnesImages.route(live) &&
            AutoModelStore.routeFingerprint(live) == AutoModelStore.routeFingerprint(ep.instance) &&
            repo.tamaOwnApiKey(live) == ep.apiKey && ep.model in availableModels(context, live)
    }

    private fun requireImageCostScope(context: Context, ep: Endpoint) {
        if (!OwnKeyImagePolicy.mayRequest(freeImagesOnly(context) || freeImagesOnly(context, ep.instanceId), declaredFree(context, ep))) {
            throw OwnKeyImagePolicy.NoVerifiedFreeGenerator()
        }
    }

    fun eligibleInstances(context: Context): List<ProviderInstance> {
        val app = context.applicationContext as? MinisApp ?: return emptyList()
        val repo = app.providerRepositoryOrNull ?: return emptyList()
        val cloudId = NanoMuseCloud.instance(context)?.id
        val freeOnly = freeImagesOnly(context)
        val relayCanDraw = cloudId != null &&
            NanoMuseCloud.baseUrl(context) != NanoMuseCloud.DEFAULT_BASE &&
            repo.config.value.instances.firstOrNull { it.id == cloudId }
                ?.let { availableModels(context, it).isNotEmpty() } == true
        return repo.config.value.instances.filter { inst ->
            if (AgnesImages.account(inst)) return@filter availableModels(context, inst).isNotEmpty()
            !freeOnly && !freeImagesOnly(context, inst.id) && (inst.id != cloudId || relayCanDraw) &&
                ImageModelRequests.supports(inst) &&
                if (inst.id != cloudId && customOpenAi(inst)) {
                    val key = repo.usableApiKey(inst)
                    key != null &&
                        inst.imageEndpointMode != com.openminis.app.data.model.ImageEndpointMode.chatCompletions &&
                        availableModels(context, inst).isNotEmpty()
                } else io.github.nanomuse.cloud.Capabilities.allows(context, inst, io.github.nanomuse.cloud.ProviderCatalogue.IMAGE)
        }
    }

    /** No configured provider can draw: the one sentence that says so (C11), or null when one can. */
    fun unavailableLine(context: Context): String? =
        selectionProblem(context)?.let { context.getString(it.message) }
            ?: if (noFreeImageAvailable(context)) context.getString(com.openminis.app.R.string.nm_free_image_unavailable)
            else if (eligibleInstances(context).isEmpty()) io.github.nanomuse.cloud.Capabilities.unavailableLine(context, io.github.nanomuse.cloud.ProviderCatalogue.IMAGE) else null

    /** A selected custom route is not silently replaced by another provider. */
    internal fun selectionProblem(context: Context): CustomImageRoute.Problem? {
        if (!parallel) return null
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return null
        val saved = savedChoice(context)
        if (saved == null && endpoint(context) != null) return null
        val instance = if (saved != null) repo.instance(saved.instanceId)
            else agnesAccounts(context).firstOrNull() ?: io.github.nanomuse.models.ModelSlots.ownChatInstance(context)
        val selected = saved?.model
        if (instance?.let(AgnesImages::account) == true || selected?.let(AgnesImages::model) != null) {
            if (instance == null || !instance.isEnabled || !AgnesImages.route(instance) ||
                (selected != null && AgnesImages.model(selected) == null)) return CustomImageRoute.Problem.AGNES_UNAVAILABLE
            if (repo.tamaOwnApiKey(instance) == null) return CustomImageRoute.Problem.NEEDS_KEY
            // nanoMuse: distinguish an ambiguous legacy pin from an HTTP or key failure.
            if (selected != null) {
                val matching = repo.entriesFor(instance.id).filter { it.baseModel.id == selected }
                if (matching.isNotEmpty() && AgnesImages.registeredEntry(matching) == null)
                    return CustomImageRoute.Problem.AGNES_IMAGE_AMBIGUOUS
            }
            val ids = selected?.let { listOf(it) } ?: AgnesImages.models.map { it.id }
            if (ids.all { repo.tamaPaymentRefused(instance.id, it) }) return CustomImageRoute.Problem.AGNES_PAYMENT
            return if (ids.any { it in availableModels(context, instance) }) null else CustomImageRoute.Problem.AGNES_UNAVAILABLE
        }
        if (saved != null && (instance == null || !instance.isEnabled || selected.isNullOrBlank()))
            return CustomImageRoute.Problem.UNCONFIRMED
        if (instance == null || !customOpenAi(instance) || instance.id == NanoMuseCloud.instance(context)?.id) return null
        if (noFreeImageAvailable(context)) return null
        if (CustomImageRoute.nvidia(baseUrlOf(instance))) return CustomImageRoute.Problem.NVIDIA_NATIVE
        val catalogue = availableModels(context, instance)
        if (instance.imageEndpointMode == com.openminis.app.data.model.ImageEndpointMode.chatCompletions)
            return CustomImageRoute.Problem.UNCONFIRMED
        if (selected?.let { it in catalogue } ?: catalogue.isNotEmpty()) return null
        val entries = repo.entriesFor(instance.id).filterNot { it.isHidden }.associate {
            it.model.id to (repo.tamaModelMetadata(it, instance)?.outputs ?: it.model.outputModalities?.toSet())
        }
        return CustomImageRoute.missingModel(selected, entries)
    }

    /**
     * Model entries of [instance] that draw: the catalogue says so (`image` among the output
     * modalities) or the name does ([looksLikeImageModel]). Providers' `/models` lists rarely
     * carry modalities — Model Studio's says nothing beyond the id — so the name is what tells
     * qwen-image-3.0-pro apart from qwen3-max. Edit-only models are left out: they need a
     * picture to start from.
     */
    fun imageEntries(context: Context, instance: ProviderInstance): List<ModelEntry> {
        val app = context.applicationContext as? MinisApp ?: return emptyList()
        val repo = app.providerRepositoryOrNull ?: return emptyList()
        if (freeImagesOnly(context) || freeImagesOnly(context, instance.id)) return emptyList() // nanoMuse
        val dashScope = speaksDashScope(baseUrlOf(instance))
        return repo.config.value.modelEntries.filter {
            it.providerInstanceId == instance.id && !it.isHidden && run {
                val id = it.model.id
                val outputs = repo.tamaModelMetadata(it, instance)?.outputs
                // nanoMuse: explicit output metadata wins over the legacy name hint.
                val draws = if (outputs != null) "image" in outputs
                    else "image" in it.model.outputModalities.orEmpty() ||
                        ((!customOpenAi(instance) || instance.id == NanoMuseCloud.instance(context)?.id) && looksLikeImageModel(id))
                // Model Studio's native endpoint takes qwen-image and wan-image; z-image and the
                // rest go through a different (asynchronous) API this build does not speak.
                val native = id.startsWith("qwen-image", ignoreCase = true) ||
                    (id.startsWith("wan", ignoreCase = true) && id.contains("-image", ignoreCase = true))
                draws && !id.contains("edit", ignoreCase = true) && (!dashScope || native) &&
                    ImageModelRequests.supportsModel(instance.providerType, id)
            }
        }
    }

    /** Names that mean "text to image" across the providers nanoMuse meets. */
    fun looksLikeImageModel(id: String): Boolean {
        val s = id.lowercase()
        if (s.contains("embedding") || s.contains("-vl") || s.contains("vision") || s.contains("caption")) return false
        return listOf("image", "nano-banana", "dall-e", "flux", "stable-diffusion", "sdxl", "sd3", "seedream", "kolors", "imagen", "ideogram", "recraft", "hidream", "cogview")
            .any { s.contains(it) }
    }

    /**
     * The models of [instance] the user can pick from, once the provider's list has been
     * fetched (the same `/models` call the chat models come from). The recommended one for the
     * host leads; dated snapshots (`…-2026-03-03`) hide behind their undated alias.
     */
    fun availableModels(context: Context, instance: ProviderInstance): List<String> {
        if (AgnesImages.account(instance)) {
            if (!instance.isEnabled || !AgnesImages.route(instance)) return emptyList()
            val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return emptyList()
            val key = repo.tamaOwnApiKey(instance) ?: return emptyList()
            if (!KeyDestinationGuard.permits(key, ProviderEndpoints.AGNES_BASE)) return emptyList()
            if (repo.config.value.instances.count { it.id == instance.id } != 1) return emptyList()
            val entries = repo.entriesFor(instance.id)
            return AgnesImages.models.map { it.id }.filter { id ->
                // A direct pick needs no /models response. If a row exists,
                // honor the one registered identity and its visibility.
                val matching = entries.filter { it.baseModel.id == id }
                (matching.isEmpty() || AgnesImages.registeredEntry(matching)?.isHidden == false) &&
                    !repo.tamaPaymentRefused(instance.id, id)
            }
        }
        if (freeImagesOnly(context) || freeImagesOnly(context, instance.id)) return emptyList()
        var ids = imageEntries(context, instance).map { it.model.id }.distinct()
        if (parallel) ids = ids.filter { AgnesImages.model(it) == null }
        if (instance.id == NanoMuseCloud.instance(context)?.id) {
            ids = RelayMediaPolicy.available(
                NanoMuseCloud.baseUrl(context),
                NanoMuseCloud.menuModelIds(context),
                ids,
            )
        }
        val undated = ids.filterNot { DATED.containsMatchIn(it) }.toSet()
        val shown = ids.filter { id -> !DATED.containsMatchIn(id) || DATED.replace(id, "") !in undated }
        val rec = recommendedModel(context, instance)
        return if (rec in shown) listOf(rec) + shown.filterNot { it == rec } else shown
    }

    private val DATED = Regex("-\\d{4}-\\d{2}-\\d{2}$")

    /**
     * The model nanoMuse would pick for [instance]: the catalogue's `defaults.image` for its
     * vendor (0.1.41), else the host's known default. Empty when neither knows the host.
     */
    fun recommendedModel(context: Context, instance: ProviderInstance): String =
        io.github.nanomuse.models.ModelSlots.defaultOf(context, instance, io.github.nanomuse.models.ModelSlots.Slot.IMAGE)
            ?: recommendedModel(instance)

    /** The model nanoMuse would pick on this host, list or no list. Empty when the host is unknown. */
    fun recommendedModel(instance: ProviderInstance): String {
        val base = baseUrlOf(instance)
        return when {
            AgnesImages.account(instance) -> AgnesImages.DEFAULT
            speaksDashScope(base) -> "qwen-image-3.0" // ¥0.18 a picture; the Pro tier draws the same face for ¥0.25–0.5
            base.contains("api.openai.com") -> "gpt-image-1"
            base.contains("api.x.ai") -> "grok-2-image"
            base.contains("openrouter.ai") -> "google/gemini-2.5-flash-image"
            instance.providerType == ProviderType.gemini -> "gemini-2.5-flash-image"
            else -> ""
        }
    }

    /**
     * The default when nothing was chosen: the host's recommended model if the provider lists
     * it (or lists nothing yet), otherwise the first model that draws. So a Model Studio key
     * lands on qwen-image-3.0-pro — the model the page talks about — not on whichever image
     * model happens to sort first.
     */
    fun suggestedModel(context: Context, instance: ProviderInstance): String {
        val available = availableModels(context, instance)
        val rec = recommendedModel(context, instance)
        return when {
            available.isEmpty() -> rec
            rec in available -> rec
            else -> available.first()
        }
    }

    fun speaksDashScope(baseUrl: String): Boolean = baseUrl.contains("aliyuncs.com") || baseUrl.contains("dashscope")

    fun baseUrlOf(inst: ProviderInstance): String =
        inst.effectiveBaseURL ?: when (inst.providerType) {
            ProviderType.openRouter -> "https://openrouter.ai/api/v1"
            ProviderType.xAI -> "https://api.x.ai/v1"
            ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta"
            else -> "https://api.openai.com/v1"
        }

    /**
     * The image model: the one chosen (Settings → Models, or → Image & video models); with
     * nothing chosen, in the Models page's order (0.1.41, [io.github.nanomuse.models.SlotOrder]):
     * the chat provider's own image model when the chat provider is the person's own and
     * draws, else nanoMuse Cloud's when its menu actually has one, else the first own provider
     * that draws. Tama V2 Test never replaces an explicit pick, and without a pick
     * an enabled Agnes account limits the default to Agnes.
     * [automatic] leaves the choice aside and answers what that order gives now (the picker's
     * *Automatic* row says so).
     */
    fun endpoint(context: Context, automatic: Boolean = false): Endpoint? {
        val app = context.applicationContext as? MinisApp ?: return null
        val repo = app.providerRepositoryOrNull ?: return null
        val cloudId = io.github.nanomuse.cloud.NanoMuseCloud.instance(context)?.id
        val eligible = eligibleInstances(context).filter { inst ->
            inst.id != cloudId || availableModels(context, inst).isNotEmpty()
        }
        val saved = if (automatic) null else savedChoice(context)
        val savedId = saved?.instanceId
        val savedModel = saved?.model?.takeIf { it.isNotBlank() }
        if (parallel && saved != null) {
            // The image slot is a selection, not permission to try a different provider.
            val chosen = eligible.singleOrNull { it.id == savedId } ?: return null
            val model = savedModel ?: return null
            val agnes = AgnesImages.account(chosen)
            if (!agnes && AgnesImages.model(model) != null) return null
            if ((agnes || (chosen.id != cloudId && customOpenAi(chosen))) && model !in availableModels(context, chosen)) return null
            val key = (if (agnes) repo.tamaOwnApiKey(chosen) else repo.usableApiKey(chosen)) ?: return null
            return Endpoint(chosen, key, model)
        }
        // General cost permission is not an image-model choice. Once Agnes is
        // configured, an unset slot stays on Agnes even if it is unavailable.
        val defaults = if (savedId == null && agnesAccounts(context).isNotEmpty())
            eligible.filter(AgnesImages::route) else eligible
        val inst = eligible.firstOrNull { it.id == savedId }
            ?: io.github.nanomuse.models.SlotOrder.resolve(
                chosen = null,
                chatProvider = io.github.nanomuse.models.ModelSlots.ownChatInstance(context)?.takeIf { own -> defaults.any { it.id == own.id } },
                cloud = defaults.firstOrNull { it.id == cloudId },
                firstOwn = defaults.firstOrNull { it.id != cloudId },
            )?.value
            ?: return null
        val model = savedModel?.takeIf { inst.id == savedId } ?: suggestedModel(context, inst)
        if (model.isBlank()) return null
        val key = (if (AgnesImages.account(inst)) repo.tamaOwnApiKey(inst) else repo.usableApiKey(inst)) ?: return null
        return Endpoint(inst, key, model)
    }

    @Synchronized // nanoMuse: serialize the revision with the preference write.
    fun save(context: Context, instanceId: String, model: String) {
        advanceImageChoice() // nanoMuse: even a failed write invalidates earlier attempts.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_INSTANCE, instanceId).putString(KEY_MODEL, model.trim()).apply()
    }

    /** True when the person chose an image model; false when the slot follows the automatic order. */
    fun isChosen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_INSTANCE, null) != null

    /** Forgets the choice: the slot follows the automatic order again. Nothing else moves. */
    @Synchronized // nanoMuse: clearing the choice also invalidates earlier observations.
    fun clear(context: Context) {
        advanceImageChoice()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_INSTANCE).remove(KEY_MODEL).apply()
    }

    private fun provider(context: Context, ep: Endpoint, modelId: String): LLMProvider {
        if (customOpenAi(ep.instance)) ai.tama.providers.KeyDestinationGuard.requireAllowed(true, ep.apiKey, ep.baseUrl)
        if (!ImageModelRequests.supportsModel(ep.instance.providerType, modelId)) {
            throw ImageGenException(context.getString(com.openminis.app.R.string.nm_avatar_no_model))
        }
        val app = context.applicationContext as MinisApp
        val entry = app.providerRepositoryOrNull?.config?.value?.modelEntries
            ?.firstOrNull { it.providerInstanceId == ep.instanceId && it.model.id == modelId }
        val model = entry?.model ?: LLMModel(
            id = modelId,
            displayName = modelId,
            provider = ep.instance.providerType.name,
            outputModalities = listOf("image"),
        )
        // record = false: a picture is not a chat turn, and the concrete provider is needed
        val provider = ProviderFactory.create(
            ep.instance, ep.apiKey, ImageModelRequests.modelFor(ep.instance.providerType, model),
            context, record = false,
        )
        return provider.takeIf { it is OpenAIProvider || it is GeminiProvider }
            ?: throw ImageGenException(context.getString(com.openminis.app.R.string.nm_avatar_no_model))
    }

    suspend fun generate(
        context: Context, ep: Endpoint, prompt: String, size: String = "1024x1024", allowFormatRetry: Boolean = true,
        // nanoMuse: optional caller authorization, checked again at network dispatch.
        beforeRequest: (() -> Unit)? = null,
    ): Bitmap = withContext(Dispatchers.IO) {
        if (AgnesImages.account(ep.instance)) return@withContext agnes(context, ep, prompt, size, emptyList(), beforeRequest)
        if (parallel && AgnesImages.model(ep.model) != null) throw CustomImageRoute.Rejected()
        // nanoMuse: image selection and per-image permission are not price evidence.
        requireImageCostScope(context, ep)
        ai.tama.pricing.ModelCostGuard.requireAllowed()
        val costChecked = if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") ({
            requireImageCostScope(context, ep)
            beforeRequest?.invoke()
            Unit
        }) else beforeRequest
        if (ep.model.isBlank()) throw ImageGenException("No image model set")
        // Model Studio's OpenAI-compatible host has no `images/generations` (the public one
        // answers 404 to every model); its native endpoint draws with the same key.
        if (ep.isDashScope) return@withContext generateDashScope(ep, prompt, size, singleAttempt = !allowFormatRetry, beforeRequest = costChecked)
        val response = try {
            ImageModelRequests.request(provider(context, ep, ep.model), prompt, size,
                allowFormatRetry = allowFormatRetry && !customOpenAi(ep.instance), beforeRequest = costChecked)
        } catch (e: CustomImageRoute.Rejected) {
            throw e
        } catch (e: com.openminis.app.data.model.LLMError.ProviderError) {
            if (customOpenAi(ep.instance) && (e.nmProviderReach?.httpStatus in setOf(404, 405, 501) ||
                    e.detail == "[404] /images/generations not supported (got chat completions response)"))
                throw CustomImageRoute.Rejected()
            throw ImageGenException(e.message ?: e.javaClass.simpleName)
        } catch (e: ImageGenException) {
            throw e
        } catch (e: ai.tama.media.OwnKeyImagePolicy.NoVerifiedFreeGenerator) {
            throw e // nanoMuse: keep the local free-only refusal distinct.
        } catch (e: ai.tama.media.AutomaticAvatarGuard.Blocked) {
            throw e
        } catch (e: ai.tama.media.ImageDispatch.AuthorizationChanged) {
            throw e // nanoMuse: preserve a route refusal from the transport preflight.
        } catch (e: ai.tama.media.ImageAttemptTracker.Stopped) {
            throw e // nanoMuse: distinguish a registered Stop before and after possible submission.
        } catch (e: ai.tama.pricing.ModelCostGuard.Blocked) {
            throw e // nanoMuse: keep a local cost refusal out of network-retry handling.
        } catch (e: CancellationException) {
            throw e // the caller was cancelled; not a failure of the picture (#337)
        } catch (e: Exception) {
            throw ImageGenException(e.message ?: e.javaClass.simpleName)
        }
        val bytes = response.mediaAttachments.firstOrNull()?.data
            ?: throw ImageGenException(response.text.take(200).ifBlank { "No image in response" })
        decode(bytes)
    }

    // nanoMuse: a caller can prohibit automatic retries of an edit that may already be charged.
    suspend fun edit(
        context: Context, ep: Endpoint, image: Bitmap, instruction: String, allowFormatRetry: Boolean = true,
        // nanoMuse: optional caller authorization, checked again at network dispatch.
        beforeRequest: (() -> Unit)? = null,
    ): Bitmap = withContext(Dispatchers.IO) {
        if (AgnesImages.account(ep.instance)) return@withContext agnes(context, ep, instruction, "1024x1024", listOf(pngBytes(image)), beforeRequest)
        if (parallel && AgnesImages.model(ep.model) != null) throw CustomImageRoute.Rejected()
        // nanoMuse: editing can have its own price even when chat is advertised free.
        requireImageCostScope(context, ep)
        ai.tama.pricing.ModelCostGuard.requireAllowed()
        val costChecked = if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") ({
            requireImageCostScope(context, ep)
            beforeRequest?.invoke()
            Unit
        }) else beforeRequest
        if (ep.isDashScope) return@withContext editDashScope(ep, image, instruction, singleAttempt = !allowFormatRetry, beforeRequest = costChecked)
        val png = pngBytes(image)
        val response = try {
            // gpt-image-* edits with the same model; other hosts get the configured one.
            ImageModelRequests.request(
                provider = provider(context, ep, ep.model),
                prompt = instruction,
                images = listOf(LLMMessage.ImagePart(data = png, mimeType = "image/png")),
                size = "1024x1024",
                allowFormatRetry = allowFormatRetry,
                beforeRequest = costChecked,
            )
        } catch (e: ai.tama.media.OwnKeyImagePolicy.NoVerifiedFreeGenerator) {
            throw e // nanoMuse: keep the local free-only refusal distinct.
        } catch (e: ai.tama.media.AutomaticAvatarGuard.Blocked) {
            throw e
        } catch (e: ai.tama.media.ImageDispatch.AuthorizationChanged) {
            throw e // nanoMuse: preserve a route refusal from the transport preflight.
        } catch (e: ai.tama.media.ImageAttemptTracker.Stopped) {
            throw e // nanoMuse: a stopped edit is not a generic provider failure.
        } catch (e: ai.tama.pricing.ModelCostGuard.Blocked) {
            throw e // nanoMuse: a cost refusal must not be treated as an image transport error.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ImageGenException(e.message ?: e.javaClass.simpleName)
        }
        val bytes = response.mediaAttachments.firstOrNull()?.data
            ?: throw ImageGenException(response.text.take(200).ifBlank { "No image in edit response" })
        decode(bytes)
    }

    private val agnesApi by lazy { AgnesImageApi(http) }

    internal suspend fun editAgnes(
        context: Context, ep: Endpoint, prompt: String, size: String, references: List<ByteArray>, beforeRequest: (() -> Unit)?,
    ): Bitmap = withContext(Dispatchers.IO) { agnes(context, ep, prompt, size, references, beforeRequest) }

    private suspend fun agnes(
        context: Context, ep: Endpoint, prompt: String, size: String, references: List<ByteArray>, beforeRequest: (() -> Unit)?,
    ): Bitmap {
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: throw ImageDispatch.AuthorizationChanged()
        val snapshot = repo.nmOAuthKeySnapshot(ep.instanceId)
        fun requireCurrent() {
            if (repo.tamaPaymentRefused(ep.instanceId, ep.model)) throw AgnesImageApi.Failure(AgnesImageApi.Problem.PAYMENT)
            if (snapshot.key != ep.apiKey || !repo.nmOAuthKeyStillCurrent(snapshot) || !declaredFree(context, ep))
                throw ImageDispatch.AuthorizationChanged()
        }
        requireCurrent()
        val bytes = try {
            agnesApi.request(ep.apiKey, ep.model, prompt, size, references) {
                requireCurrent()
                KeyDestinationGuard.requireAllowed(true, ep.apiKey, ProviderEndpoints.AGNES_BASE)
                beforeRequest?.invoke()
            }
        } catch (e: AgnesImageApi.Failure) {
            if (e.problem == AgnesImageApi.Problem.PAYMENT) repo.tamaRecordPaymentRefusal(ep.instanceId, ep.model, snapshot.version)
            throw e
        }
        requireCurrent()
        // Keep the provider's actual dimensions. Chat displays the result as
        // returned; the avatar uses its existing square framing on screen.
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw AgnesImageApi.Failure(AgnesImageApi.Problem.NO_IMAGE)
    }

    /**
     * DashScope text-to-image: the native multimodal endpoint with a text-only turn. qwen-image
     * (2.x, 3.x, plus, max) and wan-image all take `size` as `W*H`; prompt extension stays off
     * so the avatar prompts are drawn as written.
     */
    private fun generateDashScope(
        ep: Endpoint, prompt: String, size: String, singleAttempt: Boolean, beforeRequest: (() -> Unit)?,
    ): Bitmap {
        val parameters = JSONObject().put("size", size.replace('x', '*')).put("watermark", false)
        if (ep.model.startsWith("qwen-image")) parameters.put("prompt_extend", false)
        val content = JSONArray().put(JSONObject().put("text", prompt))
        return callDashScope(ep, ep.model, content, parameters, what = "generation", singleAttempt = singleAttempt, beforeRequest = beforeRequest)
    }

    /** DashScope image editing: same host and key, native path, data-URI input. */
    private fun editDashScope(
        ep: Endpoint, image: Bitmap, instruction: String, singleAttempt: Boolean, beforeRequest: (() -> Unit)?,
    ): Bitmap {
        // qwen-image-3.x, wan-image and the qwen-image-edit-* models take a picture themselves;
        // an older text-only qwen-image is posed by qwen-image-edit-max on the same key.
        val threeX = ep.model.startsWith("qwen-image-3") || ep.model.startsWith("wan")
        val model = if (threeX || ep.model.contains("edit")) ep.model else "qwen-image-edit-max"
        // nanoMuse: the implicit edit sibling can have a different price and approval.
        ai.tama.media.MediaModelChangeGuard.requireSame(
            com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test", ep.model, model,
        )
        val parameters = if (threeX) JSONObject().put("size", "1024*1024").put("prompt_extend", false).put("watermark", false)
        else JSONObject().put("n", 1).put("watermark", false)
        val content = JSONArray()
            .put(JSONObject().put("image", "data:image/png;base64," + Base64.encodeToString(pngBytes(image), Base64.NO_WRAP)))
            .put(JSONObject().put("text", instruction))
        return callDashScope(ep, model, content, parameters, what = "edit", singleAttempt = singleAttempt, beforeRequest = beforeRequest)
    }

    /** One call to `multimodal-generation/generation`; returns the first image of the reply. */
    private fun callDashScope(
        ep: Endpoint, model: String, content: JSONArray, parameters: JSONObject, what: String,
        singleAttempt: Boolean = false, beforeRequest: (() -> Unit)? = null,
    ): Bitmap {
        val host = ep.baseUrl.substringBefore("/compatible-mode").substringBefore("/api/v1").trimEnd('/')
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content))))
            .put("parameters", parameters)
        val requestBody = body.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$host/api/v1/services/aigc/multimodal-generation/generation")
            .header("Authorization", "Bearer ${ep.apiKey}")
            .header("Content-Type", "application/json")
            .post(if (singleAttempt) requestBody.singleUseImageBody() else requestBody)
            .build()
        val submitClient = if (singleAttempt) http.singleUseImageClient() else http
        // nanoMuse: the native image endpoint bypasses ProviderFactory, so check at its POST.
        beforeRequest?.invoke()
        ai.tama.pricing.ModelCostGuard.requireAllowed()
        val json = submitClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // Provider replies may echo prompts or credentials. Never log the body;
                // retain only a bounded, decoded message with this request's key removed.
                val msg = io.github.nanomuse.net.ProviderReach.diagnosticMessage(
                    text, maxChars = 300, credentials = listOf(ep.apiKey),
                )
                AppLogger.warning(TAG, "DashScope $what HTTP ${resp.code}")
                throw ImageGenException("HTTP ${resp.code}" + (msg.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
            }
            runCatching { JSONObject(text) }.getOrElse { throw ImageGenException("Unreadable $what response") }
        }
        val url = json.optJSONObject("output")?.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optJSONArray("content")?.optJSONObject(0)?.optString("image")
            ?.takeIf { it.isNotBlank() }
            ?: throw ImageGenException(json.optString("message").ifBlank { "Empty $what response" })
        // nanoMuse: revocation stops the follow-up download, not the already submitted image.
        if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") {
            beforeRequest?.invoke()
            ai.tama.pricing.ModelCostGuard.requireAllowed()
        }
        // nanoMuse: do not follow an unapproved redirect or retry the result URL in the parallel app.
        val downloadClient = if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
            http.singleUseImageClient() else http
        val bytes = downloadClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw ImageGenException("Image download failed (${r.code})")
            r.body?.bytes() ?: throw ImageGenException("Empty image")
        }
        return decode(bytes)
    }

    private fun decode(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw ImageGenException("Undecodable image")

    private fun pngBytes(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
