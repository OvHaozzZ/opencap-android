# OpenCap Android (Replica of `opencap-iphone`)

Chinese version: [README.zh-CN.md](README.zh-CN.md)

This project recreates the same app behavior as the iPhone client in this repository:

- QR scan to bind a recording session.
- Polling session status every 1 second.
- Status-driven camera workflow:
  - `recording` -> start recording
  - `uploading` -> stop recording and upload
  - `uploading -> ready` -> cancel/stop upload
- Upload recorded video to S3 using presigned POST credentials.
- PATCH video metadata back to the OpenCap API (`fov`, `model`, `max_framerate`).
- On-device pose estimation with MediaPipe Pose Landmarker:
  - Auto-select `Full` on high-end devices.
  - Auto-fallback to `Lite` if initialization/download fails.
  - Map 33 landmarks to OpenCap-compatible 20 keypoints.
  - Export OpenCap-20 keypoint time series as JSON.
  - Upload JSON to your API endpoint when configured.
- Instruction overlay, QR frame overlay, rotating bottom controls, network warning, portrait-lock warning.
- Runtime error logging to local logcat (`Log.e`).

## Parity Mapping from iPhone Code

- `CameraViewController.swift` -> `MainActivity.kt`
- `CameraController.swift` -> `camera/CameraController.kt`
- `VideoCredentials.swift` -> `model/VideoCredentials.kt`
- `RetryRequestInterceptor.swift` -> `util/RetryInterceptor.kt`
- `InstructionView.swift` + `UXQRMiddleSquareView` -> `ui/QrOverlayView.kt` + `activity_main.xml`
- `FirebaseErrorLogger.swift` -> `ErrorLogger.kt`

## Build Notes

1. Open `opencap-android` in Android Studio (JDK 17).
2. Sync Gradle.
3. Run on a physical Android device (CameraX + ML Kit QR scanning needs camera hardware).

## Known Environment Limitation in This Workspace

- Gradle CLI is not installed in this environment, so I could not execute a local build check here.
- The project is prepared to compile in Android Studio with standard Android tooling installed.

## Model Note

The iPhone client itself does not run pose estimation on-device. It records video and uploads to cloud processing.
This Android replica keeps that behavior and additionally provides optional local pose inference for modern mobile deployment.

### Local Pose Pipeline

- `util/OpenCapKeypointMapper.kt`
- `pose/PoseEstimator.kt`
- `pose/PoseModelManager.kt`
- Supports mapping `OPENPOSE_BODY25`, `COCO17`, and `BLAZEPOSE33` to an OpenCap-compatible 20-keypoint schema.
- If source keypoints are missing (e.g., COCO17 has no toes/heels, BlazePose has no small-toes), it reconstructs them using geometric interpolation/extrapolation.
- Model files are downloaded and cached at runtime from:
  - `pose_landmarker_full.task`
  - `pose_landmarker_lite.task`
- First run requires network to download model files into app private storage.

### Pose JSON Upload (Your API)

Configure in `gradle.properties`:

- `POSE_UPLOAD_URL=https://your.api/pose/upload`
- `POSE_UPLOAD_BEARER=your_token` (optional)

Behavior:

- If `POSE_UPLOAD_URL` is configured, app POSTs JSON payload after local pose inference.
- If not configured, app still exports JSON locally to:
  - `<app_files_dir>/pose_outputs/<video_name>.pose20.json`

Payload contains:

- session/device identifiers
- selected model (`mediapipe_pose_full` or `mediapipe_pose_lite`)
- frame timing metadata
- OpenCap-20 keypoint time series (`frames[]`)

### Multi-Phone Sessions

Like iPhone, Android binds each phone with `device_id` and can run with 2+ phones in the same OpenCap session by scanning the same QR status URL.
