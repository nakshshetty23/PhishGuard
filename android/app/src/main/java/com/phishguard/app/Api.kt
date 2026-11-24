package com.phishguard.app

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class CheckRequest(val url: String)

data class Factor(
    val feature: String,
    val label: String,
    val weight: Double,
    val value: Double,
)

data class CheckResponse(
    val url: String,
    val host: String?,
    val verdict: String,
    val score: Double,
    val threshold: Double,
    val factors: List<Factor>?,
    val impersonates: String?,
    val guidance: String?,
    val source: String,
    @SerializedName("checked_ms") val checkedMs: Double,
)

interface PhishGuardApi {
    @POST("check")
    suspend fun check(@Body request: CheckRequest): CheckResponse

    /** Used only to wake the hosted backend early; the body is ignored. */
    @GET("health")
    suspend fun health(): ResponseBody
}

object ApiClient {
    val api: PhishGuardApi by lazy {
        // The hosted backend (Render free tier) sleeps when idle and the first
        // request can take 30-60 s, so allow up to 60 s per phase.
        val http = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PhishGuardApi::class.java)
    }
}
