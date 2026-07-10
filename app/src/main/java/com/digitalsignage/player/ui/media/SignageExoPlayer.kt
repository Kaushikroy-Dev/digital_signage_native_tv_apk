package com.digitalsignage.player.ui.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/** Muted signage player — audio track disabled to halve decoder work per clip. */
fun Context.buildSignageExoPlayer(): ExoPlayer =
    ExoPlayer.Builder(this).build().apply {
        volume = 0f
        trackSelectionParameters = trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
    }
