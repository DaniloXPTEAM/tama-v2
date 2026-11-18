package ai.tama.pricing

import com.openminis.app.BuildConfig
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Scoped to the main chat stream. Does not authorize any other model, endpoint or feature. */
internal class DocumentedChatDispatch(
    private val modelId: String,
    private val credential: String,
    private val current: () -> Boolean,
) : AbstractCoroutineContextElement(Key) {
    fun requireCurrent() {
        if (!current()) throw ModelCostGuard.Blocked()
    }

    companion object Key : CoroutineContext.Key<DocumentedChatDispatch> {
        fun ticket(context: CoroutineContext): DocumentedChatDispatch? =
            if (BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") context[Key] else null

        fun checkGemini(
            context: CoroutineContext, base: String, key: String, id: String, supportedBody: Boolean,
            nowMillis: Long = System.currentTimeMillis(),
        ) {
            val ticket = ticket(context)
            if (ticket == null) return ModelCostGuard.requireAllowed()
            if (!supportedBody || id != ticket.modelId || key != ticket.credential ||
                DocumentedFreeChat.status(base, id, nowMillis, false) != DocumentedFreeChat.Status.FREE) {
                throw ModelCostGuard.Blocked()
            }
            ticket.requireCurrent()
        }
    }
}
