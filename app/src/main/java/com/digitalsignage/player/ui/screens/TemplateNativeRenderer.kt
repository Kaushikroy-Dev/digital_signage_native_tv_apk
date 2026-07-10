package com.digitalsignage.player.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.digitalsignage.player.R
import com.digitalsignage.player.ui.media.buildSignageExoPlayer
import com.digitalsignage.player.data.api.ApiClient
import com.digitalsignage.player.data.api.models.PlaylistItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
fun TemplateNativeRenderer(
    item: PlaylistItem,
    ownsPlaybackClock: Boolean,
    onDisplayReady: () -> Unit,
    onFinished: () -> Unit,
    onStageFailed: () -> Unit = onFinished
) {
    val template = item.template ?: return
    val onDisplayReadyState by rememberUpdatedState(onDisplayReady)
    val onFinishedState by rememberUpdatedState(onFinished)
    val ownsClockState by rememberUpdatedState(ownsPlaybackClock)
    var readySignalled by remember(item.playbackKey()) { mutableStateOf(false) }

    val zones = remember(template.zonesJson) { parseMediaZones(template.zonesJson) }
    val bg = remember(template.backgroundColor) { parseHexColor(template.backgroundColor, Color.Black) }

    LaunchedEffect(item.playbackKey(), ownsPlaybackClock) {
        if (!ownsPlaybackClock) return@LaunchedEffect
        delay(item.effectiveDurationSeconds() * 1000L)
        onFinishedState()
    }

    fun signalReady() {
        if (!readySignalled) {
            readySignalled = true
            onDisplayReadyState()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
        val cw = template.canvasWidth().toFloat()
        val ch = template.canvasHeight().toFloat()
        zones.forEach { zone ->
            key(zone.id) {
                val left = zone.x / cw
                val top = zone.y / ch
                val w = zone.width / cw
                val h = zone.height / ch
                val fit = when (zone.mediaFit.lowercase()) {
                    "contain" -> ContentScale.Fit
                    "fill" -> ContentScale.FillBounds
                    else -> ContentScale.Crop
                }
                Box(
                    Modifier
                        .offset { IntOffset((maxWidth * left).roundToPx(), (maxHeight * top).roundToPx()) }
                        .width(maxWidth * w)
                        .height(maxHeight * h)
                        .zIndex(zone.zIndex.toFloat())
                        .graphicsLayer { clip = true }
                        .clipToBounds()
                        .background(Color.Black)
                ) {
                    when {
                        zone.slides != null && zone.slides.isNotEmpty() ->
                            ZoneSlideshow(zone.slides, fit, ::signalReady)
                        zone.url != null && zone.fileType == "video" ->
                            ZoneVideo(url = zone.url, contentScale = fit, layerAlpha = 1f, onReady = ::signalReady)
                        zone.url != null ->
                            ZoneImage(
                                url = zone.url,
                                thumbUrl = zone.thumbUrl,
                                contentScale = fit,
                                layerAlpha = 1f,
                                onReady = ::signalReady
                            )
                    }
                }
            }
        }
    }

    LaunchedEffect(zones) {
        if (zones.isEmpty()) {
            signalReady()
            delay(500)
            if (ownsClockState) onStageFailed()
        }
    }
}

private data class MediaZone(
    val id: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val zIndex: Int,
    val fileType: String,
    val url: String?,
    val thumbUrl: String?,
    val mediaFit: String,
    val slides: List<MediaSlide>? = null
)

private data class MediaSlide(
    val fileType: String,
    val url: String?,
    val thumbUrl: String?,
    val durationSec: Int
)

private fun parseMediaZones(zonesJson: String?): List<MediaZone> {
    val arr = try {
        JSONArray(zonesJson ?: "[]")
    } catch (_: Exception) {
        return emptyList()
    }
    val out = mutableListOf<MediaZone>()
    for (i in 0 until arr.length()) {
        val zone = arr.optJSONObject(i) ?: continue
        if (zone.optBoolean("isVisible", true) == false) continue
        val ct = zone.optString("contentType", zone.optString("content_type")).lowercase()
        if (ct != "media") continue

        val items = zone.optJSONArray("mediaItems")
        val slides = if (items != null && items.length() >= 2) {
            buildList {
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val asset = item.optJSONObject("mediaAsset") ?: item.optJSONObject("media_asset") ?: continue
                    val ft = asset.optString("fileType", asset.optString("file_type", "image")).lowercase()
                    add(
                        MediaSlide(
                            fileType = ft,
                            // ponytail: full asset url for zone carousel — thumbnails skew crop in split zones.
                            url = resolveZoneUrl(asset, preferThumb = false),
                            thumbUrl = thumbField(asset)?.let { ApiClient.resolveMediaUrl(it) },
                            durationSec = item.optInt("duration", zone.optInt("duration", 5)).coerceAtLeast(1)
                        )
                    )
                }
            }.takeIf { it.size >= 2 }
        } else null

        val asset = zone.optJSONObject("mediaAsset") ?: zone.optJSONObject("media_asset")
            ?: (items?.optJSONObject(0)?.optJSONObject("mediaAsset"))
        val ft = (asset?.optString("fileType", asset.optString("file_type"))
            ?: zone.optString("type")).lowercase()
        val url = if (slides == null) asset?.let { resolveZoneUrl(it, preferThumb = ft == "image") } else null

        out += MediaZone(
            id = zone.optString("id", "zone-$i"),
            x = zone.optInt("x"),
            y = zone.optInt("y"),
            width = zone.optInt("width", 100),
            height = zone.optInt("height", 100),
            zIndex = zone.optInt("zIndex", zone.optInt("z_index")),
            fileType = ft,
            url = url,
            thumbUrl = asset?.let { thumbField(it) }?.let { ApiClient.resolveMediaUrl(it) },
            mediaFit = zone.optString("mediaFit", zone.optString("media_fit", "cover")),
            slides = slides
        )
    }
    return out.sortedBy { it.zIndex }
}

private fun thumbField(asset: JSONObject): String? =
    asset.optString("thumbnailUrl", "").ifBlank { null }
        ?: asset.optString("thumbnail_url", "").ifBlank { null }

private fun resolveZoneUrl(asset: JSONObject, preferThumb: Boolean): String? {
    val url = asset.optString("url", "").ifBlank { null }
    val thumb = thumbField(asset)
    val pick = if (preferThumb) thumb ?: url else url ?: thumb
    return pick?.let { ApiClient.resolveMediaUrl(it) }
}

private fun parseHexColor(hex: String?, default: Color): Color {
    if (hex.isNullOrBlank()) return default
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        default
    }
}

private const val ZONE_SLIDE_FADE_MS = 600
private const val ZONE_SLIDE_PRELOAD_LEAD_MS = 2000L

@Composable
private fun ZoneSlideshow(
    slides: List<MediaSlide>,
    contentScale: ContentScale,
    onReady: () -> Unit
) {
    var frontSlot by remember(slides) { mutableIntStateOf(0) }
    var slot0Index by remember(slides) { mutableIntStateOf(0) }
    var slot1Index by remember(slides) { mutableIntStateOf(-1) }
    var slot0Ready by remember(slides) { mutableStateOf(false) }
    var slot1Ready by remember(slides) { mutableStateOf(false) }
    var crossfading by remember(slides) { mutableStateOf(false) }
    var fadeActive by remember(slides) { mutableStateOf(false) }
    var readyOnce by remember(slides) { mutableStateOf(false) }

    val fade by animateFloatAsState(
        targetValue = if (fadeActive) 1f else 0f,
        animationSpec = tween(ZONE_SLIDE_FADE_MS),
        label = "zoneSlideFade"
    )

    fun slotIndex(slot: Int) = if (slot == 0) slot0Index else slot1Index
    fun slotReady(slot: Int) = if (slot == 0) slot0Ready else slot1Ready
    fun setSlotReady(slot: Int, ready: Boolean) {
        if (slot == 0) slot0Ready = ready else slot1Ready = ready
    }
    fun setSlotIndex(slot: Int, index: Int) {
        if (slot == 0) slot0Index = index else slot1Index = index
    }

    fun requestAdvance() {
        if (slides.size <= 1 || crossfading) return
        val back = 1 - frontSlot
        if (slotIndex(back) >= 0) return
        val next = (slotIndex(frontSlot) + 1) % slides.size
        setSlotReady(back, false)
        setSlotIndex(back, next)
    }

    LaunchedEffect(slides) {
        frontSlot = 0
        slot0Index = 0
        slot1Index = -1
        slot0Ready = false
        slot1Ready = false
        crossfading = false
        fadeActive = false
    }

    // Web-style fade-in: outgoing stays opaque; incoming fades in on top (no white gap).
    LaunchedEffect(slot0Ready, slot1Ready, frontSlot, slot0Index, slot1Index) {
        if (crossfading) return@LaunchedEffect
        val back = 1 - frontSlot
        if (slotIndex(back) < 0 || !slotReady(back)) return@LaunchedEffect
        crossfading = true
        fadeActive = true
        delay(ZONE_SLIDE_FADE_MS.toLong())
        val oldFront = frontSlot
        frontSlot = back
        fadeActive = false
        setSlotIndex(oldFront, -1)
        setSlotReady(oldFront, false)
        crossfading = false
    }

    fun markReady(slot: Int) {
        setSlotReady(slot, true)
        if (!readyOnce && slot == frontSlot && !crossfading) {
            readyOnce = true
            onReady()
        }
    }

    // Image timer: preload next slide early so fade can start on time.
    LaunchedEffect(frontSlot, slot0Index, slot1Index, slides, crossfading) {
        if (crossfading) return@LaunchedEffect
        val frontIdx = slotIndex(frontSlot)
        if (frontIdx < 0) return@LaunchedEffect
        val current = slides[frontIdx % slides.size]
        if (current.fileType == "video" || slides.size <= 1) return@LaunchedEffect
        val totalMs = current.durationSec.coerceAtLeast(1) * 1000L
        val preloadLeadMs = ZONE_SLIDE_PRELOAD_LEAD_MS.coerceAtMost((totalMs - 200L).coerceAtLeast(0L))
        delay(totalMs - preloadLeadMs)
        requestAdvance()
        if (preloadLeadMs > 0) delay(preloadLeadMs)
    }

    val backSlot = 1 - frontSlot
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { clip = true }
            .background(Color.Black)
    ) {
        for (slot in 0..1) {
            val idx = slotIndex(slot)
            if (idx < 0) continue
            val isFront = slot == frontSlot
            val isIncoming = slot == backSlot && crossfading
            val backLoading = slotIndex(backSlot) >= 0 && !slotReady(backSlot)
            val alpha = when {
                isIncoming -> fade
                isFront -> 1f
                else -> 0f
            }
            key("zone-slide-slot-$slot") {
                ZoneSlideContent(
                    slide = slides[idx % slides.size],
                    contentScale = contentScale,
                    layerAlpha = alpha,
                    holdLastFrame = isFront && crossfading,
                    loopWhilePreloading = isFront && backLoading,
                    onReady = { markReady(slot) },
                    onNearEnd = if (isFront && !crossfading && slides.size > 1) ({ requestAdvance() }) else null,
                    onEnded = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (isIncoming) 2f else 1f)
                )
            }
        }
    }
}

@Composable
private fun ZoneSlideContent(
    slide: MediaSlide,
    contentScale: ContentScale,
    layerAlpha: Float,
    holdLastFrame: Boolean = false,
    loopWhilePreloading: Boolean = false,
    onReady: () -> Unit,
    onNearEnd: (() -> Unit)? = null,
    onEnded: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    when {
        slide.fileType == "video" && slide.url != null ->
            ZoneVideo(slide.url, contentScale, layerAlpha, holdLastFrame, loopWhilePreloading, onReady, onNearEnd, onEnded, modifier)
        slide.url != null ->
            ZoneImage(slide.url, slide.thumbUrl, contentScale, layerAlpha, onReady, modifier)
    }
}

@Composable
private fun ZoneImage(
    url: String,
    thumbUrl: String?,
    contentScale: ContentScale,
    layerAlpha: Float,
    onReady: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var triedFull by remember(url) { mutableStateOf(false) }
    var loaded by remember(url) { mutableStateOf(false) }
    val onReadyState by rememberUpdatedState(onReady)
    val model = remember(url, thumbUrl, triedFull) {
        val data = if (!triedFull && thumbUrl != null) thumbUrl else url
        ImageRequest.Builder(context)
            .data(data)
            .crossfade(false)
            .memoryCacheKey(url)
            .diskCacheKey(url)
            .build()
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier.alpha((layerAlpha * if (loaded) 1f else 0f).coerceIn(0f, 1f)),
        contentScale = contentScale,
        onState = { state ->
            when (state) {
                is coil.compose.AsyncImagePainter.State.Success -> {
                    loaded = true
                    onReadyState()
                }
                is coil.compose.AsyncImagePainter.State.Error -> {
                    if (!triedFull && thumbUrl != null) triedFull = true
                    else {
                        loaded = true
                        onReadyState()
                    }
                }
                else -> Unit
            }
        }
    )
}

@Composable
private fun ZoneVideo(
    url: String,
    contentScale: ContentScale,
    layerAlpha: Float,
    holdLastFrame: Boolean = false,
    loopWhilePreloading: Boolean = false,
    onReady: () -> Unit,
    onNearEnd: (() -> Unit)? = null,
    onEnded: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val onEndedState by rememberUpdatedState(onEnded)
    val onNearEndState by rememberUpdatedState(onNearEnd)
    val onReadyState by rememberUpdatedState(onReady)
    val layerAlphaState by rememberUpdatedState(layerAlpha)
    val holdLastFrameState by rememberUpdatedState(holdLastFrame)
    val loopWhilePreloadingState by rememberUpdatedState(loopWhilePreloading)
    var readyFired by remember(url) { mutableStateOf(false) }
    var hasFirstFrame by remember(url) { mutableStateOf(false) }
    var nearEndFired by remember(url) { mutableStateOf(false) }
    val exoPlayer = remember { context.buildSignageExoPlayer() }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    LaunchedEffect(url, onEndedState) {
        readyFired = false
        hasFirstFrame = false
        nearEndFired = false
        exoPlayer.repeatMode = if (onEndedState != null) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
        exoPlayer.setMediaItem(MediaItem.fromUri(url.toUri()))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = layerAlpha > 0f
    }

    // ponytail: hidden preload slots decode one frame then pause — keeps 2-zone templates off 4 decoders.
    LaunchedEffect(layerAlpha, hasFirstFrame, holdLastFrame) {
        val wantsPlay = !holdLastFrame && (layerAlpha > 0f || !hasFirstFrame)
        exoPlayer.playWhenReady = wantsPlay
    }

    LaunchedEffect(url, onNearEndState) {
        if (onNearEndState == null) return@LaunchedEffect
        while (isActive) {
            delay(500)
            val dur = exoPlayer.duration
            if (dur > 0) {
                val lead = when {
                    dur > 8000 -> 4000L
                    dur > 4000 -> (dur * 0.45).toLong()
                    else -> (dur * 0.35).toLong().coerceAtLeast(800)
                }
                if (exoPlayer.currentPosition >= dur - lead) {
                    if (!nearEndFired) {
                        nearEndFired = true
                        onNearEndState?.invoke()
                    }
                    break
                }
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                // ponytail: slideshow front loops until crossfade replaces it — avoids black ENDED frame.
                if (onNearEndState != null && layerAlphaState > 0f && !holdLastFrameState) {
                    exoPlayer.seekTo(0)
                    exoPlayer.playWhenReady = true
                    return
                }
                if (loopWhilePreloadingState && layerAlphaState > 0f) {
                    exoPlayer.seekTo(0)
                    exoPlayer.playWhenReady = true
                    return
                }
                onEndedState?.invoke()
            }

            override fun onRenderedFirstFrame() {
                if (!readyFired) {
                    readyFired = true
                    hasFirstFrame = true
                    onReadyState()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).apply {
                clipChildren = true
                clipToPadding = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(
                    (android.view.LayoutInflater.from(ctx).inflate(R.layout.exo_player_texture_view, null) as PlayerView).apply {
                        player = exoPlayer
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        resizeMode = when (contentScale) {
                            ContentScale.Fit -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                            ContentScale.FillBounds -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        }
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                )
            }
        },
        update = { frame ->
            val pv = frame.getChildAt(0) as? PlayerView
            pv?.player = exoPlayer
            val visible = if (hasFirstFrame) 1f else 0f
            frame.alpha = (layerAlphaState * visible).coerceIn(0f, 1f)
        },
        modifier = modifier.graphicsLayer { clip = true }
    )
}
