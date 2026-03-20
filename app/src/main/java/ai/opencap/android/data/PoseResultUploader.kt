package ai.opencap.android.data

import ai.opencap.android.BuildConfig
import ai.opencap.android.model.PoseUploadPayload
import ai.opencap.android.pose.PoseEstimationResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

data class PoseUploadResult(
    val jsonFile: File,
    val uploaded: Boolean,
    val statusCode: Int?,
    val errorMessage: String?
)

class PoseResultUploader {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val payloadAdapter = moshi.adapter(PoseUploadPayload::class.java).indent("  ")
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun exportAndUpload(
        appFilesDir: File,
        estimation: PoseEstimationResult,
        deviceId: String,
        sessionStatusUrl: String,
        apiBaseUrl: String,
        trialLink: String?,
        videoFile: File
    ): PoseUploadResult {
        val payload = PoseUploadPayload(
            schemaVersion = "opencap_pose20_v1",
            deviceId = deviceId,
            sessionStatusUrl = sessionStatusUrl,
            apiBaseUrl = apiBaseUrl,
            trialLink = trialLink,
            videoFileName = videoFile.name,
            model = estimation.summary.modelVariant.modelName,
            frameStepMs = estimation.frameStepMs,
            videoDurationMs = estimation.videoDurationMs,
            framesProcessed = estimation.summary.framesProcessed,
            framesWithPose = estimation.summary.framesWithPose,
            averagePoseScore = estimation.summary.averagePoseScore,
            frames = estimation.frames
        )

        val outputDir = File(appFilesDir, "pose_outputs").apply { mkdirs() }
        val jsonFile = File(outputDir, "${videoFile.nameWithoutExtension}.pose20.json")
        jsonFile.writeText(payloadAdapter.toJson(payload))

        val endpoint = BuildConfig.POSE_UPLOAD_URL.trim()
        if (endpoint.isBlank()) {
            return PoseUploadResult(
                jsonFile = jsonFile,
                uploaded = false,
                statusCode = null,
                errorMessage = "POSE_UPLOAD_URL not configured; exported locally only."
            )
        }

        return runCatching {
            val body = payloadAdapter.toJson(payload)
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .post(body)

            val token = BuildConfig.POSE_UPLOAD_BEARER.trim()
            if (token.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }
            requestBuilder.addHeader("Content-Type", "application/json")

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    PoseUploadResult(
                        jsonFile = jsonFile,
                        uploaded = true,
                        statusCode = response.code,
                        errorMessage = null
                    )
                } else {
                    PoseUploadResult(
                        jsonFile = jsonFile,
                        uploaded = false,
                        statusCode = response.code,
                        errorMessage = "HTTP ${response.code}: ${response.message}"
                    )
                }
            }
        }.getOrElse { error ->
            PoseUploadResult(
                jsonFile = jsonFile,
                uploaded = false,
                statusCode = null,
                errorMessage = error.localizedMessage ?: "Pose upload failed"
            )
        }
    }
}
