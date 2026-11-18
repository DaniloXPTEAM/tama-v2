package ai.tama.pricing

/** A view-only filter. Null evidence is unknown, not evidence that any model is free. */
internal object FreeModelList {
    class Blocked(val status: DocumentedFreeChat.Status? = null) : Exception() // local, never raw provider text

    fun restricted(hasOwnKey: Boolean, requested: Boolean, zeroSpendLimit: Boolean): Boolean =
        hasOwnKey && (requested || zeroSpendLimit)

    fun <T> filter(entries: List<T>, enabled: Boolean, advertisedFree: Set<String>?, modelId: (T) -> String): List<T> =
        if (!enabled) entries else entries.filter { modelId(it) in advertisedFree.orEmpty() }
}
