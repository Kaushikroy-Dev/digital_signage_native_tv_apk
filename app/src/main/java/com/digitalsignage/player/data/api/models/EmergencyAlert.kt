package com.digitalsignage.player.data.api.models

data class EmergencyAlert(
    val id: String,
    val title: String,
    val message: String,
    val backgroundColor: String = "#ff0000",
    val textColor: String = "#ffffff",
    val code: String? = null,
    val audioEnabled: Boolean = false,
    val audioUrl: String? = null,
    val triggeredAt: String? = null
)
