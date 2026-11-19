package com.openminis.app.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.BuildConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
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
        val provider = ProviderInstance(id, "Test fixture", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = "https://apihub.agnes-ai.com/v1")
        val repo = ProviderRepository(context)
        try {
            assertTrue(repo.addApiKeyInstanceChecked(provider, "  fixture-original  "))
            assertFalse(repo.addApiKeyInstanceChecked(provider, "fixture-duplicate"))
            val opened = ProviderRepository(context)
            opened.ensureConfigLoaded()
            assertEquals(provider.customBaseURL, opened.instance(id)?.customBaseURL)
            assertEquals("fixture-original", opened.loadApiKey(id))
            assertTrue(opened.saveApiKeyChecked(id, "fixture-edited"))
            assertEquals("fixture-edited", ProviderRepository(context).loadApiKey(id))
        } finally {
            repo.removeInstance(id)
        }
    }
}
