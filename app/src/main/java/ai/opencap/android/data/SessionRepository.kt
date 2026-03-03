package ai.opencap.android.data

import ai.opencap.android.model.PatchVideoRequest
import ai.opencap.android.model.SessionStatusResponse
import ai.opencap.android.model.VideoCredentials
import ai.opencap.android.util.RetryInterceptor
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class SessionRepository {
    private val api: SessionApi

    init {
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logger)
            .addInterceptor(RetryInterceptor(retryCount = 2))
            .build()

        val moshi = Moshi.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.opencap.ai/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(client)
            .build()

        api = retrofit.create(SessionApi::class.java)
    }

    suspend fun fetchSessionStatus(statusUrl: String): SessionStatusResponse {
        return api.fetchSessionStatus(statusUrl)
    }

    suspend fun fetchVideoCredentials(presignedUrl: String): VideoCredentials {
        return api.fetchVideoCredentials(presignedUrl)
    }

    suspend fun patchVideo(patchUrl: String, payload: PatchVideoRequest) {
        api.patchVideo(patchUrl, payload)
    }
}
