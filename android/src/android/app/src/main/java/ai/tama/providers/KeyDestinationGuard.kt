package ai.tama.providers

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Local recipient review, not proof of key ownership or authorization to spend.
 * Confirmation is process-local and binds the exact key and base URL; neither is stored.
 * Only known distinctive prefixes are considered. An invalid URL cannot be confirmed.
 */
internal object KeyDestinationGuard {
    class Blocked : Exception()

    data class Review(val provider: String, val destination: String?)
    private val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val approvals = mutableSetOf<String>()
    private const val MAX_APPROVALS = 256

    private fun parsed(base: String?): URI? = try {
        val uri = URI(base?.trim().orEmpty())
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.query != null || uri.fragment != null || (uri.port != -1 && uri.port != 443)) null else uri
    } catch (_: Exception) { null }

    fun review(key: String?, base: String?): Review? {
        val trimmed = key?.trim().orEmpty()
        // Prefixes are hints, never proof of ownership. A mismatched custom gateway
        // remains usable only after an explicit, process-local destination review.
        val expected = when {
            trimmed.startsWith("gsk_") -> Triple("Groq", "api.groq.com", setOf("/openai/v1"))
            trimmed.startsWith("sk-or-") -> Triple("OpenRouter", "openrouter.ai", setOf("/api/v1"))
            trimmed.startsWith("sk-ant-") -> Triple("Anthropic", "api.anthropic.com", setOf("", "/v1"))
            trimmed.startsWith("xai-") -> Triple("xAI", "api.x.ai", setOf("/v1"))
            trimmed.startsWith("AQ.") || trimmed.startsWith("AIza") -> Triple(
                "Gemini", "generativelanguage.googleapis.com", setOf("/v1beta", "/v1beta/openai"),
            )
            else -> return null
        }
        val uri = parsed(base)
        val host = uri?.host?.lowercase(Locale.ROOT)
        if (host == expected.second && uri?.path?.trimEnd('/') in expected.third) return null
        // URL is shown only in the local confirmation, never logged or persisted here.
        return Review(expected.first, uri?.toASCIIString())
    }

    private fun token(key: String, base: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(key.trim().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(base.trim().toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun permits(key: String?, base: String?): Boolean {
        if (review(key, base) == null) return true
        if (key == null || base == null || parsed(base) == null) return false
        return token(key, base) in approvals
    }

    /** Called only after the person confirms the displayed host. */
    @Synchronized
    fun confirm(key: String?, base: String?): Boolean {
        if (review(key, base) == null || key == null || base == null || parsed(base) == null) return false
        val token = token(key, base)
        if (token !in approvals && approvals.size >= MAX_APPROVALS) return false
        approvals.add(token)
        return true
    }

    fun requireAllowed(parallel: Boolean, key: String?, base: String?) {
        if (parallel && !permits(key, base)) throw Blocked()
    }

    fun causedByBlock(error: Throwable): Boolean {
        var current: Throwable? = error
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while (current != null && seen.add(current)) {
            if (current is Blocked) return true
            current = current.cause
        }
        return false
    }
}
