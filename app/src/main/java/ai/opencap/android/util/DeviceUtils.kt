package ai.opencap.android.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

object DeviceUtils {
    fun deviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val seed = androidId?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER}:${Build.MODEL}:${Build.DEVICE}"
        return UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
    }

    fun modelCode(): String {
        return "${Build.MANUFACTURER}-${Build.MODEL}-${Build.DEVICE}"
    }
}
