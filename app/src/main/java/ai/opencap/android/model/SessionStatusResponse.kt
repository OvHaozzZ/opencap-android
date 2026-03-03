package ai.opencap.android.model

import com.squareup.moshi.Json

data class SessionStatusResponse(
    val url: String?,
    val video: String?,
    val lenspos: Float?,
    val trial: String?,
    val status: String?,
    @Json(name = "newSessionURL") val newSessionUrl: String?,
    val framerate: Int?
)
