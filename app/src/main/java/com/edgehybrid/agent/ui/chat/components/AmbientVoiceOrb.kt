package com.edgehybrid.agent.ui.chat.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.edgehybrid.agent.ui.theme.AuraTokens
import kotlin.math.cos
import kotlin.math.sin

/**
 * ChatGPT Advanced Voice Mode fluid animated audio orb.
 *
 * Renders multiple layered breathing spheres with organic sinusoidal deformation
 * that responds dynamically to audio input amplitude.
 */
@Composable
fun AmbientVoiceOrb(
    isListening: Boolean = true,
    isSpeaking: Boolean = false,
    audioAmplitude: Float = 0.5f,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")

    // Primary breathing pulse
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Orbital rotation phase
    val rotationPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotationPhase"
    )

    // Secondary ripple
    val rippleScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rippleScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(240.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension / 4f

            // Dynamic amplitude scaling
            val activeBoost = if (isSpeaking) 1.25f else if (isListening) 1.0f else 0.8f
            val amplitudeScale = 1.0f + (audioAmplitude * 0.4f)
            val effectiveRadius = baseRadius * pulseScale * amplitudeScale * activeBoost

            // Outer glow / ripple halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AuraTokens.Accent.copy(alpha = 0.25f),
                        AuraTokens.Accent.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = effectiveRadius * rippleScale * 1.5f
                ),
                radius = effectiveRadius * rippleScale * 1.5f,
                center = center
            )

            // Dynamic fluid layers (orbiting gradient centers)
            val radPhase = Math.toRadians(rotationPhase.toDouble())
            val offset1 = Offset(
                center.x + (cos(radPhase) * 20f).toFloat(),
                center.y + (sin(radPhase) * 20f).toFloat()
            )
            val offset2 = Offset(
                center.x - (sin(radPhase) * 25f).toFloat(),
                center.y - (cos(radPhase) * 25f).toFloat()
            )

            // Layer 1: Core vibrant violet gradient
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF8B5CF6),
                        Color(0xFF6366F1),
                        Color(0xFF3B82F6).copy(alpha = 0.8f)
                    ),
                    center = offset1,
                    radius = effectiveRadius
                ),
                radius = effectiveRadius,
                center = center
            )

            // Layer 2: Organic glowing core highlight
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFFFFF).copy(alpha = 0.6f),
                        Color(0xFFA5B4FC).copy(alpha = 0.3f),
                        Color.Transparent
                    ),
                    center = offset2,
                    radius = effectiveRadius * 0.7f
                ),
                radius = effectiveRadius * 0.7f,
                center = offset2
            )
        }
    }
}
