package com.openminis.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tama.providers.ModelBadges
import ai.tama.providers.ModelModalities
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.i18n.uppercaseForDisplay

internal data class ActiveChatModelInfo(
    val modelName: String,
    val providerName: String,
    val entry: ModelEntry?,
    val metadata: ModelModalities?,
)

/**
 * Session Token Usage bottom sheet — mirrors iOS `TokenUsageSheet` and uses
 * the standardized chat sheet shell so its header/dismiss behavior matches
 * every other "⋯" menu sheet.
 *
 * Sections (top to bottom):
 *   - Context: Context Used / Context Window / Max Output
 *   - Thinking (only when the model supports reasoning): On/Off / Level / Supported
 *   - Tokens (Session Total): Input (incl. cache) / Output
 *   - Cache (Session Total): Cache Read / Cache Write
 *   - Agent Loop: Total Loops
 *
 * Data loads asynchronously via [ChatViewModel.loadSessionTokenStats] when the
 * sheet appears; we intentionally don't hold a live subscription — token
 * counters change per API call, not per keystroke, so pull-on-open is enough.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TokenUsageSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    activeModel: ActiveChatModelInfo? = null,
    displayedSessionId: String = "",
    streaming: Boolean = false,
    onChangeModel: () -> Unit = {},
) {
    val parallelTest = activeModel != null
    var stats by remember(if (parallelTest) displayedSessionId else "") { mutableStateOf<ChatViewModel.SessionTokenStats?>(null) }
    val contextWindow = if (parallelTest) viewModel.currentModelContextWindow else remember { viewModel.currentModelContextWindow }
    val maxOutput = if (parallelTest) viewModel.currentModelMaxOutputTokens else remember { viewModel.currentModelMaxOutputTokens }
    val thinking = if (parallelTest) viewModel.thinkingInfo() else remember { viewModel.thinkingInfo() }

    LaunchedEffect(if (parallelTest) displayedSessionId else "", parallelTest && streaming) {
        // Refresh after completion, not after each streamed token.
        if (!parallelTest || !streaming || stats == null) stats = viewModel.loadSessionTokenStats()
    }

    StandardChatSheet(
        title = stringResource(if (parallelTest) R.string.nm_chat_model_info_title else R.string.token_usage_sheet_title),
        onDismiss = onDismiss,
        // T148: iOS uses .presentationDetents([.medium]) for the same sheet
        // (AIChatView.swift:508). Match that proportion on Android so the
        // half-screen feel is consistent — the token-usage view holds maybe
        // a screenful of stat rows max and looked overgrown at 90%.
        heightFraction = if (parallelTest) 0.8f else 0.5f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (activeModel != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(activeModel.modelName.ifBlank { stringResource(R.string.nm_chat_model_info_unselected) },
                        style = MaterialTheme.typography.titleMedium)
                    if (activeModel.providerName.isNotBlank()) Text(activeModel.providerName,
                        style = MaterialTheme.typography.bodyMedium)
                    activeModel.entry?.let { entry ->
                        Text(entry.model.id, style = MaterialTheme.typography.bodySmall)
                        // These are catalog claims, not permissions or successful wire tests.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ModelBadges(entry, activeModel.metadata)
                        }
                        Text(stringResource(R.string.nm_chat_model_info_catalogue), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onChangeModel, enabled = !streaming) {
                        Text(stringResource(R.string.nm_chat_model_info_change))
                    }
                }
            }
            val s = stats
            // nanoMuse: unknown or not-yet-loaded usage is not a measured zero.
            val usageKnown = !parallelTest || s?.hasReportedUsage == true
            val onText = stringResource(R.string.common_on)
            val offText = stringResource(R.string.common_off)
            val yesText = stringResource(R.string.common_yes)
            val noText = stringResource(R.string.common_no)
            StatSection(title = stringResource(R.string.token_usage_section_context)) {
                StatRow(stringResource(if (parallelTest) R.string.nm_usage_last_reported_context else R.string.token_usage_context_used),
                    if (parallelTest && (s == null || s.context <= 0)) "—" else formatTokens(s?.context ?: 0))
                if (parallelTest) {
                    StatRow(stringResource(R.string.token_usage_context_window), contextWindow?.let(::formatTokens) ?: "—")
                    StatRow(stringResource(R.string.token_usage_max_output), maxOutput?.let(::formatTokens) ?: "—")
                } else {
                    contextWindow?.let { StatRow(stringResource(R.string.token_usage_context_window), formatTokens(it)) }
                    maxOutput?.let { StatRow(stringResource(R.string.token_usage_max_output), formatTokens(it)) }
                }
            }

            thinking?.let { t ->
                StatSection(title = stringResource(R.string.token_usage_section_thinking)) {
                    StatRow(stringResource(R.string.token_usage_thinking_label), if (t.enabled) onText else offText)
                    if (t.enabled) StatRow(stringResource(R.string.token_usage_thinking_level), t.level)
                    // nanoMuse: only reasoning support, not chat/image route compatibility.
                    StatRow(stringResource(if (parallelTest) R.string.nm_usage_thinking_supported
                        else R.string.token_usage_thinking_supported), if (t.supported) yesText else noText)
                }
            }

            StatSection(title = stringResource(R.string.token_usage_section_tokens)) {
                val inputTotal = (s?.input ?: 0L) + (s?.cacheRead ?: 0L) + (s?.cacheWrite ?: 0L)
                StatRow(stringResource(R.string.token_usage_input_with_cache), if (usageKnown) formatTokens(inputTotal) else "—")
                StatRow(stringResource(R.string.token_usage_output), if (usageKnown) formatTokens(s?.output ?: 0L) else "—")
                if (parallelTest) Text(
                    text = stringResource(R.string.nm_usage_reports_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            StatSection(title = stringResource(R.string.token_usage_section_cache)) {
                StatRow(stringResource(R.string.token_usage_cache_read), if (usageKnown) formatTokens(s?.cacheRead ?: 0L) else "—")
                StatRow(stringResource(R.string.token_usage_cache_write), if (usageKnown) formatTokens(s?.cacheWrite ?: 0L) else "—")
            }

            val confirmed = s?.confirmedReplySteps
            if (confirmed != null) StatSection(title = stringResource(R.string.nm_confirmed_reply_steps_title)) {
                StatRow(stringResource(R.string.nm_confirmed_reply_steps_total), confirmed.toString())
                Text(
                    text = stringResource(R.string.nm_confirmed_reply_steps_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else StatSection(title = stringResource(R.string.token_usage_section_agent_loop)) {
                StatRow(stringResource(R.string.token_usage_total_loops),
                    if (parallelTest && s == null) "—" else (s?.loopCount ?: 0).toString())
                if (parallelTest) {
                    Text(
                        text = stringResource(R.string.nm_loop_estimate_explanation),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title.uppercaseForDisplay(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

// iOS formatting: "%.1fM" >= 1M, "%.1fK" >= 1k, raw otherwise.
private fun formatTokens(n: Long): String = when {
    n >= 1_000_000L -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000L -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

private fun formatTokens(n: Int): String = formatTokens(n.toLong())
