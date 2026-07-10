package com.digitalsignage.player.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Full-bleed playlist still — fills the entire stage with [ContentScale.Crop]
 * (matches the web player's `object-fit: cover` CSS behaviour).
 *
 * ### Why this was rewritten from the old Bitmap + Image approach
 *
 * The previous implementation used [PlaylistImageDecoder] to manually decode the image into a
 * raw [android.graphics.Bitmap] and then displayed it with `androidx.compose.foundation.Image`.
 * That path had a subtle sizing bug:
 *
 *  - `Image` with a raw Bitmap measures the composable against the **bitmap's intrinsic pixel
 *    dimensions** before ContentScale is applied.
 *  - When the parent slot is still being laid out (e.g. during crossfade staging) the
 *    `fillMaxSize()` constraint can lose to the bitmap's intrinsic size, producing a
 *    pillarboxed / letterboxed result — exactly the "black bars on left and right" symptom
 *    reported against the native Android player.
 *
 * ### Fix
 *
 * Switching to Coil's [AsyncImage] (the same path already used by `ZoneImage` in
 * `TemplateNativeRenderer`, which was rendering correctly) eliminates the issue:
 *
 *  - Coil sizes the decode request to the **measured layout bounds** of the composable, so
 *    `ContentScale.Crop` always fills the container edge-to-edge, exactly matching
 *    `object-fit: cover` in the web player.
 *  - EXIF rotation is handled automatically by Coil on all supported API levels, replacing the
 *    manual `ExifInterface` + `Matrix.postRotate()` logic in `PlaylistImageDecoder`.
 *  - Both `http(s)://` remote URLs and `file://` local-cache paths are supported natively.
 */
@Composable
fun PlaylistCoverImage(
    url: String?,
    @Suppress("UNUSED_PARAMETER") cacheKey: String,  // kept for call-site compatibility
    layerAlpha: Float,
    visible: Boolean,
    onReady: () -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val context = LocalContext.current

    AsyncImage(
        model = url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
                .build()
        },
        contentDescription = null,
        // Alpha combines layer-fade (crossfade transition) with visibility gate so
        // the image never flickers on the outgoing slot.
        modifier = modifier.alpha(
            (layerAlpha * if (visible) 1f else 0f).coerceIn(0f, 1f)
        ),
        // Crop fills the container without letterboxing — mirrors web `object-fit: cover`.
        contentScale = ContentScale.Crop,
        onSuccess = { onReady() },
        onError = { onError() }
    )
}
