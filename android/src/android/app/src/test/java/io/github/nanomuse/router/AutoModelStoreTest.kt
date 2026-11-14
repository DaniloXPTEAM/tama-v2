package io.github.nanomuse.router

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import io.github.nanomuse.pet.PetPrefsContext
import org.junit.Assert.*
import org.junit.Test

class AutoModelStoreTest {
    @Test fun consentAndCapabilitiesAreExactRouteScopedAndDefaultToUnknown() {
        val context = PetPrefsContext()
        val instance = ProviderInstance("provider-a", "A", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = "https://example.test/v1?token=never-store-this")
        val entry = ModelEntry(instance.id, LLMModel("model-1", "Model", "Example"))
        val id = AutoModelStore.identity(entry, instance)
        val catalogue = setOf("chat", "tools")
        assertFalse(AutoModelStore.enabled(context))
        assertFalse(AutoModelStore.approved(context, id))
        assertEquals(emptySet<Need>(), AutoModelStore.capabilities(context, id, emptySet()))
        assertEquals(setOf(Need.CHAT, Need.TOOLS), AutoModelStore.capabilities(context, id, catalogue))
        AutoModelStore.setEnabled(context, true)
        AutoModelStore.setApproved(context, id, true)
        AutoModelStore.setCapability(context, id, Need.VISION, true)
        AutoModelStore.setCapability(context, id, Need.CHAT, false)
        assertEquals(setOf(Need.TOOLS, Need.VISION), AutoModelStore.capabilities(context, id, catalogue))
        val keys = context.getSharedPreferences("tama_auto_models", Context.MODE_PRIVATE).all.keys
        assertTrue(keys.none { it.contains("never-store-this") || it.contains("example.test") })

        val renamedModel = entry.copy(baseModel = entry.baseModel.copy(id = "model-2"))
        assertFalse(AutoModelStore.approved(context, AutoModelStore.identity(renamedModel, instance)))
        val changedEndpoint = instance.copy(customBaseURL = "https://other.test/v1")
        assertFalse(AutoModelStore.approved(context, AutoModelStore.identity(entry, changedEndpoint)))
        val changedAuth = instance.copy(credentialType = ProviderCredential.oauth)
        assertFalse(AutoModelStore.approved(context, AutoModelStore.identity(entry, changedAuth)))
    }

    @Test fun learnedRefusalPersistsAndExplicitReconfirmationCanClearIt() {
        val context = PetPrefsContext()
        val id = AutoModelStore.Identity("entry", "account", "model", "route")
        val catalogue = setOf("chat", "tools")
        AutoModelStore.setCapability(context, id, Need.TOOLS, true)
        AutoModelStore.noteUnsupportedTools(context, id)
        assertTrue(AutoModelStore.toolsWereRefused(context, id))
        assertEquals(setOf(Need.CHAT), AutoModelStore.capabilities(context, id, catalogue))
        AutoModelStore.setCapability(context, id, Need.TOOLS, true) // explicit re-confirmation
        assertFalse(AutoModelStore.toolsWereRefused(context, id))
        assertEquals(setOf(Need.CHAT, Need.TOOLS), AutoModelStore.capabilities(context, id, catalogue))
        AutoModelStore.setCapability(context, id, Need.TOOLS, false)
        assertEquals(setOf(Need.CHAT), AutoModelStore.capabilities(context, id, catalogue))
        AutoModelStore.setCapability(context, id, Need.TOOLS, null)
        assertEquals(setOf(Need.CHAT, Need.TOOLS), AutoModelStore.capabilities(context, id, catalogue))
    }

    @Test fun revokingOneProviderDoesNotEraseAnotherOrTheGlobalMode() {
        val context = PetPrefsContext()
        val a = AutoModelStore.Identity("entry-a", "provider-a", "same-model", "same-route")
        val b = AutoModelStore.Identity("entry-b", "provider-b", "same-model", "same-route")
        AutoModelStore.setEnabled(context, true)
        AutoModelStore.setApproved(context, a, true)
        AutoModelStore.setApproved(context, b, true)
        AutoModelStore.noteUnsupportedTools(context, a)
        AutoModelStore.revokeProvider(context, "provider-a")
        assertFalse(AutoModelStore.approved(context, a))
        assertFalse(AutoModelStore.toolsWereRefused(context, a))
        assertTrue(AutoModelStore.approved(context, b))
        assertTrue(AutoModelStore.enabled(context))
    }
}
