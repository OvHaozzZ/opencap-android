package ai.opencap.android.util

import android.content.Context
import android.os.Build
import android.provider.Settings

object DeviceUtils {
    fun deviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    fun modelCode(): String {
        return "${Build.MANUFACTURER}-${Build.MODEL}-${Build.DEVICE}"
    }
}
