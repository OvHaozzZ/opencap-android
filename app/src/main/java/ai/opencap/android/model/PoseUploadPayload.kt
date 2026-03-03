package ai.opencap.android.model

import ai.opencap.android.pose.PoseFrame20

data class PoseUploadPayload(
    val schemaVersion: String,
    val deviceId: String,
    val sessionStatusUrl: String,
    val apiBaseUrl: String,
    val trialLink: String?,
    val videoFileName: String,
    val model: String,
    val frameStepMs: Long,
    val videoDurationMs: Long,
    val framesProcessed: Int,
    val framesWithPose: Int,
    val averagePoseScore: Float,
    val frames: List<PoseFrame20>
)
