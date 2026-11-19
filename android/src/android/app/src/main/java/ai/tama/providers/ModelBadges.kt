package ai.tama.providers

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.ui.components.ModalityBadge
import com.openminis.app.ui.components.modalityBadges

/** Describes the model's metadata, not a transport or spending permission. */
@Composable
internal fun ModelBadges(entry: ModelEntry, metadata: ModelModalities?) {
    if (metadata == null) {
        modalityBadges(entry.model).forEach { ModalityBadge(it) }
        return
    }
    if ("chat" in metadata.chatCapabilities) ModalityBadge(stringResource(R.string.nm_models_chat_title))
    if ("tools" in metadata.chatCapabilities) ModalityBadge(stringResource(R.string.nm_model_badge_tools))
    for ((kind, label) in listOf(
        "image" to R.string.nm_model_badge_image,
        "audio" to R.string.nm_model_badge_audio,
        "video" to R.string.nm_usage_kind_video,
    )) {
        if (metadata.inputs?.contains(kind) == true) ModalityBadge(stringResource(R.string.nm_model_badge_input, stringResource(label)))
        if (metadata.outputs?.contains(kind) == true) ModalityBadge(stringResource(R.string.nm_model_badge_output, stringResource(label)))
    }
}
