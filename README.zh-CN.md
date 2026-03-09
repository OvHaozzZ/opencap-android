# OpenCap Android（`opencap-iphone` 的 Android 复刻版）

本文档是该项目的中文说明。

## 项目简介

这个项目在 Android 上复刻了 iPhone 客户端的核心行为：

- 扫描二维码并绑定录制会话。
- 每 1 秒轮询一次会话状态。
- 按状态驱动录制流程：
  - `recording`：开始录制
  - `uploading`：停止录制并上传
  - `uploading -> ready`：取消或停止上传
- 使用预签名凭证将视频上传到 S3。
- 向 OpenCap API 回写视频元数据（`fov`、`model`、`max_framerate`）。
- 集成 MediaPipe Pose Landmarker 本地姿态估计：
  - 高性能设备优先使用 `Full`
  - 初始化或下载失败时自动降级为 `Lite`
  - 将 33 个关键点映射为 OpenCap 兼容的 20 个关键点
  - 导出 OpenCap-20 时序 JSON
  - 若已配置接口，自动上传该 JSON
- 覆盖层与交互：扫码框、指引文案、底部控件旋转、网络告警、方向锁告警。
- 通过本地 logcat (`Log.e`) 记录运行错误。

## 与 iPhone 代码对应关系

- `CameraViewController.swift` -> `MainActivity.kt`
- `CameraController.swift` -> `camera/CameraController.kt`
- `VideoCredentials.swift` -> `model/VideoCredentials.kt`
- `RetryRequestInterceptor.swift` -> `util/RetryInterceptor.kt`
- `InstructionView.swift` + `UXQRMiddleSquareView` -> `ui/QrOverlayView.kt` + `activity_main.xml`
- `FirebaseErrorLogger.swift` -> `ErrorLogger.kt`

## 构建与运行

1. 用 Android Studio 打开 `opencap-android`（JDK 17）。
2. 执行 Gradle Sync。
3. 使用真机运行（涉及 CameraX 与 ML Kit 扫码）。

## 当前环境限制

- 当前开发环境没有可用的 Gradle CLI，因此这里无法直接执行本地编译验证。
- 工程结构已按 Android Studio 标准配置，建议在 IDE 中进行编译与调试。

## 模型与关键点说明

iPhone 客户端原始逻辑是“采集视频并上传云端处理”，并不在设备端跑姿态模型。  
Android 版本在保留该流程的基础上，额外提供了可选本地姿态推理能力。

本地姿态相关代码：

- `util/OpenCapKeypointMapper.kt`
- `pose/PoseEstimator.kt`
- `pose/PoseModelManager.kt`

支持输入骨架：

- `OPENPOSE_BODY25`
- `COCO17`
- `BLAZEPOSE33`

统一映射到 OpenCap 20 点骨架。若源模型缺少足部等关键点，会使用几何插值/外推进行数学重建。

模型文件会在首次运行时下载并缓存：

- `pose_landmarker_full.task`
- `pose_landmarker_lite.task`

## 姿态 JSON 导出与上传

在 `gradle.properties` 中配置：

- `POSE_UPLOAD_URL=https://your.api/pose/upload`
- `POSE_UPLOAD_BEARER=your_token`（可选）

行为规则：

- 若 `POSE_UPLOAD_URL` 已配置：本地推理后自动 POST JSON 到该地址。
- 若未配置：仍会导出本地 JSON 文件。

本地导出路径：

- `<app_files_dir>/pose_outputs/<video_name>.pose20.json`

JSON 包含：

- 会话与设备标识信息
- 实际使用的姿态模型（`mediapipe_pose_full` 或 `mediapipe_pose_lite`）
- 帧时间信息
- OpenCap-20 时序关键点（`frames[]`）

## 多手机并行采集

与 iPhone 一致，Android 通过 `device_id` 绑定会话。  
多台手机（2 台及以上）扫描同一会话二维码后，可并行采集并上传。
