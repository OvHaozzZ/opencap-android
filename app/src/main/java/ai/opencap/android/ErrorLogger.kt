package ai.opencap.android

import android.util.Log

enum class ErrorDomain(val value: String) {
    CAPTURE_SESSION("CameraX runtime error")
}

object ErrorLogger {
    private const val TAG = "OpenCapError"

    fun logError(domain: ErrorDomain, message: String?, throwable: Throwable? = null) {
        val resolvedMessage = message?.takeIf { it.isNotBlank() } ?: "Unknown error"
        val logMessage = "${domain.value}: $resolvedMessage"
        if (throwable != null) {
            Log.e(TAG, logMessage, throwable)
        } else {
            Log.e(TAG, logMessage)
        }
    }
}
