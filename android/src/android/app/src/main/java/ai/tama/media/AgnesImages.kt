package ai.tama.media

import ai.tama.providers.ProviderEndpoints
import com.openminis.app.BuildConfig
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import kotlin.math.abs
import kotlin.math.ln

/** Studio Local v4 (owner-supplied). Not a price claim for chat or for other accounts. */
internal object AgnesImages {
    const val DEFAULT = "agnes-image-2.1-flash"
    const val SOURCE = "informado pelo dono (não verificado em documentação do provedor)"
    const val REVIEW_DATE = "2026-11-19"
    const val MAX_REFERENCE_BYTES = 10_000_000

    data class Model(val id: String, val generation: String) {
        fun metadata() = LLMModel(id, "Agnes Image $generation Flash", "Agnes",
            inputModalities = listOf("text", "image"), outputModalities = listOf("image"))
    }

    val models = listOf(Model(DEFAULT, "2.1"), Model("agnes-image-2.0-flash", "2.0"))

    // Price and transport are keyed to the same fixed HTTPS destination and exact IDs.
    fun account(instance: ProviderInstance): Boolean = BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test" &&
        instance.credentialType == ProviderCredential.apiKey && !instance.azureMode &&
        instance.providerType in setOf(ProviderType.openAI, ProviderType.openAIResponses) &&
        ProviderEndpoints.isAgnesBase(instance.effectiveBaseURL)

    fun route(instance: ProviderInstance): Boolean = account(instance) &&
        instance.imageEndpointMode != ImageEndpointMode.chatCompletions

    fun model(id: String): Model? = models.firstOrNull { it.id == id }

    // Old builds let Add custom model duplicate the bundled row. Resolve the
    // exact same provider/model to its one bundled entry without deleting rows
    // or moving a custom entry's visibility, capabilities, or approval.
    fun registeredEntry(matching: List<ModelEntry>): ModelEntry? =
        matching.singleOrNull() ?: matching.filterNot { it.isCustom }.singleOrNull()

    private val sizes = listOf(1024 to 1024, 768 to 1024, 1024 to 768, 576 to 1024, 1024 to 576)
    private val aspect = Regex("(?<![\\d:])([1-9][0-9]{0,3}):([1-9][0-9]{0,3})(?![\\d:])")

    fun requestSize(requested: String): String {
        val (w, h) = requested.split('x').map { it.toInt() }
        val (width, height) = sizes.minBy { (sw, sh) -> abs(ln((w.toDouble() / h) / (sw.toDouble() / sh))) }
        return "${width}x$height"
    }

    // The direct shortcut has no separate size field. Use a ratio stated in the
    // prompt when no explicit size was supplied by the tool or CLI.
    fun sizeFromPrompt(prompt: String): String? = aspect.find(prompt)?.destructured
        ?.let { (w, h) -> requestSize("${w}x$h") }

    fun aspectRatio(width: Int, height: Int): String {
        require(width > 0 && height > 0)
        var a = width
        var b = height
        while (b != 0) { val remainder = a % b; a = b; b = remainder }
        return "${width / a}:${height / a}"
    }

    fun mimeType(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }
}
