package com.digitalsignage.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.digitalsignage.player.ui.theme.CodeTileStyle
import com.digitalsignage.player.ui.theme.Dimens
import com.digitalsignage.player.ui.theme.PrimaryBlue
import com.digitalsignage.player.ui.theme.PrimaryLight
import com.digitalsignage.player.ui.theme.SurfaceVariant

@Composable
fun MsrSpinner(modifier: Modifier = Modifier, color: Color = PrimaryLight) {
    CircularProgressIndicator(
        modifier = modifier.size(20.dp),
        color = color,
        strokeWidth = 2.5.dp
    )
}

@Composable
fun PairingCodeRow(code: String, modifier: Modifier = Modifier) {
    val clean = code.uppercase().filter { it.isLetterOrDigit() }.take(8)
    val padded = clean.padEnd(8, ' ')
    val group1 = padded.take(4).toList()
    val group2 = padded.drop(4).take(4).toList()

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            group1.forEach { PairingDigitTile(it) }
        }
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .background(SurfaceVariant, RoundedCornerShape(2.dp))
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            group2.forEach { PairingDigitTile(it) }
        }
    }
}

@Composable
private fun PairingDigitTile(char: Char) {
    GlassSurface(
        modifier = Modifier
            .width(Dimens.PairingTileW)
            .height(Dimens.PairingTileH),
        cornerRadius = 8.dp,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (char == ' ') "" else char.toString(),
            style = CodeTileStyle,
            color = PrimaryBlue,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
