package com.digitalsignage.player.data.cache

import android.content.Context
import com.digitalsignage.player.data.api.ApiClient
import com.digitalsignage.player.data.api.models.PlayerContentResponse
import com.digitalsignage.player.data.api.models.PlaylistItem
import com.digitalsignage.player.data.local.CachedMediaEntity
import com.digitalsignage.player.data.local.CachedPlaylistEntity
import com.digitalsignage.player.data.local.PlayerDatabase
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class PlaylistCacheRepository(
    private val database: PlayerDatabase,
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
) {
    private val adapter = moshi.adapter(PlayerContentResponse::class.java)
    private val ttlMs = 24 * 60 * 60 * 1000L

    suspend fun save(deviceId: String, content: PlayerContentResponse) {
        val json = adapter.toJson(content)
        database.playlistCacheDao().upsert(
            CachedPlaylistEntity(deviceId, json, System.currentTimeMillis())
        )
    }

    suspend fun load(deviceId: String): PlayerContentResponse? = withContext(Dispatchers.IO) {
        val row = database.playlistCacheDao().get(deviceId) ?: return@withContext null
        if (System.currentTimeMillis() - row.timestamp > ttlMs) {
            database.playlistCacheDao().delete(deviceId)
            return@withContext null
        }
        adapter.fromJson(row.jsonPayload)
    }
}

class MediaCacheManager(
    context: Context,
    private val database: PlayerDatabase
) {
    private val cacheDir = File(context.filesDir, "media_cache").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val maxBytes = 1536L * 1024 * 1024

    fun documentPageKey(item: PlaylistItem, pageIndex: Int): String =
        "${item.playbackKey()}:page:$pageIndex"

    suspend fun localPathForKey(mediaKey: String): String? = withContext(Dispatchers.IO) {
        database.mediaCacheDao().get(mediaKey)?.localPath?.takeIf { File(it).exists() }
    }

    suspend fun localPathFor(item: PlaylistItem): String? =
        localPathForKey(item.playbackKey())

    suspend fun localPathForDocumentPage(item: PlaylistItem, pageIndex: Int): String? =
        localPathForKey(documentPageKey(item, pageIndex))

    suspend fun resolvePlaybackUrl(
        item: PlaylistItem,
        pageIndex: Int? = null,
        localOnly: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        if (pageIndex != null) {
            localPathForKey(documentPageKey(item, pageIndex))?.let { return@withContext "file://$it" }
            if (localOnly) return@withContext null
            val path = item.pageImages?.getOrNull(pageIndex)
            return@withContext ApiClient.resolveMediaUrl(path)
        }
        localPathForKey(item.playbackKey())?.let { return@withContext "file://$it" }
        if (localOnly) return@withContext null
        ApiClient.resolveMediaUrl(item.url)
    }

    suspend fun prefetchAll(
        items: List<PlaylistItem>,
        onProgress: (current: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val entries = mutableListOf<Pair<String, String>>()
        items.forEach { item ->
            val baseKey = item.playbackKey()
            when (item.normalizedFileType()) {
                "video", "image" -> {
                    ApiClient.resolveMediaUrl(item.url)?.let { entries.add(baseKey to it) }
                }
                "document" -> {
                    item.pageImages?.forEachIndexed { index, path ->
                        ApiClient.resolveMediaUrl(path)?.let { url ->
                            entries.add(documentPageKey(item, index) to url)
                        }
                    }
                }
            }
        }
        val total = entries.size.coerceAtLeast(1)
        if (entries.isEmpty()) {
            onProgress(1, total)
            return@withContext
        }
        entries.forEachIndexed { index, (mediaKey, url) ->
            if (database.mediaCacheDao().get(mediaKey)?.let { File(it.localPath).exists() } != true) {
                download(url, mediaKey)
            }
            onProgress(index + 1, total)
        }
    }

    private suspend fun download(url: String, mediaKey: String) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return
        val body = response.body ?: return
        val ext = url.substringAfterLast('.', "bin").take(8)
        val file = File(cacheDir, "${mediaKey.hashCode()}.$ext")
        body.byteStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val size = file.length()
        database.mediaCacheDao().upsert(
            CachedMediaEntity(mediaKey, url, file.absolutePath, size, System.currentTimeMillis())
        )
        evictIfNeeded()
    }

    private suspend fun evictIfNeeded() {
        var total = database.mediaCacheDao().totalSize() ?: 0L
        if (total <= maxBytes) return
        for (row in database.mediaCacheDao().allOrdered()) {
            if (total <= maxBytes * 0.85) break
            File(row.localPath).delete()
            database.mediaCacheDao().delete(row.mediaKey)
            total -= row.sizeBytes
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        database.mediaCacheDao().clearAll()
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
