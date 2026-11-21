package ai.tama.providers

/** Local API key only. A Cloud session token (including a self-hosted relay) is not a personal key. */
internal object OwnKeyScope {
    fun active(parallel: Boolean, apiKeyCredential: Boolean, key: String?, managedRelay: Boolean): Boolean =
        parallel && apiKeyCredential && !managedRelay && !key.isNullOrBlank()
}
