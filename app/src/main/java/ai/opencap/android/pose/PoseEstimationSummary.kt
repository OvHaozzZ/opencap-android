package ai.opencap.android.pose

data class PoseEstimationSummary(
    val modelVariant: PoseModelVariant,
    val framesProcessed: Int,
    val framesWithPose: Int,
    val averagePoseScore: Float
)
