package ai.tama.media

import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject
import java.io.File

/** Only an allowlisted outcome reaches the image detail UI, never raw tool arguments or JSON. */
internal object ImageToolPresentation {
    private const val ATTACHMENTS = "/var/minis/attachments/"
    private const val MISSING_PNG = "image_attachment_missing"
    private val generatedName = Regex("nanomuse-[0-9]{8}-[0-9]{6}-[a-f0-9-]{36}\\.png")

    /** Bind a local handler receipt, never model text, to the existing tool artifact field. */
    fun attachSavedPng(result: ToolExecutionResult, attachmentsDir: File): ToolExecutionResult {
        if (!result.success) return result
        val file = runCatching {
            val receipt = JSONObject(result.output)
            val path = receipt.optString("path")
            if (!receipt.optBoolean("ok") || !path.startsWith(ATTACHMENTS)) return@runCatching null
            val name = path.removePrefix(ATTACHMENTS).takeIf(generatedName::matches) ?: return@runCatching null
            val expected = File(attachmentsDir.canonicalFile, name)
            val bytes = receipt.optLong("bytes", 0)
            expected.takeIf { bytes > 0 && it.canonicalFile == it && it.isFile && it.length() == bytes }
        }.getOrNull()
        return if (file != null) result.copy(imageFilePath = file.absolutePath)
        else result.copy(success = false, output = """{"ok":false,"error":"$MISSING_PNG"}""")
    }

    /** The markup is made by the app; it does not contain the model's reply or tool JSON. */
    fun generatedMarkdown(filePath: String?): String? = filePath?.substringAfterLast('/')
        ?.takeIf(generatedName::matches)?.let { "![](minis://attachments/$it)" }

    enum class Summary {
        PREPARING, GENERATED, ATTACHMENT_MISSING, NO_MODEL, NO_APPROVAL, UNVERIFIED_COST, MODEL_COST_UNVERIFIED,
        AUTHORIZATION_CHANGED, PERMISSION_DENIED, EDIT_UNAVAILABLE, MODEL_CHANGE_BLOCKED, AMBIGUOUS, ALREADY_HANDLED, INVALID, FAILED,
        NO_CONFIRMED_GENERATOR, NO_VERIFIED_FREE_GENERATOR, CHAT_ONLY, ROUTE_UNCONFIRMED,
        KEY_MISSING, NVIDIA_NATIVE, ENDPOINT_REJECTED, AGNES_AUTHENTICATION, AGNES_ACCESS_DENIED,
        AGNES_IMAGE_NEEDS_ENTRY, AGNES_IMAGE_AMBIGUOUS, AGNES_IMAGE_CAPABILITY_OFF, AGNES_IMAGE_NEEDS_APPROVAL,
    }

    fun summary(
        raw: String,
        live: Boolean,
        success: Boolean,
        ambiguousMessage: String,
        repeatedMessage: String,
        invalidMessage: String,
    ): Summary {
        if (live) return Summary.PREPARING
        // These exact local strings come from the image guard, not an arbitrary provider reply.
        if (raw == ambiguousMessage) return Summary.AMBIGUOUS
        if (raw == repeatedMessage) return Summary.ALREADY_HANDLED
        if (raw == invalidMessage) return Summary.INVALID
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return Summary.FAILED
        if (success && json.optBoolean("ok", false)) return Summary.GENERATED
        return when (json.optString("error", "")) {
            MISSING_PNG -> Summary.ATTACHMENT_MISSING
            "no_image_model" -> Summary.NO_MODEL
            "no_confirmed_image_generator" -> Summary.NO_CONFIRMED_GENERATOR
            "no_verified_free_image_generator" -> Summary.NO_VERIFIED_FREE_GENERATOR
            "image_chat_only" -> Summary.CHAT_ONLY
            "image_route_unconfirmed", "agnes_image_unavailable" -> Summary.ROUTE_UNCONFIRMED
            "image_key_missing" -> Summary.KEY_MISSING
            "agnes_image_authentication" -> Summary.AGNES_AUTHENTICATION
            "agnes_image_access_denied" -> Summary.AGNES_ACCESS_DENIED
            "image_nvidia_native" -> Summary.NVIDIA_NATIVE
            "image_endpoint_rejected" -> Summary.ENDPOINT_REJECTED
            "no_approved_image_model" -> Summary.NO_APPROVAL
            CustomImageRoute.Problem.AGNES_IMAGE_NEEDS_ENTRY.code -> Summary.AGNES_IMAGE_NEEDS_ENTRY
            CustomImageRoute.Problem.AGNES_IMAGE_AMBIGUOUS.code -> Summary.AGNES_IMAGE_AMBIGUOUS
            CustomImageRoute.Problem.AGNES_IMAGE_CAPABILITY_OFF.code -> Summary.AGNES_IMAGE_CAPABILITY_OFF
            CustomImageRoute.Problem.AGNES_IMAGE_NEEDS_APPROVAL.code -> Summary.AGNES_IMAGE_NEEDS_APPROVAL
            "automatic_image_cost_unverified" -> Summary.UNVERIFIED_COST
            "image_cost_unverified" -> Summary.MODEL_COST_UNVERIFIED
            "image_authorization_changed" -> Summary.AUTHORIZATION_CHANGED
            "permission_denied" -> Summary.PERMISSION_DENIED
            "automatic_image_edit_unavailable" -> Summary.EDIT_UNAVAILABLE
            "media_model_change_blocked" -> Summary.MODEL_CHANGE_BLOCKED
            "invalid_image_request" -> Summary.INVALID
            else -> Summary.FAILED
        }
    }

    /** Rebuild a failed tool result without copying any provider-supplied message or URL. */
    fun sanitizedFailure(
        raw: String,
        messages: Map<String, String>,
        fallback: String,
        neutralCharge: Boolean = false,
        explainCostRefusal: (() -> String)? = null,
    ): String {
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
        val code = parsed?.optString("error", "").orEmpty()
        val routeProblem = CustomImageRoute.Problem.entries.firstOrNull { it.code == code }
        val safeCode = routeProblem?.code ?: when (code) {
            "no_image_model", "no_approved_image_model", "automatic_image_cost_unverified", "image_cost_unverified",
            "image_authorization_changed", "permission_denied", "automatic_image_edit_unavailable", "invalid_image_request",
            "media_model_change_blocked", "image_failed", "no_confirmed_image_generator",
            "no_verified_free_image_generator", MISSING_PNG -> code
            else -> if (raw in setOf(
                    "Invalid generate_image arguments; no image was requested.",
                    "Unsupported generate_image parameter; no image was requested.",
                    "A non-empty image prompt of at most 4000 characters is required.",
                    "Image size must be WIDTHxHEIGHT, each from 128 to 4096; no image was requested.",
                )) "invalid_image_request" else "image_failed"
        }
        val explainedCode = if (safeCode in setOf("image_cost_unverified", "automatic_image_cost_unverified"))
            explainCostRefusal?.invoke()?.takeIf {
                it == "no_confirmed_image_generator" || it == "no_verified_free_image_generator"
            } ?: safeCode else safeCode
        val message = messages[explainedCode] ?: fallback
        val settings = if (routeProblem != null) "minis://settings/media" else when (explainedCode) {
            "no_image_model", "media_model_change_blocked", "no_confirmed_image_generator",
            "no_verified_free_image_generator" -> "minis://settings/media"
            "no_approved_image_model", "automatic_image_cost_unverified", "image_cost_unverified",
            "image_authorization_changed", "automatic_image_edit_unavailable" -> "minis://settings/models"
            "permission_denied" -> "minis://settings/permissions"
            else -> null
        }
        // The result is sent back to the agent, not just displayed. Reconstruct
        // the no-retry instruction locally instead of dropping it or trusting an
        // untrusted `tell_user` field supplied by a provider.
        val instruction = ImageFailureInstruction.forCode(explainedCode, neutralCharge)
        return JSONObject().put("ok", false).put("error", explainedCode).put("message", message).apply {
            if (settings != null) put("settings", settings)
            put("tell_user", instruction)
        }.toString()
    }
}
