package ai.tama.router

/** Shared among chat view models in this process; no credential or conversation is retained. */
object AutoModelRuntime {
    val health: ModelHealth by lazy { ModelHealth { android.os.SystemClock.elapsedRealtime() } }
    private val accountVersions = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    /** In-flight responses from an older credential/endpoint must not block the new account. */
    fun accountVersion(providerId: String): Long = accountVersions[providerId]?.get() ?: 0L
    fun invalidateAccount(providerId: String) {
        accountVersions.getOrPut(providerId) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        health.clearAccount(providerId)
    }
}
