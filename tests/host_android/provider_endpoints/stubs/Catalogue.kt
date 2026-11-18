package io.github.nanomuse.cloud
import android.content.Context
class CatalogueProvider(val id: String, val protocol: String, private val url: String, val signIn: String? = null) {
    fun baseUrl(mainland: Boolean): String = url
    fun displayName(chinese: Boolean): String = id
}
object ProviderCatalogue {
    const val BAILIAN = "bailian"
    const val OPENROUTER = "openrouter"
    const val XAI = "xai"
    const val AUTH_CLAUDE = "claude"
    const val AUTH_KIMI = "kimi"
    fun byId(context: Context, id: String?): CatalogueProvider? = null
    fun chinese(context: Context) = false
}
object Region { fun mainland(context: Context) = false }
object ClaudeSignIn { val available = false }
