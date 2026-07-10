package com.digitalsignage.player.data.cache

import android.content.Context
import com.digitalsignage.player.data.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads template zone media to disk and rewrites zone JSON to use file:// URLs.
 * WebView over HTTP returns ERR_CONTENT_LENGTH_MISMATCH on large video files through
 * the emulator network stack; local files avoid that entirely.
 */
object TemplateZoneMediaCache {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun localizeZones(context: Context, zonesJson: String): String = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "template_zones").apply { mkdirs() }
        val arr = try {
            JSONArray(zonesJson)
        } catch (_: Exception) {
            return@withContext "[]"
        }
        for (i in 0 until arr.length()) {
            val zone = arr.optJSONObject(i) ?: continue
            val ct = zone.optString("contentType", zone.optString("content_type")).lowercase()
            if (ct != "media") continue

            // Multi-slide zones (mediaItems) each carry their own denormalized asset.
            val items = zone.optJSONArray("mediaItems")
            if (items != null) {
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val asset = item.optJSONObject("mediaAsset") ?: continue
                    localizeAssetField(asset, "url", dir)
                    localizeAssetField(asset, "thumbnailUrl", dir)
                    localizeAssetField(asset, "thumbnail_url", dir)
                }
            }

            val asset = zone.optJSONObject("mediaAsset") ?: zone.optJSONObject("media_asset") ?: continue
            localizeAssetField(asset, "url", dir)
            localizeAssetField(asset, "thumbnailUrl", dir)
            localizeAssetField(asset, "thumbnail_url", dir)
        }
        arr.toString()
    }

    private fun localizeAssetField(asset: JSONObject, key: String, dir: File) {
        val remote = asset.optString(key, "").takeIf { it.isNotBlank() } ?: return
        if (remote.startsWith("file://")) return
        val fullUrl = ApiClient.resolveMediaUrl(remote) ?: return
        val ext = remote.substringAfterLast('.', "bin").take(8)
        val file = File(dir, "${fullUrl.hashCode()}.$ext")
        if (!file.exists() || file.length() == 0L) {
            try {
                client.newCall(Request.Builder().url(fullUrl).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return
                    val body = resp.body ?: return
                    body.byteStream().use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("TemplateZoneMediaCache", "Failed to cache $fullUrl", e)
                return
            }
        }
        if (file.exists() && file.length() > 0L) {
            asset.put(key, "file://${file.absolutePath}")
        }
    }
}
