package ai.tama.media
import android.content.Context
import ai.tama.router.AutoModelStore
import io.github.nanomuse.avatar.ImageGen
internal object AutoImageSelection {
 data class Selection(val entryId:String,val providerId:String,val modelId:String,val identity:AutoModelStore.Identity,val accountVersion:Long,val reviewVersion:Long?=null)
 fun choose(context:Context, freeOnly:Boolean = false, excluded:Set<Pair<String,String>> = emptySet()):Selection? = null
 fun endpointIfStillAllowed(context:Context, selection:Selection):ImageGen.Endpoint? = null
 data class AgnesSetup(val problem:CustomImageRoute.Problem?)
 fun agnesSetup(context:Context):AgnesSetup? = null
 fun hasRegisteredImage(context:Context):Boolean = false
}
