package ai.tama.media

import ai.tama.chat.SessionBindingSnapshot
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Job
import org.junit.Test

class CliImageScopeTest {
    @Test fun `provisional free turn remains local to its chat until its job completes`() {
        val job = Job()
        try {
            CliImageScope.markFreeTurn("side", job)
            assertTrue(CliImageScope.freeDuringTurn("side"))
            assertFalse(CliImageScope.freeDuringTurn("default"))
        } finally { job.complete() }
        assertFalse(CliImageScope.freeDuringTurn("side"))
    }

    @Test fun `side binding uses entry identity not the default or a duplicate model id`() {
        val config = ProviderConfig(
            modelEntries = mutableListOf(
                ModelEntry(uuid = "default-entry", providerInstanceId = "default", baseModel = LLMModel("same", "Default", "Test")),
                ModelEntry(uuid = "side-entry", providerInstanceId = "side", baseModel = LLMModel("same", "Side", "Test")),
            ),
            modelGroups = mutableListOf(ModelGroup(id = "group", name = "Test", memberEntryIds = mutableListOf("default-entry", "side-entry"))),
        )
        fun accounts(binding: String?) = CliImageScope.chatAccounts(SessionBindingSnapshot(binding, "same"), config)
        assertEquals(setOf("side"), accounts("""{"type":"entry","entryId":"side-entry"}"""))
        assertEquals(setOf("side"), accounts("""{"type":"group","groupId":"group","lastEntryId":"side-entry"}"""))
        assertEquals(setOf("default", "side"), accounts("""{"type":"group","groupId":"group"}"""))
        assertEquals(setOf("default", "side"), accounts(null))
        assertEquals(emptySet<String>(), accounts("""{"type":"group","groupId":"missing","lastEntryId":"side-entry"}"""))
    }
}
