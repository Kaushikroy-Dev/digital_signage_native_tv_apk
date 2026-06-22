package com.digitalsignage.player.domain

import com.digitalsignage.player.data.api.models.EnvironmentalData
import com.digitalsignage.player.data.api.models.OverlayBarFlags
import com.digitalsignage.player.data.api.models.PlayerContentResponse
import com.digitalsignage.player.data.api.models.PlayerOverlayConfig
import com.digitalsignage.player.data.api.models.PlaylistItem

enum class PairingPhase { Generating, Waiting, Error }

sealed class PlayerUiState {
    data class Splash(val playerIdLabel: String) : PlayerUiState()

    data class Pairing(
        val phase: PairingPhase,
        val code: String = "",
        val errorMessage: String? = null,
        val playerIdLabel: String = ""
    ) : PlayerUiState()

    data class Loading(val deviceId: String) : PlayerUiState()

    data class Idle(
        val message: String,
        val backgroundUrl: String? = null,
        val isSubscriptionExpired: Boolean = false
    ) : PlayerUiState()

    data class Playing(
        val content: PlayerContentResponse,
        val currentIndex: Int,
        val environmental: EnvironmentalData? = null,
        val environmentalLoading: Boolean = false,
        val isOffline: Boolean = false,
        val cacheSyncCurrent: Int = 0,
        val cacheSyncTotal: Int = 0,
        val showCacheSync: Boolean = false
    ) : PlayerUiState()

    data object DisplayOff : PlayerUiState()
}

data class PlaybackSlice(
    val item: PlaylistItem,
    val mediaUrl: String?,
    val localPath: String?
)

fun PlayerContentResponse.resolvedOverlay(): PlayerOverlayConfig? =
    playerOverlay?.forOrientation(deviceOrientation)

fun OverlayBarFlags?.hasAnyEnabled(): Boolean {
    if (this == null) return false
    return clock == true || date == true || weather == true || aqi == true
}

fun PlayerContentResponse.overlayNeedsEnvironmentalFetch(): Boolean {
    val cfg = resolvedOverlay() ?: return false
    val top = cfg.top
    val bottom = cfg.bottom
    return top?.weather == true || top?.aqi == true || bottom?.weather == true || bottom?.aqi == true
}
