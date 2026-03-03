package ai.opencap.android.model

import com.squareup.moshi.Json

data class PatchVideoRequest(
    @Json(name = "video_url") val videoUrl: String,
    val parameters: VideoParameters
)

data class VideoParameters(
    val fov: String,
    val model: String,
    @Json(name = "max_framerate") val maxFramerate: Int
)
