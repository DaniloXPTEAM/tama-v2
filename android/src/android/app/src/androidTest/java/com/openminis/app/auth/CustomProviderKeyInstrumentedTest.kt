package com.openminis.app.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.tama.media.AgnesImages
import ai.tama.pricing.DocumentedFreeChat
import com.openminis.app.BuildConfig
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import io.github.nanomuse.cloud.OwnKeyPresets
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No real credentials, provider calls, or changes to any existing provider. */
@RunWith(AndroidJUnit4::class)
class CustomProviderKeyInstrumentedTest {
    @Test fun encryptedKeyAndProviderSurviveReopeningAndEditing() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "synthetic-custom-${UUID.randomUUID()}"
        val preset = requireNotNull(OwnKeyPresets.template(context, "agnes"))
        assertEquals("Agnes", preset.name)
        val provider = ProviderInstance(id, preset.name, preset.providerType, ProviderCredential.apiKey,
            customBaseURL = preset.baseURL, appendV1Suffix = preset.appendV1)
        val repo = ProviderRepository(context)
        try {
            assertFalse(provider.allowsEmptyAPIKey)
            assertFalse(repo.addApiKeyInstanceChecked(provider, "   "))
            assertTrue(repo.addApiKeyInstanceChecked(provider, "  fixture-original  "))
            assertFalse(repo.addApiKeyInstanceChecked(provider, "fixture-duplicate"))
            val opened = ProviderRepository(context)
            opened.ensureConfigLoaded()
            val saved = requireNotNull(opened.instance(id))
            assertEquals("https://apihub.agnes-ai.com/v1", saved.effectiveBaseURL)
            assertEquals("fixture-original", opened.usableApiKey(saved))
            // A chat-only /models catalogue cannot remove the reviewed image models.
            opened.replaceEntries(id, listOf(LLMModel("agnes-2.5-flash", "Agnes chat", "Agnes")))
            // Old builds let adding a custom copy make the registered image ambiguous.
            val bundled = opened.entriesFor(id).single { it.model.id == AgnesImages.DEFAULT }
            opened.addEntry(ModelEntry(id, bundled.model.copy(displayName = "Fixture duplicate"), isCustom = true))
            val entries = opened.entriesFor(id)
            assertEquals(bundled.id, entries.single { it.model.id == AgnesImages.DEFAULT }.id)
            assertEquals(AgnesImages.models.map { it.id }.toSet(), entries.filter { !it.model.isTextOutput }.map { it.model.id }.toSet())
            assertTrue(entries.any { opened.tamaIsChatModel(it, saved) })
            for (model in AgnesImages.models) {
                assertEquals(DocumentedFreeChat.Status.FREE, opened.tamaAgnesImageStatus(saved, model.id))
                assertFalse(opened.tamaAllowsChatModel(saved, model.id))
            }
            assertEquals(null, opened.tamaAgnesImageStatus(saved, "agnes-2.5-flash"))
            assertTrue(opened.saveApiKeyChecked(id, "fixture-edited"))
            assertEquals("fixture-edited", ProviderRepository(context).loadApiKey(id))
        } finally {
            repo.removeInstance(id)
        }
    }
}
