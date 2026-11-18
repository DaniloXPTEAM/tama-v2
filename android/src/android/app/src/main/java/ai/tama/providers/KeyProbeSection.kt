package ai.tama.providers

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ProviderType
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.settings.SettingsSection
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** The add and edit forms share an explicit read-only check. Never probe while typing. */
@Composable
internal fun KeyProbeSection(
    type: ProviderType,
    apiKey: String,
    destination: String?,
    allowed: Boolean,
    generation: Long? = null,
    current: () -> Boolean = { true },
    check: suspend (KeyProbePlan.Plan, String) -> KeyProbePlan.Result = KeyProbe::check,
    onResult: (KeyProbePlan.Result?) -> Unit,
): Unit = key(type, apiKey, destination, allowed, generation) {
    // Changing any input or leaving the screen cancels this request and discards its result.
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<KeyProbePlan.Result?>(null) }
    var running by remember { mutableStateOf(false) }
    val plan = remember(type, destination) { KeyProbePlan.create(type, destination) }
    val probeKey = apiKey.trim()
    val enabled = plan != null && allowed && KeyProbePlan.validKey(probeKey) && !running
    SettingsSection(footer = stringResource(R.string.nm_key_probe_help)) {
        MinisButton(
            onClick = {
                if (plan != null && enabled && current() && KeyDestinationGuard.permits(probeKey, destination)) {
                    running = true
                    result = null
                    onResult(null)
                    scope.launch {
                        try {
                            if (!current()) return@launch
                            val checked = check(plan, probeKey)
                            ensureActive()
                            if (current()) {
                                result = checked
                                onResult(checked)
                            }
                        } finally { running = false }
                    }
                }
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text(stringResource(if (running) R.string.quicktest_testing else R.string.nm_key_probe_button)) }
        val message = if (plan == null) R.string.nm_key_probe_unsupported else when (result?.outcome) {
            KeyProbePlan.Outcome.ACCEPTED -> R.string.nm_key_probe_accepted
            KeyProbePlan.Outcome.REJECTED -> R.string.nm_key_probe_rejected
            KeyProbePlan.Outcome.ZERO_SPEND -> R.string.nm_reach_key_zero_body
            KeyProbePlan.Outcome.NO_CREDIT -> R.string.nm_key_probe_no_credit
            KeyProbePlan.Outcome.DENIED -> R.string.nm_key_probe_denied
            KeyProbePlan.Outcome.UNAVAILABLE -> R.string.nm_key_probe_unavailable
            null -> null
        }
        if (message != null) Text(stringResource(message), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}
