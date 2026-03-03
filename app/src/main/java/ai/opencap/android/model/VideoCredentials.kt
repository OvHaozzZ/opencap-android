package ai.opencap.android.model

import com.squareup.moshi.Json

data class VideoCredentials(
    val url: String,
    val fields: VideoCredentialFields
)

data class VideoCredentialFields(
    val key: String,
    @Json(name = "AWSAccessKeyId") val accessKeyId: String,
    val policy: String,
    val signature: String
)
