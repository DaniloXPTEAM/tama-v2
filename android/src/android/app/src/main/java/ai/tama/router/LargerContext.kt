package ai.tama.router

/** A local size fallback must be strictly larger and backed by a known positive limit. */
internal object LargerContext {
    fun eligible(currentWindow: Int, candidateWindow: Int?): Boolean =
        currentWindow > 0 && candidateWindow != null && candidateWindow > currentWindow

    /** A status alone is not a token limit (413 can be a byte limit). */
    fun serverRejected(status: Int?, message: String?): Boolean =
        status in setOf(400, 413, 422) && io.github.nanomuse.chat.ContextErrors.isContextOverflow(message)
}
