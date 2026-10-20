package com.livingworld.game.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Where the player pastes a Hugging Face token to let the AI voice NPC dialogue. The token is never shown back:
 * the field starts empty and only says whether one is saved.
 */
@Composable
fun AiSettingsScreen(hasToken: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit) {
    var entry by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI dialogue", style = MaterialTheme.typography.titleLarge)
        Text(
            if (hasToken) "On: NPCs speak in their own words, limited to what they know."
            else "Off: NPCs use their built-in lines. Paste a Hugging Face access token to turn it on.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = entry,
            onValueChange = { entry = it },
            label = { Text(if (hasToken) "Replace token" else "Hugging Face token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(entry); entry = "" }, enabled = entry.isNotBlank()) { Text("Save") }
            OutlinedButton(onClick = onRemove, enabled = hasToken) { Text("Remove") }
        }
        Text(
            "The token is stored only on this phone, outside backups, and is sent only to huggingface.co. " +
                "Use a fine-grained token whose only permission is \"Make calls to Inference Providers\".",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
