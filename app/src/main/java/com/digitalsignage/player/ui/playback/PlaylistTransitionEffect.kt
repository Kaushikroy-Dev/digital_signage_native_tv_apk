package com.digitalsignage.player.ui.playback

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

enum class PlaylistTransitionEffect {
    Fade,
    Slide,
    Wipe,
    Zoom,
    Rotate,
    Dissolve,
    Blur,
    SlideUp,
    SlideDown,
    SlideLeft,
    SlideRight,
    Flip,
    ScaleDown,
    Bounce,
    None;

    val easing: Easing
        get() = when (this) {
            Bounce -> CubicBezierEasing(0.22f, 1.4f, 0.36f, 1f)
            else -> FastOutSlowInEasing
        }

    companion object {
        private val known = entries.associateBy { it.name.lowercase() }

        fun normalize(raw: String?): PlaylistTransitionEffect {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return Fade
            return known[trimmed.lowercase()] ?: Fade
        }
    }
}

data class LayerVisualState(
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val translationXFraction: Float = 0f,
    val translationYFraction: Float = 0f,
    val rotationZ: Float = 0f,
    val rotationY: Float = 0f,
    val blurRadius: Dp = 0.dp,
    /** 0 = fully clipped (hidden), 1 = fully visible. Used for wipe. */
    val wipeReveal: Float = 1f
)

fun layerVisualState(
    effect: PlaylistTransitionEffect,
    isIncoming: Boolean,
    isTransitioning: Boolean,
    progress: Float
): LayerVisualState {
    val t = progress.coerceIn(0f, 1f)
    if (!isTransitioning) {
        return if (isIncoming) effect.hiddenIncoming() else LayerVisualState()
    }
    return if (isIncoming) effect.incomingAt(t) else effect.outgoingAt(t)
}

private fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t

private fun PlaylistTransitionEffect.hiddenIncoming(): LayerVisualState = incomingAt(0f)

private fun PlaylistTransitionEffect.incomingAt(t: Float): LayerVisualState = when (this) {
    PlaylistTransitionEffect.Fade, PlaylistTransitionEffect.None -> LayerVisualState(alpha = t)
    PlaylistTransitionEffect.Slide, PlaylistTransitionEffect.SlideRight -> LayerVisualState(
        alpha = t,
        translationXFraction = lerp(1f, 0f, t)
    )
    PlaylistTransitionEffect.SlideLeft -> LayerVisualState(
        alpha = t,
        translationXFraction = lerp(-1f, 0f, t)
    )
    PlaylistTransitionEffect.SlideUp -> LayerVisualState(
        alpha = t,
        translationYFraction = lerp(1f, 0f, t)
    )
    PlaylistTransitionEffect.SlideDown -> LayerVisualState(
        alpha = t,
        translationYFraction = lerp(-1f, 0f, t)
    )
    PlaylistTransitionEffect.Zoom -> LayerVisualState(
        alpha = t,
        scale = lerp(0.78f, 1f, t)
    )
    PlaylistTransitionEffect.ScaleDown -> LayerVisualState(
        alpha = t,
        scale = lerp(1.18f, 1f, t)
    )
    PlaylistTransitionEffect.Rotate -> LayerVisualState(
        alpha = t,
        scale = lerp(0.88f, 1f, t),
        rotationZ = lerp(-14f, 0f, t)
    )
    PlaylistTransitionEffect.Bounce -> LayerVisualState(
        alpha = t,
        scale = lerp(0.72f, 1f, t)
    )
    PlaylistTransitionEffect.Flip -> LayerVisualState(
        alpha = t,
        rotationY = lerp(88f, 0f, t)
    )
    PlaylistTransitionEffect.Wipe -> LayerVisualState(
        alpha = t,
        wipeReveal = t
    )
    PlaylistTransitionEffect.Dissolve -> LayerVisualState(
        alpha = t,
        blurRadius = lerp(14f, 0f, t).dp
    )
    PlaylistTransitionEffect.Blur -> LayerVisualState(
        alpha = t,
        blurRadius = lerp(22f, 0f, t).dp
    )
}

private fun PlaylistTransitionEffect.outgoingAt(t: Float): LayerVisualState = when (this) {
    PlaylistTransitionEffect.Fade -> LayerVisualState(alpha = 1f - t)
    PlaylistTransitionEffect.None -> LayerVisualState(alpha = 1f)
    PlaylistTransitionEffect.Slide, PlaylistTransitionEffect.SlideRight -> LayerVisualState(
        alpha = 1f,
        translationXFraction = lerp(0f, -0.45f, t)
    )
    PlaylistTransitionEffect.SlideLeft -> LayerVisualState(
        alpha = 1f,
        translationXFraction = lerp(0f, 0.45f, t)
    )
    PlaylistTransitionEffect.SlideUp -> LayerVisualState(
        alpha = 1f,
        translationYFraction = lerp(0f, -0.45f, t)
    )
    PlaylistTransitionEffect.SlideDown -> LayerVisualState(
        alpha = 1f,
        translationYFraction = lerp(0f, 0.45f, t)
    )
    PlaylistTransitionEffect.Zoom -> LayerVisualState(
        alpha = 1f,
        scale = lerp(1f, 1.12f, t)
    )
    PlaylistTransitionEffect.ScaleDown -> LayerVisualState(
        alpha = 1f,
        scale = lerp(1f, 0.72f, t)
    )
    PlaylistTransitionEffect.Rotate -> LayerVisualState(
        alpha = 1f,
        scale = lerp(1f, 0.9f, t),
        rotationZ = lerp(0f, 10f, t)
    )
    PlaylistTransitionEffect.Bounce -> LayerVisualState(
        alpha = 1f,
        scale = lerp(1f, 0.86f, t)
    )
    PlaylistTransitionEffect.Flip -> LayerVisualState(
        alpha = 1f,
        rotationY = lerp(0f, -88f, t)
    )
    PlaylistTransitionEffect.Wipe -> LayerVisualState(
        alpha = 1f,
        wipeReveal = lerp(1f, 0f, t)
    )
    PlaylistTransitionEffect.Dissolve -> LayerVisualState(
        alpha = 1f,
        blurRadius = lerp(0f, 14f, t).dp
    )
    PlaylistTransitionEffect.Blur -> LayerVisualState(
        alpha = 1f,
        blurRadius = lerp(0f, 22f, t).dp
    )
}

@Composable
fun TransitionLayerBox(
    state: LayerVisualState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val blurMod = if (state.blurRadius > 0.dp) Modifier.blur(state.blurRadius) else Modifier
    val wipeMod = if (state.wipeReveal < 1f) {
        Modifier.drawWithContent {
            val revealWidth = size.width * state.wipeReveal.coerceIn(0f, 1f)
            clipRect(left = 0f, top = 0f, right = max(revealWidth, 0f), bottom = size.height) {
                this@drawWithContent.drawContent()
            }
        }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = state.alpha
                scaleX = state.scale
                scaleY = state.scale
                translationX = state.translationXFraction * size.width
                translationY = state.translationYFraction * size.height
                rotationZ = state.rotationZ
                rotationY = state.rotationY
                cameraDistance = 12f * density
            }
            .then(blurMod)
            .then(wipeMod)
    ) {
        content()
    }
}
