package io.github.nanomuse.media

import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Executes one already-classified image shortcut, without a chat-model dependency. */
internal object ImageShortcutRunner {
    data class Reply(val text: String, val success: Boolean)

    suspend fun run(
        prompt: String,
        failureMessage: String,
        settingsLabel: String,
        execute: suspend (String) -> ToolExecutionResult,
    ): Reply {
        val result = try {
            execute(prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Reply(failureMessage, false)
        }
        val body = try { JSONObject(result.output) } catch (_: Exception) { return Reply(failureMessage, false) }
        if (result.success) {
            val markdown = body.optString("markdown", "").takeIf {
                it.startsWith("![") && it.contains("](minis://attachments/") && it.endsWith(")")
            } ?: return Reply(failureMessage, false)
            return Reply(markdown, true)
        }
        // Provider errors may echo the person's prompt or secrets. The generic
        // localized message also warns that an unsuccessful POST may be charged.
        val code = body.optString("error", "")
        val message = if (code == "image_failed") failureMessage
            else body.optString("message", "").takeIf { it.isNotBlank() } ?: failureMessage
        val settings = body.optString("settings", "").takeIf {
            it in setOf("minis://settings/media", "minis://settings/models", "minis://settings/permissions")
        }
        return Reply(if (settings == null) message else "$message\n\n[$settingsLabel]($settings)", false)
    }
}
