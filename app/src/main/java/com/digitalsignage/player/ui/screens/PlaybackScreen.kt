package com.digitalsignage.player.ui.screens

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.core.net.toUri
import com.digitalsignage.player.ui.components.PlaylistCoverImage
import com.digitalsignage.player.R
import com.digitalsignage.player.ui.media.buildSignageExoPlayer
import com.digitalsignage.player.data.api.ApiClient
import com.digitalsignage.player.data.api.models.PlaylistItem
import com.digitalsignage.player.domain.PlayerUiState
import com.digitalsignage.player.domain.hasAnyEnabled
import com.digitalsignage.player.domain.resolvedOverlay
import com.digitalsignage.player.ui.playback.PlaylistTransitionEffect
import com.digitalsignage.player.ui.playback.TransitionLayerBox
import com.digitalsignage.player.ui.playback.layerVisualState
import com.digitalsignage.player.ui.components.CacheSyncOverlay
import com.digitalsignage.player.ui.components.EnvBarPosition
import com.digitalsignage.player.ui.components.EnvironmentalBar
import com.digitalsignage.player.ui.theme.BlackCanvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos

private val NATIVE_MEDIA_TYPES = setOf("video", "image", "document")

@Composable
fun PlaybackScreen(
    state: PlayerUiState.Playing,
    resolveUrl: suspend (PlaylistItem, Int?) -> String?,
    onItemFinished: () -> Unit
) {
    val items = state.content.items.orEmpty()
    val overlay = state.content.resolvedOverlay()
    val showTop = overlay?.top.hasAnyEnabled()
    val showBottom = overlay?.bottom.hasAnyEnabled()
    val transitionMs = (state.content.playlist?.transitionDurationMs ?: 500).coerceIn(100, 5000)
    val transitionEffect = PlaylistTransitionEffect.normalize(state.content.playlist?.transitionEffect)

    Box(modifier = Modifier.fillMaxSize().background(BlackCanvas)) {
        if (!state.showCacheSync && items.isNotEmpty() && state.currentIndex in items.indices) {
            StagedPlaylistPlayer(
                items = items,
                targetIndex = state.currentIndex,
                transitionMs = transitionMs,
                transitionEffect = transitionEffect,
                resolveUrl = resolveUrl,
                onItemFinished = onItemFinished
            )
        } else if (!state.showCacheSync) {
            Text("No item", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        if (showTop) {
            EnvironmentalBar(
                position = EnvBarPosition.Top,
                flags = overlay?.top,
                environmental = state.environmental,
                loading = state.environmentalLoading,
                bgColor = overlay?.bgColor,
                modifier = Modifier.align(Alignment.TopCenter).zIndex(10f)
            )
        }
        if (showBottom) {
            EnvironmentalBar(
                position = EnvBarPosition.Bottom,
                flags = overlay?.bottom,
                environmental = state.environmental,
                loading = state.environmentalLoading,
                bgColor = overlay?.bgColor,
                modifier = Modifier.align(Alignment.BottomCenter).zIndex(10f)
            )
        }
        if (state.showCacheSync) {
            CacheSyncOverlay(
                current = state.cacheSyncCurrent,
                total = state.cacheSyncTotal,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Two-layer staged player: preloads into the back slot and crossfades on top.
 * The outgoing layer stays at full opacity underneath so the black canvas never flashes.
 * Slots are swapped after transition so preloaded video/image composables are not remounted.
 */
@Composable
private fun StagedPlaylistPlayer(
    items: List<PlaylistItem>,
    targetIndex: Int,
    transitionMs: Int,
    transitionEffect: PlaylistTransitionEffect,
    resolveUrl: suspend (PlaylistItem, Int?) -> String?,
    onItemFinished: () -> Unit
) {
    val playlistKey = remember(items) { items.joinToString("|") { it.playbackKey() } }
    val safeTarget = targetIndex.coerceIn(items.indices)

    var activeSlot by remember(playlistKey) { mutableIntStateOf(0) }
    var slotIndices by remember(playlistKey) { mutableStateOf(intArrayOf(safeTarget, -1)) }
    var incomingReady by remember { mutableStateOf(false) }
    var isTransitioning by remember { mutableStateOf(false) }
    val transitionProgress = remember { Animatable(0f) }
    var animatedProgress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(transitionProgress) {
        snapshotFlow { transitionProgress.value }.collect { animatedProgress = it }
    }

    LaunchedEffect(playlistKey) {
        activeSlot = 0
        slotIndices = intArrayOf(safeTarget, -1)
        incomingReady = false
        transitionProgress.snapTo(0f)
        animatedProgress = 0f
        isTransitioning = false
    }

    LaunchedEffect(safeTarget, playlistKey) {
        if (items.isEmpty()) return@LaunchedEffect
        val current = slotIndices[activeSlot]
        if (safeTarget == current || isTransitioning) return@LaunchedEffect
        val back = 1 - activeSlot
        if (slotIndices[back] == safeTarget) return@LaunchedEffect
        incomingReady = false
        transitionProgress.snapTo(0f)
        animatedProgress = 0f
        slotIndices = slotIndices.copyOf().also { it[back] = safeTarget }
    }

    LaunchedEffect(incomingReady, slotIndices, transitionEffect) {
        if (!incomingReady || isTransitioning) return@LaunchedEffect
        val back = 1 - activeSlot
        if (slotIndices[back] < 0) return@LaunchedEffect
        isTransitioning = true
        if (transitionEffect == PlaylistTransitionEffect.None) {
            transitionProgress.snapTo(1f)
        } else {
            transitionProgress.animateTo(
                1f,
                animationSpec = tween(
                    durationMillis = transitionMs,
                    easing = transitionEffect.easing
                )
            )
        }
        val outgoingSlot = activeSlot
        val newActive = back
        activeSlot = newActive
        transitionProgress.snapTo(0f)
        animatedProgress = 0f
        incomingReady = false
        isTransitioning = false
        // Let the crossfade composite finish before tearing down the outgoing surface.
        withFrameNanos { }
        withFrameNanos { }
        slotIndices = slotIndices.copyOf().also { it[outgoingSlot] = -1 }
    }

    LaunchedEffect(slotIndices, activeSlot) {
        val back = 1 - activeSlot
        val backIdx = slotIndices[back]
        if (backIdx < 0) return@LaunchedEffect
        delay(12_000)
        if (slotIndices[back] == backIdx && !incomingReady && !isTransitioning) {
            slotIndices = slotIndices.copyOf().also { it[back] = -1 }
            transitionProgress.snapTo(0f)
            onItemFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (transitionEffect == PlaylistTransitionEffect.Flip) {
                    Modifier.graphicsLayer { cameraDistance = 28f * density }
                } else {
                    Modifier
                }
            )
    ) {
        val back = 1 - activeSlot
        val progress = animatedProgress
        val inTransition = isTransitioning || progress > 0f
        // Outgoing (active) first at bottom, incoming (back) on top during crossfade.
        for (slot in listOf(activeSlot, back)) {
            val itemIndex = slotIndices[slot]
            key("persistent-slot-$slot") {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (slot == back) 1f else 0f)
                ) {
                    if (itemIndex in items.indices) {
                        val isIncoming = slot == back
                        val layerState = layerVisualState(
                            effect = transitionEffect,
                            isIncoming = isIncoming,
                            isTransitioning = inTransition,
                            progress = progress
                        )
                        val fileType = items[itemIndex].normalizedFileType()
                        val ownsPlaybackClock = slot == activeSlot && !isTransitioning
                        val shouldPlay = ownsPlaybackClock ||
                            (isIncoming && inTransition && fileType == "video")
                        val contentActive = when {
                            ownsPlaybackClock -> true
                            fileType == "video" && slot == back -> true
                            slot == back && (incomingReady || inTransition) -> true
                            else -> false
                        }
                        // ponytail: WebView (templates) must not sit inside graphicsLayer — Android
                        // composites video to a separate surface and transforms make it invisible.
                        val mediaLayerAlpha = if (fileType in NATIVE_MEDIA_TYPES) layerState.alpha else 1f
                        val renderer: @Composable () -> Unit = {
                            key(items[itemIndex].playbackKey()) {
                                MediaItemRenderer(
                                    item = items[itemIndex],
                                    resolveUrl = resolveUrl,
                                    layerAlpha = mediaLayerAlpha,
                                    isActive = contentActive,
                                    shouldPlay = shouldPlay,
                                    ownsPlaybackClock = ownsPlaybackClock,
                                    onDisplayReady = {
                                        if (slot == back && !isTransitioning) {
                                            incomingReady = true
                                        }
                                    },
                                    onFinished = {
                                        if (ownsPlaybackClock) {
                                            onItemFinished()
                                        }
                                    },
                                    onStageFailed = {
                                        if (slot == back) {
                                            slotIndices = slotIndices.copyOf().also { it[back] = -1 }
                                            incomingReady = false
                                            scope.launch { transitionProgress.snapTo(0f) }
                                            onItemFinished()
                                        }
                                    }
                                )
                            }
                        }
                        if (fileType == "template") {
                            Box(Modifier.fillMaxSize()) { renderer() }
                        } else if (fileType in NATIVE_MEDIA_TYPES) {
                            // ponytail: AndroidView (video/image) won't follow scale/slide graphicsLayer — alpha only.
                            Box(Modifier.fillMaxSize()) { renderer() }
                        } else {
                            TransitionLayerBox(state = layerState) { renderer() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaItemRenderer(
    item: PlaylistItem,
    resolveUrl: suspend (PlaylistItem, Int?) -> String?,
    layerAlpha: Float = 1f,
    isActive: Boolean,
    shouldPlay: Boolean = isActive,
    ownsPlaybackClock: Boolean,
    onDisplayReady: () -> Unit,
    onFinished: () -> Unit,
    onStageFailed: () -> Unit = onFinished
) {
    when (item.normalizedFileType()) {
        "video" -> VideoRenderer(item, layerAlpha, resolveUrl, isActive, shouldPlay, ownsPlaybackClock, onDisplayReady, onFinished, onStageFailed)
        "image" -> ImageRenderer(item, layerAlpha, resolveUrl, item.effectiveDurationSeconds(), ownsPlaybackClock, onDisplayReady, onFinished, onStageFailed)
        "document" -> DocumentRenderer(item, layerAlpha, resolveUrl, ownsPlaybackClock, onDisplayReady, onFinished, onStageFailed)
        // Templates are rendered via a WebView — the full zone graph (media + widgets) is
        // injected as JSON and rendered by a browser-native JavaScript engine, keeping parity
        // with the web player without re-implementing every widget type in Compose.
        "template" -> {
            val zones = item.template?.zonesJson
            when {
                zones != null && templateHasWidgets(zones) && templateHasMedia(zones) ->
                    TemplateLayeredRenderer(item, ownsPlaybackClock, onDisplayReady, onFinished, onStageFailed)
                zones != null && templateIsMediaOnly(zones) ->
                    TemplateNativeRenderer(item, ownsPlaybackClock, onDisplayReady, onFinished, onStageFailed)
                else ->
                    TemplateWebRenderer(item, ownsPlaybackClock, onDisplayReady, onFinished, onStageFailed)
            }
        }
        else -> UnsupportedPlaceholder(item.fileType ?: item.contentType ?: "unknown", ownsPlaybackClock, onDisplayReady, onFinished)
    }
}

@Composable
private fun VideoRenderer(
    item: PlaylistItem,
    layerAlpha: Float,
    resolveUrl: suspend (PlaylistItem, Int?) -> String?,
    isActive: Boolean,
    shouldPlay: Boolean,
    ownsPlaybackClock: Boolean,
    onDisplayReady: () -> Unit,
    onFinished: () -> Unit,
    onStageFailed: () -> Unit
) {
    var url by remember(item.playbackKey()) { mutableStateOf<String?>(null) }
    var mediaReady by remember(item.playbackKey()) { mutableStateOf(false) }
    var hasFirstFrame by remember(item.playbackKey()) { mutableStateOf(false) }
    var playbackStarted by remember(item.playbackKey()) { mutableStateOf(false) }
    val durationCapSec = item.durationSeconds?.takeIf { it > 0 }
    val onFinishedState by rememberUpdatedState(onFinished)
    val onDisplayReadyState by rememberUpdatedState(onDisplayReady)
    val onStageFailedState by rememberUpdatedState(onStageFailed)
    val ownsClockState by rememberUpdatedState(ownsPlaybackClock)
    val shouldPlayState by rememberUpdatedState(shouldPlay)
    val layerAlphaState by rememberUpdatedState(layerAlpha)

    val readySignalled = remember(item.playbackKey()) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    LaunchedEffect(item) {
        mediaReady = false
        hasFirstFrame = false
        playbackStarted = false
        readySignalled.set(false)
        url = resolveUrl(item, null) ?: ApiClient.resolveMediaUrl(item.url)
    }

    val context = LocalContext.current
    val exoPlayer = remember(item.playbackKey()) {
        context.buildSignageExoPlayer().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(url) {
        val u = url ?: return@LaunchedEffect
        mediaReady = false
        hasFirstFrame = false
        playbackStarted = false
        readySignalled.set(false)
        exoPlayer.setMediaItem(MediaItem.fromUri(u.toUri()))
        exoPlayer.setPlaybackSpeed(1f)
        exoPlayer.volume = 0f
        exoPlayer.playWhenReady = false
        exoPlayer.prepare()
    }

    LaunchedEffect(shouldPlay, mediaReady, ownsPlaybackClock, url) {
        if (url == null) return@LaunchedEffect
        if (!mediaReady) {
            exoPlayer.volume = 0f
            exoPlayer.playWhenReady = true
            return@LaunchedEffect
        }
        if (shouldPlay) {
            if (exoPlayer.playbackState == Player.STATE_ENDED) {
                exoPlayer.seekTo(0)
                playbackStarted = false
            }
            if (!playbackStarted) {
                playbackStarted = true
            }
            exoPlayer.setPlaybackSpeed(1f)
            exoPlayer.volume = 1f
            exoPlayer.playWhenReady = true
        } else {
            exoPlayer.volume = 0f
            exoPlayer.playWhenReady = false
            if (exoPlayer.playbackState == Player.STATE_ENDED) {
                exoPlayer.seekTo(0)
                playbackStarted = false
            }
        }
    }

    LaunchedEffect(ownsPlaybackClock, mediaReady, durationCapSec) {
        if (!ownsPlaybackClock || !mediaReady || durationCapSec == null) return@LaunchedEffect
        delay(durationCapSec * 1000L)
        if (ownsClockState) onFinishedState()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        if (durationCapSec == null && ownsClockState) {
                            onFinishedState()
                        } else if (!ownsClockState) {
                            // Preload slot finished before transition — rewind so the visible
                            // handoff starts from frame 0 instead of freezing on the last frame.
                            exoPlayer.seekTo(0)
                            exoPlayer.playWhenReady = false
                            mainHandler.post { playbackStarted = false }
                        }
                    }
                    Player.STATE_READY -> {
                        if (!shouldPlayState && !ownsClockState) {
                            exoPlayer.playWhenReady = false
                            mainHandler.post { playbackStarted = false }
                        }
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                mainHandler.post {
                    hasFirstFrame = true
                    if (readySignalled.compareAndSet(false, true)) {
                        mediaReady = true
                        onDisplayReadyState()
                    }
                    if (!shouldPlayState) {
                        exoPlayer.playWhenReady = false
                        playbackStarted = false
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (ownsClockState) onFinishedState() else onStageFailedState()
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(url) {
        if (url == null) {
            delay(3000)
            if (ownsClockState) onFinishedState() else onStageFailedState()
        }
    }

    // Keep PlayerView in composition while URL resolves so the surface can warm up.
    AndroidView(
        factory = { ctx ->
            (android.view.LayoutInflater.from(ctx).inflate(R.layout.exo_player_texture_view, null) as PlayerView).apply {
                player = exoPlayer
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { view ->
            view.player = exoPlayer
            // Hide until a real frame is drawn — avoids black shutter flash during image→video crossfade.
            val visible = if (hasFirstFrame) 1f else 0f
            view.alpha = (layerAlphaState * visible).coerceIn(0f, 1f)
        },
        onRelease = { view ->
            view.player = null
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun ImageRenderer(
    item: PlaylistItem,
    layerAlpha: Float,
    resolveUrl: suspend (PlaylistItem, Int?) -> String?,
    durationSec: Int,
    ownsPlaybackClock: Boolean,
    onDisplayReady: () -> Unit,
    onFinished: () -> Unit,
    onStageFailed: () -> Unit
) {
    var url by remember(item.playbackKey()) { mutableStateOf<String?>(null) }
    var imageReady by remember(item.playbackKey()) { mutableStateOf(false) }
    var loadFailed by remember(item.playbackKey()) { mutableStateOf(false) }
    var displayReadySignalled by remember(item.playbackKey()) { mutableStateOf(false) }
    val displaySeconds = durationSec.coerceAtLeast(5)
    val onDisplayReadyState by rememberUpdatedState(onDisplayReady)
    val onFinishedState by rememberUpdatedState(onFinished)
    val onStageFailedState by rememberUpdatedState(onStageFailed)
    val ownsClockState by rememberUpdatedState(ownsPlaybackClock)
    val layerAlphaState by rememberUpdatedState(layerAlpha)
    val cacheKey = item.playbackKey()

    LaunchedEffect(item) {
        imageReady = false
        loadFailed = false
        displayReadySignalled = false
        url = resolveUrl(item, null) ?: ApiClient.resolveMediaUrl(item.url)
    }

    LaunchedEffect(item.playbackKey(), loadFailed) {
        if (loadFailed) {
            delay(500)
            if (ownsClockState) onFinishedState() else onStageFailedState()
        }
    }

    LaunchedEffect(item.playbackKey(), url) {
        if (url == null) {
            delay(3000)
            if (ownsClockState) onFinishedState() else onStageFailedState()
        }
    }

    LaunchedEffect(item.playbackKey(), url, imageReady, ownsPlaybackClock) {
        if (!ownsPlaybackClock || url == null || !imageReady) return@LaunchedEffect
        delay(displaySeconds * 1000L)
        onFinishedState()
    }

    PlaylistCoverImage(
        url = url,
        cacheKey = cacheKey,
        layerAlpha = layerAlphaState,
        visible = imageReady,
        onReady = {
            imageReady = true
            if (!displayReadySignalled) {
                displayReadySignalled = true
                onDisplayReadyState()
            }
        },
        onError = { loadFailed = true }
    )
}

@Composable
private fun DocumentRenderer(
    item: PlaylistItem,
    layerAlpha: Float,
    resolveUrl: suspend (PlaylistItem, Int?) -> String?,
    ownsPlaybackClock: Boolean,
    onDisplayReady: () -> Unit,
    onFinished: () -> Unit,
    onStageFailed: () -> Unit
) {
    val pageCount = item.pageImages.orEmpty().size
    var pageIndex by remember(item.playbackKey()) { mutableIntStateOf(0) }
    var pageUrl by remember(item.playbackKey(), pageIndex) { mutableStateOf<String?>(null) }
    var pageReady by remember(item.playbackKey(), pageIndex) { mutableStateOf(false) }
    val pageDuration = (item.pageDurationSeconds ?: 5).coerceAtLeast(3)
    val layerAlphaState by rememberUpdatedState(layerAlpha)

    LaunchedEffect(item.playbackKey(), pageIndex) {
        pageReady = false
        pageUrl = resolveUrl(item, pageIndex)
    }

    LaunchedEffect(pageReady) {
        if (pageReady) onDisplayReady()
    }

    LaunchedEffect(item.playbackKey(), pageIndex, ownsPlaybackClock) {
        if (!ownsPlaybackClock || !pageReady) return@LaunchedEffect
        delay(pageDuration * 1000L)
        if (pageCount == 0) {
            onFinished()
        } else if (pageIndex >= pageCount - 1) {
            onFinished()
        } else {
            pageIndex++
        }
    }

    if (pageCount == 0) {
        UnsupportedPlaceholder("document", ownsPlaybackClock, onDisplayReady, onFinished)
    } else {
        val pageKey = "${item.playbackKey()}:page:$pageIndex"
        var pageReadySignalled by remember(pageKey) { mutableStateOf(false) }
        PlaylistCoverImage(
            url = pageUrl,
            cacheKey = pageKey,
            layerAlpha = layerAlphaState,
            visible = pageReady,
            onReady = {
                pageReady = true
                if (!pageReadySignalled) {
                    pageReadySignalled = true
                    onDisplayReady()
                }
            },
            onError = {
                if (ownsPlaybackClock) onFinished() else onStageFailed()
            }
        )
    }
}

@Composable
private fun UnsupportedPlaceholder(
    type: String,
    ownsPlaybackClock: Boolean,
    onDisplayReady: () -> Unit,
    onFinished: () -> Unit
) {
    LaunchedEffect(type) {
        onDisplayReady()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Unsupported content: $type", color = Color.White)
    }
    LaunchedEffect(type, ownsPlaybackClock) {
        if (!ownsPlaybackClock) return@LaunchedEffect
        delay(5000)
        onFinished()
    }
}
