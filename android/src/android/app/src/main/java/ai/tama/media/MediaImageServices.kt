package ai.tama.media

import io.github.nanomuse.media.*

import android.content.Context
import android.graphics.Bitmap
import com.openminis.app.MinisApp
import io.github.nanomuse.avatar.ImageGen
import ai.tama.router.AutoModelStore
import java.io.OutputStream

/** Keeps the Android services at the edge of the testable image dispatch gate. */
internal interface MediaImageServices {
    fun automatic(): Boolean
    fun automaticCostAllowed(): Boolean
    /** Process-local revision of automatic mode and its separate cost setting. */
    fun automaticPolicyRevision(): Long
    /** Process-local revision of the image slot, including changes away and back. */
    fun imageChoiceRevision(): Long
    /** Changes for any account while the route is being resolved, not only the selected one. */
    fun accountRevision(): Long
    /** The dispatcher must check this independently from route and image approval. */
    fun freeImagesOnly(endpoint: ImageGen.Endpoint? = null): Boolean = false
    suspend fun observeCliImageScope(sessionId: String?): () -> CliImageScope.State =
        { CliImageScope.State.UNRESTRICTED }
    fun modelCostAllowed(): Boolean
    /** Revocation witness spanning a single attempt; it is not a permission. */
    fun observeModelCost(): () -> Boolean
    fun choose(): AutoImageSelection.Selection?
    fun revalidate(selection: AutoImageSelection.Selection): ImageGen.Endpoint?
    fun manualEndpoint(): ImageGen.Endpoint?
    fun agentEndpoint(): ImageGen.Endpoint?
    fun hasConfiguredImage(): Boolean = manualEndpoint() != null
    /** Called at the transport boundary and, for URL results, before download. */
    suspend fun generate(
        endpoint: ImageGen.Endpoint, prompt: String, size: String, allowFormatRetry: Boolean,
        beforeRequest: (() -> Unit)? = null,
    ): MediaPicture
    suspend fun edit(
        endpoint: ImageGen.Endpoint, reference: Bitmap, prompt: String,
        beforeRequest: (() -> Unit)? = null,
    ): MediaPicture
}

internal fun interface MediaPicture {
    fun writeTo(output: OutputStream): Boolean
}

internal class AndroidMediaImageServices(private val context: Context) : MediaImageServices {
    override fun automatic() = AutoModelStore.enabled(context)
    override fun automaticCostAllowed() = ai.tama.router.AutomaticCostGuard.permits(context)
    override fun automaticPolicyRevision() = AutoModelStore.automaticPolicyRevision()
    override fun imageChoiceRevision() = ImageGen.imageChoiceRevision()
    override fun accountRevision() = ai.tama.router.AutoModelRuntime.accountRevision()
    override fun freeImagesOnly(endpoint: ImageGen.Endpoint?) =
        ImageGen.freeImagesOnly(context) || if (endpoint == null) ImageGen.noFreeImageAvailable(context)
        else ImageGen.freeImagesOnly(context, endpoint.instanceId)
    override suspend fun observeCliImageScope(sessionId: String?): () -> CliImageScope.State {
        val changed = CliImageScope.State.CHANGED
        val app = context.applicationContext as? MinisApp ?: return { changed }
        val providers = app.providerRepositoryOrNull ?: return { changed }
        val chat = app.chatRepositoryOrNull ?: return { changed }
        val (binding, current) = chat.nmObserveBinding(sessionId ?: return { changed }) ?: return { changed }
        val accounts = CliImageScope.chatAccounts(binding, providers.config.value)
        return {
            val config = providers.config.value
            when {
                !current() || accounts.isEmpty() || accounts != CliImageScope.chatAccounts(binding, config) ||
                    accounts.any { id -> config.instances.none { it.id == id } } -> changed
                config.instances.any { it.id in accounts && providers.tamaFreeChatOnly(it) } -> CliImageScope.State.FREE_ONLY
                else -> CliImageScope.State.UNRESTRICTED
            }
        }
    }
    override fun modelCostAllowed() = ai.tama.pricing.ModelCostGuard.permits()
    override fun observeModelCost(): () -> Boolean {
        val observation = ai.tama.pricing.ModelCostGuard.observe()
        return observation::isCurrent
    }
    override fun choose() = AutoImageSelection.choose(context)
    override fun revalidate(selection: AutoImageSelection.Selection) = AutoImageSelection.endpointIfStillAllowed(context, selection)
    // ProviderInstance is mutable. Snapshot it so a route changed during a
    // permission dialog cannot compare equal merely because both endpoints
    // reference the same object.
    override fun manualEndpoint() = MediaModels.imageEndpoint(context)?.let { it.copy(instance = it.instance.copy()) }
    override fun agentEndpoint() = MediaModels.agentImageEndpoint(context)
    override fun hasConfiguredImage() = manualEndpoint() != null || AutoImageSelection.hasRegisteredImage(context)

    override suspend fun generate(
        endpoint: ImageGen.Endpoint, prompt: String, size: String, allowFormatRetry: Boolean,
        beforeRequest: (() -> Unit)?,
    ): MediaPicture = picture(ImageGen.generate(context, endpoint, prompt, size, allowFormatRetry, beforeRequest))

    override suspend fun edit(
        endpoint: ImageGen.Endpoint, reference: Bitmap, prompt: String, beforeRequest: (() -> Unit)?,
    ): MediaPicture = picture(ImageGen.edit(context, endpoint, reference, prompt,
        allowFormatRetry = com.openminis.app.BuildConfig.APPLICATION_ID != "ai.tama.assistant.tamav2test",
        beforeRequest = beforeRequest))

    private fun picture(bitmap: Bitmap) = MediaPicture { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
}
