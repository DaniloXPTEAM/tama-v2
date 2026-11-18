package ai.tama.media

/** A text/vision capability, model name or free chat price is not image generation. */
internal object OwnKeyImagePolicy {
    enum class Reason { NO_GENERATOR, NO_VERIFIED_FREE_GENERATOR }
    class NoVerifiedFreeGenerator : Exception()

    // There is no transport with verified zero image cost in this build.
    fun mayRequest(freeOnly: Boolean): Boolean = !freeOnly

    fun generates(compatible: Boolean, outputs: List<String>?, capabilities: Set<String>): Boolean =
        compatible && (outputs.orEmpty().any { it.equals("image", true) || it.equals("image_output", true) } ||
            "image_gen" in capabilities)

    // No image transport in this build enforces a zero price at dispatch.
    // A catalogue's free chat labels must never open an image request.
    fun reason(hasCompatibleGenerator: Boolean): Reason = if (hasCompatibleGenerator)
        Reason.NO_VERIFIED_FREE_GENERATOR else Reason.NO_GENERATOR
}
