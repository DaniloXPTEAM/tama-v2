package ai.tama.providers

/** Keep input (understanding) and output (generation) independent. No name heuristics. */
internal data class ModelModalities(
    val inputs: Set<String>?,
    val outputs: Set<String>?,
    val chatCapabilities: Set<String>,
    // Display hints, never inputs to capability, cost or authorization checks.
    val coding: Boolean = false,
    val reasoning: Boolean = false,
) {
    companion object {
        private fun normal(values: List<String>?) = values?.mapTo(linkedSetOf()) {
            it.lowercase().removeSuffix("_input").removeSuffix("_output")
        }

        fun from(
            input: List<String>?, output: List<String>?, tools: Boolean = false,
            confirmedVision: Boolean = false, confirmedChat: Boolean = false,
            exactInput: List<String>? = input, exactOutput: List<String>? = output,
            coding: Boolean = false, reasoning: Boolean = false,
        ): ModelModalities {
            fun visible(saved: List<String>?, exact: List<String>?): Set<String>? {
                val set = normal(saved)?.minus(setOf("audio", "video"))
                val media = normal(exact)?.intersect(setOf("audio", "video")).orEmpty()
                return (set.orEmpty() + media).takeUnless { it.isEmpty() && saved == null && media.isEmpty() }
            }
            val seenInputs = visible(input, exactInput)
            val seenOutputs = visible(output, exactOutput)
            val inputs = if (confirmedVision) seenInputs.orEmpty() + "image" else seenInputs
            val outputs = if (confirmedChat) seenOutputs.orEmpty() + "text" else seenOutputs
            return ModelModalities(inputs, outputs, buildSet {
                if (confirmedChat || (inputs?.contains("text") == true && outputs?.contains("text") == true)) add("chat")
                if (inputs?.contains("image") == true) add("vision")
                if (tools) add("tools")
            }, coding = coding && "text" in outputs.orEmpty(), reasoning = reasoning)
        }
    }
}
