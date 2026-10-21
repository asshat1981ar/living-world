package com.livingworld.game.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.livingworld.game.android.ai.AiEligibility
import com.livingworld.game.android.ai.AiUiState
import org.livingworld.ai.ondevice.ModelAssetDescriptor

/**
 * The AI tab: an opt-in switch for on-device NPC dialogue, the model download, a speed test and plain-words status.
 * Nothing here interrupts play; a broken or slow AI only shows up on this screen.
 */
@Composable
fun AiSettingsScreen(
    state: AiUiState,
    choices: List<ModelAssetDescriptor>,
    onEnabledChange: (Boolean) -> Unit,
    onChooseModel: (ModelAssetDescriptor) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
    onSpeedTest: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Enhanced dialogue", style = MaterialTheme.typography.titleLarge)
        Text(
            "An AI on this phone rewords what NPCs say, using only what they know. It needs no account or internet " +
                "after the one-time download. NPCs always have their built-in lines, so the game works the same without it.",
            style = MaterialTheme.typography.bodyMedium
        )

        when (val e = state.eligibility) {
            is AiEligibility.Unsupported -> {
                Text(e.reason, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            is AiEligibility.Eligible -> Unit
        }

        state.autoDisabledReason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("On-device AI", modifier = Modifier.weight(1f))
            Switch(checked = state.enabled, onCheckedChange = onEnabledChange)
        }

        if (choices.size > 1) {
            Text("Model", style = MaterialTheme.typography.titleSmall)
            choices.forEach { choice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = choice == state.model, onClick = { onChooseModel(choice) })
                    Text("${choice.displayName}, ${megabytes(choice.expectedSizeBytes)}")
                }
            }
        } else {
            Text("Model: ${state.model.displayName}, ${megabytes(state.model.expectedSizeBytes)}")
        }

        val progress = state.downloadProgress
        when {
            progress != null -> {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("Downloading... ${(progress * 100).toInt()}%")
                OutlinedButton(onClick = onCancelDownload) { Text("Cancel") }
            }
            state.installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSpeedTest, enabled = !state.busy) { Text("Test speed") }
                OutlinedButton(onClick = onDelete, enabled = !state.busy) { Text("Delete model") }
            }
            else -> Button(onClick = onDownload) { Text("Download (${megabytes(state.model.expectedSizeBytes)})") }
        }

        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        if (state.enabled && state.installed) {
            val stats = state.stats
            val usage = state.usage
            Text("This session", style = MaterialTheme.typography.titleSmall)
            Text(
                "${usage.aiLines} of ${usage.npcLines} NPC lines were the AI's." +
                    (state.timings.loadMillis?.let { " Model load: ${"%.1f".format(it / 1000.0)} s." } ?: "") +
                    (state.timings.lastReplyMillis?.let { " Last reply: ${"%.1f".format(it / 1000.0)} s." } ?: ""),
                style = MaterialTheme.typography.bodySmall
            )
            if (stats != null) {
                Text(
                    "Prepared: ${stats.accepted} used whole, ${stats.salvaged} trimmed, ${stats.rejected} rejected, ${stats.failed} failed.",
                    style = MaterialTheme.typography.bodySmall
                )
                stats.lastFailure?.let { Text("Last problem: $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun megabytes(bytes: Long) = "${bytes / 1_000_000} MB"
