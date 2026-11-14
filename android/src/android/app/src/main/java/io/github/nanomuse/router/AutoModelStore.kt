package io.github.nanomuse.router

import android.content.Context
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import java.security.MessageDigest

/**
 * Device-local consent and capability evidence for an exact model route. None of these
 * preferences contains credentials, prompts or replies. A changed model id, endpoint, auth
 * mode or provider instance gets a different key and cannot inherit automatic-use consent.
 */
object AutoModelStore {
    private const val PREFS = "tama_auto_models"
    private const val MODE = "enabled"

    data class Identity(val entryId: String, val providerId: String, val modelId: String, val route: String)

    fun identity(entry: ModelEntry, instance: ProviderInstance): Identity {
        require(entry.providerInstanceId == instance.id)
        return Identity(
            entry.id, instance.id, entry.model.id,
            digest(listOf(instance.providerType.name, instance.credentialType.name, instance.effectiveBaseURL.orEmpty(),
                instance.useResponsesAPI.toString(), instance.azureMode.toString())),
        )
    }

    private fun digest(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        // Length prefixes prevent ambiguity when a provider or model name contains a separator.
        parts.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            md.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun providerPrefix(identity: Identity) = "p.${digest(listOf(identity.providerId))}."
    private fun prefix(identity: Identity) = providerPrefix(identity) +
        digest(listOf(identity.entryId, identity.providerId, identity.modelId, identity.route)) + "."
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Manual is the safe default until the person turns automatic selection on. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(MODE, false)
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(MODE, enabled).apply() }

    /** This explicit consent permits an automatic call even if the route might charge money. */
    fun approved(context: Context, identity: Identity): Boolean =
        prefs(context).getBoolean(prefix(identity) + "approved", false)
    fun setApproved(context: Context, identity: Identity, approved: Boolean) {
        prefs(context).edit().putBoolean(prefix(identity) + "approved", approved).apply()
    }

    private fun capabilityKey(identity: Identity, need: Need) = prefix(identity) + "cap." + need.name
    private fun deniedToolsKey(identity: Identity) = prefix(identity) + "tools_refused"

    /** Null inherits only exact-model catalogue evidence; false is an explicit negative. */
    fun override(context: Context, identity: Identity, need: Need): Boolean? {
        val preferences = prefs(context)
        val key = capabilityKey(identity, need)
        return if (preferences.contains(key)) preferences.getBoolean(key, false) else null
    }

    /** An explicit user assertion of tool support clears a previously learned refusal. */
    fun setCapability(context: Context, identity: Identity, need: Need, value: Boolean?) {
        val edit = prefs(context).edit()
        val key = capabilityKey(identity, need)
        if (value == null) edit.remove(key) else edit.putBoolean(key, value)
        if (need == Need.TOOLS && value == true) edit.remove(deniedToolsKey(identity))
        edit.apply()
    }

    fun noteUnsupportedTools(context: Context, identity: Identity) {
        prefs(context).edit().putBoolean(deniedToolsKey(identity), true).apply()
    }

    fun toolsWereRefused(context: Context, identity: Identity): Boolean =
        prefs(context).getBoolean(deniedToolsKey(identity), false)

    /** Only exact-model metadata and explicit user assertions are considered; unknown is absent. */
    fun capabilities(context: Context, identity: Identity, exactModelCapabilities: Set<String>): Set<Need> {
        val fromCatalogue = buildSet {
            if ("chat" in exactModelCapabilities) add(Need.CHAT)
            if ("tools" in exactModelCapabilities) add(Need.TOOLS)
            if ("vision" in exactModelCapabilities) add(Need.VISION)
            if ("image_gen" in exactModelCapabilities) add(Need.IMAGE_GENERATION)
        }
        return Need.entries.filterTo(mutableSetOf()) { need -> override(context, identity, need) ?: (need in fromCatalogue) }
            .also { if (toolsWereRefused(context, identity)) it.remove(Need.TOOLS) }
    }

    /** Explicit revocation after changing an account; other providers and mode stay untouched. */
    fun revokeProvider(context: Context, providerId: String) {
        val preferences = prefs(context)
        val providerPrefix = "p.${digest(listOf(providerId))}."
        val edit = preferences.edit()
        preferences.all.keys.filter { it.startsWith(providerPrefix) }.forEach(edit::remove)
        edit.apply()
    }
}
