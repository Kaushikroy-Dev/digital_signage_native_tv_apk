package com.digitalsignage.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.digitalsignage.player.domain.normalizeDisplayRotationQuadrant
import com.digitalsignage.player.ui.theme.BlackCanvas

/**
 * Fullscreen shell that rotates player UI in 90° steps (matches web PlayerRotationFrame).
 * Portrait devices keep a landscape activity window; content is rotated 90° to fill the panel.
 */
@Composable
fun PlayerRotationFrame(
    degrees: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val rotation = normalizeDisplayRotationQuadrant(degrees)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BlackCanvas),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints {
            val quarterTurn = rotation == 90 || rotation == 270
            val innerModifier = if (quarterTurn) {
                Modifier
                    .width(maxHeight)
                    .height(maxWidth)
                    .graphicsLayer { rotationZ = rotation.toFloat() }
            } else {
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = if (rotation == 180) 180f else 0f
                    }
            }
            Box(modifier = innerModifier) {
                content()
            }
        }
    }
}
