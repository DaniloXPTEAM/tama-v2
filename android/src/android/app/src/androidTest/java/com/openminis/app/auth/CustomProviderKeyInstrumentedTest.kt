package com.openminis.app.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.tama.media.AgnesImages
import ai.tama.pricing.DocumentedFreeChat
import ai.tama.router.AutoModelRuntime
import ai.tama.router.AutoModelStore
import ai.tama.router.Need
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
        var cleanup = repo
        try {
            assertFalse(provider.allowsEmptyAPIKey)
            assertFalse(repo.addApiKeyInstanceChecked(provider, "   "))
            assertTrue(repo.addApiKeyInstanceChecked(provider, "  fixture-original  "))
            assertFalse(repo.addApiKeyInstanceChecked(provider, "fixture-duplicate"))
            val opened = ProviderRepository(context)
            cleanup = opened
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
            // The actual Room row and preferences survive reopening; never review the first
            // matching label or inherit a chat approval. No HTTP call is made in this test.
            val identity = AutoModelStore.identity(bundled, saved)
            val review = requireNotNull(opened.nmBeginAutoReview(identity))
            assertFalse(review.imageApproved)
            assertTrue(opened.nmSaveAutoReview(review, chat = false, image = true, edits = mapOf(Need.IMAGE_GENERATION to true)))
            val reopened = ProviderRepository(context)
            cleanup = reopened
            reopened.ensureConfigLoaded()
            assertEquals(identity.entryId, reopened.entriesFor(id).single { it.model.id == AgnesImages.DEFAULT }.id)
            val stored = requireNotNull(reopened.nmBeginAutoReview(identity))
            assertTrue(stored.imageApproved)
            assertFalse(stored.chatApproved)

            // A missing row can be restored via an explicit review, but the canonical
            // Room ID must not bring back its old grant or invalidate the sibling.
            val sibling = reopened.entriesFor(id).single { it.model.id == AgnesImages.models.last().id }
            val siblingIdentity = AutoModelStore.identity(sibling, saved)
            assertTrue(reopened.nmSaveAutoReview(requireNotNull(reopened.nmBeginAutoReview(siblingIdentity)),
                chat = false, image = true, edits = emptyMap()))
            reopened.removeEntry(bundled.id)
            val restored = reopened.nmRegisterAgnesImage(id, AgnesImages.DEFAULT, AutoModelRuntime.accountVersion(id))
            assertEquals(bundled.id, restored)
            val restoredReview = requireNotNull(reopened.nmBeginAutoReview(identity))
            assertFalse(restoredReview.imageApproved)
            assertFalse(reopened.nmSaveAutoReview(stored, chat = false, image = true, edits = emptyMap()))
            assertTrue(requireNotNull(reopened.nmBeginAutoReview(siblingIdentity)).imageApproved)
            assertTrue(reopened.saveApiKeyChecked(id, "fixture-edited"))
            assertEquals("fixture-edited", ProviderRepository(context).loadApiKey(id))
            assertFalse(reopened.nmSaveAutoReview(restoredReview, chat = false, image = true, edits = emptyMap()))
        } finally {
            cleanup.removeInstance(id)
        }
    }
}
