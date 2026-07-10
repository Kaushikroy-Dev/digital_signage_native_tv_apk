package com.digitalsignage.player.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** EXIF-aware decode for playlist stills (web: `image-orientation: from-image`). */
object PlaylistImageDecoder {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun decode(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                url.startsWith("file://") -> decodeFile(File(requireNotNull(Uri.parse(url).path)))
                url.startsWith("/") -> decodeFile(File(url))
                url.startsWith("http://") || url.startsWith("https://") -> decodeHttp(url)
                else -> null
            }
        }.getOrNull()
    }

    private fun decodeFile(file: File): Bitmap? {
        if (!file.exists()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            decodeBytesLegacy(file.readBytes())
        }
    }

    private fun decodeHttp(url: String): Bitmap? {
        val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.bytes() ?: return null
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            decodeBytesLegacy(bytes)
        }
    }

    private fun decodeBytesLegacy(bytes: ByteArray): Bitmap? {
        val orientation = ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        bitmap = rotateForExif(bitmap, orientation)
        return bitmap
    }

    private fun rotateForExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
