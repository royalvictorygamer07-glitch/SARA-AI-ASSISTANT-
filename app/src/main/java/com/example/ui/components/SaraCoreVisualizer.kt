package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ui.AssistantStatus
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanPlasma
import com.example.ui.theme.DeepIndigoCore
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.SapphirePulse
import com.example.ui.theme.VoidObsidian
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SaraCoreVisualizer(
    status: AssistantStatus,
    waveAmplitude: Float,
    liveCallMode: Boolean,
    onCoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sara_core")

    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (status) {
                    AssistantStatus.CONNECTING,
                    AssistantStatus.RECONNECTING,
                    AssistantStatus.THINKING -> 2400
                    AssistantStatus.SPEAKING -> 4500
                    AssistantStatus.CONNECTED,
                    AssistantStatus.LISTENING -> 6000
                    AssistantStatus.ERROR,
                    AssistantStatus.IDLE -> 14000
                },
                easing = LinearEasing
            )
        ),
        label = "outer_rotation"
    )

    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (status) {
                    AssistantStatus.CONNECTING,
                    AssistantStatus.RECONNECTING,
                    AssistantStatus.THINKING -> 1800
                    AssistantStatus.SPEAKING -> 3400
                    AssistantStatus.CONNECTED,
                    AssistantStatus.LISTENING -> 4800
                    AssistantStatus.ERROR,
                    AssistantStatus.IDLE -> 11000
                },
                easing = LinearEasing
            )
        ),
        label = "inner_rotation"
    )

    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing_scale"
    )

    val smoothAmp by animateFloatAsState(
        targetValue = waveAmplitude.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = 380f),
        label = "smooth_amp"
    )

    val primaryCoreColor by animateColorAsState(
        targetValue = when {
            status == AssistantStatus.ERROR -> CrimsonAlert
            status == AssistantStatus.CONNECTING ||
                status == AssistantStatus.RECONNECTING ||
                status == AssistantStatus.THINKING -> AmberThinking
            status == AssistantStatus.SPEAKING -> SapphirePulse
            status == AssistantStatus.CONNECTED -> EmeraldActive
            liveCallMode && status == AssistantStatus.LISTENING -> EmeraldActive
            status == AssistantStatus.LISTENING -> CyanPlasma
            else -> CyanPlasma.copy(alpha = 0.72f)
        },
        animationSpec = tween(350),
        label = "core_color"
    )

    val secondaryCoreColor by animateColorAsState(
        targetValue = when (status) {
            AssistantStatus.ERROR -> CrimsonAlert.copy(alpha = 0.7f)
            AssistantStatus.CONNECTING,
            AssistantStatus.RECONNECTING,
            AssistantStatus.THINKING -> Color(0xFFFF6D00)
            AssistantStatus.SPEAKING -> CyanPlasma
            AssistantStatus.CONNECTED,
            AssistantStatus.LISTENING -> if (liveCallMode) CyanPlasma else SapphirePulse
            AssistantStatus.IDLE -> SapphirePulse.copy(alpha = 0.55f)
        },
        animationSpec = tween(350),
        label = "secondary_color"
    )

    val statusLabel = when {
        status == AssistantStatus.CONNECTING -> "CONNECTING"
        status == AssistantStatus.RECONNECTING -> "RECONNECTING"
        status == AssistantStatus.CONNECTED -> "CONNECTED"
        status == AssistantStatus.LISTENING && liveCallMode -> "LIVE CALL"
        status == AssistantStatus.LISTENING -> "LISTENING"
        status == AssistantStatus.THINKING -> "THINKING"
        status == AssistantStatus.SPEAKING -> "SPEAKING"
        status == AssistantStatus.ERROR -> "ERROR"
        else -> "IDLE"
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(216.dp)
            .clip(CircleShape)
            .semantics { contentDescription = "SARA AI Core, status $statusLabel. Tap to toggle voice." }
            .testTag("sara_ai_core")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCoreClick
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f
            val dynamicPulse = if (status == AssistantStatus.IDLE || status == AssistantStatus.ERROR) {
                breathingScale
            } else {
                0.96f + smoothAmp * 0.14f
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryCoreColor.copy(alpha = 0.28f + smoothAmp * 0.22f),
                        secondaryCoreColor.copy(alpha = 0.09f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = maxRadius
                ),
                radius = maxRadius,
                center = center
            )

            val outerRingRadius = maxRadius * 0.88f
            val outerTopLeft = Offset(center.x - outerRingRadius, center.y - outerRingRadius)
            val outerSize = Size(outerRingRadius * 2f, outerRingRadius * 2f)
            for (i in 0 until 4) {
                drawArc(
                    color = primaryCoreColor.copy(alpha = 0.72f),
                    startAngle = outerRotation + i * 90f,
                    sweepAngle = 56f,
                    useCenter = false,
                    topLeft = outerTopLeft,
                    size = outerSize,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            val barCount = 48
            val baseWaveRadius = maxRadius * 0.69f
            for (i in 0 until barCount) {
                val angleRad = ((i.toFloat() / barCount) * 2f * PI).toFloat()
                val harmonic = sin(angleRad * 5f + outerRotation * 0.05f) * 0.5f + 0.5f
                val barLen = when (status) {
                    AssistantStatus.IDLE,
                    AssistantStatus.ERROR -> 4.dp.toPx() + harmonic * 4.dp.toPx()
                    AssistantStatus.CONNECTING,
                    AssistantStatus.RECONNECTING,
                    AssistantStatus.THINKING -> 6.dp.toPx() + harmonic * 12.dp.toPx()
                    AssistantStatus.CONNECTED,
                    AssistantStatus.LISTENING,
                    AssistantStatus.SPEAKING -> 5.dp.toPx() + (0.25f + smoothAmp * 0.95f) * harmonic * 18.dp.toPx()
                }
                val start = Offset(
                    x = center.x + cos(angleRad) * baseWaveRadius,
                    y = center.y + sin(angleRad) * baseWaveRadius
                )
                val end = Offset(
                    x = center.x + cos(angleRad) * (baseWaveRadius + barLen),
                    y = center.y + sin(angleRad) * (baseWaveRadius + barLen)
                )
                drawLine(
                    color = if (i % 2 == 0) primaryCoreColor.copy(alpha = 0.85f) else secondaryCoreColor.copy(alpha = 0.75f),
                    start = start,
                    end = end,
                    strokeWidth = 2.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            val innerRingRadius = maxRadius * 0.58f
            val innerTopLeft = Offset(center.x - innerRingRadius, center.y - innerRingRadius)
            val innerSize = Size(innerRingRadius * 2f, innerRingRadius * 2f)
            for (i in 0 until 3) {
                drawArc(
                    color = secondaryCoreColor.copy(alpha = 0.85f),
                    startAngle = innerRotation + i * 120f,
                    sweepAngle = 78f,
                    useCenter = false,
                    topLeft = innerTopLeft,
                    size = innerSize,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            val nucleusRadius = maxRadius * 0.44f * dynamicPulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryCoreColor.copy(alpha = 0.34f),
                        DeepIndigoCore,
                        VoidObsidian
                    ),
                    center = center,
                    radius = nucleusRadius
                ),
                radius = nucleusRadius,
                center = center
            )
            drawCircle(
                color = primaryCoreColor,
                radius = nucleusRadius,
                center = center,
                style = Stroke(width = 2.2.dp.toPx())
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "S.A.R.A",
                style = MaterialTheme.typography.titleSmall,
                color = primaryCoreColor
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun VoiceWaveformStrip(
    status: AssistantStatus,
    waveAmplitude: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_strip")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing)
        ),
        label = "wave_phase"
    )

    val barColor by animateColorAsState(
        targetValue = when (status) {
            AssistantStatus.CONNECTED -> EmeraldActive
            AssistantStatus.LISTENING -> CyanPlasma
            AssistantStatus.SPEAKING -> SapphirePulse
            AssistantStatus.CONNECTING,
            AssistantStatus.RECONNECTING,
            AssistantStatus.THINKING -> AmberThinking
            AssistantStatus.ERROR -> CrimsonAlert
            AssistantStatus.IDLE -> CyanPlasma.copy(alpha = 0.35f)
        },
        label = "wave_color"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val count = 32
            val slotWidth = size.width / count
            val barWidth = (slotWidth * 0.52f).coerceAtLeast(3.dp.toPx())
            val midY = size.height / 2f

            for (i in 0 until count) {
                val normPos = i.toFloat() / count
                val edgeWeight = sin(normPos * PI).toFloat().coerceAtLeast(0.15f)
                val osc = sin(phase + i * 0.45f) * 0.5f + 0.5f
                val activeBoost = when (status) {
                    AssistantStatus.IDLE,
                    AssistantStatus.ERROR -> 0.16f
                    AssistantStatus.CONNECTING,
                    AssistantStatus.RECONNECTING,
                    AssistantStatus.THINKING -> 0.35f + 0.25f * osc
                    AssistantStatus.CONNECTED,
                    AssistantStatus.LISTENING,
                    AssistantStatus.SPEAKING -> 0.22f + waveAmplitude.coerceIn(0.05f, 1f) * 0.78f
                }
                val barHeight = (size.height * activeBoost * osc * edgeWeight)
                    .coerceIn(4.dp.toPx(), size.height)

                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(
                        x = i * slotWidth + (slotWidth - barWidth) / 2f,
                        y = midY - barHeight / 2f
                    ),
                    size = Size(width = barWidth, height = barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
    }
}
