package io.github.nanomuse.avatar
import android.content.Context
import android.graphics.Bitmap
import com.openminis.app.data.model.ProviderInstance
object ImageGen {
 data class Endpoint(val instance:ProviderInstance, val apiKey:String, val model:String) {
  val instanceId get() = instance.id
  val label get() = instance.label
 }
 fun freeImagesOnly(context:Context, instanceId:String? = null) = false
 fun hasDeclaredFreeImage(context:Context) = false
 fun declaredFree(context:Context, endpoint:Endpoint) = false
 suspend fun editAgnes(context:Context, endpoint:Endpoint, prompt:String, size:String, references:List<ByteArray>, beforeRequest:(()->Unit)?):Bitmap = error("not used by host test")
 fun noFreeImageAvailable(context:Context) = false
 internal fun selectionProblem(context:Context):ai.tama.media.CustomImageRoute.Problem? = null
 fun imageChoiceRevision():Long = 0
 suspend fun generate(context:Context, endpoint:Endpoint, prompt:String, size:String, allowFormatRetry:Boolean, beforeRequest:(()->Unit)?):Bitmap = error("not used by host test")
 suspend fun edit(context:Context, endpoint:Endpoint, reference:Bitmap, prompt:String, allowFormatRetry:Boolean, beforeRequest:(()->Unit)?):Bitmap = error("not used by host test")
}
