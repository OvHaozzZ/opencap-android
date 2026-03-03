package ai.opencap.android.pose

enum class PoseModelVariant(
    val modelName: String,
    val fileName: String,
    val downloadUrl: String
) {
    FULL(
        modelName = "mediapipe_pose_full",
        fileName = "pose_landmarker_full.task",
        downloadUrl = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task"
    ),
    LITE(
        modelName = "mediapipe_pose_lite",
        fileName = "pose_landmarker_lite.task",
        downloadUrl = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task"
    )
}
