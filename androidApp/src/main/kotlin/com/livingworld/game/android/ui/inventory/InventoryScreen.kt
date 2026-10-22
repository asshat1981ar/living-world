package com.livingworld.game.android.ui.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.livingworld.game.android.ui.components.RaritySealBadge
import com.livingworld.game.android.ui.theme.toComposeColor
import org.livingworld.ui.theme.ChimeraSpritePalette

@Composable
fun InventoryScreen(
    viewModel: InventoryViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState(initial = InventoryUiState())

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ChimeraSpritePalette.PARCHMENT_DARK.toComposeColor())
            .padding(16.dp)
    ) {
        // Player Purse
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(ChimeraSpritePalette.PARCHMENT_MID.toComposeColor())
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Marketplace & Inventory",
                color = ChimeraSpritePalette.ACCENT_GOLD.toComposeColor(),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                text = "${String.format("%.1f", state.playerCoins)} Silver",
                color = Color(0xFFFFD700),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Commodity Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.catalog) { item ->
                val owned = state.inventoryCounts[item.itemId.value] ?: 0

                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ChimeraSpritePalette.PARCHMENT_MID.toComposeColor())
                        .border(1.dp, ChimeraSpritePalette.INK_GREY.toComposeColor(), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.name,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                        RaritySealBadge(rarity = item.rarity, size = 18.dp)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Price: ${String.format("%.1f", item.currentPrice)} s",
                        color = ChimeraSpritePalette.ACCENT_GOLD.toComposeColor(),
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Owned: $owned",
                        color = ChimeraSpritePalette.INK_GREY.toComposeColor(),
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { viewModel.buyItem(item.itemId) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ChimeraSpritePalette.INK_CHARCOAL.toComposeColor())
                        ) {
                            Text("Buy", fontSize = 10.sp, color = Color.White)
                        }
                        Button(
                            onClick = { viewModel.sellItem(item.itemId) },
                            enabled = owned > 0,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ChimeraSpritePalette.ACCENT_CRIMSON.toComposeColor())
                        ) {
                            Text("Sell", fontSize = 10.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
