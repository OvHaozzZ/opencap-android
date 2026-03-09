package ai.opencap.android.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import ai.opencap.android.util.PhysicalOrientation
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan
import kotlin.math.sqrt

@OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    interface Listener {
        fun onQrCodeScanned(value: String)
        fun onRecordingFinished(file: File?, error: String?)
        fun onCameraError(message: String)
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var listener: Listener? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val qrEnabled = AtomicBoolean(true)

    private var lastLensPosition = 0.8f
    private var targetFpsRange: android.util.Range<Int>? = null
    private var useAutoFocus = true
    private var focusDistance = 0f

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun prepare(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                runCatching {
                    cameraProvider = cameraProviderFuture.get()
                    bindUseCases(previewView)
                }.onFailure {
                    listener?.onCameraError("Failed to prepare camera: ${it.localizedMessage}")
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun disableQrScanning() {
        qrEnabled.set(false)
    }

    fun startRecording(frameRate: Int, orientation: PhysicalOrientation) {
        val output = videoCapture ?: return
        val cameraControl = camera?.cameraControl ?: return
        setFrameRate(frameRate)
        setManualFocus(lastLensPosition)
        output.targetRotation = orientation.toSurfaceRotation()

        val file = File(context.cacheDir, "${System.currentTimeMillis()}_recording.mp4")
        val outputOptions = FileOutputOptions.Builder(file).build()

        recording = output.output
            .prepareRecording(context, outputOptions)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> {
                        val error = if (event.hasError()) {
                            "Video finalize error: ${event.error}"
                        } else {
                            null
                        }
                        listener?.onRecordingFinished(file.takeIf { !event.hasError() }, error)
                        recording = null
                    }

                    else -> Unit
                }
            }

        cameraControl.cancelFocusAndMetering()
    }

    fun stopRecording() {
        recording?.stop()
    }

    fun shutdown() {
        runCatching {
            cameraProvider?.unbindAll()
        }
        cameraExecutor.shutdown()
    }

    fun setLensPosition(lensPosition: Float) {
        lastLensPosition = lensPosition
        setManualFocus(lensPosition)
    }

    fun setAutoFocus() {
        useAutoFocus = true
        applyCaptureRequestOptions()
    }

    fun getMaxFrameRate(): Int {
        val cam = camera ?: return 0
        val characteristics = Camera2CameraInfo.from(cam.cameraInfo).getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ) ?: return 0
        return characteristics.maxOfOrNull { it.upper } ?: 0
    }

    fun getFieldOfView(): String {
        val cam = camera ?: return "0"
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val sensorSize = info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
        ) ?: return "0"
        val focalLengths = info.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
        ) ?: return "0"
        val focal = focalLengths.firstOrNull() ?: return "0"
        val diagonal = sqrt(
            sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height
        )
        val fov = 2.0 * atan((diagonal / 2.0) / focal.toDouble()) * 180.0 / Math.PI
        return String.format(Locale.US, "%.2f", fov)
    }

    private fun bindUseCases(previewView: PreviewView) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(
                    cameraExecutor,
                    QrCodeAnalyzer(qrEnabled) { qr ->
                        listener?.onQrCodeScanned(qr)
                    }
                )
            }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis,
            videoCapture
        )
        setAutoFocus()
    }

    private fun setFrameRate(frameRate: Int) {
        val cam = camera ?: return
        val fpsRanges = Camera2CameraInfo.from(cam.cameraInfo).getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ) ?: return
        val bestRange = fpsRanges.minByOrNull { fpsRange ->
            kotlin.math.abs(fpsRange.upper - frameRate)
        } ?: return
        targetFpsRange = bestRange
        applyCaptureRequestOptions()
    }

    private fun setManualFocus(lensPosition: Float) {
        val cam = camera ?: return
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val minFocusDistance = info.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        ) ?: return

        val normalized = lensPosition.coerceIn(0f, 1f)
        focusDistance = ((1f - normalized) * minFocusDistance).coerceAtLeast(0f)
        useAutoFocus = false
        applyCaptureRequestOptions()
    }

    private fun applyCaptureRequestOptions() {
        val cam = camera ?: return
        val builder = CaptureRequestOptions.Builder()
        targetFpsRange?.let { builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        if (useAutoFocus) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
        }

        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        camera2Control.captureRequestOptions = builder.build()
    }
}

private fun PhysicalOrientation.toSurfaceRotation(): Int {
    return when (this) {
        PhysicalOrientation.PORTRAIT -> Surface.ROTATION_0
        PhysicalOrientation.PORTRAIT_UPSIDE_DOWN -> Surface.ROTATION_180
        PhysicalOrientation.LANDSCAPE_LEFT -> Surface.ROTATION_90
        PhysicalOrientation.LANDSCAPE_RIGHT -> Surface.ROTATION_270
        PhysicalOrientation.UNKNOWN -> Surface.ROTATION_0
    }
}
