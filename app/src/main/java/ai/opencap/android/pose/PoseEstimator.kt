package ai.opencap.android.pose

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import ai.opencap.android.util.Keypoint2D
import ai.opencap.android.util.OpenCapKeypointMapper
import ai.opencap.android.util.PoseSchema
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

class PoseEstimator(private val context: Context) {
    private val modelManager = PoseModelManager(context)

    fun preferredVariant(): PoseModelVariant {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val totalMemGb = memoryInfo.totalMem / (1024f * 1024f * 1024f)
        val cores = Runtime.getRuntime().availableProcessors()
        return if (totalMemGb >= 6.0f && cores >= 8) {
            PoseModelVariant.FULL
        } else {
            PoseModelVariant.LITE
        }
    }

    fun estimateVideo(videoFile: File): PoseEstimationResult {
        val preferred = preferredVariant()
        val attemptOrder = listOf(preferred, fallbackVariant(preferred))
        var lastError: Throwable? = null

        for (variant in attemptOrder) {
            runCatching {
                val modelFile = modelManager.ensureModel(variant)
                    ?: error("Model unavailable for ${variant.modelName}")
                return runPoseEstimation(videoFile, modelFile, variant)
            }.onFailure { error ->
                lastError = error
            }
        }

        throw IllegalStateException("Failed to run pose estimation", lastError)
    }

    private fun runPoseEstimation(
        videoFile: File,
        modelFile: File,
        variant: PoseModelVariant
    ): PoseEstimationResult {
        val modelBuffer = modelFile.loadMappedBuffer()
        val baseOptions = BaseOptions.builder()
            .setModelAssetBuffer(modelBuffer)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(2)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()

        val landmarker = PoseLandmarker.createFromOptions(context, options)
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(videoFile.absolutePath)

        var processed = 0
        var valid = 0
        var scoreSum = 0f
        val frames = mutableListOf<PoseFrame20>()
        var durationMs = 0L
        val frameStepMs = if (variant == PoseModelVariant.FULL) 66L else 100L

        try {
            durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            var ts = 0L
            while (ts <= durationMs) {
                val bitmap = retriever.getFrameAtTime(
                    ts * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (bitmap != null) {
                    val resized = bitmap.downscale(maxSide = if (variant == PoseModelVariant.FULL) 720 else 640)
                    if (resized !== bitmap) {
                        bitmap.recycle()
                    }
                    processed++
                    val mpImage = BitmapImageBuilder(resized).build()
                    val result = landmarker.detectForVideo(mpImage, ts)
                    val points = result.landmarks().firstOrNull()
                    if (!points.isNullOrEmpty() && points.size >= 33) {
                        val mapped = OpenCapKeypointMapper.toOpenCap20(
                            keypoints = points.take(33).map { Keypoint2D(it.x(), it.y(), 1f) },
                            schema = PoseSchema.BLAZEPOSE33
                        )
                        if (mapped.size == 20) {
                            valid++
                            val frameScore = mapped.map { it.score }.average().toFloat()
                            scoreSum += frameScore
                            frames += PoseFrame20(
                                timestampMs = ts,
                                keypoints = mapped,
                                score = frameScore
                            )
                        }
                    }
                    resized.recycle()
                }
                ts += frameStepMs
            }
        } finally {
            retriever.release()
            landmarker.close()
        }

        val summary = PoseEstimationSummary(
            modelVariant = variant,
            framesProcessed = processed,
            framesWithPose = valid,
            averagePoseScore = if (valid > 0) scoreSum / valid else 0f
        )
        return PoseEstimationResult(
            summary = summary,
            frameStepMs = frameStepMs,
            videoDurationMs = durationMs,
            frames = frames
        )
    }

    private fun fallbackVariant(variant: PoseModelVariant): PoseModelVariant {
        return if (variant == PoseModelVariant.FULL) {
            PoseModelVariant.LITE
        } else {
            PoseModelVariant.FULL
        }
    }
}

private fun File.loadMappedBuffer(): MappedByteBuffer {
    RandomAccessFile(this, "r").use { raf ->
        val channel = raf.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
    }
}

private fun Bitmap.downscale(maxSide: Int): Bitmap {
    val longer = maxOf(width, height)
    if (longer <= maxSide) return this
    val scale = maxSide.toFloat() / longer.toFloat()
    val newW = (width * scale).roundToInt().coerceAtLeast(1)
    val newH = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, newW, newH, true)
}
