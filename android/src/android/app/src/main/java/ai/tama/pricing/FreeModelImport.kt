package ai.tama.pricing

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderType

/** An import filter, not permission to make a request or a guarantee of its cost. */
internal object FreeModelImport {
    // Other catalogues and free tiers do not establish prices for this account.
    fun supports(type: ProviderType): Boolean = type == ProviderType.openRouter

    fun eligible(
        models: List<LLMModel>,
        catalogue: OpenRouterPricing.Catalogue?,
        nowMillis: Long,
    ): List<LLMModel> = models.distinctBy { it.id }.filter {
        OpenRouterPricing.chatStatus(catalogue, it.id, nowMillis) == OpenRouterPricing.Status.ADVERTISED_FREE
    }

    /** Image evidence is counted separately and can never qualify an ID for free chat. */
    fun summarize(visibleIds: Set<String>, eligibleChatIds: Set<String>, added: Int,
                  declaredImageIds: Set<String>? = null): FreeModelImportStatus.Complete {
        val images = visibleIds.intersect(declaredImageIds.orEmpty())
        val chat = (visibleIds - images).count { it in eligibleChatIds }
        return FreeModelImportStatus.Complete(chat, added, visibleIds.size - chat - images.size,
            images.size.takeIf { declaredImageIds != null })
    }

    /** Existing entries, customizations, groups and pins are deliberately untouched. */
    fun additions(instanceId: String, existing: List<ModelEntry>, eligible: List<LLMModel>): List<ModelEntry> {
        val present = existing.filter { it.providerInstanceId == instanceId }
            .mapTo(mutableSetOf()) { it.baseModel.id }
        return eligible.mapNotNull { model ->
            if (present.add(model.id)) ModelEntry(providerInstanceId = instanceId, baseModel = model) else null
        }
    }
}

/** Process-local status; it contains no keys, endpoint URLs or model names. */
sealed interface FreeModelImportStatus {
    data object Checking : FreeModelImportStatus
    data object Unsupported : FreeModelImportStatus
    data object MissingKey : FreeModelImportStatus
    data object Failed : FreeModelImportStatus
    data object SaveFailed : FreeModelImportStatus
    data class Complete(
        val eligible: Int, val added: Int, val unconfirmed: Int = 0,
        val declaredImages: Int? = null,
    ) : FreeModelImportStatus
}

/**
 * A later refresh or mode change invalidates an older in-flight result, including
 * an off → on → off transition. Callers also compare the current route/mode
 * while holding the provider configuration lock before committing a result.
 */
internal class ModelRefreshGate {
    class Ticket internal constructor(
        val providerId: String,
        val sequence: Long,
        val freeOnly: Boolean,
        val route: String,
    )

    private var sequence = 0L
    private val latest = mutableMapOf<String, Long>()

    @Synchronized
    fun begin(providerId: String, freeOnly: Boolean, route: String): Ticket {
        check(sequence < Long.MAX_VALUE) { "model refresh sequence exhausted" }
        sequence += 1
        latest[providerId] = sequence
        return Ticket(providerId, sequence, freeOnly, route)
    }

    @Synchronized
    fun invalidate(providerId: String) {
        latest.remove(providerId)
    }

    @Synchronized
    fun invalidateAll() {
        latest.clear()
    }

    @Synchronized
    fun isCurrent(ticket: Ticket, freeOnly: Boolean, route: String): Boolean =
        latest[ticket.providerId] == ticket.sequence && ticket.freeOnly == freeOnly && ticket.route == route
}

/** Account-bound live evidence, independent of the import filter and entry list. */
internal sealed class FreeChatCatalogue(val fetchedAtMillis: Long) {
    fun fresh(now: Long): Boolean = fetchedAtMillis >= 0 && now >= fetchedAtMillis &&
        now - fetchedAtMillis <= OpenRouterPricing.MAX_AGE_MILLIS

    class OpenRouter(val prices: OpenRouterPricing.Catalogue) : FreeChatCatalogue(prices.fetchedAtMillis) {
        fun status(id: String, now: Long): DocumentedFreeChat.Status = when (OpenRouterPricing.chatStatus(prices, id, now)) {
            OpenRouterPricing.Status.ADVERTISED_FREE -> DocumentedFreeChat.Status.FREE
            OpenRouterPricing.Status.PAID -> DocumentedFreeChat.Status.PAID
            OpenRouterPricing.Status.UNKNOWN -> DocumentedFreeChat.Status.UNKNOWN
        }

        fun capabilities(id: String, now: Long): Set<String> {
            if (status(id, now) != DocumentedFreeChat.Status.FREE) return emptySet()
            val quote = prices.quotes[id] ?: return emptySet()
            return buildSet {
                add("chat")
                if (quote.tools) add("tools")
                if (quote.inputModalities?.contains("image") == true) add("vision")
            }
        }
    }

    class Groq(fetchedAtMillis: Long, val models: Set<String>, val paymentRequired: Boolean = false) :
        FreeChatCatalogue(fetchedAtMillis) {
        fun status(id: String, now: Long): DocumentedFreeChat.Status = when {
            fresh(now) && paymentRequired -> DocumentedFreeChat.Status.PAYMENT_REQUIRED
            else -> DocumentedFreeChat.status(DocumentedFreeChat.GROQ_BASE, id, now, false, models.takeIf { fresh(now) })
        }
    }
}

/** Bind the snapshot to the exact saved route and key generation, not the API-key prefix. */
internal class FreeChatCatalogueEvidence(
    private val route: String,
    private val accountVersion: Long,
    private val catalogue: FreeChatCatalogue,
) {
    fun current(route: String, accountVersion: Long, now: Long): FreeChatCatalogue? =
        catalogue.takeIf { this.route == route && this.accountVersion == accountVersion && it.fresh(now) }
}
