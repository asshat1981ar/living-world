package com.livingworld.game.android.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livingworld.game.android.ui.theme.toComposeColor
import org.livingworld.ui.theme.ChimeraSpritePalette

@Composable
fun MapScreen(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val canvas = state.canvasState

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ChimeraSpritePalette.PARCHMENT_DARK.toComposeColor())
            .padding(16.dp)
    ) {
        // Title Bar
        Text(
            text = canvas.title,
            color = ChimeraSpritePalette.ACCENT_GOLD.toComposeColor(),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 2D Spatial Map Canvas Box
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(ChimeraSpritePalette.PARCHMENT_MID.toComposeColor())
                .border(2.dp, ChimeraSpritePalette.INK_GREY.toComposeColor(), RoundedCornerShape(8.dp))
        ) {
            // Draw connection lines
            Canvas(modifier = Modifier.fillMaxSize()) {
                for (conn in canvas.connections) {
                    val start = Offset(conn.sourcePoint.x * size.width, conn.sourcePoint.y * size.height)
                    val end = Offset(conn.targetPoint.x * size.width, conn.targetPoint.y * size.height)
                    val lineColor = if (conn.isBlocked) ChimeraSpritePalette.ACCENT_CRIMSON.toComposeColor()
                                    else ChimeraSpritePalette.INK_GREY.toComposeColor().copy(alpha = 0.5f)
                    drawLine(
                        color = lineColor,
                        start = start,
                        end = end,
                        strokeWidth = if (conn.isBlocked) 4f else 2f
                    )
                }
            }

            // Draw interactive nodes
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val w = maxWidth
                val h = maxHeight

                for (node in canvas.nodes) {
                    val isSelected = state.selectedNode?.locationId == node.locationId
                    val borderColor = when {
                        node.isPlayerPresent -> ChimeraSpritePalette.ACCENT_GOLD.toComposeColor()
                        isSelected -> Color.White
                        else -> ChimeraSpritePalette.INK_GREY.toComposeColor()
                    }

                    Column(
                        modifier = Modifier
                            .offset(
                                x = (node.position.x * w.value).dp - 32.dp,
                                y = (node.position.y * h.value).dp - 32.dp
                            )
                            .clickable { viewModel.selectNode(node.locationId) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(ChimeraSpritePalette.INK_CHARCOAL.toComposeColor())
                                .border(2.dp, borderColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = node.residentTokens.size.toString(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            text = node.label,
                            color = Color.White,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Selected Node Inspection & Travel Panel
        val selected = state.selectedNode
        if (selected != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChimeraSpritePalette.PARCHMENT_LIGHT.toComposeColor())
                    .padding(12.dp)
            ) {
                Text(text = "Location: ${selected.label}", color = ChimeraSpritePalette.INK_BLACK.toComposeColor(), fontWeight = FontWeight.Bold)
                Text(text = "Occupants: ${selected.residentTokens.size} NPCs present", color = ChimeraSpritePalette.INK_CHARCOAL.toComposeColor(), fontSize = 12.sp)

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.travelToSelected() },
                    enabled = !selected.isPlayerPresent,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ChimeraSpritePalette.ACCENT_CRIMSON.toComposeColor())
                ) {
                    Text(if (selected.isPlayerPresent) "Current Location" else "Travel to ${selected.label}")
                }
            }
        }
    }
}
