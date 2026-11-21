package ai.tama.media

import ai.tama.chat.SessionBindingSnapshot
import com.openminis.app.data.model.ProviderConfig
import kotlinx.coroutines.Job
import org.json.JSONObject

/** Identifies the chat policy of the session already authenticated by the host socket. */
internal object CliImageScope {
    enum class State { UNRESTRICTED, FREE_ONLY, CHANGED }

    // A temporary chat route is not necessarily persisted yet. Until its
    // pending job ends, do not widen a free-only turn to the paid image slot.
    private val freeTurns = mutableMapOf<String, MutableSet<Job>>()

    fun markFreeTurn(sessionId: String, job: Job) {
        val registered = synchronized(freeTurns) { freeTurns.getOrPut(sessionId) { mutableSetOf() }.add(job) }
        if (registered) job.invokeOnCompletion {
            synchronized(freeTurns) {
                freeTurns[sessionId]?.let { jobs ->
                    jobs.remove(job)
                    if (jobs.isEmpty()) freeTurns.remove(sessionId)
                }
            }
        }
    }

    fun freeDuringTurn(sessionId: String?): Boolean = synchronized(freeTurns) { !freeTurns[sessionId].isNullOrEmpty() }

    /** Saved entry IDs name the account; an ambiguous legacy model ID cannot rule one out. */
    fun chatAccounts(binding: SessionBindingSnapshot, config: ProviderConfig): Set<String> {
        val json = binding.binding?.takeIf { it.isNotBlank() }?.let {
            runCatching { JSONObject(it) }.getOrNull() ?: return emptySet()
        }
        val entries = config.modelEntries
        val candidates = when (json?.optString("type")) {
            "entry" -> entries.filter { it.id == json.optString("entryId") }
            "group" -> {
                val members = config.modelGroups.singleOrNull { it.id == json.optString("groupId") }?.memberEntryIds.orEmpty()
                val matching = entries.filter { it.id in members }
                val last = entries.singleOrNull { it.id == json.optString("lastEntryId") && it.model.id == binding.modelId }
                    ?.takeIf { it.id in members || (matching.isNotEmpty() && json.optBoolean("autoFallback")) }
                if (last != null) listOf(last) else matching
            }
            null -> entries.filter { it.model.id == binding.modelId }
            else -> emptyList()
        }
        return candidates.mapTo(linkedSetOf()) { it.providerInstanceId }
    }
}
