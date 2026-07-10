package com.digitalsignage.player.ui.components

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import com.digitalsignage.player.domain.normalizeDisplayRotationQuadrant
import com.digitalsignage.player.ui.theme.BlackCanvas

val LocalDisplayRotation = compositionLocalOf { 0 }

/**
 * Fullscreen shell that rotates player UI in 90° steps (matches web `PlayerRotationFrame.css`).
 *
 * Web uses swapped viewport units + CSS `transform: rotate()` on `.player-rotation-inner`.
 * On Android TV the OS stays landscape, so we apply the same transform on the Compose host
 * [View]. That rotates Compose *and* TextureView/ExoPlayer — unlike [graphicsLayer], which
 * video surfaces do not composite through.
 */
@Composable
fun PlayerRotationFrame(
    degrees: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val rotation = normalizeDisplayRotationQuadrant(degrees)
    val composeView = LocalView.current
    val config = LocalConfiguration.current
    // Transform the FrameLayout host, not ComposeView — Compose resets its own layout params.
    val rotationHost = (composeView.parent as? View) ?: composeView

    DisposableEffect(rotation, config.screenWidthDp, config.screenHeightDp) {
        val dm = composeView.context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        (rotationHost.parent as? ViewGroup)?.let { parent ->
            parent.clipChildren = true
            parent.clipToPadding = true
        }

        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            applyDisplayRotationTransform(rotationHost, rotation, screenW, screenH)
        }
        rotationHost.viewTreeObserver.addOnGlobalLayoutListener(listener)
        applyDisplayRotationTransform(rotationHost, rotation, screenW, screenH)

        onDispose {
            rotationHost.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            applyDisplayRotationTransform(rotationHost, 0, screenW, screenH)
        }
    }

    CompositionLocalProvider(LocalDisplayRotation provides rotation) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(BlackCanvas)
        ) {
            content()
        }
    }
}

/** ponytail: View-system swap+rotate — same math as web `100vh×100vw` + `rotate(90deg)`. */
private fun applyDisplayRotationTransform(view: View, rotation: Int, screenW: Int, screenH: Int) {
    val lp = view.layoutParams ?: return
    when (rotation) {
        0 -> {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            view.rotation = 0f
            view.translationX = 0f
            view.translationY = 0f
            view.pivotX = screenW / 2f
            view.pivotY = screenH / 2f
        }
        90 -> {
            lp.width = screenH
            lp.height = screenW
            view.rotation = 90f
            view.translationX = (screenW - screenH) / 2f
            view.translationY = (screenH - screenW) / 2f
            // Pivot from swapped layout size — view.width is often 0 before first layout.
            view.pivotX = screenH / 2f
            view.pivotY = screenW / 2f
        }
        180 -> {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            view.rotation = 180f
            view.translationX = 0f
            view.translationY = 0f
            view.pivotX = screenW / 2f
            view.pivotY = screenH / 2f
        }
        270 -> {
            lp.width = screenH
            lp.height = screenW
            view.rotation = 270f
            view.translationX = (screenW - screenH) / 2f
            view.translationY = (screenH - screenW) / 2f
            view.pivotX = screenH / 2f
            view.pivotY = screenW / 2f
        }
    }
    view.layoutParams = lp
    view.requestLayout()
}
