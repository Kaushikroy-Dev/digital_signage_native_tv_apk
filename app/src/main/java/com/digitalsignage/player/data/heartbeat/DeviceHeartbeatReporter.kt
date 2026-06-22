package com.digitalsignage.player.data.heartbeat

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.util.Log
import com.digitalsignage.player.data.api.MsrApiService
import com.digitalsignage.player.data.api.models.HeartbeatRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class HeartbeatState(
    val isPlaying: Boolean,
    val networkOnline: Boolean
)

class DeviceHeartbeatReporter(
    private val context: Context,
    private val api: MsrApiService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(deviceId: String, stateProvider: () -> HeartbeatState) {
        stop()
        job = scope.launch {
            while (isActive) {
                sendHeartbeat(deviceId, stateProvider())
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun sendHeartbeat(deviceId: String, state: HeartbeatState) {
        try {
            val memoryUsage = readMemoryUsagePercent()
            val (storageUsedGb, storageTotalGb) = readStorageStats()
            api.heartbeat(
                deviceId,
                HeartbeatRequest(
                    cpuUsage = 0.0,
                    memoryUsage = memoryUsage,
                    storageUsedGb = storageUsedGb,
                    storageTotalGb = storageTotalGb,
                    networkStatus = if (state.networkOnline) "online" else "offline",
                    isPlaying = state.isPlaying
                )
            )
            Log.d(TAG, "Heartbeat sent isPlaying=${state.isPlaying} network=${state.networkOnline}")
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat failed", e)
        }
    }

    private fun readMemoryUsagePercent(): Double {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val used = info.totalMem - info.availMem
            (used.toDouble() / info.totalMem.toDouble()) * 100.0
        } catch (_: Exception) {
            0.0
        }
    }

    private fun readStorageStats(): Pair<Double, Double> {
        return try {
            val filesDir = context.filesDir
            val cacheDir = File(filesDir, "media_cache")
            val stat = StatFs(filesDir.absolutePath)
            val totalBytes = stat.totalBytes
            val freeBytes = stat.availableBytes
            val cacheBytes = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
            val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L) + cacheBytes
            val totalGb = totalBytes.toDouble() / BYTES_PER_GB
            val usedGb = usedBytes.toDouble() / BYTES_PER_GB
            usedGb to totalGb
        } catch (_: Exception) {
            0.0 to 0.0
        }
    }

    companion object {
        private const val TAG = "DeviceHeartbeat"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0
    }
}
