package io.github.nanomuse.media

import android.content.Context
import com.openminis.app.R
import com.openminis.app.offload.OffloadPermissionManager
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.guard.GateOutcome
import io.github.nanomuse.guard.RiskGate
import io.github.nanomuse.net.ProviderReach
import kotlinx.coroutines.withTimeoutOrNull

/** The same permission and risk checks apply to the CLI, the native tool and shortcuts. */
internal fun interface MediaImageGate {
    suspend fun allow(endpoint: ImageGen.Endpoint, prompt: String, size: String, sessionId: String?, freshApproval: Boolean): Boolean
}

internal class AndroidMediaImageGate(
    private val context: Context,
    private val checkRisk: suspend (String, String) -> GateOutcome = RiskGate::checkShell,
    private val permissionTimeoutMs: Long = PERMISSION_TIMEOUT_MS,
) : MediaImageGate {
    override suspend fun allow(endpoint: ImageGen.Endpoint, prompt: String, size: String, sessionId: String?, freshApproval: Boolean): Boolean {
        val sid = sessionId ?: OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID
        // This is assessed, not executed. Quoting makes the assessment match the
        // shell CLI without treating the user's description as command syntax.
        val command = "nanomuse-media image --prompt ${quote(prompt)} --size ${quote(size)}"
        if (checkRisk(sid, command) is GateOutcome.Denied) return false
        val model = ProviderReach.redactedText(endpoint.model, listOf(endpoint.apiKey), 128)
        val provider = ProviderReach.redactedText(endpoint.label, listOf(endpoint.apiKey), 128)
        val title = context.getString(R.string.nm_models_image_title) + ": $model ($provider)"
        // The permission manager queues dialogs; a timeout is a refusal, not consent.
        return withTimeoutOrNull(permissionTimeoutMs) {
            OffloadPermissionManager.checkPermission(IMAGE_PERMISSION, title, sid, requireFreshApproval = freshApproval)
        } ?: false
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        const val IMAGE_PERMISSION = "image_generation"
        private const val PERMISSION_TIMEOUT_MS = 120_000L
    }
}
