package com.digitalsignage.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.digitalsignage.player.domain.PlayerUiState
import com.digitalsignage.player.domain.PlayerViewModel
import com.digitalsignage.player.ui.components.NetworkStatusDot
import com.digitalsignage.player.ui.components.PlayerRotationFrame
import com.digitalsignage.player.ui.screens.DisplayOffScreen
import com.digitalsignage.player.ui.screens.IdleScreen
import com.digitalsignage.player.ui.screens.LoadingScreen
import com.digitalsignage.player.ui.screens.PairingScreen
import com.digitalsignage.player.ui.screens.PlaybackScreen
import com.digitalsignage.player.ui.screens.SplashScreen
import com.digitalsignage.player.ui.theme.Dimens
import com.digitalsignage.player.ui.theme.MsrTheme

@Composable
fun MsrPlayerApp(viewModel: PlayerViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val displayRotation by viewModel.displayRotationDeg.collectAsState()
    MsrTheme {
        // Full-screen shell: status dot lives in display space, not inside the rotated stage.
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerRotationFrame(degrees = displayRotation, modifier = Modifier.fillMaxSize()) {
                when (val s = state) {
                is PlayerUiState.Splash -> SplashScreen(s.playerIdLabel)
                is PlayerUiState.Pairing -> PairingScreen(
                    phase = s.phase,
                    code = s.code,
                    errorMessage = s.errorMessage,
                    playerIdLabel = s.playerIdLabel,
                    onRetry = viewModel::retryPairing
                )
                is PlayerUiState.Loading -> LoadingScreen(s.deviceId)
                is PlayerUiState.Idle -> IdleScreen(s.message, s.backgroundUrl)
                is PlayerUiState.Playing -> PlaybackScreen(
                    state = s,
                    resolveUrl = viewModel::resolveMediaUrl,
                    onItemFinished = viewModel::onItemFinished
                )
                PlayerUiState.DisplayOff -> DisplayOffScreen()
                }
            }
            val playing = state as? PlayerUiState.Playing
            if (playing != null && !playing.showCacheSync) {
                NetworkStatusDot(
                    isOffline = playing.isOffline,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = Dimens.StatusDotInset, end = Dimens.StatusDotInset)
                        .zIndex(10f)
                )
            }
        }
    }
}
