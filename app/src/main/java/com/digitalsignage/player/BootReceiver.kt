package com.digitalsignage.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Launches the player automatically when the TV boots or finishes unlocking (signage kiosk).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS) return

        android.util.Log.d(TAG, "Boot event ($action) — scheduling MainActivity launch")

        val pendingResult = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                launchPlayer(context)
            } finally {
                pendingResult.finish()
            }
        }, BOOT_LAUNCH_DELAY_MS)
    }

    private fun launchPlayer(context: Context) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            context.startActivity(launchIntent)
            android.util.Log.d(TAG, "MainActivity launched")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to launch MainActivity", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val BOOT_LAUNCH_DELAY_MS = 8_000L
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"

        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            ACTION_QUICKBOOT_POWERON
        )
    }
}
