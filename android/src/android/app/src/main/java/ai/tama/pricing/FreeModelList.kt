package ai.tama.pricing

/** A view-only filter. Null evidence is unknown, not evidence that any model is free. */
internal object FreeModelList {
    class Blocked(val status: DocumentedFreeChat.Status? = null) : Exception() // local, never raw provider text

    fun restricted(hasOwnKey: Boolean, requested: Boolean, zeroSpendLimit: Boolean): Boolean =
        hasOwnKey && (requested || zeroSpendLimit)

    // Visibility is not dispatch permission. An unknown row is useful for
    // inspection, but only verified chat on a supported route can be sent.
    fun <T> filterForDisplay(entries: List<T>, enabled: Boolean, status: (T) -> DocumentedFreeChat.Status): List<T> =
        if (!enabled) entries else entries.filter { item ->
            status(item) !in setOf(DocumentedFreeChat.Status.PAID, DocumentedFreeChat.Status.PAYMENT_REQUIRED)
        }

    fun canDispatch(status: DocumentedFreeChat.Status, restricted: Boolean, supportedRoute: Boolean): Boolean =
        status != DocumentedFreeChat.Status.PAYMENT_REQUIRED &&
            (!restricted || (supportedRoute && status == DocumentedFreeChat.Status.FREE))

    fun <T> filter(entries: List<T>, enabled: Boolean, advertisedFree: Set<String>?, modelId: (T) -> String): List<T> =
        if (!enabled) entries else entries.filter { modelId(it) in advertisedFree.orEmpty() }
}
