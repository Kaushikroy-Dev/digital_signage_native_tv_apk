package com.digitalsignage.player.domain

import android.app.Application
import android.content.Intent
import android.util.Log
import kotlin.system.exitProcess
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.digitalsignage.player.AppContainer
import com.digitalsignage.player.data.api.ApiClient
import com.digitalsignage.player.data.api.models.DeviceInitRequest
import com.digitalsignage.player.BuildConfig
import com.digitalsignage.player.data.api.models.PairingGenerateRequest
import com.digitalsignage.player.data.api.models.PairingGenerateResponse
import com.digitalsignage.player.data.api.models.PlayerContentResponse
import com.digitalsignage.player.data.api.models.PlaylistItem
import com.digitalsignage.player.data.heartbeat.HeartbeatState
import com.digitalsignage.player.data.ws.WsEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val container = AppContainer(application)
    private val storage = container.deviceStorage
    private val api = container.api
    private val pairingApi = container.pairingApi

    private val _uiState = MutableStateFlow<PlayerUiState>(
        PlayerUiState.Splash(storage.shortPlayerIdLabel())
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _displayRotationDeg = MutableStateFlow(
        normalizeDisplayRotationQuadrant(storage.getDisplayRotationDeg())
    )
    val displayRotationDeg: StateFlow<Int> = _displayRotationDeg.asStateFlow()

    private var pairingJob: Job? = null
    private var pollJob: Job? = null
    private var contentJob: Job? = null
    private var envJob: Job? = null
    private var currentContent: PlayerContentResponse? = null
    private var currentIndex = 0
    private val playedKeys = mutableSetOf<String>()
    private var displayPowerOn = true
    private var isOnline = true
    private var activePlaylistId: String? = null

    init {
        container.networkMonitor.start()
        if (storage.getDeviceId().isBlank()) {
            _uiState.value = PlayerUiState.Pairing(
                phase = PairingPhase.Generating,
                playerIdLabel = storage.shortPlayerIdLabel()
            )
        }
        viewModelScope.launch {
            delay(if (storage.getDeviceId().isBlank()) 100 else 800)
            bootstrap()
        }
        observeWebSocket()
        observeNetwork()
    }

    private fun observeNetwork() {
        viewModelScope.launch {
            container.networkMonitor.isConnected.collect { connected ->
                if (connected) {
                    onNetworkRestored()
                } else {
                    onNetworkLost()
                }
            }
        }
    }

    private fun onNetworkLost() {
        markOffline()
        val deviceId = storage.getDeviceId()
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            val cached = tryCachedContent(deviceId)
            when (_uiState.value) {
                is PlayerUiState.Playing -> Unit
                is PlayerUiState.Loading, is PlayerUiState.Idle -> {
                    if (isPlayableContent(cached)) {
                        applyContent(
                            deviceId = deviceId,
                            content = cached!!,
                            prefetch = false,
                            offline = true
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    private fun onNetworkRestored() {
        markOnline()
        val pairingState = _uiState.value
        if (pairingState is PlayerUiState.Pairing &&
            (pairingState.phase == PairingPhase.Error || pairingState.phase == PairingPhase.Generating)
        ) {
            viewModelScope.launch { startPairing() }
            return
        }
        val deviceId = storage.getDeviceId()
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            try {
                val content = api.playerContent(deviceId)
                val preserve = _uiState.value is PlayerUiState.Playing
                applyContent(
                    deviceId = deviceId,
                    content = content,
                    prefetch = false,
                    offline = false,
                    preservePlaybackPosition = preserve
                )
            } catch (e: Exception) {
                if (!isContentFetchTerminalError(e)) {
                    markOffline()
                }
            }
        }
    }

    private fun observeWebSocket() {
        viewModelScope.launch {
            container.webSocket.events.collect { event ->
                when (event) {
                    is WsEvent.DevicePaired -> {
                        storage.saveDeviceId(event.deviceId)
                        container.webSocket.updateRegistration(event.deviceId)
                        loadContent(event.deviceId)
                    }
                    is WsEvent.Command -> handleCommand(event.command, event.daysOld)
                    is WsEvent.Connected -> ensureDeviceRegistered()
                    else -> Unit
                }
            }
        }
        container.webSocket.connect(storage.getPlayerId(), storage.getDeviceId().takeIf { it.isNotBlank() })
    }

    private suspend fun bootstrap() {
        storage.getOrCreatePlayerId()
        val existingDeviceId = storage.getDeviceId()

        if (existingDeviceId.isNotBlank()) {
            ensureDeviceRegistered()
            loadContent(existingDeviceId)
            viewModelScope.launch { runOptionalDeviceInit() }
            return
        }

        // Unpaired: never block on /device/init (may be absent or slow on some deployments).
        startPairing()
        viewModelScope.launch { runOptionalDeviceInit() }
    }

    /** Best-effort init for device_token; must not block pairing or splash transition. */
    private suspend fun runOptionalDeviceInit() {
        try {
            withTimeout(DEVICE_INIT_TIMEOUT_MS) {
                val init = pairingApi.deviceInit(DeviceInitRequest(storage.getOrCreatePlayerId()))
                init.deviceToken?.let { storage.saveDeviceToken(it) }
                val deviceId = init.deviceId?.takeIf { it.isNotBlank() } ?: return@withTimeout
                if (init.pairingRequired == true) return@withTimeout
                if (storage.getDeviceId().isNotBlank()) return@withTimeout
                storage.saveDeviceId(deviceId)
                ensureDeviceRegistered()
                loadContent(deviceId)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Optional device init skipped: ${e.message}")
        }
    }

    fun retryPairing() {
        viewModelScope.launch { startPairing() }
    }

    private suspend fun startPairing() {
        stopHeartbeat()
        pairingJob?.cancel()
        _uiState.value = PlayerUiState.Pairing(
            phase = PairingPhase.Generating,
            playerIdLabel = storage.shortPlayerIdLabel()
        )
        try {
            val res = generatePairingResilient()
            _uiState.value = PlayerUiState.Pairing(
                phase = PairingPhase.Waiting,
                code = res.code,
                playerIdLabel = storage.shortPlayerIdLabel()
            )
            pollPairing(res.code)
        } catch (e: Exception) {
            Log.e(TAG, "Pairing generate failed", e)
            _uiState.value = PlayerUiState.Pairing(
                phase = PairingPhase.Error,
                errorMessage = pairingErrorMessage(e),
                playerIdLabel = storage.shortPlayerIdLabel()
            )
            schedulePairingAutoRetry()
        }
    }

    private suspend fun generatePairingResilient(): PairingGenerateResponse {
        var lastError: Exception? = null
        repeat(PAIRING_GENERATE_MAX_ATTEMPTS) { attempt ->
            try {
                return pairingApi.generatePairing(
                    PairingGenerateRequest(playerId = storage.getOrCreatePlayerId())
                )
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Pairing generate attempt ${attempt + 1}/$PAIRING_GENERATE_MAX_ATTEMPTS failed", e)
                if (attempt < PAIRING_GENERATE_MAX_ATTEMPTS - 1) {
                    delay(PAIRING_GENERATE_RETRY_DELAY_MS)
                }
            }
        }
        throw lastError ?: IllegalStateException("Failed to generate pairing code")
    }

    private fun schedulePairingAutoRetry() {
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            delay(PAIRING_AUTO_RETRY_DELAY_MS)
            val state = _uiState.value
            if (state is PlayerUiState.Pairing && state.phase == PairingPhase.Error) {
                startPairing()
            }
        }
    }

    private fun pairingErrorMessage(e: Exception): String {
        val root = generateSequence<Throwable>(e) { it.cause }.last()
        if (root is SocketTimeoutException || e is SocketTimeoutException) {
            return "Cannot reach server (${BuildConfig.API_BASE_URL}). Check that the API is online and port 3000 is open."
        }
        if (root is ConnectException || root is UnknownHostException ||
            e is ConnectException || e is UnknownHostException
        ) {
            return "Cannot connect to server. Check network and API URL in build.gradle."
        }
        if (e is HttpException && e.code() == 404) {
            return "Cannot reach API (HTTP 404). For local dev: start docker compose (port 3000) and use a debug APK build."
        }
        return e.message ?: "Failed to generate pairing code"
    }

    private fun pollPairing(code: String) {
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            while (isActive) {
                delay(3000)
                try {
                    val status = pairingApi.pairingStatus(code)
                    val assigned = status.assignedDeviceId
                    if (!assigned.isNullOrBlank()) {
                        storage.saveDeviceId(assigned)
                        container.webSocket.updateRegistration(assigned)
                        loadContent(assigned)
                        return@launch
                    }
                } catch (_: HttpException) {
                    // still waiting
                } catch (e: Exception) {
                    if (e is UnknownHostException) markOffline()
                }
            }
        }
    }

    private fun ensureDeviceRegistered() {
        val deviceId = storage.getDeviceId()
        if (deviceId.isNotBlank()) {
            container.webSocket.updateRegistration(deviceId)
        }
    }

    private fun loadContent(deviceId: String) {
        storage.saveDeviceId(deviceId)
        ensureDeviceRegistered()
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            val cached = tryCachedContent(deviceId)
            val hasPlayableCache = isPlayableContent(cached)

            if (hasPlayableCache && displayPowerOn) {
                applyContent(
                    deviceId = deviceId,
                    content = cached!!,
                    prefetch = false,
                    offline = !isOnline,
                    preservePlaybackPosition = _uiState.value is PlayerUiState.Playing
                )
            } else if (_uiState.value !is PlayerUiState.Playing) {
                _uiState.value = PlayerUiState.Loading(deviceId)
            }

            val fetched = fetchPlayerContentResilient(deviceId)
            if (fetched != null) {
                val preserve = _uiState.value is PlayerUiState.Playing && hasPlayableCache
                applyContent(
                    deviceId = deviceId,
                    content = fetched,
                    prefetch = !hasPlayableCache && isOnline,
                    offline = false,
                    preservePlaybackPosition = preserve
                )
                return@launch
            }

            if (hasPlayableCache && cached != null) {
                markOffline()
                when (_uiState.value) {
                    is PlayerUiState.Playing -> markOffline()
                    is PlayerUiState.Loading, is PlayerUiState.Idle -> {
                        applyContent(
                            deviceId = deviceId,
                            content = cached,
                            prefetch = false,
                            offline = true
                        )
                    }
                    else -> Unit
                }
                return@launch
            }

            if (_uiState.value !is PlayerUiState.Playing) {
                _uiState.value = PlayerUiState.Loading(deviceId)
            }
        }
        startContentPolling(deviceId)
        startHeartbeat(deviceId)
    }

    /**
     * After pairing/assignment the API can briefly return empty or fail while the schedule propagates.
     * Mirror the web player's react-query retries instead of flashing an error screen.
     */
    private suspend fun fetchPlayerContentResilient(deviceId: String): PlayerContentResponse? {
        repeat(CONTENT_FETCH_MAX_ATTEMPTS) { attempt ->
            try {
                val content = api.playerContent(deviceId)
                markOnline()
                if (isPlayableContent(content)) return content
                Log.d(TAG, "Content not playable yet (attempt ${attempt + 1}/$CONTENT_FETCH_MAX_ATTEMPTS)")
            } catch (e: Exception) {
                if (isContentFetchTerminalError(e)) {
                    handleUnpairedDevice()
                    return null
                }
                markOffline()
                Log.w(TAG, "Content fetch failed (attempt ${attempt + 1}/$CONTENT_FETCH_MAX_ATTEMPTS)", e)
            }
            if (attempt < CONTENT_FETCH_MAX_ATTEMPTS - 1) {
                delay(CONTENT_FETCH_RETRY_DELAY_MS)
            }
        }
        return null
    }

    private fun startContentPolling(deviceId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val content = api.playerContent(deviceId)
                    markOnline()
                    container.playlistCache.save(deviceId, content)
                    currentContent = content
                    if (!displayPowerOn) continue
                    val fingerprint = playlistFingerprint(content)
                    val samePlaylist = fingerprint == activePlaylistId
                    when (val state = _uiState.value) {
                        is PlayerUiState.Playing -> {
                            if (!samePlaylist) {
                                applyContent(
                                    deviceId = deviceId,
                                    content = content,
                                    prefetch = false,
                                    offline = false,
                                    preservePlaybackPosition = false
                                )
                            }
                        }
                        is PlayerUiState.Loading, is PlayerUiState.Idle -> {
                            applyContent(
                                deviceId = deviceId,
                                content = content,
                                prefetch = false,
                                offline = false
                            )
                        }
                        else -> Unit
                    }
                } catch (e: Exception) {
                    if (isContentFetchTerminalError(e)) {
                        handleUnpairedDevice()
                        return@launch
                    }
                    markOffline()
                    val cached = tryCachedContent(deviceId)
                    if (isPlayableContent(cached)) {
                        when (_uiState.value) {
                            is PlayerUiState.Playing -> Unit
                            is PlayerUiState.Loading, is PlayerUiState.Idle -> {
                                applyContent(
                                    deviceId = deviceId,
                                    content = cached!!,
                                    prefetch = false,
                                    offline = true
                                )
                            }
                            else -> Unit
                        }
                    }
                }
                delay(CONTENT_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun applyContent(
        deviceId: String,
        content: PlayerContentResponse,
        prefetch: Boolean,
        offline: Boolean = false,
        preservePlaybackPosition: Boolean = false
    ) {
        container.playlistCache.save(deviceId, content)

        syncDisplayOrientation(content.deviceOrientation)

        if (content.subscriptionExpired == true || content.subscriptionStatus == "expired") {
            activePlaylistId = null
            _uiState.value = PlayerUiState.Idle(
                message = content.message ?: "Subscription has expired. Please contact your administrator to renew.",
                backgroundUrl = ApiClient.resolveMediaUrl(content.defaultIdleBackgroundUrl),
                isSubscriptionExpired = true
            )
            return
        }

        val items = content.items.orEmpty()
        if (content.playlist == null || items.isEmpty()) {
            activePlaylistId = null
            if (_uiState.value is PlayerUiState.Loading) {
                return
            }
            val msg = content.idleMessage ?: content.message ?: "No playlist assigned"
            _uiState.value = PlayerUiState.Idle(
                message = msg,
                backgroundUrl = ApiClient.resolveMediaUrl(content.defaultIdleBackgroundUrl)
            )
            return
        }

        if (!displayPowerOn) return

        val newFingerprint = playlistFingerprint(content)
        val samePlaylist = preservePlaybackPosition &&
            activePlaylistId != null &&
            activePlaylistId == newFingerprint &&
            _uiState.value is PlayerUiState.Playing

        currentContent = content
        if (!samePlaylist) {
            currentIndex = 0
            playedKeys.clear()
        }
        activePlaylistId = newFingerprint

        val resolvedIndex = if (samePlaylist) {
            (_uiState.value as? PlayerUiState.Playing)?.currentIndex ?: currentIndex
        } else {
            currentIndex
        }

        if (prefetch && isOnline) {
            _uiState.value = PlayerUiState.Playing(
                content = content,
                currentIndex = resolvedIndex,
                showCacheSync = true,
                cacheSyncCurrent = 0,
                cacheSyncTotal = items.size,
                isOffline = offline || !isOnline
            )
            container.mediaCache.prefetchAll(items) { cur, total ->
                viewModelScope.launch(Dispatchers.Main) {
                    val playing = _uiState.value
                    if (playing is PlayerUiState.Playing) {
                        _uiState.value = playing.copy(
                            cacheSyncCurrent = cur,
                            cacheSyncTotal = total,
                            showCacheSync = cur < total
                        )
                    }
                }
            }
        }

        currentIndex = resolvedIndex
        _uiState.value = PlayerUiState.Playing(
            content = content,
            currentIndex = resolvedIndex,
            isOffline = offline || !isOnline,
            showCacheSync = false
        )
        startEnvironmentalPolling(deviceId, content)
    }

    private fun startEnvironmentalPolling(deviceId: String, content: PlayerContentResponse) {
        envJob?.cancel()
        if (!content.overlayNeedsEnvironmentalFetch()) return
        val orientation = content.deviceOrientation ?: "landscape"
        envJob = viewModelScope.launch {
            fetchEnvironmental(deviceId, orientation)
            while (isActive) {
                delay(10 * 60 * 1000)
                fetchEnvironmental(deviceId, orientation)
            }
        }
    }

    private suspend fun fetchEnvironmental(deviceId: String, orientation: String) {
        val state = _uiState.value as? PlayerUiState.Playing ?: return
        _uiState.value = state.copy(environmentalLoading = true)
        try {
            val env = api.playerEnvironmental(deviceId, orientation)
            val latest = _uiState.value as? PlayerUiState.Playing ?: return
            _uiState.value = latest.copy(environmental = env, environmentalLoading = false)
        } catch (_: Exception) {
            val latest = _uiState.value as? PlayerUiState.Playing ?: return
            _uiState.value = latest.copy(environmentalLoading = false)
        }
    }

    fun onItemFinished() {
        val state = _uiState.value as? PlayerUiState.Playing ?: return
        val items = state.content.items.orEmpty()
        val current = items.getOrNull(state.currentIndex) ?: return
        playedKeys.add(current.playbackKey())
        val next = PlaylistEngine.nextIndex(items, state.currentIndex, playedKeys)
        if (next == null) {
            playedKeys.clear()
            currentIndex = 0
            _uiState.value = state.copy(currentIndex = 0)
        } else {
            currentIndex = next
            _uiState.value = state.copy(currentIndex = next)
        }
    }

    suspend fun resolveMediaUrl(item: PlaylistItem, pageIndex: Int? = null): String? {
        return container.mediaCache.resolvePlaybackUrl(item, pageIndex, localOnly = !isOnline)
    }

    private fun markOffline() {
        isOnline = false
        val state = _uiState.value
        if (state is PlayerUiState.Playing) {
            _uiState.value = state.copy(isOffline = true)
        }
    }

    private fun markOnline() {
        isOnline = true
        val state = _uiState.value
        if (state is PlayerUiState.Playing) {
            _uiState.value = state.copy(isOffline = false)
        }
    }

    private suspend fun tryCachedContent(deviceId: String): PlayerContentResponse? {
        return container.playlistCache.load(deviceId)
    }

    private fun isPlayableContent(content: PlayerContentResponse?): Boolean {
        if (content == null) return false
        if (content.subscriptionExpired == true || content.subscriptionStatus == "expired") return false
        return content.playlist != null && !content.items.isNullOrEmpty()
    }

    private fun isContentFetchTerminalError(e: Exception): Boolean {
        return e is HttpException && e.code() == 404
    }

    private suspend fun handleUnpairedDevice() {
        stopHeartbeat()
        pollJob?.cancel()
        storage.clearDeviceId()
        storage.clearOrientationPrefs()
        activePlaylistId = null
        currentContent = null
        _displayRotationDeg.value = 0
        startPairing()
    }

    private fun syncDisplayOrientation(deviceOrientation: String?) {
        val basis = deviceOrientation?.lowercase()?.takeIf { it == "portrait" || it == "landscape" }
            ?: return
        val prevBasis = storage.getOrientationBasis()
        if (prevBasis == basis) return

        if (prevBasis == null) {
            storage.setOrientationBasis(basis)
            if (!storage.hasMeaningfulStoredDisplayRotation()) {
                setDisplayRotation(displayRotationForOrientation(basis))
            }
            return
        }

        storage.setOrientationBasis(basis)
        setDisplayRotation(displayRotationForOrientation(basis))
    }

    private fun setDisplayRotation(deg: Int) {
        val normalized = normalizeDisplayRotationQuadrant(deg)
        _displayRotationDeg.value = normalized
        storage.saveDisplayRotationDeg(normalized)
    }

    private fun playlistFingerprint(content: PlayerContentResponse): String {
        val playlistId = content.playlist?.id ?: ""
        val itemKeys = content.items?.joinToString(",") { it.playbackKey() } ?: ""
        return "$playlistId|$itemKeys"
    }

    private fun startHeartbeat(deviceId: String) {
        if (deviceId.isBlank()) return
        container.heartbeatReporter.start(deviceId) {
            val state = _uiState.value
            HeartbeatState(
                isPlaying = state is PlayerUiState.Playing && displayPowerOn,
                networkOnline = isOnline
            )
        }
    }

    private fun stopHeartbeat() {
        container.heartbeatReporter.stop()
    }

    private fun handleCommand(command: String, daysOld: Int?) {
        val deviceId = storage.getDeviceId()
        Log.d(TAG, "Handling WS command: $command")
        when (command) {
            "reboot" -> {
                if (deviceId.isNotBlank()) container.webSocket.sendAck("reboot", deviceId)
                restartApp()
            }
            "screen_off" -> {
                displayPowerOn = false
                _uiState.value = PlayerUiState.DisplayOff
                if (deviceId.isNotBlank()) container.webSocket.sendAck("screen_off", deviceId)
            }
            "screen_on" -> {
                displayPowerOn = true
                if (deviceId.isNotBlank()) {
                    container.webSocket.sendAck("screen_on", deviceId)
                    currentContent?.let { content ->
                        viewModelScope.launch {
                            applyContent(
                                deviceId = deviceId,
                                content = content,
                                prefetch = false,
                                preservePlaybackPosition = true
                            )
                        }
                    } ?: loadContent(deviceId)
                }
            }
            "reset_device_id" -> {
                storage.clearAll()
                viewModelScope.launch {
                    container.mediaCache.clearAll()
                    container.webSocket.sendAck("reset_device_id", deviceId)
                    handleUnpairedDevice()
                }
            }
            "refresh", "refresh_content" -> {
                if (deviceId.isNotBlank()) {
                    container.webSocket.sendAck(command, deviceId)
                    loadContent(deviceId)
                }
            }
            "rotate_display" -> {
                setDisplayRotation(advanceDisplayRotationQuadrant(_displayRotationDeg.value))
                if (deviceId.isNotBlank()) container.webSocket.sendAck("rotate_display", deviceId)
            }
            "clear_cache" -> {
                viewModelScope.launch {
                    container.mediaCache.clearAll()
                    container.webSocket.sendAck("clear_cache", deviceId)
                    if (deviceId.isNotBlank()) loadContent(deviceId)
                }
            }
            "cleanup_cache" -> {
                viewModelScope.launch {
                    container.mediaCache.clearAll()
                    container.webSocket.sendAck("cleanup_cache", deviceId)
                    if (deviceId.isNotBlank()) loadContent(deviceId)
                }
            }
            else -> Log.w(TAG, "Unknown WS command: $command")
        }
    }

    private fun restartApp() {
        val context = getApplication<Application>()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(launch)
        }
        exitProcess(0)
    }

    override fun onCleared() {
        stopHeartbeat()
        container.networkMonitor.stop()
        container.webSocket.disconnect()
        super.onCleared()
    }

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val CONTENT_FETCH_MAX_ATTEMPTS = 8
        private const val CONTENT_FETCH_RETRY_DELAY_MS = 2_000L
        private const val CONTENT_POLL_INTERVAL_MS = 10_000L
        private const val DEVICE_INIT_TIMEOUT_MS = 8_000L
        private const val PAIRING_GENERATE_MAX_ATTEMPTS = 3
        private const val PAIRING_GENERATE_RETRY_DELAY_MS = 2_000L
        private const val PAIRING_AUTO_RETRY_DELAY_MS = 20_000L
    }
}
