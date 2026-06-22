package com.digitalsignage.player.data.api

import com.digitalsignage.player.BuildConfig
import com.digitalsignage.player.data.api.models.DeviceInitRequest
import com.digitalsignage.player.data.api.models.DeviceInitResponse
import com.digitalsignage.player.data.api.models.EnvironmentalData
import com.digitalsignage.player.data.api.models.HeartbeatRequest
import com.digitalsignage.player.data.api.models.HeartbeatResponse
import com.digitalsignage.player.data.api.models.PairingGenerateRequest
import com.digitalsignage.player.data.api.models.PairingGenerateResponse
import com.digitalsignage.player.data.api.models.PairingStatusResponse
import com.digitalsignage.player.data.api.models.PlayerContentResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface MsrApiService {
    @POST("device/init")
    suspend fun deviceInit(@Body body: DeviceInitRequest): DeviceInitResponse

    @POST("devices/pairing/generate")
    suspend fun generatePairing(@Body body: PairingGenerateRequest): PairingGenerateResponse

    @GET("devices/pairing/status/{code}")
    suspend fun pairingStatus(@Path("code") code: String): PairingStatusResponse

    @GET("schedules/player/{deviceId}/content")
    suspend fun playerContent(@Path("deviceId") deviceId: String): PlayerContentResponse

    @GET("devices/player/{deviceId}/environmental")
    suspend fun playerEnvironmental(
        @Path("deviceId") deviceId: String,
        @Query("orientation") orientation: String
    ): EnvironmentalData

    @POST("devices/{deviceId}/heartbeat")
    suspend fun heartbeat(
        @Path("deviceId") deviceId: String,
        @Body body: HeartbeatRequest
    ): HeartbeatResponse
}

object ApiClient {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    fun create(deviceTokenProvider: () -> String?): MsrApiService =
        buildService(deviceTokenProvider, connectTimeoutSec = 30, readTimeoutSec = 60)

    /** Shorter timeouts for pairing/bootstrap so the UI is not stuck on "Generating…" for a minute. */
    fun createPairingClient(deviceTokenProvider: () -> String?): MsrApiService =
        buildService(deviceTokenProvider, connectTimeoutSec = 10, readTimeoutSec = 20)

    private fun buildService(
        deviceTokenProvider: () -> String?,
        connectTimeoutSec: Long,
        readTimeoutSec: Long
    ): MsrApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val tokenInterceptor = Interceptor { chain ->
            val token = deviceTokenProvider()
            val request = if (!token.isNullOrBlank()) {
                chain.request().newBuilder()
                    .addHeader("x-device-token", token)
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(tokenInterceptor)
            .addInterceptor(logging)
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .build()

        val baseUrl = BuildConfig.API_BASE_URL.let { if (it.endsWith("/")) it else "$it/" }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(MsrApiService::class.java)
    }

    fun mediaBaseUrl(): String = BuildConfig.API_BASE_URL.removeSuffix("/api").removeSuffix("/")

    fun resolveMediaUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val base = mediaBaseUrl()
        return if (url.startsWith("/")) "$base$url" else "$base/$url"
    }
}
