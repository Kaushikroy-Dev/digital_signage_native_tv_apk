package com.digitalsignage.player.data.ws

import android.util.Log
import com.digitalsignage.player.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class WsEvent {
    data class DevicePaired(val deviceId: String) : WsEvent()
    data class Command(val command: String, val daysOld: Int? = null) : WsEvent()
    data class EmergencyOverride(val alert: com.digitalsignage.player.data.api.models.EmergencyAlert) : WsEvent()
    data class EmergencyClear(val alertId: String) : WsEvent()
    data object Connected : WsEvent()
    data object Disconnected : WsEvent()
}

class DeviceWebSocketClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<WsEvent> = _events

    private var webSocket: WebSocket? = null
    private var playerId: String? = null
    private var deviceId: String? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun connect(playerId: String?, deviceId: String?) {
        this.playerId = playerId
        this.deviceId = deviceId
        disconnect()
        val request = Request.Builder().url(BuildConfig.WS_BASE_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WS connected")
                scope.launch { _events.emit(WsEvent.Connected) }
                register(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { _events.emit(WsEvent.Disconnected) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure", t)
                scope.launch { _events.emit(WsEvent.Disconnected) }
                scheduleReconnect()
            }
        })
    }

    private fun register(ws: WebSocket) {
        val pid = playerId?.takeIf { it.isNotBlank() }
        val id = deviceId?.takeIf { it.isNotBlank() }
        if (pid != null) {
            ws.send("""{"type":"register_player","playerId":"$pid"}""")
            Log.d(TAG, "WS register_player playerId=$pid")
        }
        if (id != null) {
            ws.send("""{"type":"register","deviceId":"$id"}""")
            Log.d(TAG, "WS register deviceId=$id")
        }
        if (pid == null && id == null) {
            Log.w(TAG, "WS register skipped: no playerId or deviceId")
        }
    }

    fun updateRegistration(deviceId: String) {
        this.deviceId = deviceId
        webSocket?.let { register(it) }
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "device_paired" -> {
                    val id = json.optString("deviceId")
                    if (id.isNotBlank()) {
                        scope.launch { _events.emit(WsEvent.DevicePaired(id)) }
                    }
                }
                "command" -> {
                    val cmd = json.optString("command")
                    if (cmd.isBlank()) return
                    val days = if (json.has("daysOld")) json.optInt("daysOld") else null
                    Log.d(TAG, "WS command received: $cmd")
                    scope.launch { _events.emit(WsEvent.Command(cmd, days)) }
                }
                "emergency_override" -> {
                    val alertJson = json.optJSONObject("alert") ?: return
                    val alert = parseEmergencyAlert(alertJson) ?: return
                    Log.d(TAG, "WS emergency_override: ${alert.code ?: alert.title}")
                    scope.launch { _events.emit(WsEvent.EmergencyOverride(alert)) }
                }
                "emergency_clear" -> {
                    val alertId = json.optString("alertId")
                    if (alertId.isBlank()) return
                    Log.d(TAG, "WS emergency_clear: $alertId")
                    scope.launch { _events.emit(WsEvent.EmergencyClear(alertId)) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WS parse error: $text", e)
        }
    }

    private fun scheduleReconnect() {
        scope.launch {
            kotlinx.coroutines.delay(3000)
            connect(playerId, deviceId)
        }
    }

    fun sendAck(command: String, deviceId: String) {
        webSocket?.send("""{"type":"command_ack","command":"$command","deviceId":"$deviceId"}""")
    }

    fun sendEmergencyAck(alertId: String, deviceId: String) {
        webSocket?.send("""{"type":"emergency_ack","alertId":"$alertId","deviceId":"$deviceId"}""")
    }

    private fun parseEmergencyAlert(json: JSONObject): com.digitalsignage.player.data.api.models.EmergencyAlert? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        return com.digitalsignage.player.data.api.models.EmergencyAlert(
            id = id,
            title = json.optString("title", "Emergency Alert"),
            message = json.optString("message", ""),
            backgroundColor = json.optString("backgroundColor", json.optString("background_color", "#ff0000")),
            textColor = json.optString("textColor", json.optString("text_color", "#ffffff")),
            code = json.optString("code").takeIf { it.isNotBlank() },
            audioEnabled = json.optBoolean("audioEnabled", json.optBoolean("audio_enabled", false)),
            audioUrl = json.optString("audioUrl", json.optString("audio_url")).takeIf { it.isNotBlank() }
                ?.let { com.digitalsignage.player.data.api.ApiClient.resolveMediaUrl(it) },
            triggeredAt = json.optString("triggeredAt", json.optString("triggered_at")).takeIf { it.isNotBlank() }
        )
    }

    fun disconnect() {
        webSocket?.close(1000, "bye")
        webSocket = null
    }

    companion object {
        private const val TAG = "DeviceWebSocket"
    }
}
