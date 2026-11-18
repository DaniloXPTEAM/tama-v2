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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real form component; the injected check never uses the network. */
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
