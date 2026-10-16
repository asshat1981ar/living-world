package com.livingworld.game.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.livingworld.ui.sprites.ItemRarity
import org.livingworld.ui.sprites.PortraitExpression
import org.livingworld.ui.sprites.SpriteRef
import org.livingworld.ui.theme.ChimeraSpritePalette
import org.livingworld.ui.theme.ColorValue

fun ColorValue.toCompose(): Color = Color(argb)

/**
 * Letter avatar fallback fulfilling Linear Issue PRO-58:
 * "Wire NPC letter-avatar portrait fallback in dialogue, party, camp screens when portraitResName is null"
 */
@Composable
fun LetterAvatarFallback(
    name: String,
    modifier: Modifier = Modifier.size(80.dp),
    tint: Color = ChimeraSpritePalette.INK_GREY.toCompose()
) {
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ChimeraSpritePalette.PARCHMENT_MID.toCompose())
            .border(2.dp, tint, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val spacing = 12f
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = ChimeraSpritePalette.INK_CHARCOAL.toCompose().copy(alpha = 0.15f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y + 4f),
                    strokeWidth = 1f
                )
                y += spacing
            }
        }
        Text(
            text = initial,
            color = ChimeraSpritePalette.ACCENT_GOLD.toCompose(),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Serif,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Ink wash overlay effect with bleeding vignette at card boundaries.
 */
@Composable
fun InkWashOverlay(
    modifier: Modifier = Modifier,
    color: Color = ChimeraSpritePalette.INK_BLACK.toCompose(),
    intensity: Float = 0.25f
) {
    val safeIntensity = if (intensity.isNaN()) 0.25f else intensity.coerceIn(0f, 1f)
    Canvas(modifier = modifier) {
        val radial = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                color.copy(alpha = safeIntensity * 0.5f),
                color.copy(alpha = safeIntensity)
            ),
            center = center,
            radius = (size.minDimension * 0.65f).coerceAtLeast(1f)
        )
        drawRect(brush = radial)
    }
}

/**
 * NPC Portrait with expression tint, ink-wash overlay, and Linear PRO-58 letter avatar fallback.
 */
@Composable
fun NpcPortraitCard(
    name: String,
    role: String,
    expression: PortraitExpression,
    portraitSprite: SpriteRef?,
    healthPercent: Float,
    tint: ColorValue,
    showInkWashBleed: Boolean,
    badges: List<String>,
    modifier: Modifier = Modifier.width(160.dp)
) {
    // RT-9 Fix: Clamp healthPercent against NaN or out-of-bounds values before passing to fillMaxWidth()
    val clampedHealth = if (healthPercent.isNaN()) 0f else healthPercent.coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ChimeraSpritePalette.PARCHMENT_DARK.toCompose())
            .border(2.dp, tint.toCompose(), RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            if (portraitSprite != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ChimeraSpritePalette.PARCHMENT_LIGHT.toCompose()),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name.trim().take(3).uppercase(),
                        color = ChimeraSpritePalette.INK_BLACK.toCompose(),
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                LetterAvatarFallback(
                    name = name,
                    modifier = Modifier.fillMaxSize(),
                    tint = tint.toCompose()
                )
            }

            if (showInkWashBleed) {
                InkWashOverlay(
                    modifier = Modifier.fillMaxSize(),
                    color = ChimeraSpritePalette.ACCENT_CRIMSON.toCompose(),
                    intensity = 0.4f
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = name,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1
        )
        Text(
            text = role,
            color = ChimeraSpritePalette.INK_GREY.toCompose(),
            fontSize = 12.sp,
            maxLines = 1
        )

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(ChimeraSpritePalette.INK_CHARCOAL.toCompose())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(clampedHealth)
                    .background(
                        if (clampedHealth > 0.4f) ChimeraSpritePalette.ACCENT_GOLD.toCompose()
                        else ChimeraSpritePalette.ACCENT_CRIMSON.toCompose()
                    )
            )
        }

        if (badges.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                badges.take(2).forEach { badge ->
                    Text(
                        text = badge,
                        color = ChimeraSpritePalette.ACCENT_GOLD.toCompose(),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .background(ChimeraSpritePalette.INK_BLACK.toCompose(), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Rarity seal badge for items.
 */
@Composable
fun RaritySealBadge(rarity: ItemRarity, size: Dp = 20.dp) {
    val (sealColor, text) = when (rarity) {
        ItemRarity.COMMON -> ChimeraSpritePalette.INK_GREY.toCompose() to "C"
        ItemRarity.RARE -> ChimeraSpritePalette.ACCENT_CRIMSON.toCompose() to "R"
        ItemRarity.LEGENDARY -> ChimeraSpritePalette.ACCENT_GOLD.toCompose() to "L"
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(sealColor)
            .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}
