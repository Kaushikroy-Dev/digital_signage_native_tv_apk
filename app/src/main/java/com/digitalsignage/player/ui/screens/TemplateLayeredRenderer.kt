package com.digitalsignage.player.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.digitalsignage.player.data.api.models.PlaylistItem

/**
 * Media zones via native Compose (reliable video on TV) + widget zones via transparent WebView overlay.
 */
@Composable
fun TemplateLayeredRenderer(
    item: PlaylistItem,
    ownsPlaybackClock: Boolean,
    onDisplayReady: () -> Unit,
    onFinished: () -> Unit,
    onStageFailed: () -> Unit = onFinished
) {
    var mediaReady by remember(item.playbackKey()) { mutableStateOf(false) }
    var widgetReady by remember(item.playbackKey()) { mutableStateOf(false) }
    var readySignalled by remember(item.playbackKey()) { mutableStateOf(false) }

    fun trySignalReady() {
        if (!readySignalled && mediaReady && widgetReady) {
            readySignalled = true
            onDisplayReady()
        }
    }

    androidx.compose.runtime.LaunchedEffect(mediaReady, widgetReady) {
        trySignalReady()
    }

    Box(Modifier.fillMaxSize()) {
        TemplateNativeRenderer(
            item = item,
            ownsPlaybackClock = ownsPlaybackClock,
            onDisplayReady = {
                mediaReady = true
                trySignalReady()
            },
            onFinished = onFinished,
            onStageFailed = onStageFailed
        )
        TemplateWebRenderer(
            item = item,
            ownsPlaybackClock = false,
            onDisplayReady = {
                widgetReady = true
                trySignalReady()
            },
            onFinished = { },
            onStageFailed = {
                widgetReady = true
                trySignalReady()
            },
            widgetOverlayOnly = true
        )
    }
}
