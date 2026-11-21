package ai.tama.providers

/** Checked enrollment, called under the configuration and credential locks. */
internal object ProviderKeyEnrollment {
    fun add(
        exists: () -> Boolean, read: () -> String?, key: String?, store: (String) -> Boolean,
        publish: () -> Boolean, restore: (String?) -> Unit,
    ): Boolean {
        if (exists()) return false
        if (key == null) return runCatching(publish).getOrDefault(false)
        val previous = try { read() } catch (_: Exception) { return false }
        val saved = runCatching { store(key) && read() == key }.getOrDefault(false)
        if (saved && runCatching(publish).getOrDefault(false)) return true
        // Never remove the key of an already-published provider.
        if (!exists()) runCatching { restore(previous) }
        return false
    }
}
