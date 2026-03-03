package ai.opencap.android.util

import kotlin.math.hypot

data class Keypoint2D(
    val x: Float,
    val y: Float,
    val score: Float = 1f
)

enum class PoseSchema {
    OPENPOSE_BODY25,
    COCO17,
    BLAZEPOSE33
}

/**
 * Maps popular pose schemas to OpenCap-like 20-keypoint skeleton:
 * [neck, rShoulder, rElbow, rWrist, lShoulder, lElbow, lWrist, midHip,
 *  rHip, rKnee, rAnkle, lHip, lKnee, lAnkle, lBigToe, lSmallToe, lHeel,
 *  rBigToe, rSmallToe, rHeel]
 */
object OpenCapKeypointMapper {
    fun toOpenCap20(
        keypoints: List<Keypoint2D>,
        schema: PoseSchema
    ): List<Keypoint2D> {
        return when (schema) {
            PoseSchema.OPENPOSE_BODY25 -> fromOpenPose25(keypoints)
            PoseSchema.COCO17 -> fromCoco17(keypoints)
            PoseSchema.BLAZEPOSE33 -> fromBlazePose33(keypoints)
        }
    }

    private fun fromOpenPose25(k: List<Keypoint2D>): List<Keypoint2D> {
        fun g(i: Int) = k[i]
        return listOf(
            g(1), g(2), g(3), g(4), g(5), g(6), g(7), g(8),
            g(9), g(10), g(11), g(12), g(13), g(14), g(19), g(20), g(21), g(22), g(23), g(24)
        )
    }

    private fun fromCoco17(k: List<Keypoint2D>): List<Keypoint2D> {
        fun g(i: Int) = k[i]
        val rShoulder = g(6)
        val lShoulder = g(5)
        val rHip = g(12)
        val lHip = g(11)
        val neck = midpoint(lShoulder, rShoulder)
        val midHip = midpoint(lHip, rHip)

        val rKnee = g(14)
        val lKnee = g(13)
        val rAnkle = g(16)
        val lAnkle = g(15)

        val leftFoot = reconstructFoot(lKnee, lAnkle)
        val rightFoot = reconstructFoot(rKnee, rAnkle)

        return listOf(
            neck, rShoulder, g(8), g(10), lShoulder, g(7), g(9), midHip,
            rHip, rKnee, rAnkle, lHip, lKnee, lAnkle,
            leftFoot.bigToe, leftFoot.smallToe, leftFoot.heel,
            rightFoot.bigToe, rightFoot.smallToe, rightFoot.heel
        )
    }

    private fun fromBlazePose33(k: List<Keypoint2D>): List<Keypoint2D> {
        fun g(i: Int) = k[i]
        val rShoulder = g(12)
        val lShoulder = g(11)
        val rHip = g(24)
        val lHip = g(23)
        val neck = midpoint(lShoulder, rShoulder)
        val midHip = midpoint(lHip, rHip)

        val rKnee = g(26)
        val lKnee = g(25)
        val rAnkle = g(28)
        val lAnkle = g(27)

        val lBigToe = g(31)
        val rBigToe = g(32)
        val lHeel = g(29)
        val rHeel = g(30)

        val lSmallToe = mirrorToe(ankle = lAnkle, bigToe = lBigToe)
        val rSmallToe = mirrorToe(ankle = rAnkle, bigToe = rBigToe)

        return listOf(
            neck, rShoulder, g(14), g(16), lShoulder, g(13), g(15), midHip,
            rHip, rKnee, rAnkle, lHip, lKnee, lAnkle,
            lBigToe, lSmallToe, lHeel, rBigToe, rSmallToe, rHeel
        )
    }

    private fun midpoint(a: Keypoint2D, b: Keypoint2D): Keypoint2D {
        return Keypoint2D(
            x = (a.x + b.x) * 0.5f,
            y = (a.y + b.y) * 0.5f,
            score = minScore(a, b)
        )
    }

    private fun mirrorToe(ankle: Keypoint2D, bigToe: Keypoint2D): Keypoint2D {
        val vx = bigToe.x - ankle.x
        val vy = bigToe.y - ankle.y
        val px = -vy
        val py = vx
        val length = hypot(px.toDouble(), py.toDouble()).toFloat().coerceAtLeast(1e-4f)
        val nx = px / length
        val ny = py / length
        val spread = hypot(vx.toDouble(), vy.toDouble()).toFloat() * 0.35f
        return Keypoint2D(
            x = ankle.x + vx - nx * spread,
            y = ankle.y + vy - ny * spread,
            score = minScore(ankle, bigToe) * 0.9f
        )
    }

    private fun reconstructFoot(knee: Keypoint2D, ankle: Keypoint2D): ReconstructedFoot {
        val vx = ankle.x - knee.x
        val vy = ankle.y - knee.y
        val length = hypot(vx.toDouble(), vy.toDouble()).toFloat().coerceAtLeast(1e-4f)
        val dx = vx / length
        val dy = vy / length
        val px = -dy
        val py = dx

        val footLen = length * 0.35f
        val toeSpread = length * 0.12f
        val heelBack = length * 0.18f

        val centerToeX = ankle.x + dx * footLen
        val centerToeY = ankle.y + dy * footLen

        return ReconstructedFoot(
            bigToe = Keypoint2D(
                x = centerToeX + px * toeSpread,
                y = centerToeY + py * toeSpread,
                score = minScore(knee, ankle) * 0.8f
            ),
            smallToe = Keypoint2D(
                x = centerToeX - px * toeSpread,
                y = centerToeY - py * toeSpread,
                score = minScore(knee, ankle) * 0.8f
            ),
            heel = Keypoint2D(
                x = ankle.x - dx * heelBack,
                y = ankle.y - dy * heelBack,
                score = minScore(knee, ankle) * 0.8f
            )
        )
    }

    private fun minScore(a: Keypoint2D, b: Keypoint2D): Float {
        return minOf(a.score, b.score)
    }

    private data class ReconstructedFoot(
        val bigToe: Keypoint2D,
        val smallToe: Keypoint2D,
        val heel: Keypoint2D
    )
}
