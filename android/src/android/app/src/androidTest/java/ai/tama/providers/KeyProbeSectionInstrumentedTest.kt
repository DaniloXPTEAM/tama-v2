package ai.tama.providers

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.data.model.ProviderType
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real form component; the injected checks never use the network. */
@RunWith(AndroidJUnit4::class)
class KeyProbeSectionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyTheButtonChecksTheKeyAndChangingTheAddressClearsTheResult() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val endpoint = mutableStateOf("https://api.groq.com/openai/v1")
        val result = mutableStateOf<KeyProbePlan.Result?>(null)
        var calls = 0
        compose.setContent {
            MinisTheme {
                KeyProbeSection(ProviderType.openAI, "fixture-key", endpoint.value, allowed = true,
                    check = { plan, key ->
                        calls++
                        assertEquals("fixture-key", key)
                        assertEquals("https://api.groq.com/openai/v1/models", plan.url)
                        KeyProbePlan.Result(KeyProbePlan.Outcome.NO_CREDIT)
                    }, onResult = { result.value = it })
            }
        }
        val button = compose.onNodeWithText(context.getString(R.string.nm_key_probe_button))
        button.assertIsEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
        button.performClick()
        compose.onNodeWithText(context.getString(R.string.nm_key_probe_no_credit)).assertExists()
        compose.runOnIdle {
            assertEquals(1, calls)
            assertEquals(KeyProbePlan.Outcome.NO_CREDIT, result.value?.outcome)
            endpoint.value = "https://fixture.invalid/v1"
        }
        button.assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.nm_key_probe_no_credit)).assertDoesNotExist()
    }

    @Test fun lateProbeFromOldGenerationDoesNotSurviveKeyAtoBtoA() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = mutableStateOf("fixture-a")
        val generation = mutableStateOf(1L)
        val calls = AtomicInteger()
        val returned = AtomicBoolean()
        val delayed = CompletableDeferred<KeyProbePlan.Result>()
        val published = mutableListOf<KeyProbePlan.Outcome>()
        val stale = KeyProbePlan.Result(KeyProbePlan.Outcome.NO_CREDIT)
        val fresh = KeyProbePlan.Result(KeyProbePlan.Outcome.ACCEPTED)
        try {
            compose.setContent {
                MinisTheme {
                    KeyProbeSection(ProviderType.openAI, key.value, "https://api.groq.com/openai/v1",
                        allowed = true, generation = generation.value, check = { _, supplied ->
                            when (calls.incrementAndGet()) {
                                1 -> withContext(NonCancellable) {
                                    // Emulate a checker returning a result even after the UI cancelled it.
                                    assertEquals("fixture-a", supplied)
                                    delayed.await().also { returned.set(true) }
                                }
                                else -> {
                                    assertEquals("fixture-a", supplied)
                                    fresh
                                }
                            }
                        }, onResult = { result -> result?.let { published += it.outcome } })
                }
            }
            val button = compose.onNodeWithText(context.getString(R.string.nm_key_probe_button))
            button.performClick()
            compose.waitUntil(timeoutMillis = 5_000) { calls.get() == 1 }
            compose.runOnIdle {
                key.value = "fixture-b"
                generation.value = 2L
            }
            button.assertIsEnabled() // Wait for the intermediate composition before returning to A.
            compose.runOnIdle {
                key.value = "fixture-a"
                generation.value = 3L
            }
            button.assertIsEnabled()
            delayed.complete(stale)
            compose.waitUntil(timeoutMillis = 5_000) { returned.get() }
            compose.runOnIdle { assertEquals(emptyList<KeyProbePlan.Outcome>(), published) }
            compose.onNodeWithText(context.getString(R.string.nm_key_probe_no_credit)).assertDoesNotExist()

            button.performClick()
            compose.onNodeWithText(context.getString(R.string.nm_key_probe_accepted)).assertExists()
            compose.runOnIdle {
                assertEquals(2, calls.get())
                assertEquals(listOf(KeyProbePlan.Outcome.ACCEPTED), published)
            }
        } finally {
            delayed.complete(stale)
        }
    }

    @Test fun agnesCatalogResultIsDistinctAndRequiresAReviewedDestinationAndRawKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apiKey = mutableStateOf("Bearer fixture-key")
        val allowed = mutableStateOf(false)
        var calls = 0
        compose.setContent {
            MinisTheme {
                KeyProbeSection(ProviderType.openAI, apiKey.value, ProviderEndpoints.AGNES_BASE,
                    allowed = allowed.value, check = { plan, supplied ->
                        calls++
                        assertEquals("fixture-key", supplied)
                        assertEquals("https://apihub.agnes-ai.com/v1/models", plan.url)
                        KeyProbePlan.Result(KeyProbePlan.Outcome.ACCEPTED, availableModels = emptySet())
                    }, onResult = {})
            }
        }
        val button = compose.onNodeWithText(context.getString(R.string.nm_key_probe_button))
        button.assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.nm_agnes_key_format)).assertExists()
        compose.runOnIdle { apiKey.value = "fixture-key" }
        button.assertIsNotEnabled()
        compose.runOnIdle { allowed.value = true }
        button.assertIsEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
        button.performClick()
        compose.onNodeWithText(context.getString(R.string.nm_agnes_key_probe_accepted)).assertExists()
        compose.onNodeWithText(context.getString(R.string.nm_key_probe_accepted)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun noKeyKeepsTheCheckDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MinisTheme {
                KeyProbeSection(ProviderType.openRouter, "", "https://openrouter.ai/api/v1", allowed = true,
                    check = { _, _ -> error("Checking must not run without a key") }, onResult = {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.nm_key_probe_button)).assertIsNotEnabled()
    }
}
