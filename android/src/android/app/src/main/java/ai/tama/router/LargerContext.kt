package ai.tama.router

/** A local size fallback must be strictly larger and backed by a known positive limit. */
internal object LargerContext {
    fun eligible(currentWindow: Int, candidateWindow: Int?): Boolean =
        currentWindow > 0 && candidateWindow != null && candidateWindow > currentWindow
}
