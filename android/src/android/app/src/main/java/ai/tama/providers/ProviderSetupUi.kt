package ai.tama.providers

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ai.tama.pricing.FreeModelImport
import ai.tama.pricing.FreeModelImportStatus
import com.openminis.app.R
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.VoiceProviderTemplate
import com.openminis.app.ui.settings.SettingsCardBlock
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitchRow
import io.github.nanomuse.cloud.OwnKeyPresets
import io.github.nanomuse.cloud.ProviderCatalogue
import io.github.nanomuse.cloud.Region

/** Explicit vendor selection, so a compatible protocol never guesses a host from a key. */
@Composable
internal fun KnownProviderSection(onSelect: (VoiceProviderTemplate) -> Unit) {
    val context = LocalContext.current
    val mainland = remember { Region.mainland(context) }
    val chinese = remember { ProviderCatalogue.chinese(context) }
    val providers = remember {
        ProviderCatalogue.ordered(ProviderCatalogue.load(context), mainland).filter { it.takesKey }
    }
    val featured = remember(providers) {
        listOf("openrouter", "groq", "gemini", "nvidia", "deepseek", "mistral", "openai", "anthropic")
            .mapNotNull { id -> providers.firstOrNull { it.id == id } }
    }
    var showAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var shownCount by remember(query) { mutableStateOf(24) }
    val directory by produceState<List<ProviderDirectory.Vendor>>(emptyList(), showAll) {
        if (showAll) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.openminis.app.provider.ModelsDevApi.bundledRegistrySnapshot().values.map {
                ProviderDirectory.Vendor(it.id, it.name ?: it.id, it.sdk, it.api, it.docs)
            }
        }
    }
    val results = remember(directory, query) { ProviderDirectory.search(directory, query) }
    fun template(vendor: ProviderDirectory.Vendor): VoiceProviderTemplate? {
        val key = if (vendor.id == "google") "gemini" else vendor.id
        providers.firstOrNull { it.id == key }?.let { return OwnKeyPresets.template(it, mainland, chinese) }
        val endpoint = ProviderDirectory.endpoint(vendor) ?: return null
        val type = if (endpoint.protocol == "anthropic") ProviderType.anthropic else ProviderType.openAI
        return newKeyTemplate(vendor.id, vendor.name, type, endpoint.base)
    }
    val customName = stringResource(R.string.nm_provider_custom)
    SettingsSection(
        header = stringResource(R.string.nm_provider_known_title),
        footer = stringResource(R.string.nm_provider_known_help),
    ) {
        if (!showAll) featured.forEach { provider ->
            val preset = OwnKeyPresets.template(provider, mainland, chinese)
            SettingsRow(
                title = preset.name,
                subtitle = ProviderEndpoints.effective(preset.providerType, preset.baseURL, preset.appendV1),
                onClick = { onSelect(preset) },
            )
        }
        SettingsRow(
            title = customName,
            onClick = { onSelect(newKeyTemplate("tama-custom", customName, ProviderType.openAI, "")) },
        )
        SettingsRow(
            title = stringResource(if (showAll) R.string.nm_provider_hide_catalogue else R.string.nm_provider_show_all),
            onClick = { showAll = !showAll },
            showDivider = false,
        )
    }
    if (showAll) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text(stringResource(R.string.nm_provider_search)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        SettingsSection(header = stringResource(R.string.nm_provider_catalogue_count, results.size, directory.size)) {
            results.take(shownCount).forEach { vendor ->
                val preset = template(vendor)
                SettingsRow(
                    title = vendor.name,
                    subtitle = preset?.let { ProviderEndpoints.effective(it.providerType, it.baseURL, it.appendV1) }
                        ?: stringResource(R.string.nm_provider_no_preset),
                    onClick = preset?.let { { onSelect(it) } },
                    showChevron = preset != null,
                )
            }
            if (results.size > shownCount) SettingsRow(
                title = stringResource(R.string.nm_provider_more_results), onClick = { shownCount += 24 }, showDivider = false,
            )
        }
    }
}

private fun newKeyTemplate(id: String, name: String, type: ProviderType, base: String) = VoiceProviderTemplate(
    id = id, name = name, providerType = type, baseURL = base, appendV1 = false,
    capability = VoiceProviderTemplate.Capability.BOTH, baseURLMarkers = emptyList(), mockModels = emptyList(),
)

/** Suggest locally. Prefixes are hints, not proof of an account or recipient. */
internal fun suggestedKeyProvider(context: android.content.Context, apiKey: String): VoiceProviderTemplate? {
    val providers = ProviderCatalogue.load(context).filter { it.takesKey }
    val hints = providers.map { it.id to it.keyHint } + listOf("gemini" to "AQ.…", "openrouter" to "sk-or-…")
    val matchId = ProviderKeySuggestion.match(apiKey, hints) ?: return null
    val provider = providers.firstOrNull { it.id == matchId } ?: return null
    return OwnKeyPresets.template(provider, Region.mainland(context), ProviderCatalogue.chinese(context))
}

/** Local, process-only confirmation for a key whose prefix and destination differ. */
@Composable
internal fun KeyDestinationReview(key: String, endpoint: String?): Boolean {
    var revision by remember(key, endpoint) { mutableStateOf(0) }
    var showDialog by remember(key, endpoint) { mutableStateOf(false) }
    // Reading revision makes a confirmation immediately update the Save button.
    val allowed = remember(key, endpoint, revision) { KeyDestinationGuard.permits(key, endpoint) }
    val review = KeyDestinationGuard.review(key, endpoint)
    if (!allowed && review != null) {
        val destination = review.destination
        SettingsSection(footer = if (destination == null) stringResource(R.string.nm_key_destination_invalid)
            else stringResource(R.string.nm_key_destination_body, review.provider, destination)) {
            SettingsRow(
                title = stringResource(R.string.nm_key_destination_action),
                onClick = if (destination == null) null else ({ showDialog = true }),
                showDivider = false,
            )
        }
        if (showDialog && destination != null) {
            com.openminis.app.ui.components.MinisAlertDialog(
                onDismissRequest = { showDialog = false },
                title = stringResource(R.string.nm_key_destination_action),
                text = stringResource(R.string.nm_key_destination_body, review.provider, destination),
                confirmText = stringResource(R.string.nm_key_destination_action),
                onConfirm = {
                    if (KeyDestinationGuard.confirm(key, endpoint)) revision += 1
                    showDialog = false
                },
            )
        }
    }
    return allowed
}

/** A suggestion does not change either endpoint or vendor until the person taps. */
@Composable
internal fun KeyProviderHint(providerName: String, applyPreset: (() -> Unit)?) {
    SettingsSection(footer = stringResource(R.string.nm_key_autofill_help)) {
        SettingsRow(
            title = stringResource(R.string.nm_key_autofill_confirm, providerName),
            onClick = applyPreset,
            showChevron = applyPreset != null,
            showDivider = false,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EndpointRegionSection(mainland: Boolean, onSelect: (Boolean) -> Unit) {
    SettingsSection(
        header = stringResource(R.string.nm_endpoint_region),
        footer = stringResource(R.string.nm_endpoint_region_help),
    ) {
        SettingsCardBlock {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !mainland,
                    onClick = { onSelect(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.nm_endpoint_global)) }
                SegmentedButton(
                    selected = mainland,
                    onClick = { onSelect(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.nm_endpoint_mainland)) }
            }
        }
    }
}

@Composable
internal fun freeModelImportStatusText(status: FreeModelImportStatus?): String? = when (status) {
    FreeModelImportStatus.Checking -> stringResource(R.string.nm_free_import_checking)
    FreeModelImportStatus.MissingKey -> stringResource(R.string.nm_free_import_missing_key)
    FreeModelImportStatus.Failed -> stringResource(R.string.nm_free_import_failed)
    FreeModelImportStatus.SaveFailed -> stringResource(R.string.nm_provider_save_failed)
    is FreeModelImportStatus.Complete -> when {
        status.unconfirmed > 0 -> stringResource(R.string.nm_free_import_classified, status.eligible, status.unconfirmed)
        status.eligible == 0 -> stringResource(R.string.nm_free_import_empty)
        else -> stringResource(R.string.nm_free_import_found, status.eligible, status.added)
    }
    FreeModelImportStatus.Unsupported -> stringResource(R.string.nm_free_import_unsupported)
    null -> null
}

@Composable
internal fun FreeModelImportSection(
    type: ProviderType,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    status: FreeModelImportStatus? = null,
    lockedByZeroLimit: Boolean = false,
    hasCatalogue: Boolean = true,
    hasKey: Boolean = true,
) {
    // A legacy filter must remain removable after the endpoint changes or
    // loses its supported catalogue. Only enabling requires a catalogue.
    val enabled = (hasCatalogue || checked) && hasKey && !lockedByZeroLimit
    val statusText = freeModelImportStatusText(status)
    val help = stringResource(when {
        lockedByZeroLimit -> R.string.nm_reach_key_zero_body
        !hasCatalogue -> R.string.nm_free_no_plan
        !hasKey -> R.string.nm_free_import_missing_key
        FreeModelImport.supports(type) -> R.string.nm_free_import_help
        else -> R.string.nm_free_direct_help
    })
    SettingsSection(footer = listOfNotNull(help, statusText?.takeIf { checked || status == FreeModelImportStatus.SaveFailed }).joinToString("\n")) {
        SettingsSwitchRow(
            title = stringResource(R.string.nm_free_import_title),
            checked = checked,
            onCheckedChange = { if (enabled && (!it || hasCatalogue)) onCheckedChange(it) },
            enabled = enabled,
            showDivider = false,
        )
    }
}

@Composable
internal fun OpenRouterEndpointSection() {
    SettingsSection(
        header = stringResource(R.string.add_provider_endpoint),
        footer = stringResource(R.string.nm_endpoint_fixed_help) + "\n" + stringResource(
            R.string.nm_endpoint_effective, "${ProviderEndpoints.OPENROUTER_BASE}/v1",
        ),
    ) {
        SettingsRow(
            title = ProviderEndpoints.OPENROUTER_BASE,
            showChevron = false,
            showDivider = false,
        )
    }
}
