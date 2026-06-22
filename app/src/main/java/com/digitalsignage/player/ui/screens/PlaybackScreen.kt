package com.digitalsignage.player.ui.screens

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import androidx.core.net.toUri
import com.digitalsignage.player.R
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
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
        if (showBottom) {
            EnvironmentalBar(
                position = EnvBarPosition.Bottom,
                flags = overlay?.bottom,
                environmental = state.environmental,
                loading = state.environmentalLoading,
                bgColor = overlay?.bgColor,
                modifier = Modifier.align(Alignment.BottomCenter)
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
                        TransitionLayerBox(state = layerState) {
                            key(items[itemIndex].playbackKey()) {
                                MediaItemRenderer(
                                item = items[itemIndex],
                                resolveUrl = resolveUrl,
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
    isActive: Boolean,
    shouldPlay: Boolean = isActive,
    ownsPlaybackClock: Boolean,
    onDisplayReady: () -> Unit,
    onFinished: () -> Unit,
    onStageFailed: () -> Unit = onFinished
) {
    when (item.normalizedFileType()) {
        "video" -> VideoRenderer(item, resolveUrl, isActive, shouldPlay, ownsPlaybackClock, onDisplayReady, onFinished, onStageFailed)
        "image" -> ImageRenderer(item, resolveUrl, item.effectiveDurationSeconds(), ownsPlaybackClock, onDisplayReady, onFinished, onStageFailed)
        "document" -> DocumentRenderer(item, resolveUrl, ownsPlaybackClock, onDisplayReady, onFinished, onStageFailed)
        "template" -> TemplatePlaceholder(item, ownsPlaybackClock, onDisplayReady, onFinished)
        else -> UnsupportedPlaceholder(item.fileType ?: item.contentType ?: "unknown", ownsPlaybackClock, onDisplayReady, onFinished)
    }
}

@Composable
private fun VideoRenderer(
    item: PlaylistItem,
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
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
            volume = 0f
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
        // Decode the first frame for staging without advancing playback (matches web autoPlay=false).
        exoPlayer.playWhenReady = true
    }

    LaunchedEffect(shouldPlay, mediaReady, ownsPlaybackClock) {
        if (!mediaReady) return@LaunchedEffect
        if (shouldPlay) {
            if (exoPlayer.playbackState == Player.STATE_ENDED) {
                exoPlayer.seekTo(0)
                playbackStarted = false
            }
            if (!playbackStarted) {
                playbackStarted = true
                // Preload already decoded frame 0 — avoid seekTo(0) which blanks TextureView.
                if (exoPlayer.currentPosition > 150) {
                    exoPlayer.seekTo(0)
                }
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
                        if (!shouldPlayState && !ownsClockState && exoPlayer.isPlaying) {
                            exoPlayer.playWhenReady = false
                            if (exoPlayer.currentPosition > 0) {
                                exoPlayer.seekTo(0)
                            }
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
                        if (exoPlayer.currentPosition > 0) {
                            exoPlayer.seekTo(0)
                        }
                        mainHandler.post { playbackStarted = false }
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
        },
        onRelease = { view ->
            view.player = null
        },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Parent layer handles crossfade; only hide until the first decoded frame exists.
                alpha = if (mediaReady) 1f else 0f
            }
    )
}

@Composable
private fun ImageRenderer(
    item: PlaylistItem,
    resolveUrl: suspend (PlaylistItem, Int?) -> String?,
    durationSec: Int,
    ownsPlaybackClock: Boolean,
    onDisplayReady: () -> Unit,
    onFinished: () -> Unit,
    onStageFailed: () -> Unit
) {
    val context = LocalContext.current
    var url by remember(item.playbackKey()) { mutableStateOf<String?>(null) }
    var imageReady by remember(item.playbackKey()) { mutableStateOf(false) }
    var loadFailed by remember(item.playbackKey()) { mutableStateOf(false) }
    var displayReadySignalled by remember(item.playbackKey()) { mutableStateOf(false) }
    var hasPainted by remember(item.playbackKey()) { mutableStateOf(false) }
    val displaySeconds = durationSec.coerceAtLeast(5)

    LaunchedEffect(item) {
        imageReady = false
        loadFailed = false
        displayReadySignalled = false
        hasPainted = false
        url = resolveUrl(item, null) ?: ApiClient.resolveMediaUrl(item.url)
    }

    LaunchedEffect(imageReady) {
        if (imageReady && !displayReadySignalled) {
            displayReadySignalled = true
            onDisplayReady()
        }
    }

    LaunchedEffect(item.playbackKey(), loadFailed) {
        if (loadFailed) {
            delay(500)
            if (ownsPlaybackClock) onFinished() else onStageFailed()
        }
    }

    LaunchedEffect(item.playbackKey(), url) {
        if (url == null) {
            delay(3000)
            if (ownsPlaybackClock) onFinished() else onStageFailed()
        }
    }

    LaunchedEffect(item.playbackKey(), url, imageReady, ownsPlaybackClock) {
        if (!ownsPlaybackClock || url == null || !imageReady) return@LaunchedEffect
        delay(displaySeconds * 1000L)
        onFinished()
    }

    val imageRequest = remember(url, item.playbackKey()) {
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .memoryCacheKey(item.playbackKey())
                .diskCacheKey(item.playbackKey())
                .crossfade(false)
                .build()
        }
    }

    if (imageRequest != null) {
        AsyncImage(
            model = imageRequest,
            contentDescription = item.name,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (hasPainted || imageReady) 1f else 0f },
            contentScale = ContentScale.Crop,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Success -> {
                        imageReady = true
                        hasPainted = true
                    }
                    is AsyncImagePainter.State.Error -> loadFailed = true
                    else -> Unit
                }
            }
        )
    }
}

@Composable
private fun DocumentRenderer(
    item: PlaylistItem,
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
        AsyncImage(
            model = pageUrl,
            contentDescription = "Page ${pageIndex + 1}",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Success -> pageReady = true
                    is AsyncImagePainter.State.Error -> {
                        if (ownsPlaybackClock) onFinished() else onStageFailed()
                    }
                    else -> Unit
                }
            }
        )
    }
}

@Composable
private fun TemplatePlaceholder(
    item: PlaylistItem,
    ownsPlaybackClock: Boolean,
    onDisplayReady: () -> Unit,
    onFinished: () -> Unit
) {
    LaunchedEffect(item.playbackKey()) {
        onDisplayReady()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Template playback", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(item.template?.name ?: item.name ?: "", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f))
            Text("Full template rendering is planned for a future release.", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.5f))
        }
    }
    LaunchedEffect(item.playbackKey(), ownsPlaybackClock) {
        if (!ownsPlaybackClock) return@LaunchedEffect
        delay(item.effectiveDurationSeconds() * 1000L)
        onFinished()
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
