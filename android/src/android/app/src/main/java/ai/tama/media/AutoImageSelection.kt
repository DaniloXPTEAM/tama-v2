package ai.tama.media

import io.github.nanomuse.media.*

import android.content.Context
import com.openminis.app.BuildConfig
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.avatar.ImageModelRequests
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.cloud.ProviderCatalogue
import ai.tama.router.AutoImageRouter
import ai.tama.router.AutoModelRuntime
import ai.tama.router.AutoModelStore
import ai.tama.router.Need

/** Agent picture calls use a separately approved, exact image model and transport. */
internal object AutoImageSelection {
    private val parallel: Boolean get() = BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"

    data class Selection(
        val entryId: String,
        val providerId: String,
        val modelId: String,
        val identity: AutoModelStore.Identity,
        val accountVersion: Long,
        // The parallel app must not revive a selection after consent or capability ABA.
        val reviewVersion: Long? = null,
        val slotRevision: Long? = null,
        val freeOnly: Boolean = false,
        val paymentRefused: Set<Pair<String, String>> = emptySet(),
    )

    private data class Entry(
        val candidate: AutoImageRouter.Candidate,
        val model: ModelEntry,
        val instance: ProviderInstance,
        val identity: AutoModelStore.Identity,
    )

    private fun entries(context: Context, repo: ProviderRepository, freeOnly: Boolean = false): List<Entry> {
        val config = repo.config.value
        val eligible = ImageGen.eligibleInstances(context).mapTo(mutableSetOf()) { it.id }
        val cloudId = NanoMuseCloud.instance(context)?.id
        val menu = if (cloudId == null) emptySet() else NanoMuseCloud.menuModelIds(context).toSet()
        val catalogue = ProviderCatalogue.load(context)
        val allowUnverifiedCost = !freeOnly && ai.tama.router.AutomaticCostGuard.permits(context)
        // ImageGen resolves model metadata by provider/model, so duplicates could
        // otherwise use a different entry from the one the person approved.
        val duplicateProviders = config.instances.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        val agnesIds = config.instances.filter(AgnesImages::account).mapTo(mutableSetOf()) { it.id }
        val catalogueEntries = config.modelEntries.filterNot { it.providerInstanceId in agnesIds } +
            agnesIds.flatMap { providerId ->
                val models = config.modelEntries.filter { it.providerInstanceId == providerId }
                models.groupBy { it.baseModel.id }.values.mapNotNull(AgnesImages::registeredEntry)
            }
        val duplicateModels = catalogueEntries.groupingBy { it.providerInstanceId to it.model.id }
            .eachCount().filterValues { it > 1 }.keys
        val duplicateEntryIds = config.modelEntries.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        return catalogueEntries.mapNotNull { entry ->
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: return@mapNotNull null
            val identity = AutoModelStore.identity(entry, instance)
            val vendor = ProviderCatalogue.match(catalogue, instance)
            val id = entry.model.id
            val agnes = AgnesImages.account(instance)
            val confirmedByRoute = AgnesImages.route(instance) && AgnesImages.model(id) != null
            val capabilities = AutoModelStore.capabilities(context, identity, if (confirmedByRoute) setOf("image_gen")
                else vendor?.models?.get(id)?.capabilities.orEmpty())
            val priceAllowed = if (agnes) confirmedByRoute && id in ImageGen.availableModels(context, instance)
                else allowUnverifiedCost && (!parallel || AgnesImages.model(id) == null)
            val supported = priceAllowed && instance.id !in duplicateProviders && (instance.id to id) !in duplicateModels &&
                (!parallel || entry.id !in duplicateEntryIds) &&
                instance.id in eligible && ImageModelRequests.supportsAutomaticGeneration(instance, id) &&
                (instance.id != cloudId || id in menu)
            val candidate = AutoImageRouter.Candidate(
                entryId = entry.id, providerId = instance.id, modelId = id,
                enabled = instance.isEnabled, visible = !entry.isHidden,
                // An explicitly keyless compatible endpoint is represented by an empty string.
                credentialed = (if (agnes) repo.tamaOwnApiKey(instance) else repo.usableApiKey(instance)) != null,
                supportedTransport = supported,
                confirmedImageGeneration = Need.IMAGE_GENERATION in capabilities,
                imageApproved = AutoModelStore.imageApproved(context, identity),
            )
            Entry(candidate, entry, instance, identity)
        }
    }

    /** Distinguish no configured image model from a configured but unapproved one. */
    fun hasRegisteredImage(context: Context): Boolean {
        val app = context.applicationContext as? MinisApp ?: return false
        val repo = app.providerRepositoryOrNull ?: return false
        return entries(context, repo).any {
            it.candidate.confirmedImageGeneration || "image" in it.model.model.outputModalities.orEmpty() ||
                ImageGen.looksLikeImageModel(it.model.model.id)
        }
    }

    // This is the same exact Agnes identity used by entries(), with no alternative
    // provider or approval guessed from the name. A direct Manual pick can exist
    // without a registered row, but only a registered entry can carry Auto consent.
    data class AgnesSetup(
        val providerId: String, val modelId: String, val entryId: String?, val problem: CustomImageRoute.Problem?,
        val slotRevision: Long, val accountVersion: Long,
    )

    fun agnesSetup(context: Context): AgnesSetup? {
        if (!parallel) return null
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return null
        val revision = ImageGen.imageChoiceRevision()
        val endpoint = ImageGen.endpoint(context) ?: return null
        if (!AgnesImages.route(endpoint.instance) || AgnesImages.model(endpoint.model) == null) return null
        val config = repo.config.value
        val live = config.instances.singleOrNull { it.id == endpoint.instanceId }?.takeIf { it == endpoint.instance } ?: return null
        val version = AutoModelRuntime.accountVersion(live.id)
        val matching = config.modelEntries.filter { it.providerInstanceId == live.id && it.baseModel.id == endpoint.model }
        val entry = AgnesImages.registeredEntry(matching)
        val problem = when {
            matching.isEmpty() -> CustomImageRoute.Problem.AGNES_IMAGE_NEEDS_ENTRY
            entry == null || entry.model.id != endpoint.model || config.modelEntries.count { it.id == entry.id } != 1 ->
                CustomImageRoute.Problem.AGNES_IMAGE_AMBIGUOUS
            entry.isHidden -> return null
            else -> {
                val (approved, override) = AutoModelStore.inspectImageReview(context, AutoModelStore.identity(entry, live))
                when {
                    override == false -> CustomImageRoute.Problem.AGNES_IMAGE_CAPABILITY_OFF
                    !approved -> CustomImageRoute.Problem.AGNES_IMAGE_NEEDS_APPROVAL
                    else -> null
                }
            }
        }
        if (revision != ImageGen.imageChoiceRevision() || version != AutoModelRuntime.accountVersion(live.id)) return null
        return AgnesSetup(live.id, endpoint.model, entry?.id, problem, revision, version)
    }

    /** Only a tap on Review can restore a missing row. Opening it does not grant consent. */
    fun prepareAgnesReview(context: Context, expected: AgnesSetup): String? {
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return null
        if (agnesSetup(context) != expected || expected.problem == CustomImageRoute.Problem.AGNES_IMAGE_AMBIGUOUS) return null
        expected.entryId?.let { return it }
        val restored = repo.nmRegisterAgnesImage(expected.providerId, expected.modelId, expected.accountVersion) ?: return null
        return restored.takeIf {
            val live = agnesSetup(context)
            live == expected.copy(entryId = restored, problem = CustomImageRoute.Problem.AGNES_IMAGE_NEEDS_APPROVAL)
        }
    }

    private fun candidates(
        context: Context, repo: ProviderRepository, freeOnly: Boolean, excluded: Set<Pair<String, String>>,
    ): List<Entry> {
        val candidates = entries(context, repo, freeOnly).filter { (it.instance.id to it.model.model.id) !in excluded }
        if (!parallel) return candidates
        val saved = ImageGen.savedChoice(context)
        if (saved != null) {
            // Only a payment refusal of this exact pick can open a second,
            // already approved Agnes image on the same account. Never a paid sibling.
            val afterPayment = freeOnly && (saved.instanceId to saved.model) in excluded &&
                repo.tamaPaymentRefused(saved.instanceId, saved.model)
            return candidates.filter {
                it.instance.id == saved.instanceId &&
                    (if (afterPayment) AgnesImages.route(it.instance) else it.model.model.id == saved.model)
            }
        }
        return if (ImageGen.agnesAccounts(context).isNotEmpty()) candidates.filter { AgnesImages.route(it.instance) }
            else candidates
    }

    /** Does not make a network call or retain credentials. */
    fun choose(context: Context, freeOnly: Boolean = false, excluded: Set<Pair<String, String>> = emptySet()): Selection? {
        if (!AutoModelStore.enabled(context)) return null
        val app = context.applicationContext as? MinisApp ?: return null
        val repo = app.providerRepositoryOrNull ?: return null
        val revision = if (parallel) ImageGen.imageChoiceRevision() else null
        val candidates = candidates(context, repo, freeOnly, excluded)
        val preferred = ImageGen.endpoint(context)
        val choice = AutoImageRouter.pick(candidates.map { it.candidate }, preferred?.instanceId, preferred?.model)
            as? AutoImageRouter.Choice.Selected ?: return null
        val selected = candidates.first { it.candidate.entryId == choice.candidate.entryId }
        if (revision != null && revision != ImageGen.imageChoiceRevision()) return null
        val review = if (parallel) {
            AutoModelStore.review(context, selected.identity).takeIf { it.imageApproved } ?: return null
        } else null
        return Selection(selected.candidate.entryId, selected.instance.id, selected.model.model.id,
            selected.identity, review?.accountVersion ?: AutoModelRuntime.accountVersion(selected.instance.id),
            review?.reviewVersion, revision, freeOnly, excluded.toSet())
    }

    /** Re-read route, consent, capability, account generation and the current key before sending. */
    fun endpointIfStillAllowed(context: Context, selection: Selection): ImageGen.Endpoint? {
        if (!AutoModelStore.enabled(context)) return null
        fun reviewCurrent(): Boolean = !parallel || (selection.slotRevision == ImageGen.imageChoiceRevision() &&
            AutoModelStore.imageReviewCurrent(context, selection.identity, selection.accountVersion, selection.reviewVersion))
        if (!reviewCurrent()) return null
        val app = context.applicationContext as? MinisApp ?: return null
        val repo = app.providerRepositoryOrNull ?: return null
        val candidates = candidates(context, repo, selection.freeOnly, selection.paymentRefused)
        val choice = AutoImageRouter.pick(candidates.map { it.candidate }, selection.providerId, selection.modelId)
            as? AutoImageRouter.Choice.Selected ?: return null
        if (choice.candidate.entryId != selection.entryId) return null
        val current = candidates.first { it.candidate.entryId == selection.entryId }
        if (current.identity != selection.identity || current.instance.id != selection.providerId ||
            current.model.model.id != selection.modelId ||
            AutoModelRuntime.accountVersion(selection.providerId) != selection.accountVersion) return null
        val key = (if (AgnesImages.account(current.instance)) repo.tamaOwnApiKey(current.instance)
            else repo.usableApiKey(current.instance)) ?: return null
        val endpoint = ImageGen.Endpoint(current.instance.copy(), key, selection.modelId)
        if (!AutoModelStore.enabled(context) ||
            !(ImageGen.declaredFree(context, endpoint) || ai.tama.router.AutomaticCostGuard.permits(context)) ||
            !AutoModelStore.imageApproved(context, current.identity) || !reviewCurrent() ||
            AutoModelRuntime.accountVersion(selection.providerId) != selection.accountVersion) return null
        // The repository instance is mutable. Dispatch uses this snapshot rather
        // than following later UI edits to a route that was not approved.
        return endpoint
    }
}
