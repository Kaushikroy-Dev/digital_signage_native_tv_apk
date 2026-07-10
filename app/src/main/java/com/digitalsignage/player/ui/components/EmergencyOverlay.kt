package com.digitalsignage.player.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digitalsignage.player.data.api.models.EmergencyAlert
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun EmergencyOverlay(
    alert: EmergencyAlert,
    onAcknowledge: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = parseColor(alert.backgroundColor, Color.Red)
    val fg = parseColor(alert.textColor, Color.White)
    var acknowledged by remember(alert.id) { mutableStateOf(false) }
    val pulse = rememberInfiniteTransition(label = "emergency-pulse")
    val brightness by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "brightness"
    )
    val flashBg = lerp(bg, bg.copy(alpha = 1f), 0.12f * brightness)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(flashBg)
            .alpha(1f),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 32.dp)
        ) {
            Text(
                text = alert.title,
                color = fg,
                fontSize = 72.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                lineHeight = 80.sp
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = alert.message,
                color = fg,
                fontSize = 32.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = 44.sp
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = {
                    if (!acknowledged) {
                        acknowledged = true
                        onAcknowledge(alert.id)
                    }
                },
                enabled = !acknowledged,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black.copy(alpha = 0.25f),
                    contentColor = fg,
                    disabledContainerColor = Color.Black.copy(alpha = 0.4f),
                    disabledContentColor = fg.copy(alpha = 0.7f)
                ),
                modifier = Modifier.border(2.dp, fg, ButtonDefaults.shape)
            ) {
                Text(
                    text = if (acknowledged) "Acknowledged" else "I Acknowledge This Alert",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        val timeLabel = alert.triggeredAt?.let { formatTriggeredAt(it) }
        if (timeLabel != null) {
            Text(
                text = "Activated $timeLabel",
                color = fg.copy(alpha = 0.75f),
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
            )
        }
    }
}

private fun parseColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        fallback
    }
}

private fun formatTriggeredAt(iso: String): String? = try {
    val instant = Instant.parse(iso)
    DateTimeFormatter.ofPattern("h:mm a")
        .withZone(ZoneId.systemDefault())
        .format(instant)
} catch (_: Exception) {
    null
}
