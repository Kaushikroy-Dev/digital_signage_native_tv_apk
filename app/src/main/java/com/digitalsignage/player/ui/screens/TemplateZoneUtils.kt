package com.digitalsignage.player.ui.screens

import org.json.JSONArray
import org.json.JSONObject

/** Visible zone is a widget (clock, ticker/text bar, queue board, …). */
fun templateHasWidgets(zonesJson: String?): Boolean {
    val arr = parseZonesArray(zonesJson) ?: return false
    for (i in 0 until arr.length()) {
        val zone = arr.optJSONObject(i) ?: continue
        if (!zone.isVisible()) continue
        val ct = zone.contentType()
        if (ct == "widget" || ct == "text") return true
    }
    return false
}

/** Visible zone carries image/video media. */
fun templateHasMedia(zonesJson: String?): Boolean {
    val arr = parseZonesArray(zonesJson) ?: return false
    for (i in 0 until arr.length()) {
        val zone = arr.optJSONObject(i) ?: continue
        if (!zone.isVisible()) continue
        if (zone.contentType() == "media") return true
    }
    return false
}

/** All visible zones are media — safe for native Compose (slideshow included). */
fun templateIsNativeMediaOnly(zonesJson: String?): Boolean {
    if (!templateHasMedia(zonesJson)) return false
    if (templateHasWidgets(zonesJson)) return false
    val arr = parseZonesArray(zonesJson) ?: return false
    for (i in 0 until arr.length()) {
        val zone = arr.optJSONObject(i) ?: continue
        if (!zone.isVisible()) continue
        if (zone.contentType() != "media") return false
    }
    return true
}

/** JSON array containing only widget/text zones (for transparent WebView overlay). */
fun filterWidgetZonesJson(zonesJson: String?): String {
    val arr = parseZonesArray(zonesJson) ?: return "[]"
    val out = JSONArray()
    for (i in 0 until arr.length()) {
        val zone = arr.optJSONObject(i) ?: continue
        if (!zone.isVisible()) continue
        val ct = zone.contentType()
        if (ct == "widget" || ct == "text") out.put(zone)
    }
    return out.toString()
}

private fun parseZonesArray(zonesJson: String?): JSONArray? = try {
    JSONArray(zonesJson ?: "[]")
} catch (_: Exception) {
    null
}

private fun JSONObject.isVisible(): Boolean = optBoolean("isVisible", true)

private fun JSONObject.contentType(): String =
    optString("contentType", optString("content_type")).lowercase()

// ponytail: keep templateIsMediaOnly name for callers — same semantics as native-media-only.
fun templateIsMediaOnly(zonesJson: String?): Boolean = templateIsNativeMediaOnly(zonesJson)
