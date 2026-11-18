package ai.tama.pricing

/** Selects only visible entries of this account that occur in fresh catalogue evidence.
 * This selects routes for chat consent, not permission to spend or generate images. */
internal object FreeModelBulk {
    data class Entry(val entryId: String, val providerId: String, val modelId: String, val hidden: Boolean)

    fun select(providerId: String, advertisedFree: Set<String>, entries: List<Entry>): List<String> =
        entries.asSequence().filter {
            it.providerId == providerId && !it.hidden && it.modelId in advertisedFree && it.entryId.isNotBlank()
        }.map { it.entryId }.distinct().toList()
}
