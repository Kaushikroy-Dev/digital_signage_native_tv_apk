package com.digitalsignage.player.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.digitalsignage.player.domain.PairingPhase
import com.digitalsignage.player.ui.components.MsrLogo
import com.digitalsignage.player.ui.components.MsrSpinner
import com.digitalsignage.player.ui.components.PairingCodeRow
import com.digitalsignage.player.ui.theme.BlackCanvas
import com.digitalsignage.player.ui.theme.Dimens
import com.digitalsignage.player.ui.theme.ErrorLight
import com.digitalsignage.player.ui.theme.OnSurfaceVariant
import com.digitalsignage.player.ui.theme.PrimaryBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SplashScreen(playerIdLabel: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(BlackCanvas),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.StackMd)) {
            MsrLogo()
            MsrSpinner()
        }
        Text(
            text = "Player ID: $playerIdLabel",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Dimens.SafeAreaV)
        )
    }
}

@Composable
fun PairingScreen(
    phase: PairingPhase,
    code: String,
    errorMessage: String?,
    playerIdLabel: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BlackCanvas)
            .padding(horizontal = Dimens.SafeAreaH, vertical = Dimens.SafeAreaV)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            MsrLogo()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = Dimens.StackMd),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    "Register Your Screen",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                when (phase) {
                    PairingPhase.Generating -> {
                        Text(
                            "Generating pairing code…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(Dimens.StackMd))
                        MsrSpinner()
                    }
                    PairingPhase.Waiting -> {
                        Text(
                            "Enter this code in the admin portal to connect this screen",
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    PairingPhase.Error -> {
                        Text(
                            errorMessage ?: "Failed to generate pairing code",
                            style = MaterialTheme.typography.bodyLarge,
                            color = ErrorLight,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(Dimens.StackMd))
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                        ) { Text("Retry") }
                    }
                }
            }
            if (phase == PairingPhase.Waiting) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.StackMd),
                    contentAlignment = Alignment.Center
                ) {
                    PairingCodeRow(code)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (phase == PairingPhase.Waiting) {
                    StatusPill("Waiting for pairing…")
                }
                Text(
                    "DEVICE ID: $playerIdLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Row(
        modifier = Modifier
            .background(Color(0x801C1B1B), RoundedCornerShape(999.dp))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(999.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MsrSpinner()
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceVariant
        )
    }
}

@Composable
fun LoadingScreen(deviceId: String) {
    Box(modifier = Modifier.fillMaxSize().background(BlackCanvas), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("Loading Content…", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text("Device ID: $deviceId", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
            MsrSpinner()
        }
    }
}

@Composable
fun IdleScreen(message: String, backgroundUrl: String?) {
    Box(modifier = Modifier.fillMaxSize().background(BlackCanvas)) {
        if (!backgroundUrl.isNullOrBlank()) {
            AsyncImage(model = backgroundUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LiveClock()
                Spacer(modifier = Modifier.height(20.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun LiveClock() {
    // ponytail: tick every second so idle screen clock stays current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = Date()
        }
    }
    Text(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now), style = MaterialTheme.typography.displayMedium, color = Color.White)
    Text(SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(now), style = MaterialTheme.typography.bodyLarge, color = OnSurfaceVariant)
}

@Composable
fun DisplayOffScreen() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Reverse),
        label = "dotAlpha"
    )
    Box(modifier = Modifier.fillMaxSize().background(BlackCanvas), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(modifier = Modifier.size(20.dp).background(ErrorLight.copy(alpha = alpha), CircleShape))
            Text("DISPLAY IS OFF", style = MaterialTheme.typography.bodyLarge, color = Color(0xFF444444), letterSpacing = 4.sp)
        }
    }
}
