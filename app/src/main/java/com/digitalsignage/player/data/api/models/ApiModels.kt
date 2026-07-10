package com.digitalsignage.player.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter

@JsonClass(generateAdapter = true)
data class DeviceInitRequest(
    @Json(name = "player_id") val playerId: String
)

@JsonClass(generateAdapter = true)
data class DeviceInitResponse(
    val status: String? = null,
    @Json(name = "pairing_required") val pairingRequired: Boolean? = null,
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "player_id") val playerId: String? = null,
    @Json(name = "device_token") val deviceToken: String? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class PairingGenerateRequest(
    val platform: String = "android",
    @Json(name = "player_id") val playerId: String? = null,
    @Json(name = "deviceInfo") val deviceInfo: Map<String, String>? = null
)

@JsonClass(generateAdapter = true)
data class PairingGenerateResponse(
    val code: String,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "player_id") val playerId: String? = null
)

@JsonClass(generateAdapter = true)
data class PairingStatusResponse(
    @Json(name = "assignedDeviceId") val assignedDeviceId: String? = null
)

@JsonClass(generateAdapter = true)
data class PlaylistInfo(
    val id: String? = null,
    val name: String? = null,
    @Json(name = "transition_effect") val transitionEffect: String? = null,
    @Json(name = "transition_duration_ms") val transitionDurationMs: Int? = null
)

@JsonClass(generateAdapter = true)
data class PlaylistItem(
    val id: String? = null,
    @Json(name = "content_type") val contentType: String? = null,
    @Json(name = "content_id") val contentId: String? = null,
    val name: String? = null,
    @Json(name = "file_type") val fileType: String? = null,
    val url: String? = null,
    @Json(name = "thumbnail_url") val thumbnailUrl: String? = null,
    @Json(name = "duration_seconds") val durationSeconds: Int? = null,
    @Json(name = "duration_ms") val durationMs: Int? = null,
    @Json(name = "play_in_loop") val playInLoop: Boolean? = null,
    @Json(name = "page_images") val pageImages: List<String>? = null,
    @Json(name = "page_duration_seconds") val pageDurationSeconds: Int? = null,
    val template: TemplateInfo? = null
) {
    fun effectiveDurationSeconds(): Int {
        durationSeconds?.takeIf { it > 0 }?.let { return it }
        durationMs?.takeIf { it > 0 }?.let { return (it / 1000).coerceAtLeast(1) }
        return when (normalizedFileType()) {
            "image", "template" -> 5
            else -> 10
        }
    }

    fun normalizedFileType(): String? =
        (fileType ?: contentType)?.lowercase()?.takeIf { it.isNotBlank() }

    fun playbackKey(): String = contentId ?: id ?: url ?: name ?: "unknown"
}

/**
 * Full template definition including its zones. The API sends this as part of a playlist item
 * when content_type == "template".
 *
 * [zonesJson] captures the raw JSON array so zone types never break parsing regardless of how
 * the designer extends zone structures in the future. It's rendered by [TemplateWebRenderer]
 * which injects the raw JSON into a WebView for browser-native rendering.
 */
@JsonClass(generateAdapter = false)
data class TemplateInfo(
    val id: String? = null,
    val name: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @Json(name = "background_color") val backgroundColor: String? = null,
    @Json(name = "background_image_id") val backgroundImageId: String? = null,
    // Zones are captured as raw JSON so we can pass them directly to the WebView renderer
    // without needing strongly-typed Kotlin models for every widget type.
    @Json(name = "zones") val zonesJson: String? = null
) {
    fun canvasWidth() = width?.coerceAtLeast(1) ?: 1920
    fun canvasHeight() = height?.coerceAtLeast(1) ?: 1080
}

/**
 * Moshi adapter that captures any JSON value (array, object, primitive, null) as its raw
 * JSON string representation. Used for [TemplateInfo.zonesJson] so zone data survives parsing
 * without needing typed models for every possible widget variant.
 */
class RawJsonStringAdapter : JsonAdapter<String>() {
    override fun fromJson(reader: JsonReader): String? {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.nextNull<Unit>()
            return null
        }
        val source = reader.nextSource()
        return source.readUtf8()
    }

    override fun toJson(writer: JsonWriter, value: String?) {
        if (value == null) {
            writer.nullValue()
        } else {
            writer.jsonValue(value)
        }
    }
}

@JsonClass(generateAdapter = true)
data class OverlayBarFlags(
    val clock: Boolean? = null,
    val date: Boolean? = null,
    val weather: Boolean? = null,
    val aqi: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class PlayerOverlayConfig(
    @Json(name = "bgColor") val bgColor: String? = null,
    val top: OverlayBarFlags? = null,
    val bottom: OverlayBarFlags? = null
)

/** API returns overlay settings nested by device orientation (matches web player). */
@JsonClass(generateAdapter = true)
data class PlayerOverlayResponse(
    val landscape: PlayerOverlayConfig? = null,
    val portrait: PlayerOverlayConfig? = null
) {
    fun forOrientation(orientation: String?): PlayerOverlayConfig? {
        return when (orientation?.lowercase()) {
            "portrait" -> portrait ?: landscape
            else -> landscape ?: portrait
        }
    }
}

@JsonClass(generateAdapter = true)
data class PlayerContentResponse(
    val playlist: PlaylistInfo? = null,
    val items: List<PlaylistItem>? = null,
    @Json(name = "deviceOrientation") val deviceOrientation: String? = null,
    @Json(name = "playerOverlay") val playerOverlay: PlayerOverlayResponse? = null,
    @Json(name = "subscriptionExpired") val subscriptionExpired: Boolean? = null,
    @Json(name = "subscriptionStatus") val subscriptionStatus: String? = null,
    val message: String? = null,
    @Json(name = "idleMessage") val idleMessage: String? = null,
    @Json(name = "idleReason") val idleReason: String? = null,
    @Json(name = "defaultIdleBackgroundUrl") val defaultIdleBackgroundUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class WeatherInfo(
    @Json(name = "tempC") val tempC: Int? = null,
    val description: String? = null
)

@JsonClass(generateAdapter = true)
data class AqiInfo(
    val value: Int? = null,
    @Json(name = "category") val label: String? = null
)

@JsonClass(generateAdapter = true)
data class EnvironmentalData(
    val weather: WeatherInfo? = null,
    val aqi: AqiInfo? = null
)

@JsonClass(generateAdapter = true)
data class HeartbeatRequest(
    val cpuUsage: Double = 0.0,
    val memoryUsage: Double = 0.0,
    @Json(name = "storageUsedGb") val storageUsedGb: Double? = null,
    @Json(name = "storageTotalGb") val storageTotalGb: Double? = null,
    val networkStatus: String = "online",
    @Json(name = "isPlaying") val isPlaying: Boolean = false
)

@JsonClass(generateAdapter = true)
data class HeartbeatResponse(
    val success: Boolean? = null
)
