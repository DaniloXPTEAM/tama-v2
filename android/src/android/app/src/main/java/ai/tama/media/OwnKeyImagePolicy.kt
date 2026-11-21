package ai.tama.media

/** A text/vision capability, model name or free chat price is not image generation. */
internal object OwnKeyImagePolicy {
    enum class Reason { NO_GENERATOR, NO_VERIFIED_FREE_GENERATOR }
    class NoVerifiedFreeGenerator : Exception()

    // A chat label is not image pricing. The caller can only pass exact-route
    // evidence, never approval, model capability or the key's chat tariff.
    fun mayRequest(freeOnly: Boolean, declaredFree: Boolean = false): Boolean = !freeOnly || declaredFree

    fun generates(compatible: Boolean, outputs: List<String>?, capabilities: Set<String>): Boolean =
        compatible && (outputs.orEmpty().any { it.equals("image", true) || it.equals("image_output", true) } ||
            "image_gen" in capabilities)

    // Explain a refusal. A catalogue's free chat labels never open an image request.
    fun reason(hasCompatibleGenerator: Boolean): Reason = if (hasCompatibleGenerator)
        Reason.NO_VERIFIED_FREE_GENERATOR else Reason.NO_GENERATOR
}
