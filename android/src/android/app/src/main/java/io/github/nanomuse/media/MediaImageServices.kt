package io.github.nanomuse.media

import android.content.Context
import android.graphics.Bitmap
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.router.AutoModelStore
import java.io.OutputStream

/** Keeps the Android services at the edge of the testable image dispatch gate. */
internal interface MediaImageServices {
    fun automatic(): Boolean
    fun choose(): AutoImageSelection.Selection?
    fun revalidate(selection: AutoImageSelection.Selection): ImageGen.Endpoint?
    fun manualEndpoint(): ImageGen.Endpoint?
    fun agentEndpoint(): ImageGen.Endpoint?
    fun hasConfiguredImage(): Boolean = manualEndpoint() != null
    suspend fun generate(endpoint: ImageGen.Endpoint, prompt: String, size: String, allowFormatRetry: Boolean): MediaPicture
    suspend fun edit(endpoint: ImageGen.Endpoint, reference: Bitmap, prompt: String): MediaPicture
}

internal fun interface MediaPicture {
    fun writeTo(output: OutputStream): Boolean
}

internal class AndroidMediaImageServices(private val context: Context) : MediaImageServices {
    override fun automatic() = AutoModelStore.enabled(context)
    override fun choose() = AutoImageSelection.choose(context)
    override fun revalidate(selection: AutoImageSelection.Selection) = AutoImageSelection.endpointIfStillAllowed(context, selection)
    // ProviderInstance is mutable. Snapshot it so a route changed during a
    // permission dialog cannot compare equal merely because both endpoints
    // reference the same object.
    override fun manualEndpoint() = MediaModels.imageEndpoint(context)?.let { it.copy(instance = it.instance.copy()) }
    override fun agentEndpoint() = MediaModels.agentImageEndpoint(context)
    override fun hasConfiguredImage() = manualEndpoint() != null || AutoImageSelection.hasRegisteredImage(context)

    override suspend fun generate(endpoint: ImageGen.Endpoint, prompt: String, size: String, allowFormatRetry: Boolean): MediaPicture =
        picture(ImageGen.generate(context, endpoint, prompt, size, allowFormatRetry))

    override suspend fun edit(endpoint: ImageGen.Endpoint, reference: Bitmap, prompt: String): MediaPicture =
        picture(ImageGen.edit(context, endpoint, reference, prompt))

    private fun picture(bitmap: Bitmap) = MediaPicture { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
}
