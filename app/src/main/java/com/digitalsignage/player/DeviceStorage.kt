package com.digitalsignage.player

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class DeviceStorage(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreatePlayerId(): String {
        var playerId = prefs.getString(KEY_PLAYER_ID, null)
        if (playerId.isNullOrBlank()) {
            playerId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_PLAYER_ID, playerId).apply()
        }
        return playerId
    }

    fun getPlayerId(): String? = prefs.getString(KEY_PLAYER_ID, null)

    fun saveDeviceId(deviceId: String) {
        if (deviceId.isBlank()) {
            clearDeviceId()
            return
        }
        if (!UUID_REGEX.matches(deviceId)) return
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    fun getDeviceId(): String = prefs.getString(KEY_DEVICE_ID, "") ?: ""

    fun clearDeviceId() {
        prefs.edit().remove(KEY_DEVICE_ID).apply()
    }

    fun saveDeviceToken(token: String) {
        prefs.edit().putString(KEY_DEVICE_TOKEN, token).apply()
    }

    fun getDeviceToken(): String = prefs.getString(KEY_DEVICE_TOKEN, "") ?: ""

    fun clearDeviceToken() {
        prefs.edit().remove(KEY_DEVICE_TOKEN).apply()
    }

    fun clearAll() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_ORIENTATION_BASIS)
            .remove(KEY_DISPLAY_ROTATION_DEG)
            .apply()
    }

    fun getOrientationBasis(): String? = prefs.getString(KEY_ORIENTATION_BASIS, null)

    fun setOrientationBasis(basis: String) {
        prefs.edit().putString(KEY_ORIENTATION_BASIS, basis).apply()
    }

    fun clearOrientationPrefs() {
        prefs.edit()
            .remove(KEY_ORIENTATION_BASIS)
            .remove(KEY_DISPLAY_ROTATION_DEG)
            .apply()
    }

    fun getDisplayRotationDeg(): Int =
        prefs.getInt(KEY_DISPLAY_ROTATION_DEG, 0)

    fun saveDisplayRotationDeg(deg: Int) {
        prefs.edit().putInt(KEY_DISPLAY_ROTATION_DEG, deg).apply()
    }

    fun hasMeaningfulStoredDisplayRotation(): Boolean =
        getDisplayRotationDeg() % 90 == 0 && getDisplayRotationDeg() != 0

    fun shortPlayerIdLabel(): String {
        val id = getPlayerId() ?: return "—"
        val compact = id.replace("-", "").take(12).uppercase()
        return if (compact.length >= 8) {
            "${compact.take(4)}-${compact.drop(4).take(4)}"
        } else {
            compact
        }
    }

    companion object {
        private const val PREFS_NAME = "player_prefs"
        private const val KEY_PLAYER_ID = "player_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_ORIENTATION_BASIS = "orientation_basis"
        private const val KEY_DISPLAY_ROTATION_DEG = "display_rotation_deg"
        private val UUID_REGEX = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE
        )
    }
}
