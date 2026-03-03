package ai.opencap.android

import com.google.firebase.crashlytics.FirebaseCrashlytics

enum class ErrorDomain(val value: String) {
    CAPTURE_SESSION("CameraX runtime error")
}

object FirebaseErrorLogger {
    fun logError(domain: ErrorDomain, message: String?) {
        val throwable = RuntimeException("${domain.value}: ${message ?: ""}".trim())
        FirebaseCrashlytics.getInstance().recordException(throwable)
    }
}
