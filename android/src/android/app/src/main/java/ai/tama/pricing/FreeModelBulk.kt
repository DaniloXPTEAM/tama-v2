package ai.tama.pricing

/** Selects only visible entries of this account that occur in fresh catalogue evidence.
 * This selects routes for chat consent, not permission to spend or generate images. */
internal object FreeModelBulk {
    data class Entry(val entryId: String, val providerId: String, val modelId: String, val hidden: Boolean)

    fun select(providerId: String, advertisedFree: Set<String>, entries: List<Entry>): List<String> {
        // Do not grant consent to a row whose id resolves ambiguously elsewhere,
        // or to duplicate entries for the same provider/model. Hidden duplicates
        // still count: hiding one must not turn the other into a unique identity.
        val duplicateIds = entries.groupingBy { it.entryId }.eachCount().filterValues { it > 1 }.keys
        val duplicateModels = entries.asSequence().filter { it.providerId == providerId }
            .groupingBy { it.modelId }.eachCount().filterValues { it > 1 }.keys
        return entries.asSequence().filter {
            it.providerId == providerId && !it.hidden && it.modelId in advertisedFree && it.entryId.isNotBlank() &&
                it.entryId !in duplicateIds && it.modelId !in duplicateModels
        }.map { it.entryId }.toList()
    }
}
