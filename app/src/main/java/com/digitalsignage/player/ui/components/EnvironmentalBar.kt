package com.digitalsignage.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.digitalsignage.player.data.api.models.EnvironmentalData
import com.digitalsignage.player.data.api.models.OverlayBarFlags
import com.digitalsignage.player.ui.theme.GreenOnline
import com.digitalsignage.player.ui.theme.RedOffline
import com.digitalsignage.player.ui.theme.Dimens
import com.digitalsignage.player.ui.theme.EnvBarBg
import com.digitalsignage.player.ui.theme.OnSurfaceVariant
import com.digitalsignage.player.ui.theme.PrimaryBlue
import com.digitalsignage.player.ui.theme.SkyAccent
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class EnvBarPosition { Top, Bottom }

@Composable
fun EnvironmentalBar(
    position: EnvBarPosition,
    flags: OverlayBarFlags?,
    environmental: EnvironmentalData?,
    loading: Boolean,
    bgColor: String? = null,
    modifier: Modifier = Modifier
) {
    if (flags == null) return
    val showClock = flags.clock == true
    val showDate = flags.date == true
    val showWeather = flags.weather == true
    val showAqi = flags.aqi == true
    if (!showClock && !showDate && !showWeather && !showAqi) return

    var now by remember { mutableStateOf(Date()) }
    if (showClock || showDate) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                now = Date()
            }
        }
    }

    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateFmt = SimpleDateFormat("EEE MMM d", Locale.getDefault())
    val barColor = parseOverlayBgColor(bgColor)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.EnvBarHeight)
            .background(barColor)
            .padding(horizontal = Dimens.SafeAreaH),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ponytail: reduced spacing so all 4 items fit in portrait (narrower width)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showClock) {
                Text(
                    text = timeFmt.format(now),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )
            }
            if (showDate) {
                Text(
                    text = dateFmt.format(now).uppercase(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnSurfaceVariant
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showWeather) {
                val temp = environmental?.weather?.tempC
                val desc = environmental?.weather?.description ?: "Weather"
                Text(
                    text = if (loading) "..." else "${temp ?: "--"}°C $desc",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
            }
            if (showAqi) {
                val aqi = environmental?.aqi?.value
                val label = environmental?.aqi?.label ?: "AQI"
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (loading) "..." else "AQI ${aqi ?: "--"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = OnSurfaceVariant
                    )
                    Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
            }
        }
    }
}

private fun parseOverlayBgColor(value: String?): Color {
    if (value.isNullOrBlank()) return EnvBarBg
    val rgba = Regex("""rgba\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([0-9.]+)\s*\)""")
        .matchEntire(value.trim())
    if (rgba != null) {
        val (r, g, b, a) = rgba.destructured
        return Color(r.toInt() / 255f, g.toInt() / 255f, b.toInt() / 255f, a.toFloat())
    }
    return try {
        Color(android.graphics.Color.parseColor(value.trim()))
    } catch (_: Exception) {
        EnvBarBg
    }
}

@Composable
fun NetworkStatusDot(isOffline: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(
                color = if (isOffline) RedOffline else GreenOnline,
                shape = CircleShape
            )
    )
}

@Composable
fun CacheSyncOverlay(
    current: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        GlassSurface(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth(0.45f),
            cornerRadius = 24.dp,
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Downloading playlist for offline playback",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                LinearProgressIndicator(
                    progress = { if (total > 0) current.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = PrimaryBlue
                )
                Text(
                    text = "$current of $total items",
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
