package ai.opencap.android.pose

data class PoseEstimationResult(
    val summary: PoseEstimationSummary,
    val frameStepMs: Long,
    val videoDurationMs: Long,
    val frames: List<PoseFrame20>
)
