package com.digitalsignage.player

import android.content.Context
import androidx.room.Room
import com.digitalsignage.player.data.api.ApiClient
import com.digitalsignage.player.data.api.MsrApiService
import com.digitalsignage.player.data.cache.MediaCacheManager
import com.digitalsignage.player.data.cache.PlaylistCacheRepository
import com.digitalsignage.player.data.heartbeat.DeviceHeartbeatReporter
import com.digitalsignage.player.data.local.PlayerDatabase
import com.digitalsignage.player.data.network.NetworkMonitor
import com.digitalsignage.player.data.ws.DeviceWebSocketClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val deviceStorage = DeviceStorage(appContext)
    val api: MsrApiService = ApiClient.create { deviceStorage.getDeviceToken() }
    val pairingApi: MsrApiService = ApiClient.createPairingClient { deviceStorage.getDeviceToken() }
    val database: PlayerDatabase = Room.databaseBuilder(
        appContext,
        PlayerDatabase::class.java,
        "msr_player.db"
    ).build()
    val playlistCache = PlaylistCacheRepository(database)
    val mediaCache = MediaCacheManager(appContext, database)
    val webSocket = DeviceWebSocketClient()
    val networkMonitor = NetworkMonitor(appContext)
    val heartbeatReporter = DeviceHeartbeatReporter(appContext, api)
}
