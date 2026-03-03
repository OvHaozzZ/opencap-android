package ai.opencap.android.pose

import ai.opencap.android.util.Keypoint2D

data class PoseFrame20(
    val timestampMs: Long,
    val keypoints: List<Keypoint2D>,
    val score: Float
)
