package ai.tama.media

import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.tama.router.AutoModelStore
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection
import io.github.nanomuse.models.ModelSlots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The image picker and the media settings link show the same saved route and Auto review. */
@Composable
internal fun AgnesImageReviewSection(choiceRevision: Long, onReview: (String, String) -> Unit) {
    val context = LocalContext.current
    val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return
    val config by repo.config.collectAsState()
    val refusals by repo.tamaPaymentRefusals.collectAsState()
    val modeRevision by AutoModelStore.modeChanges.collectAsState()
    val slotChange by ModelSlots.lastChanged.collectAsState()
    val scope = rememberCoroutineScope()
    var resumed by remember { mutableIntStateOf(0) }
    var preparing by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val setup = remember(config, refusals, slotChange, resumed, choiceRevision) { AutoImageSelection.agnesSetup(context) } ?: return
    val automatic = remember(modeRevision, resumed) { AutoModelStore.enabled(context) }
    val footer = stringResource(setup.problem?.message ?: R.string.nm_agnes_auto_image_authorized)
    SettingsSection(footer = footer) {
        SettingsRow(
            title = stringResource(R.string.nm_agnes_review_image),
            subtitle = setup.modelId,
            onClick = if (preparing || setup.problem == CustomImageRoute.Problem.AGNES_IMAGE_AMBIGUOUS) null else ({
                preparing = true
                scope.launch {
                    try {
                        val entryId = withContext(Dispatchers.IO) { AutoImageSelection.prepareAgnesReview(context, setup) }
                        if (entryId != null) onReview(setup.providerId, entryId)
                        else Toast.makeText(context, R.string.nm_auto_review_changed, Toast.LENGTH_LONG).show()
                    } finally {
                        preparing = false
                        resumed++
                    }
                }
            }),
            showDivider = true,
        )
        SettingsRow(
            title = stringResource(R.string.nm_auto_models_title),
            subtitle = stringResource(if (automatic) R.string.soul_lang_auto else R.string.nm_chat_mode_manual),
            showDivider = false,
        )
    }
}
