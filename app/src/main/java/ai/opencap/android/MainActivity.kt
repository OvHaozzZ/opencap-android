package ai.opencap.android

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ai.opencap.android.camera.CameraController
import ai.opencap.android.data.PoseResultUploader
import ai.opencap.android.data.S3Uploader
import ai.opencap.android.data.SessionRepository
import ai.opencap.android.databinding.ActivityMainBinding
import ai.opencap.android.model.PatchVideoRequest
import ai.opencap.android.model.SessionStatusResponse
import ai.opencap.android.model.VideoParameters
import ai.opencap.android.pose.PoseEstimationSummary
import ai.opencap.android.pose.PoseEstimator
import ai.opencap.android.ui.InstructionTextType
import ai.opencap.android.util.ConnectivityObserver
import ai.opencap.android.util.DeviceUtils
import ai.opencap.android.util.OrientationMonitor
import ai.opencap.android.util.PhysicalOrientation
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call

class MainActivity : AppCompatActivity(), CameraController.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: CameraController
    private val sessionRepository = SessionRepository()
    private val uploader = S3Uploader()
    private val poseResultUploader = PoseResultUploader()
    private lateinit var poseEstimator: PoseEstimator

    private lateinit var connectivityObserver: ConnectivityObserver
    private lateinit var orientationMonitor: OrientationMonitor
    private var pollingJob: Job? = null
    private var uploadCall: Call? = null

    private var apiUrl = ""
    private var sessionStatusUrl = ""
    private var presignedUrl = ""
    private var videoLink: String? = null
    private var trialLink: String? = null
    private var previousStatus = "ready"
    private var currentInstructionType = InstructionTextType.SCAN
    private var shouldPresentInstructionView = true
    private var currentOrientation = PhysicalOrientation.PORTRAIT

    private var noInternetSnackbar: Snackbar? = null
    private var portraitWarningSnackbar: Snackbar? = null
    private var uploadingDialog: AlertDialog? = null
    private var uploadingProgressBar: ProgressBar? = null
    private var uploadingLabel: TextView? = null
    private var recordingTimerJob: Job? = null
    private var recordingDotAnimator: ObjectAnimator? = null
    private var recordingStartedAtMs: Long = 0L
    private var isRecordingStateActive = false
    @Volatile
    private var latestPoseSummary: PoseEstimationSummary? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraController = CameraController(this, this)
        cameraController.setListener(this)
        connectivityObserver = ConnectivityObserver(this)
        orientationMonitor = OrientationMonitor(this)
        poseEstimator = PoseEstimator(this)

        setupActionButton()
        updateInstructionText()
        observeConnectivity()
        observeOrientation()
        startPolling()
        requestCameraPermissionIfNeeded()
    }

    private fun requestCameraPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        cameraController.prepare(binding.previewView)
    }

    private fun setupActionButton() {
        binding.actionButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.action_options)
                .setPositiveButton(R.string.start_new_session) { _, _ ->
                    sessionStatusUrl = "https://api.opencap.ai"
                    cameraController.setAutoFocus()
                    val intent = Intent(this, MainActivity::class.java)
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton(R.string.dismiss, null)
                .show()
        }
    }

    private fun observeConnectivity() {
        connectivityObserver.start { connected ->
            if (connected) {
                noInternetSnackbar?.dismiss()
                noInternetSnackbar = null
            } else {
                if (noInternetSnackbar == null) {
                    noInternetSnackbar = Snackbar.make(
                        binding.root,
                        R.string.no_internet,
                        Snackbar.LENGTH_INDEFINITE
                    )
                    noInternetSnackbar?.show()
                }
            }
        }
    }

    private fun observeOrientation() {
        orientationMonitor.start { orientation ->
            currentOrientation = orientation
            runOnUiThread {
                rotateBottomControls(orientation)
                updateInstructionText()
                if (orientation == PhysicalOrientation.LANDSCAPE_LEFT ||
                    orientation == PhysicalOrientation.LANDSCAPE_RIGHT
                ) {
                    showPortraitWarning()
                } else {
                    hidePortraitWarning()
                }
            }
        }
    }

    private fun rotateBottomControls(orientation: PhysicalOrientation) {
        binding.actionButton.rotation = orientation.rotation
        binding.logoImageView.rotation = orientation.rotation
    }

    private fun showPortraitWarning() {
        if (portraitWarningSnackbar == null) {
            portraitWarningSnackbar = Snackbar.make(
                binding.root,
                R.string.portrait_lock_warning,
                Snackbar.LENGTH_INDEFINITE
            )
            portraitWarningSnackbar?.show()
        }
    }

    private fun hidePortraitWarning() {
        portraitWarningSnackbar?.dismiss()
        portraitWarningSnackbar = null
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val currentUrl = sessionStatusUrl
                if (currentUrl.isNotBlank()) {
                    runCatching {
                        sessionRepository.fetchSessionStatus(currentUrl)
                    }.onSuccess { status ->
                        handleSessionStatus(status)
                    }
                }
                delay(1000)
            }
        }
    }

    private suspend fun handleSessionStatus(response: SessionStatusResponse) {
        response.video?.let { videoLink = it }
        response.trial?.let { trialLink = it }
        response.lenspos?.let { lens ->
            cameraController.setLensPosition(lens)
        }

        val status = response.status
        withContext(Dispatchers.Main) {
            setRecordingStateUi(status == "recording")
        }
        status?.let { currentStatus ->
            if (previousStatus != currentStatus && currentStatus == "recording") {
                val frameRate = response.framerate ?: 60
                withContext(Dispatchers.Main) {
                    cameraController.startRecording(frameRate, currentOrientation)
                }
            }
            if (previousStatus != currentStatus && currentStatus == "uploading") {
                withContext(Dispatchers.Main) {
                    cameraController.stopRecording()
                }
            }
            if (previousStatus == "uploading" && currentStatus == "ready") {
                withContext(Dispatchers.Main) {
                    stopUploadingVideo()
                }
            }
            previousStatus = currentStatus
        }

        response.newSessionUrl?.let { newSession ->
            sessionStatusUrl = withDeviceIdQuery(newSession)
        }
    }

    override fun onQrCodeScanned(value: String) {
        vibrate()
        val url = Uri.parse(value)
        val authority = url.authority ?: return
        val scheme = url.scheme ?: "https"
        apiUrl = "$scheme://$authority"
        sessionStatusUrl = withDeviceIdQuery(value)
        presignedUrl = buildPresignedUrl(url).orEmpty()

        runOnUiThread {
            currentInstructionType = InstructionTextType.MOUNT_DEVICE
            updateInstructionText()
            binding.qrOverlayView.isVisible = false
            cameraController.disableQrScanning()
            removeInstructionViewWithDelay()
        }
    }

    override fun onRecordingFinished(file: java.io.File?, error: String?) {
        runOnUiThread {
            setRecordingStateUi(false)
        }
        if (error != null) {
            runOnUiThread {
                showError(error)
            }
            return
        }
        if (file == null) {
            runOnUiThread {
                showError("Video file is missing.")
            }
            return
        }
        runLocalPoseEstimation(file)
        uploadVideo(file)
    }

    private fun uploadVideo(file: java.io.File) {
        if (presignedUrl.isBlank()) {
            runOnUiThread { showError("Presigned URL is missing.") }
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val credentials = runCatching {
                sessionRepository.fetchVideoCredentials(presignedUrl)
            }.getOrElse {
                runOnUiThread {
                    showError("Error fetching presign URL: ${it.localizedMessage}")
                }
                return@launch
            }

            runOnUiThread {
                showUploadingDialog()
            }
            uploadCall = uploader.uploadVideoToS3(
                file = file,
                credentials = credentials,
                onProgress = { progress ->
                    runOnUiThread {
                        updateUploadingProgress(progress)
                    }
                },
                onDone = { result ->
                    if (result.isSuccess) {
                        runOnUiThread {
                            dismissUploadingDialog()
                        }
                        patchUploadedVideo(credentials.fields.key)
                    } else {
                        runOnUiThread {
                            dismissUploadingDialog()
                            showError(
                                "Error uploading video to S3: ${
                                    result.exceptionOrNull()?.localizedMessage
                                }"
                            )
                        }
                    }
                }
            )
        }
    }

    private fun patchUploadedVideo(uploadedKey: String) {
        val videoPath = videoLink
        if (videoPath.isNullOrBlank()) {
            return
        }
        val patchUrl = when {
            videoPath.startsWith("http://") || videoPath.startsWith("https://") -> videoPath
            else -> apiUrl.trimEnd('/') + "/" + videoPath.trimStart('/')
        }
        val payload = PatchVideoRequest(
            videoUrl = uploadedKey,
            parameters = VideoParameters(
                fov = cameraController.getFieldOfView(),
                model = modelIdentifierForPatch(),
                maxFramerate = cameraController.getMaxFrameRate()
            )
        )
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                sessionRepository.patchVideo(patchUrl, payload)
            }.onFailure { error ->
                ErrorLogger.logError(
                    ErrorDomain.CAPTURE_SESSION,
                    "Patch uploaded video failed: ${error.localizedMessage}",
                    error
                )
            }
        }
    }

    private fun stopUploadingVideo() {
        uploadCall?.cancel()
        uploadCall = null
        dismissUploadingDialog()
    }

    private fun runLocalPoseEstimation(file: java.io.File) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                poseEstimator.estimateVideo(file)
            }.onSuccess { estimation ->
                latestPoseSummary = estimation.summary
                val uploadResult = poseResultUploader.exportAndUpload(
                    appFilesDir = filesDir,
                    estimation = estimation,
                    deviceId = DeviceUtils.deviceId(this@MainActivity),
                    sessionStatusUrl = sessionStatusUrl,
                    apiBaseUrl = apiUrl,
                    trialLink = trialLink,
                    videoFile = file
                )
                runOnUiThread {
                    if (uploadResult.uploaded) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(
                                R.string.pose_uploaded,
                                estimation.summary.modelVariant.modelName,
                                estimation.summary.framesWithPose,
                                estimation.summary.framesProcessed
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        uploadResult.errorMessage?.let {
                            ErrorLogger.logError(
                                ErrorDomain.CAPTURE_SESSION,
                                "Pose upload/export notice: $it"
                            )
                        }
                        Toast.makeText(
                            this@MainActivity,
                            getString(
                                R.string.pose_exported_local,
                                uploadResult.jsonFile.absolutePath
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }.onFailure { error ->
                ErrorLogger.logError(
                    ErrorDomain.CAPTURE_SESSION,
                    "Local pose estimation failed: ${error.localizedMessage}",
                    error
                )
            }
        }
    }

    private fun modelIdentifierForPatch(): String {
        val base = DeviceUtils.modelCode()
        val pose = latestPoseSummary ?: return base
        return "$base|pose=${pose.modelVariant.modelName}|frames=${pose.framesWithPose}/${pose.framesProcessed}"
    }

    private fun removeInstructionViewWithDelay() {
        if (currentInstructionType == InstructionTextType.SCAN) return
        binding.instructionTextView.postDelayed({
            binding.instructionTextView.isVisible = false
            shouldPresentInstructionView = false
        }, 30000)
    }

    private fun updateInstructionText() {
        if (!shouldPresentInstructionView) return
        val text = when (currentInstructionType) {
            InstructionTextType.SCAN -> {
                if (currentOrientation == PhysicalOrientation.LANDSCAPE_LEFT ||
                    currentOrientation == PhysicalOrientation.LANDSCAPE_RIGHT
                ) {
                    getString(R.string.instruction_scan_full)
                } else {
                    getString(R.string.instruction_scan)
                }
            }

            InstructionTextType.SCAN_FULL_TEXT -> getString(R.string.instruction_scan_full)
            InstructionTextType.MOUNT_DEVICE -> getString(R.string.instruction_mount)
        }
        binding.instructionTextView.text = text
        binding.instructionTextView.isVisible = true
    }

    private fun showUploadingDialog() {
        if (uploadingDialog != null) return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            gravity = Gravity.CENTER
        }
        val label = TextView(this).apply {
            text = getString(R.string.uploading_video, 0)
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        layout.addView(label)
        layout.addView(progress)

        uploadingLabel = label
        uploadingProgressBar = progress
        uploadingDialog = MaterialAlertDialogBuilder(this)
            .setView(layout)
            .setCancelable(false)
            .create()
        uploadingDialog?.show()
    }

    private fun updateUploadingProgress(progress: Double) {
        val progressValue = (progress * 100).toInt().coerceIn(0, 100)
        uploadingLabel?.text = getString(R.string.uploading_video, progressValue)
        uploadingProgressBar?.progress = progressValue
    }

    private fun dismissUploadingDialog() {
        uploadingDialog?.dismiss()
        uploadingDialog = null
        uploadingLabel = null
        uploadingProgressBar = null
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onCameraError(message: String) {
        runOnUiThread {
            showError(message)
        }
        ErrorLogger.logError(ErrorDomain.CAPTURE_SESSION, message)
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        setRecordingStateUi(false)
        pollingJob?.cancel()
        stopUploadingVideo()
        connectivityObserver.stop()
        orientationMonitor.stop()
        cameraController.shutdown()
    }

    private fun withDeviceIdQuery(rawUrl: String): String {
        val parsed = Uri.parse(rawUrl)
        val builder = parsed.buildUpon().clearQuery()
        parsed.queryParameterNames
            .filterNot { it == "device_id" }
            .forEach { name ->
                parsed.getQueryParameters(name).forEach { value ->
                    builder.appendQueryParameter(name, value)
                }
            }
        builder.appendQueryParameter("device_id", DeviceUtils.deviceId(this))
        return builder.build().toString()
    }

    private fun buildPresignedUrl(statusUri: Uri): String? {
        val authority = statusUri.authority ?: return null
        val scheme = statusUri.scheme ?: "https"
        val segments = statusUri.pathSegments.filter { it.isNotBlank() }
        val rootSegments = if (segments.lastOrNull()?.equals("status", ignoreCase = true) == true) {
            segments.dropLast(1)
        } else {
            segments
        }
        val basePath = if (rootSegments.isEmpty()) {
            "/"
        } else {
            rootSegments.joinToString(separator = "/", prefix = "/", postfix = "/")
        }
        return "$scheme://$authority${basePath}get_presigned_url/"
    }

    private fun setRecordingStateUi(isRecording: Boolean) {
        if (isRecordingStateActive == isRecording) {
            return
        }
        isRecordingStateActive = isRecording
        binding.recordingIndicatorLayout.isVisible = isRecording
        if (isRecording) {
            recordingStartedAtMs = SystemClock.elapsedRealtime()
            binding.recordingTimerTextView.text = getString(R.string.recording_timer_initial)
            startRecordingDotPulse()
            startRecordingTimer()
        } else {
            stopRecordingDotPulse()
            stopRecordingTimer()
            binding.recordingTimerTextView.text = getString(R.string.recording_timer_initial)
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = lifecycleScope.launch {
            while (isActive && isRecordingStateActive) {
                val elapsedSeconds =
                    ((SystemClock.elapsedRealtime() - recordingStartedAtMs) / 1000L).toInt()
                val minutes = elapsedSeconds / 60
                val seconds = elapsedSeconds % 60
                binding.recordingTimerTextView.text =
                    getString(R.string.recording_timer_format, minutes, seconds)
                delay(1000L)
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
    }

    private fun startRecordingDotPulse() {
        recordingDotAnimator?.cancel()
        binding.recordingDotView.alpha = 1f
        recordingDotAnimator = ObjectAnimator.ofFloat(
            binding.recordingDotView,
            View.ALPHA,
            1f,
            0.25f
        ).apply {
            duration = 700L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopRecordingDotPulse() {
        recordingDotAnimator?.cancel()
        recordingDotAnimator = null
        binding.recordingDotView.alpha = 1f
    }
}
