package com.openminis.app.tools

import android.content.Context
import com.openminis.app.R
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.NativeOffloadResult
import io.github.nanomuse.media.ImageDispatch
import io.github.nanomuse.media.MediaOffloadHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Native text-to-image tool. It shares the CLI's consent, route and output handling. */
object ImageGenerationTool {
    const val NAME = "generate_image"

    fun definition() = AgentToolDefinition(
        name = NAME,
        description = "Generate one image only when the user asks. May incur a charge. " +
            "Use the returned markdown in the answer. Do not retry a failed image request.",
        parameters = mapOf(
            "prompt" to AgentToolParam("string", "Description of the image to generate."),
            "size" to AgentToolParam("string", "Optional WIDTHxHEIGHT, each from 128 to 4096, for example 1024x1024."),
        ),
        required = listOf("prompt"),
        propertyOrdering = listOf("prompt", "size"),
    )

    suspend fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult =
        execute(argsJson, context.getString(R.string.nm_models_image_title)) { prompt, size ->
            withContext(Dispatchers.IO) { MediaOffloadHandler(context).generateImage(prompt, size, sessionId) }
        }

    /** Injectable edge for tests; no client is created unless all arguments are valid. */
    internal suspend fun execute(
        argsJson: String,
        title: String,
        send: suspend (prompt: String, size: String?) -> NativeOffloadResult,
    ): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) {
            return ToolExecutionResult("Invalid generate_image arguments; no image was requested.", false, toolTitle = title)
        }
        if (args.keys().asSequence().any { it != "prompt" && it != "size" }) {
            return ToolExecutionResult("Unsupported generate_image parameter; no image was requested.", false, toolTitle = title)
        }
        val prompt = (args.opt("prompt") as? String)?.trim()
        if (prompt.isNullOrBlank() || prompt.length > 4_000) {
            return ToolExecutionResult("A non-empty image prompt of at most 4000 characters is required.", false, toolTitle = title)
        }
        val rawSize = args.opt("size")
        val size = when {
            !args.has("size") || args.isNull("size") -> null
            rawSize is String && ImageDispatch.validSize(rawSize) -> rawSize
            else -> return ToolExecutionResult("Image size must be WIDTHxHEIGHT, each from 128 to 4096; no image was requested.", false, toolTitle = title)
        }
        val result = send(prompt, size)
        return ToolExecutionResult(result.output, result.exitCode == 0, toolTitle = title)
    }
}
