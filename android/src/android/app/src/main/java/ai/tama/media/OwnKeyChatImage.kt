package ai.tama.media

import android.content.Context
import com.openminis.app.data.repository.ProviderRepository
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.avatar.ImageModelRequests
import io.github.nanomuse.cloud.ProviderCatalogue

/** Explains an existing cost refusal; never changes media selection or permission. */
internal object OwnKeyChatImage {
    fun costFailureCode(context: Context): String {
        val app = context.applicationContext as? com.openminis.app.MinisApp
        val repo: ProviderRepository = app?.providerRepositoryOrNull ?: return "no_verified_free_image_generator"
        val vendors = ProviderCatalogue.load(context)
        val accounts = ImageGen.eligibleInstances(context).filter { repo.tamaOwnApiKey(it) != null }
            .associateBy { it.id }
        val hasGenerator = repo.config.value.modelEntries.any { entry ->
            val instance = accounts[entry.providerInstanceId] ?: return@any false
            val model = entry.model
            val exact = ProviderCatalogue.match(vendors, instance)?.models?.get(model.id)?.capabilities.orEmpty()
            !entry.isHidden && OwnKeyImagePolicy.generates(
                ImageModelRequests.supportsAutomaticGeneration(instance, model.id), model.outputModalities, exact,
            )
        }
        return when (OwnKeyImagePolicy.reason(hasGenerator)) {
            OwnKeyImagePolicy.Reason.NO_GENERATOR -> "no_confirmed_image_generator"
            OwnKeyImagePolicy.Reason.NO_VERIFIED_FREE_GENERATOR -> "no_verified_free_image_generator"
        }
    }
}
