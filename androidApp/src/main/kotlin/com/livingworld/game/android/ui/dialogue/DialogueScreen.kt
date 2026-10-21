package com.livingworld.game.android.ui.dialogue

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livingworld.game.android.ui.components.NpcPortraitCard
import com.livingworld.game.android.ui.theme.toComposeColor
import org.livingworld.ui.theme.ChimeraSpritePalette

@Composable
fun DialogueScreen(
    viewModel: DialogueViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ChimeraSpritePalette.PARCHMENT_DARK.toComposeColor())
            .padding(16.dp)
    ) {
        // Header: NPC Overview
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val card = state.portraitCard
            if (card != null) {
                NpcPortraitCard(
                    name = card.name,
                    role = card.roleTitle,
                    expression = card.expression,
                    portraitSprite = card.portraitSprite,
                    healthPercent = card.healthPercent,
                    tint = card.tint,
                    showInkWashBleed = card.showInkWashBleed,
                    badges = card.badges
                )
            }

            // Relationship Telemetry Board
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChimeraSpritePalette.PARCHMENT_MID.toComposeColor())
                    .border(1.dp, ChimeraSpritePalette.INK_GREY.toComposeColor(), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "Social Telemetry",
                    color = ChimeraSpritePalette.ACCENT_GOLD.toComposeColor(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Trust: ${String.format("%.2f", state.relationship.trust)}", color = Color.White, fontSize = 12.sp)
                Text(text = "Affection: ${String.format("%.2f", state.relationship.affection)}", color = Color.White, fontSize = 12.sp)
                Text(text = "Resentment: ${String.format("%.2f", state.relationship.resentment)}", color = Color.White, fontSize = 12.sp)
                Text(text = "Sentiment: ${String.format("%.2f", state.relationship.overallSentiment)}", color = ChimeraSpritePalette.ACCENT_GOLD.toComposeColor(), fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Dialogue Log
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(ChimeraSpritePalette.PARCHMENT_MID.toComposeColor())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.dialogueHistory) { line ->
                val bg = if (line.isPlayer) ChimeraSpritePalette.INK_CHARCOAL.toComposeColor()
                         else ChimeraSpritePalette.PARCHMENT_LIGHT.toComposeColor()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(bg)
                        .padding(8.dp)
                ) {
                    Text(
                        // A faint mark on lines the AI wrote, so a player can tell the AI voice is getting through.
                        text = if (line.byAi) "${line.speaker}  ✨" else line.speaker,
                        color = if (line.isPlayer) ChimeraSpritePalette.ACCENT_GOLD.toComposeColor() else Color(0xFFD4AF37),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = line.text,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Dialogue Choices
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            state.availableOptions.forEachIndexed { index, option ->
                Button(
                    onClick = { viewModel.chooseOption(index) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ChimeraSpritePalette.INK_CHARCOAL.toComposeColor()
                    ),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = option,
                        color = ChimeraSpritePalette.ACCENT_GOLD.toComposeColor(),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
