package ai.tama.media

/** Instruction for the agent; the caller must pass a locally allowlisted result code. */
internal object ImageFailureInstruction {
    fun forCode(safeCode: String): String = if (safeCode == "image_failed") {
        "Explain the message. The request may have been charged. Do not retry or use another image model without a new user request."
    } else {
        // Known preflight refusals do not indicate an image submission.
        "Explain the message and any settings link. Do not retry automatically."
    }
}
