package ai.tama.providers

import java.net.URI
import java.text.Normalizer
import java.util.Locale

/** The bundled directory lists vendors, not credentials, approvals or shared model lists. */
internal object ProviderDirectory {
    data class Vendor(val id: String, val name: String, val sdk: String?, val base: String?, val docs: String?)
    data class Endpoint(val protocol: String, val base: String)

    private fun normalized(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun search(vendors: List<Vendor>, query: String): List<Vendor> {
        val terms = normalized(query).split(' ').filter { it.isNotBlank() }
        return vendors.filter { vendor ->
            val haystack = normalized("${vendor.id} ${vendor.name} ${vendor.base.orEmpty()}")
            terms.all { it in haystack }
        }.sortedWith(compareBy<Vendor> { normalized(it.name) }.thenBy { it.id })
    }

    private fun validPublicBase(value: String?): String? {
        val raw = value?.trimEnd('/') ?: return null
        val url = runCatching { URI(raw) }.getOrNull() ?: return null
        // These are suggestions from a static public catalogue, not templates
        // with unfilled regions, tenant names, or keys in the address.
        return raw.takeIf {
            url.scheme == "https" && url.host != null && url.userInfo == null &&
                url.query == null && url.fragment == null && !it.contains('%')
        }
    }

    fun validCustomAddress(value: String): Boolean {
        val url = runCatching { URI(value.trim()) }.getOrNull() ?: return false
        return url.scheme in setOf("https", "http") && !url.host.isNullOrBlank() &&
            url.userInfo == null && url.query == null && url.fragment == null
    }

    fun endpoint(vendor: Vendor): Endpoint? {
        val protocol = when (vendor.sdk) {
            "@ai-sdk/openai-compatible" -> "openai"
            "@ai-sdk/anthropic" -> "anthropic"
            else -> return null // existing curated native presets handle their own SDKs
        }
        return validPublicBase(vendor.base)?.let { Endpoint(protocol, it) }
    }
}
